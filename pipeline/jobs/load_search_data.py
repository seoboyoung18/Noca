"""damage_part 중심 검색 데이터와 견적 기반 사례 부품 후보를 적재한다.

원천 견적 전체는 ``load_estimate_raw.py``가 별도 보존한다. 이 job은
``is_final_searchable_case=True``인 사례만 처리하며, 이미지가 없거나
``car_class``가 허용값으로 해석되지 않는 사례는 검색 테이블에 넣지 않고
``data_validation_error``에 기록한다.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[1]
if str(PIPELINE_ROOT) not in sys.path:
    sys.path.insert(0, str(PIPELINE_ROOT))

from standardization import PARTS, normalize_estimate_item
from standardization.damage_part_pairing import damage_part_roi_rows

ESTIMATE_REL = Path("1.Training") / "1.원천데이터_230126_add" / "TS_99. 붙임_견적서"
ALLOWED_CLASSES = {"CityCar", "Compact", "Mid-size", "Full-size"}
CASE_RE = re.compile(r"^(?:as|sc)-\d+$")
LABEL_RE = re.compile(r"_(?P<case>(?:as|sc)-\d+)\.json$", re.IGNORECASE)
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}
# 한 사례의 두 이미지 유형은 함께 적재하지만 역할은 섞지 않는다.
IMAGE_GROUPS = (
    ("TRAIN", "DAMAGE", Path("1.Training/2.라벨링데이터/TL_damage/damage")),
    ("TRAIN", "DAMAGE_PART", Path("1.Training/2.라벨링데이터/TL_damage_part/damage_part")),
    ("VALIDATION", "DAMAGE", Path("2.Validation/2.라벨링데이터/VL_damage/damage")),
    ("VALIDATION", "DAMAGE_PART", Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part")),
)
SEARCH_LABEL_DIRS = tuple(group[2] for group in IMAGE_GROUPS)


def truth(value: Any) -> bool:
    return str(value).strip().lower() in {"true", "1", "yes"}


def source_for_case(case_id: str) -> str:
    return SOURCE_BY_PREFIX[case_id[:2].lower()]


def normalize_car_class(value: Any) -> str | None:
    return {
        "CityCar": "CityCar",
        "Compact": "Compact",
        "Compact car": "Compact",
        "Mid-size": "Mid-size",
        "Mid-size car": "Mid-size",
        "Full-size": "Full-size",
        "Full-size car": "Full-size",
    }.get(str(value or "").strip())


def year(value: Any) -> int | None:
    match = re.search(r"(?:19|20)\d{2}", str(value or ""))
    return int(match.group(0)) if match else None


def integer(value: Any) -> int | None:
    match = re.search(r"\d[\d,]*", str(value or ""))
    return int(match.group(0).replace(",", "")) if match else None


def money(value: Any) -> int | None:
    text = str(value or "").replace(",", "").strip()
    try:
        return int(float(text)) if text else None
    except ValueError:
        return None


def image_path_for_label(label_path: Path) -> Path:
    parts = list(label_path.parts)
    marker = "2.라벨링데이터"
    index = parts.index(marker)
    parts[index] = "1.원천데이터"
    parts[index + 1] = parts[index + 1].replace("TL_", "TS_").replace("VL_", "VS_")
    return Path(*parts[:-1])


def repair_case_image_key(case_id: int, case_image_id: int,
                          variant: str = "original", extension: str = "jpg") -> str:
    """RepairCaseImageKeys.key()와 같은 AI-Hub 사례 이미지 key를 만든다."""
    if case_id <= 0:
        raise ValueError("case_id must be positive")
    if case_image_id <= 0:
        raise ValueError("case_image_id must be positive")
    if variant not in {"original", "resized", "thumbnail", "blurred"}:
        raise ValueError(f"unsupported image variant: {variant}")
    extension = extension.strip().lower().lstrip(".")
    if not extension or "/" in extension or "\\" in extension or "." in extension:
        raise ValueError("extension must not contain path separators or dots")
    return f"repair-cases/{case_id}/images/{case_image_id}/{variant}.{extension}"


def load_readiness(path: Path, source: str) -> set[str]:
    selected: set[str] = set()
    with path.open(encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            case_id = str(row.get("case_id") or "").strip()
            if (
                CASE_RE.fullmatch(case_id)
                and truth(row.get("is_final_searchable_case"))
                and (source == "all" or source_for_case(case_id) == source)
            ):
                selected.add(case_id)
    return selected


def load_case_manifest(path: Path) -> set[str]:
    selected: set[str] = set()
    with path.open(encoding="utf-8-sig", newline="") as fp:
        reader = csv.DictReader(fp)
        has_purpose = "purpose" in (reader.fieldnames or [])
        non_dev_purposes: set[str] = set()
        for row in reader:
            case_id = str(row.get("case_id") or "").strip()
            if not CASE_RE.fullmatch(case_id):
                continue
            if has_purpose:
                purpose = str(row.get("purpose") or "").strip()
                if purpose != "DEV":
                    non_dev_purposes.add(purpose or "<empty>")
                    continue
            selected.add(case_id)

    if non_dev_purposes:
        values = ", ".join(sorted(non_dev_purposes))
        raise ValueError(
            "검색 DB 적재 manifest에는 purpose=DEV 사례만 포함할 수 있습니다. "
            f"발견된 목적: {values}. search_dev_cases.csv를 사용하세요."
        )
    return selected


def build_label_index(subset_root: Path, selected: set[str]) -> dict[str, list[Path]]:
    index: dict[str, list[Path]] = defaultdict(list)
    for relative_dir in SEARCH_LABEL_DIRS:
        label_dir = subset_root / relative_dir
        if not label_dir.is_dir():
            continue
        # os.scandir는 45만여 파일의 Path 객체 전체를 한꺼번에 만들지 않는다.
        with os.scandir(label_dir) as entries:
            for entry in entries:
                if not entry.is_file() or not entry.name.lower().endswith(".json"):
                    continue
                match = LABEL_RE.search(entry.name)
                if match and match.group("case") in selected:
                    index[match.group("case")].append(Path(entry.path))
    return index


def image_metadata_for_label(label_path: Path) -> tuple[str, str]:
    normalized = label_path.as_posix()
    for dataset_split, image_type, relative_dir in IMAGE_GROUPS:
        if relative_dir.as_posix() in normalized:
            return image_type, dataset_split
    raise ValueError(f"unknown image label group: {label_path}")


def load_mapping(workbook: Path) -> list[tuple[str, str]]:
    import pandas as pd

    frame = pd.read_excel(workbook, sheet_name="부품명 전체 매핑", header=3)
    required = {"기존 이름", "표준 코드", "매핑 상태"}
    missing = required - set(frame.columns)
    if missing:
        raise ValueError(f"워크북에 필요한 열이 없습니다: {sorted(missing)}")
    eligible = frame[
        frame["매핑 상태"].isin({"MAPPED", "MAPPED_EXTENDED"})
        & frame["표준 코드"].notna()
        & frame["기존 이름"].notna()
    ][["기존 이름", "표준 코드"]]
    rows = [(str(raw).strip(), str(code).strip()) for raw, code in eligible.itertuples(index=False, name=None)]
    if any(not raw or not code for raw, code in rows):
        raise ValueError("part_name_mapping에 빈 raw_name 또는 part_code가 있습니다")
    if len({raw for raw, _ in rows}) != len(rows):
        raise ValueError("part_name_mapping의 확정 raw_name이 중복됩니다")
    if any(len(raw) > 200 for raw, _ in rows):
        raise ValueError("part_name_mapping.raw_name VARCHAR(200)을 넘는 값이 있습니다")
    return rows


def apply_part_seed(cur, path: Path) -> None:
    cur.execute(path.read_text(encoding="utf-8"))


def apply_mapping_seed(cur, path: Path) -> None:
    cur.execute(path.read_text(encoding="utf-8"))


def upsert_case(cur, case_id: str, estimate: dict[str, Any], car_class: str) -> None:
    vehicle = estimate.get("차량정보") or {}
    settlement = estimate.get("수리비 정산정보") or {}
    totals = settlement.get("합계") or {}
    source = source_for_case(case_id)
    cur.execute(
        """
        INSERT INTO repair_case(
            source, external_ref, manufacturer, model_name, car_class,
            model_year, repair_year, labor_rate, total_cost, claim_amount, paid_amount)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (source, external_ref) DO UPDATE SET
            manufacturer=EXCLUDED.manufacturer, model_name=EXCLUDED.model_name,
            car_class=EXCLUDED.car_class, model_year=EXCLUDED.model_year,
            repair_year=EXCLUDED.repair_year, labor_rate=EXCLUDED.labor_rate,
            total_cost=EXCLUDED.total_cost, claim_amount=EXCLUDED.claim_amount,
            paid_amount=EXCLUDED.paid_amount
        RETURNING case_id
        """,
        (
            source, case_id, vehicle.get("제작사/차종"), vehicle.get("모델"), car_class,
            year(vehicle.get("최초등록일")), year(vehicle.get("입고일자")),
            integer(vehicle.get("탈부착M/H")),
            money(totals.get("총계")) if source == "AIHUB_AS" else None,
            money(totals.get("청구액")) if source == "AIHUB_SC" else None,
            money(totals.get("지급액")) if source == "AIHUB_SC" else None,
        ),
    )
    return cur.fetchone()[0]


def upsert_images(cur, case_pk: int, label_paths: list[Path], dataset_root: Path) -> dict[str, int]:
    loaded: dict[str, int] = defaultdict(int)
    for label_path in sorted(label_paths):
        # readiness 검증이 라벨·이미지 조인을 끝냈고, 원천 파일명은 라벨 stem과
        # 동일하다. 전수 적재에서 선별된 라벨 JSON을 다시 파싱하지 않는다.
        image_path = image_path_for_label(label_path) / (label_path.stem + ".jpg")
        if not image_path.is_file():
            raise FileNotFoundError(f"라벨에 대응하는 이미지가 없습니다: {image_path}")
        source_image_ref = image_path.relative_to(dataset_root).as_posix()
        image_type, source_dataset_split = image_metadata_for_label(label_path)
        cur.execute(
            """
            INSERT INTO repair_case_image(
                case_id, source_image_ref, storage_key, image_type, source_dataset_split,
                quality_status, is_searchable)
            VALUES (%s,%s,%s,%s,%s,NULL,%s)
            ON CONFLICT (source_image_ref) DO UPDATE SET
                case_id=EXCLUDED.case_id, storage_key=EXCLUDED.storage_key,
                image_type=EXCLUDED.image_type, source_dataset_split=EXCLUDED.source_dataset_split,
                quality_status=EXCLUDED.quality_status, is_searchable=EXCLUDED.is_searchable
            RETURNING case_image_id
            """,
            (case_pk, source_image_ref, source_image_ref, image_type, source_dataset_split,
             image_type == "DAMAGE_PART"),
        )
        case_image_pk = cur.fetchone()[0]
        storage_key = repair_case_image_key(case_pk, case_image_pk)
        cur.execute(
            "UPDATE repair_case_image SET storage_key=%s WHERE case_image_id=%s",
            (storage_key, case_image_pk),
        )
        loaded[image_type] += 1
    return loaded


def damage_part_pairing_counts(label_paths: list[Path]) -> dict[str, int]:
    """정식 검색 이미지의 ROI·part pairing 상태를 적재 summary로 집계한다."""
    counts: dict[str, int] = defaultdict(int)
    for label_path in label_paths:
        image_type, _ = image_metadata_for_label(label_path)
        if image_type != "DAMAGE_PART":
            continue
        document = json.loads(label_path.read_text(encoding="utf-8-sig"))
        counts["damage_part_image_count"] += 1
        for row in damage_part_roi_rows(document):
            counts["damage_part_damage_count"] += 1
            if row["match_status"] == "PAIRED":
                counts["paired_roi_count"] += 1
                counts["strict_searchable_roi_count"] += 1
            elif row["match_status"] == "AMBIGUOUS":
                counts["ambiguous_part_match_count"] += 1
                counts["vector_only_roi_count"] += 1
            else:
                counts["unpaired_damage_count"] += 1
                counts["vector_only_roi_count"] += 1

    return dict(counts)


def upsert_damage_part_annotations(cur, case_pk: int, label_paths: list[Path], dataset_root: Path) -> int:
    """DAMAGE_PART의 직접 part 영역을 보존하되 DAMAGE ROI와 연결하지 않는다."""
    loaded = 0
    for label_path in label_paths:
        image_type, _ = image_metadata_for_label(label_path)
        if image_type != "DAMAGE_PART":
            continue
        source_image_ref = image_path_for_label(label_path).relative_to(dataset_root).as_posix()
        cur.execute("SELECT case_image_id FROM repair_case_image WHERE source_image_ref=%s", (source_image_ref,))
        row = cur.fetchone()
        if not row:
            raise ValueError(f"damage_part image is not loaded: {label_path}")
        for part_code, source_annotation_ref, polygon in direct_damage_part_annotations(label_path):
            cur.execute(
                """
                INSERT INTO repair_case_image_part_annotation(
                    case_image_id, part_code, source_annotation_ref, part_polygon)
                VALUES (%s,%s,%s,%s::jsonb)
                ON CONFLICT (case_image_id, source_annotation_ref) DO UPDATE SET
                    part_code=EXCLUDED.part_code, part_polygon=EXCLUDED.part_polygon
                """,
                (row[0], part_code, source_annotation_ref, json.dumps(polygon, ensure_ascii=False)),
            )
            loaded += 1
    return loaded


def direct_damage_part_annotations(label_path: Path) -> list[tuple[str, str, Any]]:
    """보조 이미지의 직접 part 라벨만 추출한다. DAMAGE ROI에는 빈 목록을 돌려준다."""
    image_type, _ = image_metadata_for_label(label_path)
    if image_type != "DAMAGE_PART":
        return []
    part_by_raw = {values[0].casefold(): code for code, values in PARTS.items()}
    document = json.loads(label_path.read_text(encoding="utf-8-sig"))
    rows: list[tuple[str, str, Any]] = []
    for ordinal, annotation in enumerate(document.get("annotations") or [], start=1):
        part_code = part_by_raw.get(str(annotation.get("part") or "").strip().casefold())
        if part_code:
            rows.append((str(annotation.get("id") or ordinal), part_code,
                         annotation.get("segmentation") or annotation.get("bbox")))
    return [(part_code, source_ref, polygon) for source_ref, part_code, polygon in rows]


def upsert_estimate_items(cur, case_pk: int, estimate: dict[str, Any], source: str,
                          mapping: dict[str, str]) -> tuple[int, dict[str, int], list[dict[str, Any]]]:
    """견적 항목을 사례 부품 후보로 적재한다. ROI part_code에는 쓰지 않는다."""
    loaded = 0
    stats: dict[str, int] = defaultdict(int)
    errors: list[dict[str, Any]] = []
    for ordinal, raw_item in enumerate(estimate.get("수리내역") or [], start=1):
        if not isinstance(raw_item, dict):
            errors.append({"type": "invalid_estimate_item", "ordinal": ordinal})
            continue
        try:
            item = normalize_estimate_item(raw_item, source, mapping, ordinal)
        except ValueError as exc:
            reason = str(exc)
            stats[reason] += 1
            if reason == "unmapped_part":
                stats["part_mapping_missing"] += 1
            errors.append({"type": reason, "ordinal": ordinal,
                           "raw_item_name": raw_item.get("작업항목 및 부품명")})
            continue
        if item["part_code"]:
            stats["part_mapping_success"] += 1
        else:
            stats["part_mapping_missing"] += 1
        cur.execute(
            """
            INSERT INTO repair_case_item(
                case_id, source_item_key, part_code, raw_item_name, line_type, work_type, work_code,
                assessment_status, hq, reference_part_price, part_cost, paint_material_cost,
                labor_cost, pre_adjustment_part_cost, pre_adjustment_labor_cost,
                post_adjustment_part_cost, post_adjustment_labor_cost, item_total)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (case_id, source_item_key) DO UPDATE SET
                part_code=EXCLUDED.part_code, raw_item_name=EXCLUDED.raw_item_name,
                line_type=EXCLUDED.line_type, work_type=EXCLUDED.work_type, work_code=EXCLUDED.work_code,
                assessment_status=EXCLUDED.assessment_status, hq=EXCLUDED.hq,
                reference_part_price=EXCLUDED.reference_part_price, part_cost=EXCLUDED.part_cost,
                paint_material_cost=EXCLUDED.paint_material_cost, labor_cost=EXCLUDED.labor_cost,
                pre_adjustment_part_cost=EXCLUDED.pre_adjustment_part_cost,
                pre_adjustment_labor_cost=EXCLUDED.pre_adjustment_labor_cost,
                post_adjustment_part_cost=EXCLUDED.post_adjustment_part_cost,
                post_adjustment_labor_cost=EXCLUDED.post_adjustment_labor_cost, item_total=EXCLUDED.item_total
            """,
            (case_pk, item["source_item_key"], item["part_code"], item["raw_item_name"],
             item["line_type"], item["work_type"], item["work_code"], item["assessment_status"],
             item["hq"], item["reference_part_price"], item["part_cost"], item["paint_material_cost"],
             item["labor_cost"], item["pre_part_cost"], item["pre_labor_cost"],
             item["post_part_cost"], item["post_labor_cost"], item["item_total"]),
        )
        loaded += 1
    return loaded, stats, errors


def class_from_labels(label_paths: list[Path]) -> tuple[str | None, str | None]:
    classes: list[str] = []
    for label_path in sorted(label_paths):
        document = json.loads(label_path.read_text(encoding="utf-8-sig"))
        raw = (document.get("categories") or {}).get("supercategory_name")
        if not raw:
            return None, f"car_class가 없습니다: {label_path.name}"
        raw_text = str(raw).strip()
        normalized = normalize_car_class(raw_text)
        if not normalized:
            return None, f"허용되지 않는 car_class: {raw_text}"
        classes.append(normalized)

    unique_classes = sorted(set(classes))
    if len(unique_classes) != 1:
        return None, f"동일 사례 라벨 간 car_class 불일치: {unique_classes}"
    return unique_classes[0], None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--readiness-csv", type=Path, required=True)
    parser.add_argument("--case-manifest", type=Path, required=True,
                        help="DEV/DEMO/EVAL 등 사례 단위 subset manifest")
    mapping = parser.add_mutually_exclusive_group(required=True)
    mapping.add_argument("--mapping-workbook", type=Path)
    mapping.add_argument("--mapping-seed-sql", type=Path,
                         help="generate_part_name_mapping_seed.py로 만든 SQL")
    parser.add_argument("--part-code-seed", type=Path,
                        default=Path(__file__).resolve().parents[2] / "Docs" / "Erd" / "A307_part_code_seed.sql")
    parser.add_argument("--dataset-root", type=Path)
    parser.add_argument("--dsn", help="PostgreSQL DSN")
    parser.add_argument("--source", choices=("all", "AIHUB_AS", "AIHUB_SC"), default="all")
    parser.add_argument("--commit-every", type=int, default=500)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.commit_every < 1:
        parser.error("--commit-every must be positive")

    subset_root = args.subset_root.resolve()
    dataset_root = (args.dataset_root or subset_root.parent).resolve()
    selected = load_readiness(args.readiness_csv.resolve(), args.source)
    selected &= load_case_manifest(args.case_manifest.resolve())
    if args.limit is not None:
        selected = set(sorted(selected)[:args.limit])
    label_index = build_label_index(subset_root, selected)
    estimate_dir = subset_root / ESTIMATE_REL
    mapping_rows = load_mapping(args.mapping_workbook.resolve()) if args.mapping_workbook else []
    summary = {
        "policy": "damage_part_roi_with_same_image_part_pairing",
        "image_source_scope": "damage_part_search__damage_optional_reference",
        "readiness_selected": len(selected),
        "mapping_rows": len(mapping_rows) if args.mapping_workbook else "seed_sql",
        "raw_estimate_scope": "separate_aihub_estimate_raw_all_125006",
        "annotation_scope": "damage_part_damage_and_part_annotations_only",
    }

    errors: list[tuple[str, str, dict[str, Any]]] = []
    valid: list[tuple[str, Path, str, list[Path]]] = []
    for case_id in sorted(selected):
        labels = label_index.get(case_id, [])
        estimate_path = estimate_dir / f"{case_id}.json"
        if not labels:
            errors.append(("missing_image", case_id, {"reason": "readiness case에 라벨 파일이 없습니다"}))
            continue
        image_paths = [
            image_path_for_label(label_path) / (label_path.stem + ".jpg")
            for label_path in labels
        ]
        missing_images = [str(path) for path in image_paths if not path.is_file()]
        if missing_images:
            errors.append((
                "missing_image",
                case_id,
                {"paths": missing_images, "count": len(missing_images)},
            ))
            continue
        if not estimate_path.is_file():
            errors.append(("missing_estimate", case_id, {"path": str(estimate_path)}))
            continue
        try:
            car_class, reason = class_from_labels(labels)
            if reason or car_class not in ALLOWED_CLASSES:
                errors.append(("invalid_car_class", case_id, {"reason": reason or car_class}))
                continue
            # 메모리에는 경로만 보관한다. 전수 적재에서는 견적 JSON 객체를
            # 모두 쌓지 않고 DB 배치 직전에 읽는다.
            json.loads(estimate_path.read_text(encoding="utf-8-sig"))
            valid.append((case_id, estimate_path, car_class, labels))
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            errors.append(("case_input_error", case_id, {"reason": str(exc)}))

    preflight_pairing: dict[str, int] = defaultdict(int)
    for _, _, _, labels in valid:
        for key, value in damage_part_pairing_counts(labels).items():
            preflight_pairing[key] += value

    summary.update({
        "valid_case_count": len(valid),
        "excluded_case_count": len(errors),
        "label_count": sum(len(labels) for _, _, _, labels in valid),
        "error_counts": dict(sorted({kind: sum(1 for error in errors if error[0] == kind) for kind, _, _ in errors}.items())),
        "paired_roi_count": preflight_pairing.get("paired_roi_count", 0),
        "unpaired_damage_count": preflight_pairing.get("unpaired_damage_count", 0),
        "ambiguous_part_match_count": preflight_pairing.get("ambiguous_part_match_count", 0),
        "missing_part_count": preflight_pairing.get("unpaired_damage_count", 0),
        "damage_part_image_count": preflight_pairing.get("damage_part_image_count", 0),
        "damage_reference_image_count": sum(
            1 for _, _, _, labels in valid for label in labels
            if image_metadata_for_label(label)[0] == "DAMAGE"
        ),
        "strict_searchable_roi_count": preflight_pairing.get("strict_searchable_roi_count", 0),
        "vector_only_roi_count": preflight_pairing.get("vector_only_roi_count", 0),
    })
    if args.dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        raise SystemExit(1 if errors else 0)
    if not args.dsn:
        parser.error("실제 적재에는 --dsn이 필요합니다")

    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit('psycopg가 필요합니다: pip install "psycopg[binary]"') from exc

    with psycopg.connect(args.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO batch_job_execution(job_name, job_version, status, input_ref, summary)
                VALUES (%s, %s, 'RUNNING', %s, %s::jsonb)
                RETURNING batch_job_execution_id
                """,
                ("search_data_load", "001", str(args.readiness_csv), json.dumps(summary, ensure_ascii=False)),
            )
            execution_id = cur.fetchone()[0]
        conn.commit()
        with conn.cursor() as cur:
            apply_part_seed(cur, args.part_code_seed.resolve())
        conn.commit()
        with conn.cursor() as cur:
            if args.mapping_seed_sql:
                apply_mapping_seed(cur, args.mapping_seed_sql.resolve())
            else:
                cur.executemany(
                    """
                    INSERT INTO part_name_mapping(raw_name, part_code)
                    VALUES (%s,%s)
                    ON CONFLICT (raw_name) DO UPDATE SET part_code=EXCLUDED.part_code
                    """,
                    mapping_rows,
                )
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT raw_name, part_code FROM part_name_mapping")
            db_mapping = {str(raw): str(code) for raw, code in cur}

        loaded_images: dict[str, int] = defaultdict(int)
        loaded_items = 0
        loaded_part_annotations = 0
        pairing_counts: dict[str, int] = defaultdict(int)
        item_stats: dict[str, int] = defaultdict(int)
        cases_with_estimate_part_candidate = 0
        for start in range(0, len(valid), args.commit_every):
            batch = valid[start : start + args.commit_every]
            with conn.cursor() as cur:
                for case_id, estimate_path, car_class, labels in batch:
                    estimate = json.loads(estimate_path.read_text(encoding="utf-8-sig"))
                    case_pk = upsert_case(cur, case_id, estimate, car_class)
                    image_counts = upsert_images(cur, case_pk, labels, dataset_root)
                    for key, value in image_counts.items():
                        loaded_images[key] += value
                    for key, value in damage_part_pairing_counts(labels).items():
                        pairing_counts[key] += value
                    loaded_part_annotations += upsert_damage_part_annotations(
                        cur, case_pk, labels, dataset_root)
                    item_count, stats, item_errors = upsert_estimate_items(
                        cur, case_pk, estimate, source_for_case(case_id), db_mapping)
                    loaded_items += item_count
                    for key, value in stats.items():
                        item_stats[key] += value
                    if stats.get("part_mapping_success", 0):
                        cases_with_estimate_part_candidate += 1
                    for detail in item_errors:
                        errors.append(("estimate_item_" + detail["type"], case_id, detail))
            conn.commit()

        summary["loaded_cases"] = len(valid)
        summary["loaded_images"] = dict(sorted(loaded_images.items()))
        summary["loaded_repair_case_items"] = loaded_items
        summary["loaded_damage_part_annotations"] = loaded_part_annotations
        summary.update({
            "paired_roi_count": pairing_counts.get("paired_roi_count", 0),
            "unpaired_damage_count": pairing_counts.get("unpaired_damage_count", 0),
            "ambiguous_part_match_count": pairing_counts.get("ambiguous_part_match_count", 0),
            "missing_part_count": pairing_counts.get("unpaired_damage_count", 0),
            "damage_part_image_count": pairing_counts.get("damage_part_image_count", 0),
            "damage_reference_image_count": loaded_images.get("DAMAGE", 0),
            "strict_searchable_roi_count": pairing_counts.get("strict_searchable_roi_count", 0),
            "vector_only_roi_count": pairing_counts.get("vector_only_roi_count", 0),
        })
        summary["estimate_item_mapping"] = dict(sorted(item_stats.items()))
        summary["estimate_item_mapping"].setdefault("part_mapping_success", 0)
        summary["estimate_item_mapping"].setdefault("part_mapping_missing", 0)
        # 확정 mapping은 raw_name당 하나의 part_code만 허용한다. 향후 모호 mapping을
        # 별도 입력으로 받으면 이 값도 증가시킨다.
        summary["estimate_item_mapping"].setdefault("part_mapping_ambiguous", 0)
        summary["cases_with_estimate_part_candidate"] = cases_with_estimate_part_candidate
        summary["cases_with_damage_geometry"] = len(valid)
        summary["status"] = "PARTIAL" if errors else "SUCCEEDED"
        summary["error_counts"] = dict(sorted(
            {kind: sum(1 for error in errors if error[0] == kind) for kind, _, _ in errors}.items()
        ))
        with conn.cursor() as cur:
            for error_type, case_id, detail in errors:
                cur.execute(
                    """
                    INSERT INTO data_validation_error(
                        batch_job_execution_id, error_type, case_external_ref, error_detail)
                    VALUES (%s,%s,%s,%s::jsonb)
                    """,
                    (execution_id, error_type, case_id, json.dumps(detail, ensure_ascii=False)),
                )
            cur.execute(
                """
                UPDATE batch_job_execution
                   SET status=%s, completed_at=now(), summary=%s::jsonb
                 WHERE batch_job_execution_id=%s
                """,
                (summary["status"], json.dumps(summary, ensure_ascii=False), execution_id),
            )
        conn.commit()
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
