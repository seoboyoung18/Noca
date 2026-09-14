"""AI-Hub 차량파손 JSON 라벨을 PostgreSQL 스테이징 테이블에 배치 적재한다.

대상 테이블은 `aihub_vehicle_case` / `aihub_vehicle_image` /
`aihub_damage_annotation` / `aihub_annotation_repair_method`이며,
검색 테이블(`repair_case` 계열)이 아니라 원천 라벨을 그대로 보관하는
스테이징 계층이다. 검색 테이블 적재는 `build_search_sample_sql.py`가 맡는다.

사전 실행:
  psql "$DATABASE_URL" -f <스테이징 DDL 경로>/A307_AIHUB_DAMAGE_DATASET.sql
  pip install -r pipeline/requirements.txt

적재 예시:
  python pipeline/jobs/ingestion/load_aihub_damage_dataset.py \
    --dataset-root "<AI-Hub 차량파손 데이터셋 경로>" \
    --dsn "$DATABASE_URL"
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class DatasetGroup:
    split: str
    label_type: str
    image_dir: Path
    label_dir: Path


GROUP_PATHS = (
    ("TRAIN", "DAMAGE", "1.Training/1.원천데이터/TS_damage/damage", "1.Training/2.라벨링데이터/TL_damage/damage"),
    ("TRAIN", "DAMAGE_PART", "1.Training/1.원천데이터/TS_damage_part/damage_part", "1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    ("VALIDATION", "DAMAGE", "2.Validation/1.원천데이터/VS_damage/damage", "2.Validation/2.라벨링데이터/VL_damage/damage"),
    ("VALIDATION", "DAMAGE_PART", "2.Validation/1.원천데이터/VS_damage_part/damage_part", "2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)

REPAIR_METHODS = {"coating", "repair", "sheet_metal", "exchange"}

# 라벨 JSON의 date_created는 MM/DD/YYYY, 견적서 날짜 4개 필드는 YYYYMMDD다.
# 표본 5,000건(as 2,500 + sc 2,500) 전수 확인 결과 두 포맷 외의 값은 없었다.
DATE_FORMATS = ("%m/%d/%Y", "%Y-%m-%d", "%Y%m%d")

# 파싱하지 못한 날짜 원문. 조용히 None으로 떨어뜨리지 않고 실행 끝에 보고한다.
UNPARSED_DATES: Counter[str] = Counter()


def groups(dataset_root: Path) -> list[DatasetGroup]:
    data_root = dataset_root / "01.데이터"
    result = []
    for split, label_type, image_rel, label_rel in GROUP_PATHS:
        group = DatasetGroup(split, label_type, data_root / image_rel, data_root / label_rel)
        if not group.image_dir.is_dir() or not group.label_dir.is_dir():
            raise FileNotFoundError(f"압축 해제 폴더를 찾을 수 없습니다: {group}")
        result.append(group)
    return result


def parse_date(value: Any):
    """지원 포맷으로 날짜를 파싱한다. 실패한 원문은 UNPARSED_DATES에 기록한다."""
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            pass
    UNPARSED_DATES[text] += 1
    return None


def report_unparsed_dates() -> int:
    """파싱 실패 날짜를 요약 출력하고 실패 건수를 반환한다."""
    if not UNPARSED_DATES:
        return 0
    total = sum(UNPARSED_DATES.values())
    print(f"날짜 파싱 실패: {total}건 (고유 값 {len(UNPARSED_DATES)}종)")
    for text, count in UNPARSED_DATES.most_common(20):
        print(f"  {text!r}: {count}건")
    if len(UNPARSED_DATES) > 20:
        print(f"  ... 외 {len(UNPARSED_DATES) - 20}종")
    print(f"지원 포맷: {', '.join(DATE_FORMATS)}")
    return total


def parse_repair(raw: Any) -> list[tuple[str, str]]:
    values = raw if isinstance(raw, list) else [raw]
    parsed: set[tuple[str, str]] = set()
    for value in values:
        if not isinstance(value, str) or ":" not in value:
            continue
        part, methods = value.split(":", 1)
        for method in methods.split(","):
            normalized = method.strip().lower()
            if normalized in REPAIR_METHODS:
                parsed.add((part.strip(), normalized))
    return sorted(parsed)


def external_ref(document: dict[str, Any], file_name: str) -> str:
    annotations = document.get("annotations") or []
    if annotations and annotations[0].get("category_id"):
        return str(annotations[0]["category_id"])
    category = document.get("categories") or {}
    if category.get("id"):
        return str(category["id"])
    match = re.search(r"_(.+)\.[^.]+$", file_name)
    if not match:
        raise ValueError(f"category_id를 결정할 수 없습니다: {file_name}")
    return match.group(1)


def json_value(value: Any) -> str | None:
    return None if value is None else json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def load_document(cur, group: DatasetGroup, label_path: Path) -> tuple[int, int]:
    document = json.loads(label_path.read_text(encoding="utf-8-sig"))
    image = document["images"]
    annotations = document.get("annotations") or []
    first = annotations[0] if annotations else {}
    file_name = str(image["file_name"])
    ref = external_ref(document, file_name)
    category = document.get("categories") or {}

    cur.execute(
        """
        INSERT INTO aihub_vehicle_case(external_ref, car_class_raw, model_year, color_raw)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (external_ref) DO UPDATE SET
            car_class_raw = COALESCE(aihub_vehicle_case.car_class_raw, EXCLUDED.car_class_raw),
            model_year = COALESCE(aihub_vehicle_case.model_year, EXCLUDED.model_year),
            color_raw = COALESCE(aihub_vehicle_case.color_raw, EXCLUDED.color_raw),
            updated_at = now()
        RETURNING case_id
        """,
        (ref, category.get("supercategory_name"), first.get("year"), first.get("color")),
    )
    case_id = cur.fetchone()[0]
    image_path = group.image_dir / file_name
    info = document.get("info") or {}
    cur.execute(
        """
        INSERT INTO aihub_vehicle_image(
            case_id, dataset_split, label_type, source_image_id, file_name,
            file_path, label_path, width_px, height_px, source_name, source_created_on
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (dataset_split, label_type, file_name) DO UPDATE SET
            case_id = EXCLUDED.case_id,
            file_path = EXCLUDED.file_path,
            label_path = EXCLUDED.label_path,
            width_px = EXCLUDED.width_px,
            height_px = EXCLUDED.height_px
        RETURNING image_id
        """,
        (case_id, group.split, group.label_type, image.get("id"), file_name,
         str(image_path), str(label_path), image["width"], image["height"],
         info.get("name"), parse_date(info.get("date_created"))),
    )
    image_id = cur.fetchone()[0]

    annotation_count = 0
    for annotation in annotations:
        cur.execute(
            """
            INSERT INTO aihub_damage_annotation(
                image_id, source_annotation_id, damage_type_raw, part_name_raw,
                severity_level, area_px, bbox, segmentation, raw_repair, raw_annotation
            ) VALUES (%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s::jsonb,%s::jsonb)
            ON CONFLICT (image_id, source_annotation_id) DO UPDATE SET
                damage_type_raw = EXCLUDED.damage_type_raw,
                part_name_raw = EXCLUDED.part_name_raw,
                severity_level = EXCLUDED.severity_level,
                area_px = EXCLUDED.area_px,
                bbox = EXCLUDED.bbox,
                segmentation = EXCLUDED.segmentation,
                raw_repair = EXCLUDED.raw_repair,
                raw_annotation = EXCLUDED.raw_annotation
            RETURNING annotation_id
            """,
            (image_id, annotation["id"], annotation.get("damage"), annotation.get("part"),
             annotation.get("level"), annotation.get("area"), json_value(annotation.get("bbox")),
             json_value(annotation.get("segmentation")), json_value(annotation.get("repair")),
             json_value(annotation)),
        )
        annotation_id = cur.fetchone()[0]
        for part, method in parse_repair(annotation.get("repair")):
            cur.execute(
                """
                INSERT INTO aihub_annotation_repair_method(annotation_id, part_name_raw, repair_method)
                VALUES (%s,%s,%s) ON CONFLICT DO NOTHING
                """,
                (annotation_id, part, method),
            )
        annotation_count += 1
    return 1, annotation_count


def iter_labels(group: DatasetGroup) -> Iterable[Path]:
    yield from sorted(group.label_dir.glob("*.json"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--dsn", help="PostgreSQL DSN 또는 DATABASE_URL")
    parser.add_argument("--limit", type=int, help="그룹별 최대 파일 수(시험 적재용)")
    parser.add_argument("--commit-every", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true", help="DB 없이 JSON/경로만 검증")
    args = parser.parse_args()

    dataset_groups = groups(args.dataset_root.resolve())
    if args.dry_run:
        for group in dataset_groups:
            count = 0
            annotation_count = 0
            for label_path in iter_labels(group):
                doc = json.loads(label_path.read_text(encoding="utf-8-sig"))
                file_name = str(doc["images"]["file_name"])
                if not (group.image_dir / file_name).is_file():
                    raise FileNotFoundError(group.image_dir / file_name)
                external_ref(doc, file_name)
                parse_date((doc.get("info") or {}).get("date_created"))
                annotation_count += len(doc.get("annotations") or [])
                count += 1
                if args.limit and count >= args.limit:
                    break
            print(f"{group.split}/{group.label_type}: images={count}, annotations={annotation_count}")
        if report_unparsed_dates():
            raise SystemExit(1)
        return

    if not args.dsn:
        parser.error("실제 적재에는 --dsn이 필요합니다")
    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit('psycopg가 필요합니다: pip install -r pipeline/requirements.txt') from exc

    with psycopg.connect(args.dsn) as conn:
        for group in dataset_groups:
            images = annotations = pending = 0
            for label_path in iter_labels(group):
                try:
                    with conn.cursor() as cur:
                        added_images, added_annotations = load_document(cur, group, label_path)
                except Exception:
                    conn.rollback()
                    print(f"적재 실패: {label_path}")
                    raise
                images += added_images
                annotations += added_annotations
                pending += 1
                if pending >= args.commit_every:
                    conn.commit()
                    pending = 0
                    print(f"{group.split}/{group.label_type}: images={images}, annotations={annotations}")
                if args.limit and images >= args.limit:
                    break
            conn.commit()
            print(f"완료 {group.split}/{group.label_type}: images={images}, annotations={annotations}")

    # 적재 자체는 끝났더라도 파싱 실패가 있으면 비정상 종료로 알린다.
    if report_unparsed_dates():
        raise SystemExit(1)


if __name__ == "__main__":
    main()
