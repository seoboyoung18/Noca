"""Conservative, row-level validation rules for source estimate items.

The source files contain two different cost layouts (AS and SC).  This module
normalizes only the facts that can be established from one row and deliberately
does not promote the disputed NOT_APPROVED settlement equation to an invariant.
It has no database dependency so the rules can be tested before the 414 schema
contract is merged.
"""
from __future__ import annotations

import re
from typing import Any, Mapping

from shared.vision.normalizer import NormalizationError, normalize_estimate_work


CONTRACT_WORK_CODES = frozenset({
    "EXCHANGE",
    "REMOVE_INSTALL",
    "SHEET_METAL",
    "COATING",
    "OVERHAUL",
    "REPAIR",
})

_REFERENCE_PRICE_RE = re.compile(r"신품가(?:\s*\([^)]*\))?\s*([\d,]+)")
_PART_CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")


RULES = {
    "row_classification": {
        "scope": "every source estimate item",
        "formula": "classify from source work/name and preserve raw values",
        "null_handling": "empty work is classified as a non-work row only when its cost shape proves that",
        "exclusions": "none",
        "error_type": "missing_work_type or unknown_work_type",
        "evidence": "row-level source fields",
        "limitation": "does not infer a missing original work type",
    },
    "not_approved_status": {
        "scope": "rows whose source work is 불인정",
        "formula": "assessment_status=NOT_APPROVED; preserve pre-adjustment source amounts",
        "null_handling": "work_code and split cost columns may be NULL",
        "exclusions": "exclude from split cost-column reconciliation",
        "error_type": "none for the status itself",
        "evidence": "row contains the source item name and 손해사정전 values",
        "limitation": "original work type cannot be reconstructed",
    },
    "cost_column_reconciliation": {
        "scope": "rows not excluded by status or line type",
        "formula": "part_cost + paint_material_cost + labor_cost == item_total",
        "null_handling": "if a required component or independent target total is NULL, mark the row unverifiable; non-applicable paint cost is treated as zero",
        "exclusions": "assessment_status=NOT_APPROVED and line_type=REFERENCE_PRICE",
        "error_type": "item_cost_mismatch",
        "evidence": "row-level normalized cost columns",
        "limitation": "does not validate the disputed NOT_APPROVED settlement equation",
    },
    "ancillary_part_code": {
        "scope": "line_type=ANCILLARY",
        "formula": "ANCILLARY may have part_code=NULL",
        "null_handling": "NULL is valid",
        "exclusions": "none",
        "error_type": "none for missing part_code",
        "evidence": "견인·구난 are service costs, not standard parts",
        "limitation": "the 414 schema migration is required before DB loading",
    },
    "contract_work_code": {
        "scope": "category=WORK rows on the current 002 contract",
        "formula": "work_code in CONTRACT_WORK_CODES",
        "null_handling": "unknown work is a separate closed-world error",
        "exclusions": "ANCILLARY and STATUS are handled by their own categories",
        "error_type": "work_type_outside_contract",
        "evidence": "pipeline/sql/002_repair_case_item_contract.sql",
        "limitation": "414 expands the line-type/schema contract; this rule reports the current boundary",
    },
}


def money(value: Any) -> int | None:
    """Parse a source amount; empty or non-numeric values remain NULL."""
    if value is None:
        return None
    text = str(value).replace(",", "").strip()
    if not text:
        return None
    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def _text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _item_total(*values: int | None) -> int | None:
    known = [value for value in values if value is not None]
    return sum(known) if known else None


def _reference_price(raw_name: str) -> int | None:
    match = _REFERENCE_PRICE_RE.search(raw_name)
    return money(match.group(1)) if match else None


def _source_costs(item: Mapping[str, Any], source: str) -> dict[str, int | None]:
    if source == "AIHUB_AS":
        part_cost = money(item.get("부품가격"))
        labor_cost = money(item.get("공임"))
        return {
            "raw_part_cost": part_cost,
            "raw_labor_cost": labor_cost,
            "pre_part_cost": None,
            "pre_labor_cost": None,
            "post_part_cost": None,
            "post_labor_cost": None,
            "raw_item_total": _item_total(part_cost, labor_cost),
        }

    before = item.get("손해사정전") or {}
    after = item.get("손해사정후") or {}
    pre_part_cost = money(before.get("부품가격"))
    pre_labor_cost = money(before.get("공임"))
    post_part_cost = money(after.get("부품가격"))
    post_labor_cost = money(after.get("공임"))
    return {
        "raw_part_cost": pre_part_cost,
        "raw_labor_cost": pre_labor_cost,
        "pre_part_cost": pre_part_cost,
        "pre_labor_cost": pre_labor_cost,
        "post_part_cost": post_part_cost,
        "post_labor_cost": post_labor_cost,
        "raw_item_total": _item_total(pre_part_cost, pre_labor_cost),
    }


def _violation(
    row: Mapping[str, Any], error_type: str, message: str, details: Mapping[str, Any] | None = None
) -> dict[str, Any]:
    return {
        "error_type": error_type,
        "case_id": row["case_id"],
        "source_ref": row["source_ref"],
        "line_type": row["line_type"],
        "work_code": row["work_code"],
        "assessment_status": row["assessment_status"],
        "message": message,
        "details": dict(details or {}),
    }


def classify_estimate_item(
    item: Mapping[str, Any],
    source: str,
    *,
    case_id: str,
    source_ref: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Return a canonical row and classification errors without dropping data."""
    raw_name = _text(item.get("작업항목 및 부품명"))
    raw_work = _text(item.get("작업"))
    source_costs = _source_costs(item, source)
    line_type: str | None = None
    work_code: str | None = None
    assessment_status = "APPROVED" if source == "AIHUB_SC" else None
    stored_work_type: str | None = raw_work or None
    classification_errors: list[dict[str, Any]] = []

    if raw_work:
        try:
            work = normalize_estimate_work(raw_work)
        except NormalizationError as exc:
            work = None
            classification_errors.append({
                "error_type": "unknown_work_type",
                "message": str(exc),
            })
        if work:
            if work["category"] == "ANCILLARY":
                line_type = "ANCILLARY"
                work_code = work["code"]
            elif work["category"] == "STATUS":
                # 불인정 overwrites the source work field; the original work is not recoverable.
                line_type = "WORK"
                assessment_status = "NOT_APPROVED"
                stored_work_type = None
            else:
                line_type = "WORK"
                work_code = work["code"]
    else:
        reference_part_price = _reference_price(raw_name) if source == "AIHUB_AS" else None
        if reference_part_price is not None:
            line_type = "REFERENCE_PRICE"
        elif (
            source == "AIHUB_SC"
            and source_costs["raw_part_cost"] is not None
            and source_costs["raw_labor_cost"] in {None, 0}
        ):
            line_type = "PART_PRICE"
        else:
            classification_errors.append({
                "error_type": "missing_work_type",
                "message": "work is empty and the row does not match a known non-work shape",
            })

    reference_part_price = _reference_price(raw_name) if line_type == "REFERENCE_PRICE" else None
    raw_part_cost = source_costs["raw_part_cost"]
    raw_labor_cost = source_costs["raw_labor_cost"]
    is_not_approved = assessment_status == "NOT_APPROVED"
    is_paint = work_code == "COATING" and not is_not_approved
    row = {
        "case_id": case_id,
        "source_ref": source_ref,
        "raw_item_name": raw_name,
        "work_type": stored_work_type,
        "line_type": line_type,
        "work_code": work_code,
        "assessment_status": assessment_status,
        "part_code": None,
        "reference_part_price": reference_part_price,
        "part_cost": None if (is_not_approved or is_paint) else raw_part_cost,
        "paint_material_cost": raw_part_cost if is_paint else None,
        "labor_cost": raw_labor_cost,
        "pre_adjustment_part_cost": source_costs["pre_part_cost"],
        "pre_adjustment_labor_cost": source_costs["pre_labor_cost"],
        "post_adjustment_part_cost": source_costs["post_part_cost"],
        "post_adjustment_labor_cost": source_costs["post_labor_cost"],
        # Reference prices are retained as reference data, not as repair-item totals.
        "item_total": None if line_type == "REFERENCE_PRICE" else source_costs["raw_item_total"],
    }
    return row, classification_errors


def validate_estimate_item(
    item: Mapping[str, Any],
    source: str,
    *,
    case_id: str,
    source_ref: str,
    part_code: str | None = None,
    mapping_provided: bool = False,
    expected_item_total: int | None = None,
) -> dict[str, Any]:
    """Validate one source row and return both the canonical row and violations."""
    row, raw_errors = classify_estimate_item(
        item, source, case_id=case_id, source_ref=source_ref
    )
    row["part_code"] = part_code
    if expected_item_total is not None:
        row["item_total"] = expected_item_total
        row["item_total_source"] = "external"
    else:
        row["item_total_source"] = "derived_from_source_components"
    errors = [
        _violation(row, error["error_type"], error["message"])
        for error in raw_errors
    ]

    if row["line_type"] is not None and row["line_type"] != "ANCILLARY" and mapping_provided:
        if not part_code or not _PART_CODE_RE.fullmatch(part_code):
            errors.append(_violation(
                row,
                "unknown_part_code",
                "item name has no resolvable standard part_code",
                {"raw_item_name": row["raw_item_name"], "part_code": part_code},
            ))

    if row["line_type"] == "WORK" and row["work_code"] not in {None, *CONTRACT_WORK_CODES}:
        errors.append(_violation(
            row,
            "work_type_outside_contract",
            "known estimate work is outside the current 002 DB work-code contract",
            {"work_code": row["work_code"]},
        ))

    exclusions: list[str] = []
    if row["assessment_status"] == "NOT_APPROVED":
        exclusions.append("not_approved_cost_decomposition")
    elif row["line_type"] == "REFERENCE_PRICE":
        exclusions.append("reference_price_not_a_repair_total")
    elif row["line_type"] is None:
        exclusions.append("unclassified_row")
    elif row["item_total_source"] != "external":
        exclusions.append("item_total_not_independent")
    else:
        if row["line_type"] == "PART_PRICE":
            required_costs = (row["part_cost"], row["item_total"])
            actual = row["part_cost"]
        elif row["work_code"] == "COATING":
            # 도장 행은 부품비 대신 paint_material_cost를 사용한다.
            required_costs = (row["paint_material_cost"], row["labor_cost"], row["item_total"])
            actual = (
                (row["paint_material_cost"] or 0) + (row["labor_cost"] or 0)
                if all(value is not None for value in required_costs)
                else None
            )
        else:
            required_costs = (row["part_cost"], row["labor_cost"], row["item_total"])
            actual = (
                (row["part_cost"] or 0) + (row["labor_cost"] or 0)
                if all(value is not None for value in required_costs)
                else None
            )
        if actual is not None:
            if actual != row["item_total"]:
                errors.append(_violation(
                    row,
                    "item_cost_mismatch",
                    "normalized cost columns do not add up to item_total",
                    {"component_sum": actual, "item_total": row["item_total"]},
                ))
        else:
            exclusions.append("incomplete_cost_columns")

    return {
        "row": row,
        "errors": errors,
        "validation": {
            "cost_reconciliation": "EXCLUDED" if exclusions else "PASSED",
            "exclusions": exclusions,
        },
    }


def evaluate_sample_rule(
    sample_size: int, matching_count: int, *, minimum_sample_size: int = 100
) -> dict[str, Any]:
    """Keep a candidate formula from becoming an invariant on a small sample."""
    if sample_size < 0 or matching_count < 0 or matching_count > sample_size:
        raise ValueError("sample counts must satisfy 0 <= matching_count <= sample_size")
    if sample_size < minimum_sample_size:
        return {
            "status": "INSUFFICIENT_SAMPLE",
            "promote_to_invariant": False,
            "sample_size": sample_size,
            "matching_count": matching_count,
            "minimum_sample_size": minimum_sample_size,
        }
    return {
        "status": "OBSERVED",
        "promote_to_invariant": False,
        "sample_size": sample_size,
        "matching_count": matching_count,
        "minimum_sample_size": minimum_sample_size,
    }
