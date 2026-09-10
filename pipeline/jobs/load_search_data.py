"""검색 기본 데이터(``part_code``, ``part_name_mapping``, ``repair_case``,
``repair_case_image``)를 readiness 확정 범위만큼 DB에 적재한다.

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
from collections import defaultdict
from pathlib import Path
from typing import Any

ESTIMATE_REL = Path("1.Training") / "1.원천데이터_230126_add" / "TS_99. 붙임_견적서"
ALLOWED_CLASSES = {"CityCar", "Compact", "Mid-size", "Full-size"}
CASE_RE = re.compile(r"^(?:as|sc)-\d+$")
LABEL_RE = re.compile(r"_(?P<case>(?:as|sc)-\d+)\.json$", re.IGNORECASE)
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}
# 검색 사례 이미지는 서비스 검색 모델의 입력 분포를 맞추기 위해
# damage_part 계열만 사용한다. damage 계열은 별도 YOLO 목적의 데이터라
# 화각·포함 영역이 달라 같은 검색 코퍼스에 섞지 않는다.
SEARCH_LABEL_DIRS = (
    Path("1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)


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
        for row in csv.DictReader(fp):
            case_id = str(row.get("case_id") or "").strip()
            if CASE_RE.fullmatch(case_id):
                selected.add(case_id)
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


def upsert_images(cur, case_pk: int, label_paths: list[Path], dataset_root: Path) -> int:
    loaded = 0
    for label_path in sorted(label_paths):
        # readiness 검증이 라벨·이미지 조인을 끝냈고, 원천 파일명은 라벨 stem과
        # 동일하다. 전수 적재에서 선별된 라벨 JSON을 다시 파싱하지 않는다.
        image_path = image_path_for_label(label_path) / (label_path.stem + ".jpg")
        if not image_path.is_file():
            raise FileNotFoundError(f"라벨에 대응하는 이미지가 없습니다: {image_path}")
        source_image_ref = image_path.relative_to(dataset_root).as_posix()
        cur.execute(
            """
            INSERT INTO repair_case_image(case_id, source_image_ref, storage_key, quality_status, is_searchable)
            VALUES (%s,%s,%s,NULL,TRUE)
            ON CONFLICT (source_image_ref) DO UPDATE SET
                case_id=EXCLUDED.case_id, storage_key=EXCLUDED.storage_key,
                quality_status=EXCLUDED.quality_status, is_searchable=EXCLUDED.is_searchable
            RETURNING case_image_id
            """,
            (case_pk, source_image_ref, source_image_ref),
        )
        case_image_pk = cur.fetchone()[0]
        storage_key = repair_case_image_key(case_pk, case_image_pk)
        cur.execute(
            "UPDATE repair_case_image SET storage_key=%s WHERE case_image_id=%s",
            (storage_key, case_image_pk),
        )
        loaded += 1
    return loaded


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
    parser.add_argument("--case-manifest", type=Path, default=None,
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
    if args.case_manifest:
        selected &= load_case_manifest(args.case_manifest.resolve())
    if args.limit is not None:
        selected = set(sorted(selected)[:args.limit])
    label_index = build_label_index(subset_root, selected)
    estimate_dir = subset_root / ESTIMATE_REL
    mapping_rows = load_mapping(args.mapping_workbook.resolve()) if args.mapping_workbook else []
    summary = {
        "policy": "final_searchable_with_damage_part_image_and_valid_car_class",
        "image_source_scope": "damage_part_only",
        "readiness_selected": len(selected),
        "mapping_rows": len(mapping_rows) if args.mapping_workbook else "seed_sql",
        "raw_estimate_scope": "separate_aihub_estimate_raw_all_125006",
        "annotation_scope": "ddl_and_loader_only_no_full_load",
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

    summary.update({
        "valid_case_count": len(valid),
        "excluded_case_count": len(errors),
        "label_count": sum(len(labels) for _, _, _, labels in valid),
        "error_counts": dict(sorted({kind: sum(1 for error in errors if error[0] == kind) for kind, _, _ in errors}.items())),
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

        loaded_images = 0
        for start in range(0, len(valid), args.commit_every):
            batch = valid[start : start + args.commit_every]
            with conn.cursor() as cur:
                for case_id, estimate_path, car_class, labels in batch:
                    estimate = json.loads(estimate_path.read_text(encoding="utf-8-sig"))
                    case_pk = upsert_case(cur, case_id, estimate, car_class)
                    loaded_images += upsert_images(cur, case_pk, labels, dataset_root)
            conn.commit()

        summary["loaded_cases"] = len(valid)
        summary["loaded_images"] = loaded_images
        summary["status"] = "PARTIAL" if errors else "SUCCEEDED"
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
