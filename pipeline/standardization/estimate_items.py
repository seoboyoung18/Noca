"""AI-Hub 견적 수리항목을 ``repair_case_item`` 계약으로 변환한다.

표본 SQL 생성기와 정식 검색 적재기가 같은 비용·행 분류 규칙을 사용한다.
원천 항목의 배열 순번은 재실행 멱등성을 위한 ``source_item_key``다.
"""
from __future__ import annotations

import re
from typing import Any, Mapping

from .normalizer import NormalizationError, normalize_estimate_work

PAINT_MATERIAL_WORK_CODE = "COATING"


def money(value: Any) -> int | None:
    if value is None:
        return None
    text = str(value).replace(",", "").strip()
    if not text:
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def item_total(*amounts: int | None) -> int | None:
    values = [amount for amount in amounts if amount is not None]
    return sum(values) if values else None


def new_part_price(raw_name: str) -> int | None:
    match = re.search(r"신품가(?:\s*\([^)]*\))?\s*([\d,]+)", raw_name)
    return money(match.group(1)) if match else None


def estimate_item_costs(item: Mapping[str, Any], source: str, raw_name: str,
                        work_code: str | None) -> dict[str, int | None]:
    """AS/SC 비용 구조를 공통 수리항목 컬럼으로 변환한다."""
    is_paint = work_code == PAINT_MATERIAL_WORK_CODE
    if source == "AIHUB_AS":
        raw_part_cost = money(item.get("부품가격"))
        labor_cost = money(item.get("공임"))
        return {
            "part_cost": None if is_paint else raw_part_cost,
            "paint_material_cost": raw_part_cost if is_paint else None,
            "labor_cost": labor_cost,
            "reference_part_price": new_part_price(raw_name),
            "pre_part_cost": None,
            "pre_labor_cost": None,
            "post_part_cost": None,
            "post_labor_cost": None,
            "item_total": item_total(raw_part_cost, labor_cost),
        }

    before = item.get("손해사정전") or {}
    after = item.get("손해사정후") or {}
    pre_part_cost = money(before.get("부품가격"))
    pre_labor_cost = money(before.get("공임"))
    post_part_cost = money(after.get("부품가격"))
    post_labor_cost = money(after.get("공임"))
    return {
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


def normalize_estimate_item(item: Mapping[str, Any], source: str,
                            part_mapping: Mapping[str, str], ordinal: int) -> dict[str, Any]:
    """원천 견적 행 하나를 DB upsert용 값으로 바꾼다.

    알 수 없는 작업 어휘·행 분류 불능은 호출자가 격리할 수 있도록 ``ValueError``로
    돌려준다. part_code 결측은 정상값으로 남긴다. ANCILLARY 외의 결측은 DB 제약과
    충돌하므로 호출자가 `unmapped_part`로 격리해야 한다.
    """
    raw_name = str(item.get("작업항목 및 부품명") or "").strip()
    work_type = str(item.get("작업") or "").strip()
    part_code = part_mapping.get(raw_name)
    assessment_status = "APPROVED" if source == "AIHUB_SC" else None
    stored_work_type: str | None = work_type or None

    if work_type:
        try:
            work = normalize_estimate_work(work_type)
        except NormalizationError as exc:
            raise ValueError(f"unknown_work_type:{work_type}") from exc
        if work["category"] == "ANCILLARY":
            line_type, work_code = "ANCILLARY", work["code"]
        elif work["category"] == "STATUS":
            line_type, work_code = "WORK", None
            stored_work_type, assessment_status = None, "NOT_APPROVED"
        else:
            line_type, work_code = "WORK", work["code"]
    else:
        work_code = None
        probe = estimate_item_costs(item, source, raw_name, None)
        if source == "AIHUB_AS" and probe["reference_part_price"] is not None:
            line_type = "REFERENCE_PRICE"
        elif source == "AIHUB_SC" and probe["part_cost"] is not None and probe["labor_cost"] in {None, 0}:
            line_type = "PART_PRICE"
        else:
            raise ValueError("missing_work_type")

    costs = estimate_item_costs(item, source, raw_name, work_code)
    if line_type == "REFERENCE_PRICE":
        costs = {**costs, "part_cost": None, "paint_material_cost": None, "item_total": None}
    if line_type != "ANCILLARY" and not part_code:
        raise ValueError("unmapped_part")

    return {
        "source_item_key": f"item-{ordinal:05d}",
        "part_code": part_code,
        "raw_item_name": raw_name or None,
        "line_type": line_type,
        "work_type": stored_work_type,
        "work_code": work_code,
        "assessment_status": assessment_status,
        "hq": item.get("HQ%"),
        **costs,
    }
