"""검색 대상 사례를 검색 테이블 적재용 SQL로 만든다.

`validate_search_readiness.py`가 확정한 검색 가능 사고 중 앞에서부터 N건을 골라
`repair_case` / `repair_case_image` / `repair_case_item` INSERT 문을 생성한다.
ROI 임베딩은 적재하지 않는다.

정식 검색 이미지는 DAMAGE_PART다. DAMAGE 이미지는 같은 사례의 선택적 참고
이미지로 적재할 수 있지만 ``is_searchable=FALSE``로 저장한다.

견적서 한 행은 `line_type` 4종으로 갈라 저장한다. 출처(AS/SC)를 몰라도 해석되어야 한다.

  WORK            공임이 붙는 수리 작업. 정산 포함
  PART_PRICE      SC의 부품 명세. 손해사정에 반영된 부품비. 정산 포함
  REFERENCE_PRICE AS의 `신품가` 참고 정가. 정산 제외, item_total에 합산하지 않는다
  ANCILLARY       견인·구난 등 수리가 아닌 부대 비용. part_code가 없다

`불인정`은 행 종류가 아니라 `assessment_status`에 담는다. 원천이 `작업` 필드를
덮어써서 원래 작업 유형은 복구할 수 없다.

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
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import pandas as pd

PIPELINE_ROOT = Path(__file__).resolve().parents[1]
if str(PIPELINE_ROOT) not in sys.path:
    sys.path.insert(0, str(PIPELINE_ROOT))

from standardization.estimate_items import (
    estimate_item_costs as _shared_estimate_item_costs,
    money as _shared_money,
)


ESTIMATE_REL = Path("1.Training") / "1.원천데이터_230126_add" / "TS_99. 붙임_견적서"

# 원천 `부품가격`이 부품비가 아니라 도장 재료비인 작업. 정산상 공임 측 `재료대`에
# 들어가므로 part_cost와 합치지 않는다. `작업=도장`만 옮기는 것이 가장 정확하다 —
# 무작위 3,000건 표본에서 SUM(도장 행 부품가격) = 정산.공임.재료대가 AS 100.00%,
# SC(손해사정후 기준) 99.90% 일치했고, 도장+수리+판금으로 넓히면 AS 81.90%로 떨어진다.
PAINT_MATERIAL_WORK_CODE = "COATING"

# case_id 접두사로 source를 정한다. load_search_data.py·load_estimate_raw.py·
# verify_search_sample.py가 쓰는 것과 같은 표다.
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}


def source_for_case(case_id: str) -> str:
    """case_id 접두사로 source를 정한다. 모르는 접두사는 KeyError로 멈춘다.

    예전에는 ``"AIHUB_AS" if case_id.startswith("as-") else "AIHUB_SC"``였다.
    그 형태는 모르는 접두사를 **조용히 AIHUB_SC로 분류한다.** 지금은 상류의
    readiness 필터가 ``as-``/``sc-``만 통과시켜 실제로 도달하지 않지만, 그 필터가
    바뀌는 날 검색·통계가 틀린 source로 오염되고 아무도 알아채지 못한다.

    특히 ``svc-``(서비스 사고, S15P21A307-223)는 견적이 AI 추정치라 신뢰도 때문에
    검색에서 일부러 뺀 데이터다. AI-Hub 사례로 위장해 들어가면 안 된다.
    형제 job들이 전부 이 표를 쓰고, 모르는 접두사에 KeyError를 내는 것이 안전장치다.
    """
    return SOURCE_BY_PREFIX[case_id[:2].lower()]


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


def repair_case_image_key(case_variable: str, image_variable: str,
                          variant: str = "original", extension: str = "jpg") -> str:
    """RepairCaseImageKeys 규칙을 psql 변수 표현식으로 만든다."""
    extension = extension.lower().lstrip(".")
    return (
        f"('repair-cases/' || :{case_variable} || '/images/' || :{image_variable} || "
        f"'/{variant}.{extension}')"
    )


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


def estimate_item_costs(
    item: dict[str, Any], source: str, raw_name: str, work_code: str | None
) -> dict[str, int | None]:
    """AS/SC의 서로 다른 비용 구조를 공통 행 값으로 정규화한다.

    원천 `부품가격`은 `작업=도장`이면 도장 재료비이고 그 밖에는 실제 부품비다.
    두 의미를 한 열에 넣지 않도록 `work_code`로 갈라 담는다.
    """
    is_paint = work_code == PAINT_MATERIAL_WORK_CODE

    if source == "AIHUB_AS":
        raw_part_cost = money(item.get("부품가격"))
        reference_part_price = new_part_price(raw_name)
        labor_cost = money(item.get("공임"))
        return {
            "part_cost": None if is_paint else raw_part_cost,
            "paint_material_cost": raw_part_cost if is_paint else None,
            "labor_cost": labor_cost,
            # 신품가는 청구 부품비와 일치하지 않을 수 있는 기준 가격이다.
            "reference_part_price": reference_part_price,
            "pre_part_cost": None,
            "pre_labor_cost": None,
            "post_part_cost": None,
            "post_labor_cost": None,
            # 재료비는 정산상 공임에 포함되므로 합계에서 빼지 않는다.
            "item_total": item_total(raw_part_cost, labor_cost),
        }

    before = item.get("손해사정전") or {}
    after = item.get("손해사정후") or {}
    pre_part_cost = money(before.get("부품가격"))
    pre_labor_cost = money(before.get("공임"))
    post_part_cost = money(after.get("부품가격"))
    post_labor_cost = money(after.get("공임"))
    return {
        # SC의 공통 비용값은 손해사정 전 금액이다. 손해사정 후 값은 별도 열에 보존한다.
        "part_cost": None if is_paint else pre_part_cost,
        "paint_material_cost": pre_part_cost if is_paint else None,
        "labor_cost": pre_labor_cost,
        "reference_part_price": None,
        "pre_part_cost": pre_part_cost,
        "pre_labor_cost": pre_labor_cost,
        "post_part_cost": post_part_cost,
        "post_labor_cost": post_labor_cost,
        "item_total": item_total(pre_part_cost, pre_labor_cost),
    }


# 정식 loader와 표본 SQL 생성기의 비용 해석은 반드시 같아야 한다.
money = _shared_money
estimate_item_costs = _shared_estimate_item_costs


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
    parser.add_argument("--catalog-path", type=Path, default=Path(__file__).resolve().parents[1],
                        help="standardization 패키지가 있는 경로. 저장소 밖에서 실행할 때만 지정한다")
    parser.add_argument("--sample-cases", type=int, default=10)
    parser.add_argument("--source", choices=("as", "sc"), default="as",
                        help="표본 출처. MVP 예상 수리비 기준은 as, sc는 별도 검증용")
    args = parser.parse_args()

    sys.path.insert(0, str(args.catalog_path.resolve()))
    from standardization import NormalizationError, normalize_estimate_work  # noqa: E402

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
    loaded_by_line_type: dict[str, int] = defaultdict(int)
    loaded_not_approved_items = 0

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
        missing_images: list[str] = []
        for label_path in labels:
            image_path = image_path_for_label(label_path)
            if not image_path.exists():
                missing_images.append(str(image_path))
                continue
            document = json.loads(label_path.read_text(encoding="utf-8-sig"))
            car_class = normalize_car_class((document.get("categories") or {}).get("supercategory_name"))
            if car_class:
                classes.append(car_class)
            valid_labels.append((label_path, image_path, document))

        if missing_images:
            errors.append(("missing_image", case_id, case_id, {"paths": missing_images}))
            continue
        if not valid_labels or not classes:
            errors.append(("missing_car_class", case_id, case_id, {}))
            continue

        car_class = classes[0]
        if any(value != car_class for value in classes[1:]):
            errors.append(("invalid_car_class", case_id, case_id, {"car_classes": sorted(set(classes))}))
            continue

        vehicle = estimate.get("차량정보") or {}
        source = source_for_case(case_id)
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
            source_image_ref = image_path.relative_to(dataset_root).as_posix()
            image_type = "DAMAGE_PART" if "damage_part" in label_path.parts else "DAMAGE"
            source_dataset_split = "TRAIN" if "1.Training" in label_path.parts else "VALIDATION"
            image_var = f"image_{ordinal}_"
            storage_key = repair_case_image_key(
                f"case_{ordinal}_case_id", f"{image_var}case_image_id"
            )
            case_sql.extend(
                [
                    "INSERT INTO repair_case_image (case_id, source_image_ref, storage_key, image_type, source_dataset_split, quality_status, is_searchable)",
                    f"VALUES (:case_{ordinal}_case_id, {sql_e(source_image_ref)}, {sql_e(source_image_ref)}, {sql_e(image_type)}, {sql_e(source_dataset_split)}, NULL, {str(image_type == 'DAMAGE_PART').upper()})",
                    "ON CONFLICT (source_image_ref) DO UPDATE SET",
                    "    case_id = EXCLUDED.case_id,",
                    "    storage_key = EXCLUDED.storage_key,",
                    "    image_type = EXCLUDED.image_type,",
                    "    source_dataset_split = EXCLUDED.source_dataset_split,",
                    "    quality_status = EXCLUDED.quality_status,",
                    "    is_searchable = EXCLUDED.is_searchable",
                    f"RETURNING case_image_id \\gset {image_var}",
                    f"UPDATE repair_case_image SET storage_key = {storage_key}",
                    f" WHERE case_image_id = :{image_var}case_image_id;",
                    "",
                ]
            )
            loaded_images += 1

        for item_ordinal, item in enumerate(estimate.get("수리내역") or [], start=1):
            raw_name = str(item.get("작업항목 및 부품명") or "").strip()
            part_code = mapping.get(raw_name)
            work_type = str(item.get("작업") or "").strip()
            # SC는 손해사정을 거친 출처다. AS 견적서에는 손해사정 개념이 없어 NULL로 둔다.
            assessment_status = "APPROVED" if source == "AIHUB_SC" else None
            stored_work_type: str | None = work_type or None

            if work_type:
                try:
                    work = normalize_estimate_work(work_type)
                except NormalizationError as exc:
                    # 어휘에 없는 값. 원문을 버리지 않고 격리한다.
                    errors.append(("unknown_work_type", case_id, raw_name, {
                        "work_type": work_type,
                        "part_code": part_code,
                        "reason": str(exc),
                    }))
                    continue
                if work["category"] == "ANCILLARY":
                    # 견인·구난은 수리 작업이 아닌 부대 비용이다. 원문 부품명이
                    # `견인비`·`구난료`·`탁송비`처럼 부품이 아니어서 part_code가 없다.
                    line_type = "ANCILLARY"
                    work_code = work["code"]
                elif work["category"] == "STATUS":
                    # `불인정`은 작업 유형이 아니라 손해사정 결과다. 원천이 `작업` 필드를
                    # 덮어써서 원래 작업 유형은 복구할 수 없으므로 work_type·work_code를
                    # 비우고 상태만 남긴다. 원문 `불인정`은 assessment_status로 복구된다.
                    line_type = "WORK"
                    work_code = None
                    stored_work_type = None
                    assessment_status = "NOT_APPROVED"
                else:
                    line_type = "WORK"
                    work_code = work["code"]
            else:
                work_code = None
                probe = estimate_item_costs(item, source, raw_name, None)
                if source == "AIHUB_AS" and probe["reference_part_price"] is not None:
                    # AS `신품가`는 정산에 포함되지 않는 참고 정가다. item_total에 합산하지 않는다.
                    line_type = "REFERENCE_PRICE"
                elif source == "AIHUB_SC" and probe["part_cost"] is not None and probe["labor_cost"] in {None, 0}:
                    # SC의 부품가격 행은 손해사정에 반영된 부품 명세다. 정산에 포함된다.
                    line_type = "PART_PRICE"
                else:
                    errors.append(("missing_work_type", case_id, raw_name, {
                        "part_code": part_code,
                        "part_cost": probe["part_cost"],
                        "reference_part_price": probe["reference_part_price"],
                        "labor_cost": probe["labor_cost"],
                    }))
                    continue

            if not part_code and line_type != "ANCILLARY":
                if raw_name:
                    errors.append(("unknown_part_code", case_id, raw_name, {
                        "work_type": work_type,
                        "line_type": line_type,
                    }))
                continue

            costs = estimate_item_costs(item, source, raw_name, work_code)
            if assessment_status == "NOT_APPROVED":
                # 원래 작업이 도장인지 알 수 없어 `부품가격`을 부품비·재료비 어느 쪽으로도
                # 확정할 수 없다. 원천 금액은 pre_adjustment_* 열에 그대로 보존된다.
                # item_total은 그대로 둔다 — 합계는 원천에서 확정되고 나누는 것만 안 된다.
                costs = {**costs, "part_cost": None, "paint_material_cost": None}

            case_sql.extend(
                [
                    "INSERT INTO repair_case_item (case_id, source_item_key, part_code, raw_item_name, line_type, work_type, work_code, assessment_status, hq, reference_part_price, part_cost, paint_material_cost, labor_cost, pre_adjustment_part_cost, pre_adjustment_labor_cost, post_adjustment_part_cost, post_adjustment_labor_cost, item_total)",
                    f"VALUES (:case_{ordinal}_case_id, {sql_e(f'item-{item_ordinal:05d}')}, {sql_e(part_code)}, {sql_e(raw_name)}, {sql_e(line_type)}, {sql_e(stored_work_type)}, {sql_e(work_code)}, {sql_e(assessment_status)}, {sql_num(item.get('HQ%'))}, {sql_num(costs['reference_part_price'])}, {sql_num(costs['part_cost'])}, {sql_num(costs['paint_material_cost'])}, {sql_num(costs['labor_cost'])}, {sql_num(costs['pre_part_cost'])}, {sql_num(costs['pre_labor_cost'])}, {sql_num(costs['post_part_cost'])}, {sql_num(costs['post_labor_cost'])}, {sql_num(costs['item_total'])})",
                    "ON CONFLICT (case_id, source_item_key) DO UPDATE SET part_code=EXCLUDED.part_code, raw_item_name=EXCLUDED.raw_item_name, line_type=EXCLUDED.line_type, work_type=EXCLUDED.work_type, work_code=EXCLUDED.work_code, assessment_status=EXCLUDED.assessment_status, hq=EXCLUDED.hq, reference_part_price=EXCLUDED.reference_part_price, part_cost=EXCLUDED.part_cost, paint_material_cost=EXCLUDED.paint_material_cost, labor_cost=EXCLUDED.labor_cost, pre_adjustment_part_cost=EXCLUDED.pre_adjustment_part_cost, pre_adjustment_labor_cost=EXCLUDED.pre_adjustment_labor_cost, post_adjustment_part_cost=EXCLUDED.post_adjustment_part_cost, post_adjustment_labor_cost=EXCLUDED.post_adjustment_labor_cost, item_total=EXCLUDED.item_total;",
                    "",
                ]
            )
            loaded_items += 1
            loaded_by_line_type[line_type] += 1
            if assessment_status == "NOT_APPROVED":
                loaded_not_approved_items += 1

    batch_summary = {
        "requested_cases": len(case_ids),
        "loaded_cases": loaded_cases,
        "loaded_images": loaded_images,
        "loaded_items": loaded_items,
        "loaded_items_by_line_type": dict(sorted(loaded_by_line_type.items())),
        "loaded_not_approved_items": loaded_not_approved_items,
        "validation_errors": len(errors),
        "source": args.source,
        "source_workbook": Path(args.mapping_workbook).name,
        "readiness_manifest": Path(args.readiness_csv).name,
    }

    sql: list[str] = [
        "-- ============================================================",
        f"-- A307 · 검색 표본 적재 ({len(case_ids)} cases)",
        "-- 대상: repair_case, repair_case_image, repair_case_item",
        "-- 견적 금액을 line_type 4종(WORK/PART_PRICE/REFERENCE_PRICE/ANCILLARY)으로 적재한다.",
        "-- ROI 임베딩은 적재하지 않는다.",
        "-- 로컬 DB에서 1회성 표본 검증용으로 생성된 SQL이다.",
        "-- ============================================================",
        "\\set ON_ERROR_STOP on",
        "BEGIN;",
        "",
        "INSERT INTO batch_job_execution (job_name, job_version, status, input_ref, summary)",
        f"VALUES ('search_sample_load', '003', 'RUNNING', {sql_e(Path(args.readiness_csv).name)}, {json_sql(batch_summary)})",
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
