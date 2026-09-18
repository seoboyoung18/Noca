"""Backfill separate YOLO part evidence for v2 DAMAGE features.

This job deliberately never updates ``repair_case_damage_feature.part_code`` or
``pair_status``.  It does not run the damage model: DAMAGE geometry persisted
in the database is paired with one part-model invocation per source image.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.adapters.ultralytics_yolo import ModelSpec, ModelRunError, UltralyticsRunner
from AI.server.app.adapters.yolo_adapter import PART_RAW_TO_CODE
from shared.vision.roi import PART_LINK_MIN_OVERLAP, overlap, source_bbox

MODEL_NAME = "vehicle-part-detection"
MODEL_VERSION = "35ep"
WEIGHTS = REPO_ROOT / "AI" / "models" / "part" / "damage_part_best-35ep.pt"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pipeline-version-id", type=int, default=2)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--batch-size", type=int, default=1,
                        help="DB target fetch page size; each image is still committed independently")
    parser.add_argument("--source", choices=("all", "AIHUB_AS", "AIHUB_SC"), default="all")
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--review-output", type=Path,
                        help="첫 10개 이미지의 DAMAGE/part bbox·후보를 사람이 확인할 JSON")
    parser.add_argument("--dsn", help="지원 중단 옵션. DATABASE_URL 환경변수만 사용합니다.")
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("--limit must be positive")
    if args.batch_size < 1:
        parser.error("--batch-size must be positive")
    if args.dsn is not None:
        parser.error("DB 접속 정보는 DATABASE_URL 환경변수만 사용합니다")
    if not os.environ.get("DATABASE_URL"):
        parser.error("DATABASE_URL 환경변수가 필요합니다")
    return args


def weights_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def assert_v2(cur, pipeline_version_id: int) -> None:
    cur.execute("""SELECT params->>'image_source' AS image_source,
                         params->>'part_code_policy' AS part_code_policy
                     FROM feature_pipeline_version WHERE pipeline_version_id=%s""",
                (pipeline_version_id,))
    row = cur.fetchone()
    if not row:
        raise RuntimeError("pipeline-version-id must be the v2 DAMAGE/NULL pipeline")
    image_source = row["image_source"] if isinstance(row, dict) else row[0]
    part_policy = row["part_code_policy"] if isinstance(row, dict) else row[1]
    if image_source != "DAMAGE" or part_policy != "NULL":
        raise RuntimeError("pipeline-version-id must be the v2 DAMAGE/NULL pipeline")


def fetch_images(cur, pipeline_version_id: int, source: str, resume: bool, limit: int | None) -> list[dict[str, Any]]:
    where = ["f.pipeline_version_id=%s", "i.image_type='DAMAGE'"]
    values: list[Any] = [pipeline_version_id]
    if source != "all":
        where.append("c.source=%s")
        values.append(source)
    query = f"""SELECT DISTINCT i.case_image_id, i.source_image_ref
                   FROM repair_case_damage_feature f
                   JOIN repair_case_image i ON i.case_image_id=f.case_image_id
                   JOIN repair_case c ON c.case_id=i.case_id
                  WHERE {' AND '.join(where)}
                  ORDER BY i.case_image_id"""
    if limit is not None:
        query += " LIMIT %s"
        values.append(limit)
    cur.execute(query, tuple(values))
    rows = [dict(row) for row in cur.fetchall()]
    if not resume:
        return rows
    # Apply --limit to the stable ordered cohort first.  A repeated `--resume --limit 50`
    # therefore verifies the same 50-image sample rather than silently moving to the next page.
    if not rows:
        return rows
    cur.execute("""SELECT case_image_id FROM repair_case_image_part_inference
                     WHERE part_model_name=%s AND part_model_version=%s
                       AND run_status IN ('SUCCEEDED','PART_NOT_DETECTED')
                       AND case_image_id = ANY(%s)""",
                (MODEL_NAME, MODEL_VERSION, [row["case_image_id"] for row in rows]))
    completed = {int(row["case_image_id"] if isinstance(row, dict) else row[0]) for row in cur.fetchall()}
    return [row for row in rows if int(row["case_image_id"]) not in completed]


def feature_rows(cur, case_image_id: int, pipeline_version_id: int) -> list[dict[str, Any]]:
    cur.execute("""SELECT damage_feature_id, damage_polygon
                     FROM repair_case_damage_feature
                    WHERE case_image_id=%s AND pipeline_version_id=%s
                    ORDER BY damage_feature_id""", (case_image_id, pipeline_version_id))
    return [dict(row) for row in cur.fetchall()]


def damage_bbox(payload: Any) -> tuple[float, float, float, float]:
    if isinstance(payload, str):
        payload = json.loads(payload)
    annotation: dict[str, Any]
    if isinstance(payload, list) and len(payload) == 4 and all(isinstance(v, (int, float)) for v in payload):
        annotation = {"bbox": payload}
    else:
        annotation = {"segmentation": payload}
    return source_bbox(annotation)


def candidates(raw: dict[str, Any], class_map: dict[int, str]) -> list[dict[str, Any]]:
    result = []
    for prediction in raw["predictions"]:
        class_id = int(prediction["class_id"])
        class_name = str(prediction["class_name"])
        if str(class_map[class_id]) != class_name:
            raise ValueError(f"part class map mismatch: {class_id}")
        code = PART_RAW_TO_CODE.get(class_name.casefold())
        if not code:
            raise ValueError(f"unknown part class name: {class_name!r}")
        x0, y0, x1, y1 = (float(v) for v in prediction["bbox"]["coordinates"])
        if x1 <= x0 or y1 <= y0:
            raise ValueError("invalid part bbox")
        result.append({"part_code": code, "confidence": float(prediction["confidence"]),
                       "bbox": prediction["bbox"], "xywh": (x0, y0, x1 - x0, y1 - y0)})
    return result


def upsert_inference(cur, image_id: int, status: str, detected: int, raw: Any,
                     error_code: str | None, sha: str) -> int:
    cur.execute("""INSERT INTO repair_case_image_part_inference(
                    case_image_id, part_model_name, part_model_version, weights_sha256,
                    run_status, detected_part_count, raw_predictions, error_code, processed_at)
                  VALUES (%s,%s,%s,%s,%s,%s,%s::jsonb,%s,now())
                  ON CONFLICT (case_image_id, part_model_name, part_model_version) DO UPDATE SET
                    weights_sha256=EXCLUDED.weights_sha256, run_status=EXCLUDED.run_status,
                    detected_part_count=EXCLUDED.detected_part_count, raw_predictions=EXCLUDED.raw_predictions,
                    error_code=EXCLUDED.error_code, processed_at=now()
                  RETURNING image_part_inference_id""",
                (image_id, MODEL_NAME, MODEL_VERSION, sha, status, detected,
                 json.dumps(raw, ensure_ascii=False), error_code))
    row = cur.fetchone()
    return int(row["image_part_inference_id"] if isinstance(row, dict) else row[0])


def replace_mappings(cur, inference_id: int, features: list[dict[str, Any]], parts: list[dict[str, Any]]) -> Counter:
    # These are only rows owned by this new inference run; legacy hints/features are untouched.
    cur.execute("UPDATE repair_case_damage_feature_part_mapping SET primary_candidate_id=NULL WHERE image_part_inference_id=%s", (inference_id,))
    cur.execute("DELETE FROM repair_case_damage_feature_part_mapping WHERE image_part_inference_id=%s", (inference_id,))
    cur.execute("DELETE FROM repair_case_damage_feature_part_candidate WHERE image_part_inference_id=%s", (inference_id,))
    counts: Counter[str] = Counter()
    for feature in features:
        linked = [(part, overlap(damage_bbox(feature["damage_polygon"]), part["xywh"]))
                  for part in parts]
        linked = [(part, score) for part, score in linked if score >= PART_LINK_MIN_OVERLAP]
        linked.sort(key=lambda item: (-item[1], item[0]["xywh"][2] * item[0]["xywh"][3]))
        codes = {part["part_code"] for part, _ in linked}
        status = "PAIRED" if len(codes) == 1 else "AMBIGUOUS" if codes else "UNPAIRED"
        cur.execute("""INSERT INTO repair_case_damage_feature_part_mapping(
                        damage_feature_id, image_part_inference_id, mapping_status)
                      VALUES (%s,%s,%s)""", (feature["damage_feature_id"], inference_id, status))
        primary_id = None
        for index, (part, score) in enumerate(linked):
            cur.execute("""INSERT INTO repair_case_damage_feature_part_candidate(
                            damage_feature_id,image_part_inference_id,candidate_index,part_code,
                            part_confidence,part_bbox,overlap_score,is_primary)
                          VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s,FALSE) RETURNING candidate_id""",
                        (feature["damage_feature_id"], inference_id, index, part["part_code"],
                         part["confidence"], json.dumps(part["bbox"]), score))
            row = cur.fetchone()
            candidate_id = int(row["candidate_id"] if isinstance(row, dict) else row[0])
            if status == "PAIRED" and index == 0:
                primary_id = candidate_id
        if primary_id is not None:
            cur.execute("""UPDATE repair_case_damage_feature_part_candidate SET is_primary=TRUE
                           WHERE candidate_id=%s""", (primary_id,))
            cur.execute("""UPDATE repair_case_damage_feature_part_mapping SET primary_candidate_id=%s,
                           updated_at=now() WHERE damage_feature_id=%s AND image_part_inference_id=%s""",
                        (primary_id, feature["damage_feature_id"], inference_id))
        counts[status] += 1
        counts["candidate"] += len(linked)
    return counts


def main() -> None:
    args = parse_args()
    import psycopg
    from psycopg.rows import dict_row
    sha = weights_sha256(WEIGHTS)
    spec = ModelSpec("part", MODEL_NAME, MODEL_VERSION, "detect", WEIGHTS)
    runner = UltralyticsRunner(args.imgsz)
    summary: Counter[str] = Counter()
    review_rows: list[dict[str, Any]] = []
    with psycopg.connect(os.environ["DATABASE_URL"], row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            assert_v2(cur, args.pipeline_version_id)
            images = fetch_images(cur, args.pipeline_version_id, args.source, args.resume, args.limit)
            all_images = fetch_images(cur, args.pipeline_version_id, args.source, False, args.limit)
        summary["target_image_count"] = len(images)
        summary["skipped_existing_count"] = max(0, len(all_images) - len(images)) if args.resume else 0
        for image in images:
            features: list[dict[str, Any]]
            with conn.cursor() as cur:
                features = feature_rows(cur, image["case_image_id"], args.pipeline_version_id)
            summary["damage_feature_count"] += len(features)
            path = args.dataset_root.resolve() / str(image["source_image_ref"])
            try:
                raw, class_map = runner.run(spec, path, int(image["case_image_id"]))
                parts = candidates(raw, class_map)
                status = "SUCCEEDED" if parts else "PART_NOT_DETECTED"
                if len(review_rows) < 10:
                    sample_features = []
                    for feature in features:
                        linked = [
                            {"part_code": part["part_code"], "confidence": part["confidence"],
                             "part_bbox": part["bbox"], "overlap": score}
                            for part in parts
                            for score in [overlap(damage_bbox(feature["damage_polygon"]), part["xywh"])]
                            if score >= PART_LINK_MIN_OVERLAP
                        ]
                        code_count = len({item["part_code"] for item in linked})
                        sample_features.append({
                            "damage_feature_id": feature["damage_feature_id"],
                            "damage_bbox": list(damage_bbox(feature["damage_polygon"])),
                            "mapping_status": "PAIRED" if code_count == 1 else "AMBIGUOUS" if code_count else "UNPAIRED",
                            "candidates": linked,
                        })
                    review_rows.append({"case_image_id": image["case_image_id"],
                                        "source_image_ref": image["source_image_ref"],
                                        "image_path": str(path), "run_status": status,
                                        "features": sample_features})
                if args.dry_run:
                    mapped = Counter()
                    for feature in features:
                        linked_codes = {part["part_code"] for part in parts
                                        if overlap(damage_bbox(feature["damage_polygon"]), part["xywh"]) >= PART_LINK_MIN_OVERLAP}
                        mapped["PAIRED" if len(linked_codes) == 1 else "AMBIGUOUS" if linked_codes else "UNPAIRED"] += 1
                        mapped["candidate"] += sum(
                            1 for part in parts
                            if overlap(damage_bbox(feature["damage_polygon"]), part["xywh"]) >= PART_LINK_MIN_OVERLAP
                        )
                    summary.update(mapped)
                else:
                    with conn.cursor() as cur:
                        inference_id = upsert_inference(cur, image["case_image_id"], status, len(parts), raw, None, sha)
                        summary.update(replace_mappings(cur, inference_id, features, parts))
                    conn.commit()
                summary[status] += 1
            except Exception as exc:
                summary["ERROR"] += 1
                summary[f"ERROR_{type(exc).__name__}"] += 1
                if not args.dry_run:
                    try:
                        with conn.cursor() as cur:
                            upsert_inference(cur, image["case_image_id"], "ERROR", 0, [], type(exc).__name__, sha)
                        conn.commit()
                    except Exception:
                        conn.rollback()
                continue
    result = {"pipeline_version_id": args.pipeline_version_id, "model_name": MODEL_NAME,
              "model_version": MODEL_VERSION, "resume": args.resume, "dry_run": args.dry_run,
              **dict(summary), "status": "DRY_RUN" if args.dry_run else "SUCCEEDED"}
    if args.review_output:
        args.review_output.parent.mkdir(parents=True, exist_ok=True)
        args.review_output.write_text(json.dumps({"rows": review_rows}, ensure_ascii=False, indent=2), encoding="utf-8")
        result["review_output"] = str(args.review_output)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
