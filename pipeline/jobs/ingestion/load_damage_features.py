"""DAMAGE annotation을 별도 pipeline version의 ROI feature로 적재한다.

DAMAGE에는 직접 part bbox 근거가 없으므로 feature.part_code는 항상 NULL이고,
pair_status는 UNPAIRED다. annotation의 repair 부품 후보는
repair_case_damage_feature_part_hint에만 저장한다.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for import_root in (REPO_ROOT, PIPELINE_ROOT):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))

from pipeline.jobs.ingestion.load_search_data import (  # noqa: E402
    _confidence,
    _image_size,
    _roi_box_payload,
    build_label_index,
    image_metadata_for_label,
    load_case_manifest,
    load_readiness,
    source_for_case,
    source_image_for_label,
)
from shared.vision.damage_part_pairing import annotation_ref, damage_code, valid_geometry  # noqa: E402
from shared.vision.normalizer import NormalizationError, normalize_repair_label  # noqa: E402
from shared.vision.roi import (  # noqa: E402
    PAD_RATIO,
    QUALITY_GOOD,
    QUALITY_PARTIAL_PART,
    feature_quality,
    roi_box,
    roi_quality,
    source_bbox,
)


def damage_labels(label_paths: list[Path]) -> list[Path]:
    return [
        path for path in label_paths
        if image_metadata_for_label(path)[0] == "DAMAGE"
    ]


def repair_hints(annotation: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    """annotation repair를 part별로 합치고, 해석 불가 항목 수를 반환한다."""
    raw_values = annotation.get("repair")
    values = raw_values if isinstance(raw_values, list) else [raw_values]
    by_part: dict[str, dict[str, Any]] = {}
    unknown_count = 0
    for value in values:
        if not isinstance(value, str) or not value.strip():
            continue
        try:
            normalized = normalize_repair_label(value)
        except NormalizationError:
            unknown_count += 1
            continue
        part_code = normalized["part_code"]
        current = by_part.setdefault(part_code, {
            "part_code": part_code,
            "raw_part_name": value.split(":", 1)[0].strip(),
            "repair_methods": [],
        })
        for method in normalized["work_codes"]:
            if method not in current["repair_methods"]:
                current["repair_methods"].append(method)
    return list(by_part.values()), unknown_count


def selected_damage_labels(
    *,
    subset_root: Path,
    readiness_csv: Path,
    case_manifest: Path,
    manifest_scope: str = "dev",
    source: str = "all",
    limit: int | None = None,
) -> dict[str, list[Path]]:
    selected = load_readiness(readiness_csv.resolve(), source)
    selected &= load_case_manifest(case_manifest.resolve(), scope=manifest_scope)
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be positive")
        selected = set(sorted(selected)[:limit])
    label_index = build_label_index(subset_root.resolve(), selected)
    return {
        case_id: damage_labels(sorted(label_index.get(case_id, [])))
        for case_id in sorted(selected)
    }


def summarize_labels(
    labels_by_case: dict[str, list[Path]],
    *,
    dataset_root: Path,
    subset_root: Path,
) -> dict[str, Any]:
    counts: Counter[str] = Counter()
    repair_hint_count = 0
    unknown_repair_label_count = 0
    multi_part_hint_count = 0
    for labels in labels_by_case.values():
        for label_path in labels:
            image_path, _ = source_image_for_label(label_path, dataset_root, subset_root)
            if not image_path.is_file():
                raise FileNotFoundError(f"라벨에 대응하는 이미지가 없습니다: {image_path}")
            document = json.loads(label_path.read_text(encoding="utf-8-sig"))
            for annotation in document.get("annotations") or []:
                if not isinstance(annotation, dict) or not damage_code(annotation):
                    continue
                if not valid_geometry(annotation):
                    counts["invalid_damage_annotation_count"] += 1
                    continue
                if not (
                    annotation.get("segmentation")
                    or annotation.get("polygon")
                    or annotation.get("bbox")
                ):
                    counts["invalid_damage_annotation_count"] += 1
                    continue
                hints, unknown_count = repair_hints(annotation)
                repair_hint_count += len(hints)
                unknown_repair_label_count += unknown_count
                if len(hints) > 1:
                    multi_part_hint_count += 1
                counts["damage_annotation_count"] += 1
    return {
        "damage_feature_count": counts["damage_annotation_count"],
        "invalid_damage_annotation_count": counts["invalid_damage_annotation_count"],
        "repair_hint_count": repair_hint_count,
        "unknown_repair_label_count": unknown_repair_label_count,
        "multi_part_hint_count": multi_part_hint_count,
    }


def assert_damage_pipeline_version(cur, pipeline_version_id: int) -> None:
    cur.execute(
        """
        SELECT params->>'image_source', params->>'part_code_policy'
          FROM feature_pipeline_version
         WHERE pipeline_version_id=%s
        """,
        (pipeline_version_id,),
    )
    row = cur.fetchone()
    if not row or row[0] != "DAMAGE" or row[1] != "NULL":
        raise ValueError(
            "DAMAGE 전용 pipeline version이 아닙니다. "
            "pipeline/sql/011_damage_pipeline_v2.sql을 먼저 적용하세요."
        )


def upsert_damage_case(
    cur,
    case_id: str,
    labels: list[Path],
    *,
    dataset_root: Path,
    subset_root: Path,
    pipeline_version_id: int,
) -> dict[str, int]:
    counts: Counter[str] = Counter()
    source = source_for_case(case_id)
    for label_path in labels:
        document = json.loads(label_path.read_text(encoding="utf-8-sig"))
        image_size = _image_size(document)
        _, source_image_ref = source_image_for_label(label_path, dataset_root, subset_root)
        cur.execute(
            """
            SELECT i.case_image_id
              FROM repair_case_image i
              JOIN repair_case c ON c.case_id=i.case_id
             WHERE c.source=%s AND c.external_ref=%s
               AND i.source_image_ref=%s AND i.image_type='DAMAGE'
            """,
            (source, case_id, source_image_ref),
        )
        image_row = cur.fetchone()
        if not image_row:
            raise ValueError(f"DAMAGE image가 repair_case_image에 없습니다: {label_path}")
        case_image_id = image_row[0]

        for roi_index, annotation in enumerate(document.get("annotations") or []):
            if not isinstance(annotation, dict) or not damage_code(annotation):
                continue
            if not valid_geometry(annotation):
                counts["invalid_damage_annotation_count"] += 1
                continue
            bbox = source_bbox(annotation)
            confidence = _confidence(annotation)
            quality_status = feature_quality(roi_quality(bbox, confidence), None)
            is_searchable = quality_status in {QUALITY_GOOD, QUALITY_PARTIAL_PART}
            damage_polygon = (
                annotation.get("segmentation")
                or annotation.get("polygon")
                or annotation.get("bbox")
            )
            if damage_polygon is None:
                counts["invalid_damage_annotation_count"] += 1
                continue
            box, effective_padding, clipped, _ = roi_box(
                bbox, image_size, pad_ratio=PAD_RATIO
            )
            cur.execute(
                """
                INSERT INTO repair_case_damage_feature(
                    case_image_id, pipeline_version_id, roi_index, damage_type,
                    damage_polygon, roi_box, part_code, pair_status, quality_status,
                    confidence, is_searchable)
                VALUES (%s,%s,%s,%s,%s::jsonb,%s::jsonb,NULL,'UNPAIRED',%s,%s,%s)
                ON CONFLICT (case_image_id, pipeline_version_id, roi_index) DO UPDATE SET
                    damage_type=EXCLUDED.damage_type,
                    damage_polygon=EXCLUDED.damage_polygon,
                    roi_box=EXCLUDED.roi_box,
                    part_code=NULL,
                    pair_status='UNPAIRED',
                    quality_status=EXCLUDED.quality_status,
                    confidence=EXCLUDED.confidence,
                    is_searchable=EXCLUDED.is_searchable
                RETURNING damage_feature_id
                """,
                (
                    case_image_id,
                    pipeline_version_id,
                    roi_index,
                    damage_code(annotation),
                    json.dumps(damage_polygon, ensure_ascii=False),
                    json.dumps(_roi_box_payload(box, effective_padding, clipped), ensure_ascii=False),
                    quality_status,
                    confidence,
                    is_searchable,
                ),
            )
            feature_id = cur.fetchone()[0]
            counts["damage_feature_count"] += 1

            hints, unknown_count = repair_hints(annotation)
            counts["unknown_repair_label_count"] += unknown_count
            if len(hints) > 1:
                counts["multi_part_hint_count"] += 1
            for hint in hints:
                cur.execute(
                    """
                    INSERT INTO repair_case_damage_feature_part_hint(
                        damage_feature_id, part_code, hint_source, raw_part_name,
                        repair_methods, source_annotation_ref)
                    VALUES (%s,%s,'REPAIR',%s,%s::jsonb,%s)
                    ON CONFLICT (
                        damage_feature_id, part_code, hint_source, source_annotation_ref
                    ) DO UPDATE SET
                        raw_part_name=EXCLUDED.raw_part_name,
                        repair_methods=EXCLUDED.repair_methods
                    """,
                    (
                        feature_id,
                        hint["part_code"],
                        hint["raw_part_name"],
                        json.dumps(hint["repair_methods"], ensure_ascii=False),
                        annotation_ref(annotation, roi_index + 1),
                    ),
                )
                counts["repair_hint_count"] += 1
    return dict(counts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--readiness-csv", type=Path, required=True)
    parser.add_argument("--case-manifest", type=Path, required=True)
    parser.add_argument(
        "--manifest-scope",
        choices=("dev", "train-only"),
        default="dev",
        help="dev는 DEV 사례만, train-only는 TRAIN_ONLY의 DEV·POOL 사례를 적재합니다.",
    )
    parser.add_argument(
        "--dsn",
        help="지원 중단 옵션. 접속 문자열은 DATABASE_URL 환경변수만 사용합니다.",
    )
    parser.add_argument("--pipeline-version-id", type=int, required=True)
    parser.add_argument("--source", choices=("all", "AIHUB_AS", "AIHUB_SC"), default="all")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--commit-every", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.commit_every < 1:
        parser.error("--commit-every must be positive")
    if args.dsn is not None:
        parser.error("DB 접속 정보는 DATABASE_URL 환경변수만 사용합니다")

    subset_root = args.subset_root.resolve()
    dataset_root = args.dataset_root.resolve()
    labels_by_case = selected_damage_labels(
        subset_root=subset_root,
        readiness_csv=args.readiness_csv,
        case_manifest=args.case_manifest,
        manifest_scope=args.manifest_scope,
        source=args.source,
        limit=args.limit,
    )
    summary: dict[str, Any] = {
        "pipeline_version_id": args.pipeline_version_id,
        "image_source": "DAMAGE",
        "part_code_policy": "NULL",
        "pair_status": "UNPAIRED",
        "manifest_scope": args.manifest_scope,
        "selected_case_count": len(labels_by_case),
        "damage_image_count": sum(len(labels) for labels in labels_by_case.values()),
        **summarize_labels(labels_by_case, dataset_root=dataset_root, subset_root=subset_root),
    }
    if args.dry_run:
        summary["status"] = "DRY_RUN"
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        parser.error("실제 적재에는 DATABASE_URL 환경변수가 필요합니다")
    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit('psycopg가 필요합니다: pip install "psycopg[binary]"') from exc

    loaded: Counter[str] = Counter()
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            assert_damage_pipeline_version(cur, args.pipeline_version_id)
        conn.commit()
        try:
            cases = list(labels_by_case.items())
            for start in range(0, len(cases), args.commit_every):
                with conn.cursor() as cur:
                    for case_id, labels in cases[start:start + args.commit_every]:
                        for key, value in upsert_damage_case(
                            cur,
                            case_id,
                            labels,
                            dataset_root=dataset_root,
                            subset_root=subset_root,
                            pipeline_version_id=args.pipeline_version_id,
                        ).items():
                            loaded[key] += value
                conn.commit()
        except Exception:
            conn.rollback()
            raise
    summary.update(loaded)
    summary["status"] = "SUCCEEDED"
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
