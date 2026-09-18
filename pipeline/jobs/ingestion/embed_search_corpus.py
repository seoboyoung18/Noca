"""Create DINOv2 vectors for one feature pipeline version.

The job intentionally processes one ``pipeline_version_id`` per invocation. v1
(DAMAGE_PART) and v2 (DAMAGE) therefore cannot be mixed accidentally. The
database model row is resolved from the shared ``EmbeddingSpec`` contract, and
the same model version is used for either corpus.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for import_root in (REPO_ROOT, PIPELINE_ROOT):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))

from shared.vision.roi import RoiError, letterbox  # noqa: E402


def roi_from_feature_box(image, payload: Any):
    """Recreate the exact 224px ROI from persisted ``roi_box`` metadata."""
    if isinstance(payload, str):
        payload = json.loads(payload)
    if not isinstance(payload, dict) or payload.get("format") != "XYWH":
        raise RoiError("roi_box must be an XYWH object")
    try:
        x = int(payload["x"])
        y = int(payload["y"])
        width = int(payload["width"])
        height = int(payload["height"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RoiError("roi_box has invalid coordinates") from exc
    if width <= 0 or height <= 0:
        raise RoiError("roi_box width and height must be positive")
    if x < 0 or y < 0 or x + width > image.width or y + height > image.height:
        raise RoiError(f"roi_box is outside image bounds: {(x, y, width, height)}")
    return letterbox(image.convert("RGB").crop((x, y, x + width, y + height)))


def vector_literal(vector: Iterable[float]) -> str:
    values = [float(value) for value in vector]
    if len(values) != 768:
        raise ValueError(f"embedding vector must have 768 values, got {len(values)}")
    return "[" + ",".join(f"{value:.8g}" for value in values) + "]"


def upsert_embedding(cur, row: dict[str, Any], model_version_id: int, vector: Iterable[float]) -> None:
    """Idempotently upsert one feature/model vector."""
    cur.execute(
        """
        INSERT INTO repair_case_roi_embedding(
            case_image_id, model_version_id, damage_feature_id, confidence, embedding)
        VALUES (%s,%s,%s,%s,%s::vector)
        ON CONFLICT (damage_feature_id, model_version_id) DO UPDATE SET
            case_image_id=EXCLUDED.case_image_id,
            confidence=EXCLUDED.confidence,
            embedding=EXCLUDED.embedding
        """,
        (
            row["case_image_id"],
            model_version_id,
            row["damage_feature_id"],
            row.get("confidence"),
            vector_literal(vector),
        ),
    )


def resolve_model_version(cur, spec) -> int:
    """Resolve and validate the DB row matching the shared EmbeddingSpec."""
    from shared.vision.roi import PREPROCESSING_VERSION

    cur.execute(
        """
        SELECT model_version_id, dimension, preprocessing
          FROM embedding_model_version
         WHERE model_name=%s AND version=%s
        """,
        (spec.model_name, spec.version),
    )
    row = cur.fetchone()
    if not row:
        raise RuntimeError(
            "matching embedding_model_version is missing for "
            f"{spec.model_name}/{spec.version}"
        )
    if int(row["dimension"] if isinstance(row, dict) else row[1]) != spec.dimension:
        raise RuntimeError("embedding_model_version dimension does not match EmbeddingSpec")
    preprocessing = row["preprocessing"] if isinstance(row, dict) else row[2]
    if isinstance(preprocessing, str):
        preprocessing = json.loads(preprocessing)
    if not isinstance(preprocessing, dict):
        raise RuntimeError("embedding_model_version preprocessing contract is missing")
    if preprocessing.get("revision") != spec.revision:
        raise RuntimeError("embedding model revision does not match EmbeddingSpec")
    if preprocessing.get("preprocessing_version") != PREPROCESSING_VERSION:
        raise RuntimeError("ROI preprocessing version does not match shared roi.py")
    return int(row["model_version_id"] if isinstance(row, dict) else row[0])


def validate_pipeline_version(cur, pipeline_version_id: int) -> dict[str, Any]:
    cur.execute(
        """
        SELECT pipeline_version_id, pipeline_name, version, params, is_active
          FROM feature_pipeline_version
         WHERE pipeline_version_id=%s
        """,
        (pipeline_version_id,),
    )
    row = cur.fetchone()
    if not row:
        raise RuntimeError(f"pipeline_version_id={pipeline_version_id} does not exist")
    if isinstance(row, dict):
        result = dict(row)
    else:
        result = {
            "pipeline_version_id": row[0], "pipeline_name": row[1],
            "version": row[2], "params": row[3], "is_active": row[4],
        }
    params = result.get("params")
    if isinstance(params, str):
        params = json.loads(params)
        result["params"] = params
    image_source = (params or {}).get("image_source")
    if image_source not in {"DAMAGE_PART", "DAMAGE"}:
        raise RuntimeError("pipeline version must declare image_source DAMAGE_PART or DAMAGE")
    if image_source == "DAMAGE":
        required = {
            "part_code_policy": "NULL",
            "pair_status": "UNPAIRED",
            "repair_hint_source": "REPAIR",
        }
        invalid = {
            key: (params or {}).get(key)
            for key, expected in required.items()
            if (params or {}).get(key) != expected
        }
        if invalid:
            raise RuntimeError(
                "DAMAGE pipeline contract mismatch: "
                + json.dumps(invalid, ensure_ascii=False, sort_keys=True)
            )
    return result


def feature_counts(cur, pipeline_version_id: int, model_version_id: int) -> tuple[int, int, int]:
    cur.execute(
        """
        SELECT
            COUNT(*) AS total,
            COUNT(*) FILTER (WHERE NOT f.is_searchable) AS not_searchable,
            COUNT(*) FILTER (
                WHERE f.is_searchable AND e.roi_embedding_id IS NOT NULL
            ) AS existing
          FROM repair_case_damage_feature f
          LEFT JOIN repair_case_roi_embedding e
            ON e.damage_feature_id=f.damage_feature_id
           AND e.model_version_id=%s
         WHERE f.pipeline_version_id=%s
        """,
        (model_version_id, pipeline_version_id),
    )
    row = cur.fetchone()
    if isinstance(row, dict):
        return int(row["total"]), int(row["not_searchable"]), int(row["existing"])
    return int(row[0]), int(row[1]), int(row[2])


def fetch_features(
    cur,
    pipeline_version_id: int,
    model_version_id: int,
    *,
    limit: int | None,
    resume: bool,
) -> list[dict[str, Any]]:
    predicates = ["f.pipeline_version_id=%s", "f.is_searchable"]
    values: list[Any] = [pipeline_version_id]
    query = f"""
        SELECT f.damage_feature_id, f.case_image_id, f.roi_box, f.confidence,
               i.source_image_ref, e.roi_embedding_id
          FROM repair_case_damage_feature f
          JOIN repair_case_image i ON i.case_image_id=f.case_image_id
          LEFT JOIN repair_case_roi_embedding e
            ON e.damage_feature_id=f.damage_feature_id
           AND e.model_version_id=%s
         WHERE {' AND '.join(predicates)}
         ORDER BY f.damage_feature_id
    """
    values.insert(0, model_version_id)
    if limit is not None:
        query += " LIMIT %s"
        values.append(limit)
    cur.execute(query, tuple(values))
    rows = [dict(row) if isinstance(row, dict) else {
        "damage_feature_id": row[0], "case_image_id": row[1], "roi_box": row[2],
        "confidence": row[3], "source_image_ref": row[4],
        "roi_embedding_id": row[5],
    } for row in cur.fetchall()]
    if resume:
        rows = [row for row in rows if row["roi_embedding_id"] is None]
    return rows


def prepare_feature(row: dict[str, Any], dataset_root: Path):
    from PIL import Image

    image_path = dataset_root / str(row["source_image_ref"])
    if not image_path.is_file():
        raise FileNotFoundError(str(image_path))
    with Image.open(image_path) as source:
        source.load()
        return roi_from_feature_box(source, row["roi_box"])


def embed_rows(rows: list[dict[str, Any]], dataset_root: Path, embedder, batch_size: int):
    """Embed rows while isolating image/ROI/model failures per feature."""
    counts: Counter[str] = Counter()
    prepared: list[tuple[dict[str, Any], Any]] = []
    for row in rows:
        try:
            prepared.append((row, prepare_feature(row, dataset_root)))
        except Exception as exc:
            row["error"] = f"{type(exc).__name__}: {exc}"
            counts["failed"] += 1

    results: list[tuple[dict[str, Any], Any]] = []
    for start in range(0, len(prepared), batch_size):
        batch = prepared[start:start + batch_size]
        try:
            vectors = embedder.embed([item[1] for item in batch])
            results.extend((item[0], vectors[index]) for index, item in enumerate(batch))
            counts["success"] += len(batch)
        except Exception as exc:
            if "failed to load DINOv2 embedding model" in str(exc):
                raise
            for row, roi in batch:
                try:
                    results.append((row, embedder.embed([roi])[0]))
                    counts["success"] += 1
                except Exception as item_exc:
                    row["error"] = f"{type(item_exc).__name__}: {item_exc}"
                    counts["failed"] += 1
    return results, dict(counts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pipeline-version-id", type=int, required=True)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--dsn",
        help="지원 중단 옵션. 접속 문자열은 DATABASE_URL 환경변수만 사용합니다.",
    )
    args = parser.parse_args()
    if args.batch_size < 1:
        parser.error("--batch-size must be positive")
    if args.limit is not None and args.limit < 1:
        parser.error("--limit must be positive")
    if args.dsn is not None:
        parser.error("DB 접속 정보는 DATABASE_URL 환경변수만 사용합니다")
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        parser.error("DATABASE_URL 환경변수가 필요합니다")

    try:
        import psycopg
        from psycopg.rows import dict_row
        from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
    except ImportError as exc:
        raise SystemExit("임베딩 실행에는 psycopg·PyTorch·Transformers·Pillow가 필요합니다") from exc

    spec = EmbeddingSpec()
    dataset_root = args.dataset_root.resolve()
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            pipeline = validate_pipeline_version(cur, args.pipeline_version_id)
            model_version_id = resolve_model_version(cur, spec)
            total, not_searchable, existing = feature_counts(
                cur, args.pipeline_version_id, model_version_id
            )
            rows = fetch_features(
                cur, args.pipeline_version_id, model_version_id,
                limit=args.limit, resume=args.resume,
            )
        result: dict[str, Any] = {
            "pipeline_version_id": args.pipeline_version_id,
            "pipeline_version": pipeline["version"],
            "pipeline_image_source": (pipeline.get("params") or {}).get("image_source"),
            "model_version_id": model_version_id,
            "model_name": spec.model_name,
            "model_revision": spec.revision,
            "embedding_version": spec.version,
            "feature_total": total,
            "feature_not_searchable": not_searchable,
            "existing_embedding_count": existing,
            "target_count": len(rows),
            "resume": args.resume,
            "batch_size": args.batch_size,
        }
        if args.dry_run:
            preflight_failed = 0
            for row in rows:
                try:
                    prepare_feature(row, dataset_root)
                except Exception as exc:
                    row["error"] = f"{type(exc).__name__}: {exc}"
                    preflight_failed += 1
            result.update({
                "success": 0,
                "failed": preflight_failed,
                "roi_ready": len(rows) - preflight_failed,
                "skip": not_searchable + (existing if args.resume else 0),
                "status": "DRY_RUN",
            })
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return

        embedder = DinoV2Embedder(spec)
        success = 0
        failed = 0
        existing_upsert_count = 0
        failure_details: list[dict[str, Any]] = []
        # Keep only one batch of decoded PIL images in memory. A full corpus
        # otherwise grows to multiple GB before the first database commit.
        for start in range(0, len(rows), args.batch_size):
            batch_rows = rows[start:start + args.batch_size]
            embedded, embed_counts = embed_rows(
                batch_rows, dataset_root, embedder, args.batch_size
            )
            with conn.cursor() as cur:
                for row, vector in embedded:
                    upsert_embedding(cur, row, model_version_id, vector)
            conn.commit()
            success += embed_counts.get("success", 0)
            failed += embed_counts.get("failed", 0)
            existing_upsert_count += sum(
                1 for row, _ in embedded if row.get("roi_embedding_id") is not None
            )
            failure_details.extend({
                "damage_feature_id": row.get("damage_feature_id"),
                "source_image_ref": row.get("source_image_ref"),
                "error": row.get("error"),
            } for row in batch_rows if row.get("error"))
        result.update({
            "success": success,
            "failed": failed,
            "skip": not_searchable + (existing if args.resume else 0),
            "existing_upsert_count": existing_upsert_count,
            "failure_details": failure_details,
            "status": "SUCCEEDED" if not failed else "PARTIAL",
        })
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
