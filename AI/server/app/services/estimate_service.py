"""MVP real-time estimate calculation from search-referenced repair cases.

Implements `Docs/AI/견적 산출 입출력 형식_update.md` §3 입력 → §5 출력, with row
filtering/작업 매핑 from `Docs/Erd/A307_COST_POLICY.md`. `repair_cost_stat` is not
used — see the update doc's §1/§8 for why.
"""
from __future__ import annotations

import logging
import statistics
from collections import defaultdict
from typing import TYPE_CHECKING, Any

from ..infrastructure.cost_repository import CostCaseRepository, CostCaseRow
from . import estimate_config as config

if TYPE_CHECKING:
    from ..schemas.contracts import EstimateRequest

logger = logging.getLogger(__name__)

# A307_COST_POLICY.md §1/§3-2/§4: 정산에 포함되는 행 종류만 남긴다.
INCLUDED_LINE_TYPES = frozenset({"WORK", "PART_PRICE"})

# §4-1 오버홀 계열은 독립 방식이 아니라 repair 수리 과정의 한 단계.
WORK_CODE_TO_REPAIR_METHOD = {
    "COATING": "coating",
    "SHEET_METAL": "sheet_metal",
    "EXCHANGE": "exchange",
    "REPAIR": "repair",
    "OVERHAUL": "repair",
    "OVERHAUL_HALF": "repair",
    "OVERHAUL_THIRD": "repair",
    "OVERHAUL_QUARTER": "repair",
}

# 출력 repairMethod 대표값 우선순위. 교환이 있으면 교환이 주 작업, 없으면 판금,
# 그다음 수리, 도장은 마감 단계라 최하위(판금 후 도장 조합이 실제로 흔하다 —
# 원천 데이터 {판금,도장} 조합 756건, 총액 중앙값 44만원으로 단독 도장 6.97만원·
# 단독 판금 4.14만원보다 훨씬 크다).
REPAIR_METHOD_PRIORITY = ("exchange", "sheet_metal", "repair", "coating")

_GRADE_RANK = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


class UnresolvedPart:
    """A requested part whose cost could not be resolved into an item."""

    def __init__(self, part_code: str, damage_type: str, reason: str) -> None:
        self.part_code = part_code
        self.damage_type = damage_type
        self.reason = reason

    def to_dict(self) -> dict[str, Any]:
        return {"partCode": self.part_code, "damageType": self.damage_type, "reason": self.reason}


class _CaseCost:
    """One case's total cost for one part — every included WORK/PART_PRICE row
    summed together regardless of repairMethod (see `_aggregate_case`). `methods`
    records which repairMethods contributed, for the item's repairMethodReason."""

    __slots__ = ("case_id", "methods", "part_cost", "labor_cost", "paint_material_cost")

    def __init__(self, case_id: int, methods: frozenset[str],
                 part_cost: int, labor_cost: int, paint_material_cost: int) -> None:
        self.case_id = case_id
        self.methods = methods
        self.part_cost = part_cost
        self.labor_cost = labor_cost
        self.paint_material_cost = paint_material_cost

    @property
    def total(self) -> int:
        return self.part_cost + self.labor_cost + self.paint_material_cost


class EstimateService:
    def __init__(self, repository: CostCaseRepository, *,
                 enable_part_price_reference: bool = False,
                 enable_yolo_estimate_references: bool = False,
                 yolo_estimate_max_cases: int = 10) -> None:
        self._repository = repository
        self._enable_part_price_reference = enable_part_price_reference
        self._enable_yolo_estimate_references = enable_yolo_estimate_references
        self._yolo_estimate_max_cases = yolo_estimate_max_cases

    def calculate(self, request: EstimateRequest) -> dict[str, Any]:
        strict_parts = [part for part in request.parts if part.get("searchability") == "STRICT"]
        if not strict_parts:
            return _non_estimable("PART_NOT_RESOLVED", unresolved=[])

        case_ids = sorted({
            int(case_id)
            for part in strict_parts
            for case_id in (
                list(_estimate_case_ids(part, self._enable_yolo_estimate_references))
                + [candidate.get("caseId") for candidate in part.get("partPriceCandidateCases", [])]
            )
            if case_id is not None
        })
        part_codes = sorted({part["partCode"] for part in strict_parts})
        rows = self._repository.fetch_case_items(case_ids, part_codes)
        rows_by_key: dict[tuple[int, str], list[CostCaseRow]] = defaultdict(list)
        for row in rows:
            rows_by_key[(row.case_id, row.part_code)].append(row)

        part_price_references = [
            reference
            for part in strict_parts
            for reference in (
                _part_price_reference(part, rows_by_key)
                if self._enable_part_price_reference else []
            )
        ]

        items: list[dict[str, Any]] = []
        unresolved: list[UnresolvedPart] = []
        for part in strict_parts:
            resolved = _resolve_item(
                part, rows_by_key,
                case_ids=_estimate_case_ids(part, self._enable_yolo_estimate_references),
            )
            if isinstance(resolved, UnresolvedPart):
                unresolved.append(resolved)
            else:
                items.append(resolved)

        if not items:
            reason = unresolved[0].reason if unresolved else "INSUFFICIENT_CASES"
            return _non_estimable(
                reason, unresolved=unresolved, part_price_references=part_price_references,
            )

        totals = {
            "min": sum(item["costDistribution"]["p25"] for item in items),
            "median": sum(item["costDistribution"]["median"] for item in items),
            "max": sum(item["costDistribution"]["p75"] for item in items),
        }
        ref_case_total = len({case_id for item in items for case_id in item["referencedCaseIds"]})
        overall_grade = min(
            (item["confidenceGrade"] for item in items), key=lambda grade: _GRADE_RANK[grade],
        )

        return {
            "estimable": True,
            "nonEstimableReason": None,
            "confidenceGrade": overall_grade,
            "totals": totals,
            "refCaseTotal": ref_case_total,
            "items": items,
            "unresolvedParts": [part.to_dict() for part in unresolved],
            "partPriceReferences": part_price_references,
        }

    def select_full_repair_reference_candidates(
        self, candidates: list[dict[str, Any]], part_code: str,
        *, max_cases: int | None = None,
    ) -> list[dict[str, Any]]:
        """Select valid FULL_REPAIR cases from one v2 vector pool.

        SearchService supplies the already-ranked Top-100 pool. This method is
        the single cost-policy gateway for the new estimate reference path: it
        reuses the same row filter and ``_aggregate_case`` used by ``calculate``
        and therefore never treats PART_PRICE-only rows as FULL_REPAIR.
        """
        limit = max_cases or self._yolo_estimate_max_cases
        normalized: list[dict[str, Any]] = []
        seen: set[int] = set()
        for candidate in candidates:
            try:
                case_id = int(candidate["caseId"])
            except (KeyError, TypeError, ValueError):
                continue
            if case_id in seen:
                continue
            seen.add(case_id)
            normalized.append({**candidate, "caseId": case_id})
        normalized.sort(key=lambda row: (-(float(row.get("similarity") or 0.0)), row["caseId"]))
        rows = self._repository.fetch_case_items(
            [row["caseId"] for row in normalized], [part_code],
        )
        rows_by_key: dict[tuple[int, str], list[CostCaseRow]] = defaultdict(list)
        for row in rows:
            rows_by_key[(row.case_id, row.part_code)].append(row)
        output: list[dict[str, Any]] = []
        for candidate in normalized:
            case_id = candidate["caseId"]
            included = [
                row for row in rows_by_key.get((case_id, part_code), [])
                if _is_included_row(row)
            ]
            case_cost = _aggregate_case(case_id, part_code, included)
            if case_cost is None:
                continue
            output.append({
                **candidate,
                "caseId": case_id,
                "fullRepairTotal": case_cost.total,
                "repairMethods": sorted(case_cost.methods),
                "includedInDistribution": True,
            })
            if len(output) >= limit:
                break
        return output


def _non_estimable(
    reason: str, *, unresolved: list[UnresolvedPart],
    part_price_references: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "estimable": False,
        "nonEstimableReason": reason,
        "confidenceGrade": None,
        "totals": None,
        "refCaseTotal": 0,
        "items": [],
        "unresolvedParts": [part.to_dict() for part in unresolved],
        "partPriceReferences": part_price_references or [],
    }


PART_PRICE_REFERENCE_NOTICE = (
    "부품비만의 참고 범위입니다. 작업비·도장비·총 수리비는 포함하지 않습니다."
)


def _source_aware_part_price(row: CostCaseRow) -> int:
    """Return the source-specific PART_PRICE amount used by the reference path."""
    value = row.post_adjustment_part_cost if row.source == "AIHUB_SC" else row.part_cost
    return int(value or 0)


def _part_price_reference(
    part: dict[str, Any], rows_by_key: dict[tuple[int, str], list[CostCaseRow]],
) -> list[dict[str, Any]]:
    """Build an optional PART_PRICE_ONLY reference without touching FULL_REPAIR.

    Candidate metadata is supplied by SearchService from the shared v2 Top-100
    vector pool.  This helper intentionally does not inspect repair hints or
    sibling WORK rows; those rows remain exclusively in ``_aggregate_case``.
    """
    # The caller gates this helper with the feature flag by removing references
    # at construction time; keeping searchability/part checks here makes it safe
    # for direct use in tests and future callers.
    if part.get("searchability") != "STRICT" or not part.get("partCode"):
        return []
    part_code = str(part["partCode"])
    raw_candidates = part.get("partPriceCandidateCases") or [
        {"caseId": case_id}
        for case_id in part.get("referencedCaseIds", [])
    ]
    candidates: list[tuple[int, float | None]] = []
    seen: set[int] = set()
    for candidate in raw_candidates:
        if candidate.get("corpusPartMatched") is not True:
            continue
        if candidate.get("corpusPartCode") != part_code:
            continue
        try:
            case_id = int(candidate.get("caseId"))
        except (AttributeError, TypeError, ValueError):
            continue
        if case_id in seen:
            continue
        seen.add(case_id)
        similarity = candidate.get("similarity")
        candidates.append((case_id, float(similarity) if similarity is not None else None))
    # Search metadata is already similarity ordered, but sorting here makes the
    # service deterministic when a client sends a candidate list directly.
    if any(similarity is not None for _, similarity in candidates):
        candidates.sort(key=lambda pair: (-(pair[1] if pair[1] is not None else float("-inf")), pair[0]))

    valid: list[tuple[int, float | None, int]] = []
    for case_id, similarity in candidates:
        rows = rows_by_key.get((case_id, part_code), [])
        amount = sum(
            _source_aware_part_price(row)
            for row in rows
            if row.line_type == "PART_PRICE"
            and row.assessment_status != "NOT_APPROVED"
            and _source_aware_part_price(row) > 0
        )
        if amount > 0:
            valid.append((case_id, similarity, amount))
        if len(valid) >= 10:
            break
    if len(valid) < config.MIN_CASE_COUNT:
        return []
    amounts = [amount for _, _, amount in valid]
    p25, median, p75 = _percentiles(amounts)
    return [{
        "partCode": part_code,
        "referenceKind": "PART_PRICE_ONLY",
        "refCaseCount": len(valid),
        "referencedCaseIds": [case_id for case_id, _, _ in valid],
        "costDistribution": {"p25": p25, "median": median, "p75": p75},
        "notice": PART_PRICE_REFERENCE_NOTICE,
    }]


def _estimate_case_ids(part: dict[str, Any], yolo_references_enabled: bool) -> list[Any]:
    if yolo_references_enabled and "estimateReferencedCaseIds" in part:
        return list(part.get("estimateReferencedCaseIds") or [])
    return list(part.get("referencedCaseIds", []))


def _resolve_item(
    part: dict[str, Any], rows_by_key: dict[tuple[int, str], list[CostCaseRow]],
    *, case_ids: list[Any] | None = None,
) -> dict[str, Any] | UnresolvedPart:
    part_code = part["partCode"]
    damage_type = part["damageType"]
    resolved_case_ids: list[int] = [int(case_id) for case_id in (
        case_ids if case_ids is not None else part.get("referencedCaseIds", [])
    )]

    case_costs: list[_CaseCost] = []
    for case_id in resolved_case_ids:
        rows = [row for row in rows_by_key.get((case_id, part_code), []) if _is_included_row(row)]
        if not rows:
            continue
        case_cost = _aggregate_case(case_id, part_code, rows)
        if case_cost is not None:
            case_costs.append(case_cost)

    if not case_costs or len(case_costs) < config.MIN_CASE_COUNT:
        return UnresolvedPart(part_code, damage_type, "INSUFFICIENT_CASES")

    totals = [cost.total for cost in case_costs]
    p25, median, p75 = _percentiles(totals)
    ref_case_count = len(case_costs)

    all_methods: set[str] = set()
    for cost in case_costs:
        all_methods |= cost.methods
    candidates = [method for method in REPAIR_METHOD_PRIORITY if method in all_methods]
    representative_method = candidates[0]

    return {
        "partCode": part_code,
        "damageType": damage_type,
        "confidence": part.get("confidence"),
        "repairMethod": representative_method,
        "repairMethodReason": {"candidates": candidates},
        "partCost": _median_or_none([cost.part_cost for cost in case_costs]),
        "laborCost": _median_or_none([cost.labor_cost for cost in case_costs]),
        "paintMaterialCost": _median_or_none([cost.paint_material_cost for cost in case_costs]),
        "itemTotal": median,
        "detectionIds": part.get("detectionIds", []),
        "refCaseCount": ref_case_count,
        "referencedCaseIds": [cost.case_id for cost in case_costs],
        "costDistribution": {"p25": p25, "median": median, "p75": p75},
        "fallbackStage": part.get("fallbackStage"),
        "confidenceGrade": _item_confidence_grade(ref_case_count, part.get("confidence")),
    }


def _is_included_row(row: CostCaseRow) -> bool:
    if row.line_type not in INCLUDED_LINE_TYPES:
        return False  # REFERENCE_PRICE, ANCILLARY 제외 (§3-2, §3-3)
    if row.assessment_status == "NOT_APPROVED":
        return False  # §3-1
    if row.work_code in config.EXCLUDED_WORK_CODES:
        return False  # 탈착/조정/견인/구난 제외 (§4-2, §4-4)
    return True


def _aggregate_case(case_id: int, part_code: str, rows: list[CostCaseRow]) -> _CaseCost | None:
    """Sum every included WORK/PART_PRICE row for this case+part into ONE total,
    regardless of repairMethod. A part's repair is often multiple real stages on
    the same case — e.g. sheet-metal work followed by a coating pass — so a
    SHEET_METAL row and a COATING row in the same case are both genuine cost, not
    a mismatch to pick between (원천 데이터: {판금,도장} 조합 756건, 총액 중앙값
    44만원 — 단독 도장 6.97만원·단독 판금 4.14만원보다 훨씬 크다)."""
    work_rows = [row for row in rows if row.line_type == "WORK"]
    part_price_rows = [row for row in rows if row.line_type == "PART_PRICE"]

    methods: set[str] = set()
    part_cost = labor_cost = paint_material_cost = 0

    for row in work_rows:
        method = WORK_CODE_TO_REPAIR_METHOD.get(row.work_code)
        if method is None:
            if row.work_code in config.EXCLUDED_WORK_CODES:
                # 정책상 확정 제외 대상(탈착 등)이라 매핑 실패가 아니다. 보통은
                # _is_included_row가 먼저 걸러내므로 여기 도달하면 방어적 경로다.
                logger.debug(
                    "policy-excluded work_code reached _aggregate_case: "
                    "case_id=%s part_code=%s work_code=%s",
                    case_id, part_code, row.work_code,
                )
            else:
                logger.warning(
                    "unmapped work_code on a WORK row: case_id=%s part_code=%s work_code=%s",
                    case_id, part_code, row.work_code,
                )
            continue
        methods.add(method)
        is_sc = config.USE_SOURCE_AWARE_COST_COLUMNS and row.source == "AIHUB_SC"
        if method == "coating":
            # A307_COST_POLICY.md §2-6: SC엔 도장 재료비 전용 컬럼이 없어
            # post_adjustment_part_cost가 재료비를 담는다. part_cost 축적에는
            # 절대 더하지 않는다(부품비 통계 오염 방지).
            paint_material_cost += (
                (row.post_adjustment_part_cost if is_sc else row.paint_material_cost) or 0
            )
        else:
            part_cost += (row.post_adjustment_part_cost if is_sc else row.part_cost) or 0
        labor_cost += (row.post_adjustment_labor_cost if is_sc else row.labor_cost) or 0

    if part_price_rows:
        # PART_PRICE 행 자체엔 work_code가 없다(DDL). 결국 이 사례의 부품 비용에
        # 그대로 합산되므로, 매핑된 WORK 행이 하나라도 있으면(어떤 repairMethod든)
        # 포함하고 없으면 무엇에 귀속시킬지 알 수 없으니 제외한다(exchange로 추정하지 않는다).
        if methods:
            for row in part_price_rows:
                is_sc = config.USE_SOURCE_AWARE_COST_COLUMNS and row.source == "AIHUB_SC"
                part_cost += (row.post_adjustment_part_cost if is_sc else row.part_cost) or 0
        else:
            logger.warning(
                "PART_PRICE row(s) with no mapped WORK repairMethod: case_id=%s part_code=%s",
                case_id, part_code,
            )

    # 2026-09-17 팀 합의: 교환은 비용 대부분이 부품비인데, AS는 교환 행 부품비가
    # 99.97% 결측이고 적재 시에도 다른 필드로 채우지 않는다. 부품비 0인 채로 계산에
    # 넣으면 범위·중앙값이 왜곡되므로, 교환이 섞인 사례 중 부품비 합이 0(이하)이면
    # 출처명이 아니라 이 조건으로 제외한다 (COST_POLICY §2-5, DUPLICATE_AND_MISSING_NOTES §B4).
    if "exchange" in methods and part_cost <= 0:
        logger.debug(
            "exchange case with no part cost excluded: case_id=%s part_code=%s",
            case_id, part_code,
        )
        return None

    total = part_cost + labor_cost + paint_material_cost
    if total <= 0:
        return None  # §6: 비용 행의 필수 금액이 없음 → 해당 사례 제외
    return _CaseCost(case_id, frozenset(methods), part_cost, labor_cost, paint_material_cost)


def _median_or_none(values: list[int]) -> int | None:
    if not values or all(value == 0 for value in values):
        return None
    return round(statistics.median(values))


def _percentiles(values: list[int]) -> tuple[int, int, int]:
    if len(values) == 1:
        return values[0], values[0], values[0]
    q1, _, q3 = statistics.quantiles(values, n=4, method="inclusive")
    return round(q1), round(statistics.median(values)), round(q3)


def _sample_size_grade(ref_case_count: int) -> str:
    if ref_case_count >= config.SAMPLE_SIZE_HIGH_THRESHOLD:
        return "HIGH"
    if ref_case_count >= config.SAMPLE_SIZE_MEDIUM_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def _confidence_grade(confidence: float | None) -> str:
    if confidence is None:
        return "LOW"
    if confidence >= config.CONFIDENCE_HIGH_THRESHOLD:
        return "HIGH"
    if confidence >= config.CONFIDENCE_MEDIUM_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def _item_confidence_grade(ref_case_count: int, confidence: float | None) -> str:
    return min(
        _sample_size_grade(ref_case_count), _confidence_grade(confidence),
        key=lambda grade: _GRADE_RANK[grade],
    )
