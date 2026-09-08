"""검색 대상 사례를 검색 테이블 적재용 SQL로 만든다.

`validate_search_readiness.py`가 확정한 검색 가능 사고 중 앞에서부터 N건을 골라
`repair_case` / `repair_case_image` / `repair_case_item` INSERT 문을 생성한다.
ROI 임베딩은 적재하지 않는다. 견적서의 작업 행과 부품가격 행은 분리해 보존한다.

확정할 수 없는 항목은 버리지 않고 `data_validation_error`에 남기며,
오류가 하나라도 있으면 `batch_job_execution.status`를 PARTIAL로 기록한다.

실행 예시:
  python pipeline/jobs/build_search_sample_sql.py \
    --subset-root "<AI-Hub 견적서 보유 subset 경로>" \
    --readiness-csv "<2단계 output-dir>/case_search_readiness.csv" \
    --mapping-workbook "<표준화 매핑 워크북>.xlsx" \
    --output-dir "<결과를 쓸 경로>" \
    --sample-cases 10
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any

import pandas as pd


# 실제 견적서의 6종 작업 유형. 원문 한글은 표시·추적용으로, 영문 코드는
# 적재·검색 계약용으로 함께 보존한다. 사진 손상 기반의 4종 후보 규칙과는 별개다.
WORK_CODE_BY_TYPE = {
    "교환": "EXCHANGE",
    "탈착": "REMOVE_INSTALL",
    "판금": "SHEET_METAL",
    "도장": "COATING",
    "오버홀": "OVERHAUL",
    "수리": "REPAIR",
}

ESTIMATE_REL = Path("1.Training") / "1.원천데이터_230126_add" / "TS_99. 붙임_견적서"


def sql_e(value: Any) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return "NULL"
    text = str(value).replace("\\", "\\\\").replace("'", "''")
    text = text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    return f"E'{text}'"


def sql_num(value: Any) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return "NULL"
    text = str(value).replace(",", "").strip()
    if not text:
        return "NULL"
    try:
        return str(float(text)).rstrip("0").rstrip(".")
    except ValueError:
        return "NULL"


def money(value: Any) -> int | None:
    """견적서의 숫자 문자열을 정수 금액으로 변환한다."""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).replace(",", "").strip()
    if not text:
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def item_total(*amounts: int | None) -> int | None:
    known = [amount for amount in amounts if amount is not None]
    return sum(known) if known else None


def new_part_price(raw_name: str) -> int | None:
    """AS 견적의 '신품가 1,000' 또는 '신품가(좌)1,000' 표기를 읽는다."""
    match = re.search(r"신품가(?:\s*\([^)]*\))?\s*([\d,]+)", raw_name)
    return money(match.group(1)) if match else None


def estimate_item_costs(item: dict[str, Any], source: str, raw_name: str) -> dict[str, int | None]:
    """AS/SC의 서로 다른 비용 구조를 공통 행 값으로 정규화한다."""
    if source == "AIHUB_AS":
        part_cost = money(item.get("부품가격"))
        reference_part_price = new_part_price(raw_name)
        labor_cost = money(item.get("공임"))
        return {
            "part_cost": part_cost,
            "labor_cost": labor_cost,
            # 신품가는 청구 부품비와 일치하지 않을 수 있는 기준 가격이다.
            "reference_part_price": reference_part_price,
            "pre_part_cost": None,
            "pre_labor_cost": None,
            "post_part_cost": None,
            "post_labor_cost": None,
            "item_total": item_total(part_cost, labor_cost),
        }

    before = item.get("손해사정전") or {}
    after = item.get("손해사정후") or {}
    pre_part_cost = money(before.get("부품가격"))
    pre_labor_cost = money(before.get("공임"))
    post_part_cost = money(after.get("부품가격"))
    post_labor_cost = money(after.get("공임"))
    return {
        # SC의 공통 비용값은 손해사정 전 금액이다. 손해사정 후 값은 별도 열에 보존한다.
        "part_cost": pre_part_cost,
        "labor_cost": pre_labor_cost,
        "reference_part_price": None,
        "pre_part_cost": pre_part_cost,
        "pre_labor_cost": pre_labor_cost,
        "post_part_cost": post_part_cost,
        "post_labor_cost": post_labor_cost,
        "item_total": item_total(pre_part_cost, pre_labor_cost),
    }


def json_sql(value: Any) -> str:
    return f"{sql_e(json.dumps(value, ensure_ascii=False, separators=(',', ':')))}::jsonb"


def parse_year(value: Any) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return "NULL"
    match = re.search(r"(19|20)\d{2}", str(value))
    return match.group(0) if match else "NULL"


def parse_integer(value: Any) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return "NULL"
    match = re.search(r"\d[\d,]*", str(value))
    return match.group(0).replace(",", "") if match else "NULL"


def normalize_car_class(value: Any) -> str | None:
    text = str(value or "").strip()
    return {
        "CityCar": "CityCar",
        "Compact car": "Compact",
        "Mid-size car": "Mid-size",
        "Full-size car": "Full-size",
    }.get(text)


def image_path_for_label(label_path: Path) -> Path:
    parts = list(label_path.parts)
    marker = "2.라벨링데이터"
    idx = parts.index(marker)
    parts[idx] = "1.원천데이터"
    parts[idx + 1] = parts[idx + 1].replace("TL_", "TS_").replace("VL_", "VS_")
    parts[-1] = label_path.stem + ".jpg"
    return Path(*parts)


def load_mapping(workbook: Path) -> dict[str, str]:
    """표준화 워크북에서 확정 매핑(MAPPED, MAPPED_EXTENDED)만 읽는다."""
    mapping_df = pd.read_excel(workbook, sheet_name="부품명 전체 매핑", header=3)
    required = {"기존 이름", "표준 코드", "매핑 상태"}
    missing = required - set(mapping_df.columns)
    if missing:
        raise ValueError(f"워크북에 필요한 열이 없습니다: {sorted(missing)}")
    return {
        str(raw).strip(): str(code)
        for raw, code, status in mapping_df[["기존 이름", "표준 코드", "매핑 상태"]].itertuples(index=False, name=None)
        if status in {"MAPPED", "MAPPED_EXTENDED"} and not pd.isna(code)
    }


def build_label_index(subset_root: Path) -> dict[str, list[Path]]:
    label_index: dict[str, list[Path]] = defaultdict(list)
    for path in subset_root.rglob("*.json"):
        if "2.라벨링데이터" not in path.parts:
            continue
        match = re.search(r"_((?:as|sc)-\d+)\.json$", path.name)
        if match:
            label_index[match.group(1)].append(path)
    return label_index


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True,
                        help="AI-Hub 견적서 보유 subset 경로 (01.데이터_견적서보유)")
    parser.add_argument("--readiness-csv", type=Path, required=True,
                        help="validate_search_readiness.py가 만든 case_search_readiness.csv")
    parser.add_argument("--mapping-workbook", type=Path, required=True,
                        help="표준화 매핑 워크북(.xlsx)")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--estimate-dir", type=Path, default=None,
                        help="견적서 JSON 경로. 생략하면 --subset-root 기준으로 잡는다")
    parser.add_argument("--dataset-root", type=Path, default=None,
                        help="storage_key의 기준 경로. 생략하면 --subset-root의 상위 폴더")
    parser.add_argument("--sample-cases", type=int, default=10)
    parser.add_argument("--source", choices=("as", "sc"), default="as",
                        help="표본 출처. MVP 예상 수리비 기준은 as, sc는 별도 검증용")
    args = parser.parse_args()

    subset_root = args.subset_root.resolve()
    dataset_root = (args.dataset_root or subset_root.parent).resolve()
    estimate_dir = (args.estimate_dir or (subset_root / ESTIMATE_REL)).resolve()

    mapping = load_mapping(args.mapping_workbook)

    readiness = pd.read_csv(args.readiness_csv, dtype={"case_id": str})
    selected = readiness[
        readiness["is_final_searchable_case"].astype(bool)
        & readiness["case_id"].str.startswith(f"{args.source}-", na=False)
    ].head(args.sample_cases)
    case_ids = selected["case_id"].tolist()
    if not case_ids:
        raise ValueError("No searchable sample cases found")

    label_index = build_label_index(subset_root)

    errors: list[tuple[str, str, str, dict[str, Any]]] = []
    case_sql: list[str] = []
    loaded_cases = 0
    loaded_images = 0
    loaded_items = 0
    loaded_part_price_items = 0

    for ordinal, case_id in enumerate(case_ids, start=1):
        estimate_path = estimate_dir / f"{case_id}.json"
        labels = sorted(label_index.get(case_id, []))
        if not estimate_path.exists():
            errors.append(("missing_estimate", case_id, str(estimate_path), {}))
            continue
        if not labels:
            errors.append(("missing_label", case_id, case_id, {}))
            continue

        estimate = json.loads(estimate_path.read_text(encoding="utf-8-sig"))
        classes: list[str] = []
        valid_labels: list[tuple[Path, Path, dict[str, Any]]] = []
        for label_path in labels:
            image_path = image_path_for_label(label_path)
            if not image_path.exists():
                errors.append(("orphan_label", case_id, str(label_path), {"expected_image": str(image_path)}))
                continue
            document = json.loads(label_path.read_text(encoding="utf-8-sig"))
            car_class = normalize_car_class((document.get("categories") or {}).get("supercategory_name"))
            if car_class:
                classes.append(car_class)
            valid_labels.append((label_path, image_path, document))

        if not valid_labels or not classes:
            errors.append(("missing_car_class", case_id, case_id, {}))
            continue

        car_class = classes[0]
        if any(value != car_class for value in classes[1:]):
            errors.append(("key_mismatch", case_id, case_id, {"car_classes": sorted(set(classes))}))

        vehicle = estimate.get("차량정보") or {}
        source = "AIHUB_AS" if case_id.startswith("as-") else "AIHUB_SC"
        settlement = estimate.get("수리비 정산정보") or {}
        totals = settlement.get("합계") or {}
        total_cost = money(totals.get("총계")) if source == "AIHUB_AS" else None
        claim_amount = money(totals.get("청구액")) if source == "AIHUB_SC" else None
        paid_amount = money(totals.get("지급액")) if source == "AIHUB_SC" else None
        case_var = f"case_{ordinal}_"
        case_sql.extend(
            [
                f"-- {case_id}",
                "INSERT INTO repair_case (source, external_ref, manufacturer, model_name, car_class, model_year, repair_year, labor_rate, total_cost, claim_amount, paid_amount)",
                f"VALUES ({sql_e(source)}, {sql_e(case_id)}, {sql_e(vehicle.get('제작사/차종'))}, {sql_e(vehicle.get('모델'))}, {sql_e(car_class)}, {parse_year(vehicle.get('최초등록일'))}, {parse_year(vehicle.get('입고일자'))}, {parse_integer(vehicle.get('탈부착M/H'))}, {sql_num(total_cost)}, {sql_num(claim_amount)}, {sql_num(paid_amount)})",
                "ON CONFLICT (source, external_ref) DO UPDATE SET",
                "    manufacturer = EXCLUDED.manufacturer,",
                "    model_name = EXCLUDED.model_name,",
                "    car_class = EXCLUDED.car_class,",
                "    model_year = EXCLUDED.model_year,",
                "    repair_year = EXCLUDED.repair_year,",
                "    labor_rate = EXCLUDED.labor_rate,",
                "    total_cost = EXCLUDED.total_cost,",
                "    claim_amount = EXCLUDED.claim_amount,",
                "    paid_amount = EXCLUDED.paid_amount",
                f"RETURNING case_id \\gset {case_var}",
                "",
            ]
        )
        loaded_cases += 1

        for label_path, image_path, document in valid_labels:
            storage_key = image_path.relative_to(dataset_root).as_posix()
            source_image_ref = storage_key
            case_sql.extend(
                [
                    "INSERT INTO repair_case_image (case_id, source_image_ref, storage_key, quality_status, is_searchable)",
                    f"VALUES (:case_{ordinal}_case_id, {sql_e(source_image_ref)}, {sql_e(storage_key)}, 'VALID', TRUE)",
                    "ON CONFLICT (source_image_ref) DO UPDATE SET",
                    "    case_id = EXCLUDED.case_id,",
                    "    storage_key = EXCLUDED.storage_key,",
                    "    quality_status = EXCLUDED.quality_status,",
                    "    is_searchable = EXCLUDED.is_searchable;",
                    "",
                ]
            )
            loaded_images += 1

        for item in estimate.get("수리내역") or []:
            raw_name = str(item.get("작업항목 및 부품명") or "").strip()
            part_code = mapping.get(raw_name)
            work_type = str(item.get("작업") or "").strip()
            costs = estimate_item_costs(item, source, raw_name)
            if not part_code:
                if raw_name:
                    errors.append(("unknown_part_code", case_id, raw_name, {"work_type": work_type}))
                continue
            if work_type:
                work_code = WORK_CODE_BY_TYPE.get(work_type)
                if not work_code:
                    errors.append(("unsupported_work_type", case_id, raw_name, {
                        "work_type": work_type,
                        "part_code": part_code,
                    }))
                    continue
                line_type = "WORK"
            elif (costs["part_cost"] is not None or costs["reference_part_price"] is not None) and (costs["labor_cost"] in {None, 0}):
                # 신품가 또는 SC의 부품가격 행은 작업 공임에 억지로 합치지 않는다.
                line_type = "PART_PRICE"
                work_code = None
            else:
                errors.append(("missing_work_type", case_id, raw_name, {
                    "part_code": part_code,
                    "part_cost": costs["part_cost"],
                    "reference_part_price": costs["reference_part_price"],
                    "labor_cost": costs["labor_cost"],
                }))
                continue
            case_sql.extend(
                [
                    "INSERT INTO repair_case_item (case_id, part_code, raw_item_name, line_type, work_type, work_code, hq, reference_part_price, part_cost, labor_cost, pre_adjustment_part_cost, pre_adjustment_labor_cost, post_adjustment_part_cost, post_adjustment_labor_cost, item_total)",
                    f"VALUES (:case_{ordinal}_case_id, {sql_e(part_code)}, {sql_e(raw_name)}, {sql_e(line_type)}, {sql_e(work_type) if work_type else 'NULL'}, {sql_e(work_code)}, {sql_num(item.get('HQ%'))}, {sql_num(costs['reference_part_price'])}, {sql_num(costs['part_cost'])}, {sql_num(costs['labor_cost'])}, {sql_num(costs['pre_part_cost'])}, {sql_num(costs['pre_labor_cost'])}, {sql_num(costs['post_part_cost'])}, {sql_num(costs['post_labor_cost'])}, {sql_num(costs['item_total'])});",
                    "",
                ]
            )
            loaded_items += 1
            if line_type == "PART_PRICE":
                loaded_part_price_items += 1

    batch_summary = {
        "requested_cases": len(case_ids),
        "loaded_cases": loaded_cases,
        "loaded_images": loaded_images,
        "loaded_items": loaded_items,
        "loaded_part_price_items": loaded_part_price_items,
        "validation_errors": len(errors),
        "source": args.source,
        "source_workbook": Path(args.mapping_workbook).name,
        "readiness_manifest": Path(args.readiness_csv).name,
    }

    sql: list[str] = [
        "-- ============================================================",
        f"-- A307 · 검색 표본 적재 ({len(case_ids)} cases)",
        "-- 대상: repair_case, repair_case_image, repair_case_item",
        "-- 견적 금액·작업 행·부품가격 행을 적재한다. ROI 임베딩은 적재하지 않는다.",
        "-- 로컬 DB에서 1회성 표본 검증용으로 생성된 SQL이다.",
        "-- ============================================================",
        "\\set ON_ERROR_STOP on",
        "BEGIN;",
        "",
        "INSERT INTO batch_job_execution (job_name, job_version, status, input_ref, summary)",
        f"VALUES ('search_sample_load', '002', 'RUNNING', {sql_e(Path(args.readiness_csv).name)}, {json_sql(batch_summary)})",
        "RETURNING batch_job_execution_id \\gset batch_",
        "",
    ]
    sql.extend(case_sql)
    for error_type, case_external_ref, source_ref, detail in errors:
        sql.extend(
            [
                "INSERT INTO data_validation_error (batch_job_execution_id, error_type, source_ref, case_external_ref, error_detail)",
                f"VALUES (:batch_batch_job_execution_id, {sql_e(error_type)}, {sql_e(source_ref)}, {sql_e(case_external_ref)}, {json_sql(detail)});",
                "",
            ]
        )
    final_status = "'PARTIAL'" if errors else "'SUCCEEDED'"
    sql.extend(
        [
            "UPDATE batch_job_execution",
            f"SET status = {final_status}, completed_at = now(), summary = {json_sql(batch_summary)}",
            "WHERE batch_job_execution_id = :batch_batch_job_execution_id;",
            "",
            "COMMIT;",
            "",
        ]
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f"A307_SEARCH_SAMPLE_{len(case_ids)}_LOAD.sql"
    output.write_text("\n".join(sql), encoding="utf-8", newline="\n")
    print(json.dumps({"output": str(output), **batch_summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
