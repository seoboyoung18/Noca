from __future__ import annotations

import logging
import statistics

import pytest

from app.infrastructure.cost_repository import CostCaseRow, InMemoryCostCaseRepository
from app.schemas.contracts import EstimateRequest, Vehicle
from app.services.estimate_service import EstimateService, _aggregate_case


def _row(case_id, part_code, line_type, *, source="AIHUB_AS", work_code=None,
         assessment_status=None, part_cost=None, paint_material_cost=None,
         labor_cost=None, post_adjustment_part_cost=None, post_adjustment_labor_cost=None):
    return CostCaseRow(
        case_id=case_id, source=source, part_code=part_code, line_type=line_type,
        work_code=work_code, assessment_status=assessment_status,
        part_cost=part_cost, paint_material_cost=paint_material_cost, labor_cost=labor_cost,
        post_adjustment_part_cost=post_adjustment_part_cost,
        post_adjustment_labor_cost=post_adjustment_labor_cost,
    )


def _part(part_code="REAR_BUMPER", damage_type="Scratched", searchability="STRICT",
          confidence=0.9321, referenced_case_ids=None, detection_ids=None, fallback_stage="PRICE_TIER"):
    return {
        "partCode": part_code,
        "damageType": damage_type,
        "confidence": confidence,
        "detectionIds": detection_ids or ["501:damage:damage-001"],
        "pairStatus": "PAIRED",
        "searchability": searchability,
        "fallbackStage": fallback_stage,
        "referencedCaseIds": referenced_case_ids or [],
    }


def _request(parts):
    return EstimateRequest(
        vehicle=Vehicle(model_id=41, car_class="Compact", model_year=2021), parts=parts,
    )


def test_happy_path_matches_document_example_shape():
    # 문서 §3 입력 예시(REAR_BUMPER/Scratched/coating)를 그대로 쓰되, 총액은 직접
    # 검증 가능한 값으로 구성한다(문서 §4.3/§5 예시 수치는 서로 다른 사례 수를
    # 전제해 자체 일관되지 않으므로 그대로 재현하지 않는다).
    repository = InMemoryCostCaseRepository([
        _row(121381, "REAR_BUMPER", "WORK", work_code="COATING",
             paint_material_cost=80000, labor_cost=220000),   # total 300000
        _row(121414, "REAR_BUMPER", "WORK", work_code="COATING",
             paint_material_cost=85500, labor_cost=250000),   # total 335500
        _row(999999, "REAR_BUMPER", "WORK", work_code="COATING",
             paint_material_cost=100000, labor_cost=280000),  # total 380000, 3rd case
    ])
    service = EstimateService(repository)
    part = _part(referenced_case_ids=[121381, 121414, 999999])

    result = service.calculate(_request([part]))

    assert result["estimable"] is True
    assert result["nonEstimableReason"] is None
    assert result["unresolvedParts"] == []
    assert len(result["items"]) == 1
    item = result["items"][0]
    assert item["partCode"] == "REAR_BUMPER"
    assert item["repairMethod"] == "coating"
    assert item["partCost"] is None  # coating 항목은 부품비가 없다
    assert item["paintMaterialCost"] == round(statistics.median([80000, 85500, 100000]))
    assert item["laborCost"] == round(statistics.median([220000, 250000, 280000]))
    assert item["refCaseCount"] == 3
    assert set(item["referencedCaseIds"]) == {121381, 121414, 999999}
    totals = [300000, 335500, 380000]
    q1, _, q3 = statistics.quantiles(totals, n=4, method="inclusive")
    assert item["costDistribution"] == {
        "p25": round(q1), "median": round(statistics.median(totals)), "p75": round(q3),
    }
    assert item["itemTotal"] == item["costDistribution"]["median"]
    assert result["totals"] == {
        "min": item["costDistribution"]["p25"],
        "median": item["costDistribution"]["median"],
        "max": item["costDistribution"]["p75"],
    }
    assert result["refCaseTotal"] == 3


def test_part_not_resolved_when_everything_is_vector_only():
    repository = InMemoryCostCaseRepository([])
    service = EstimateService(repository)
    part = _part(searchability="VECTOR_ONLY", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    assert result["estimable"] is False
    assert result["nonEstimableReason"] == "PART_NOT_RESOLVED"
    assert result["items"] == []
    assert result["unresolvedParts"] == []


def test_insufficient_cases_when_no_cost_rows_match():
    # referencedCaseIds가 있어도 저장소에 해당 case_id+part_code 행이 전혀 없다.
    repository = InMemoryCostCaseRepository([
        _row(1, "OTHER_PART", "WORK", work_code="REPAIR", part_cost=10000, labor_cost=10000),
    ])
    service = EstimateService(repository)
    part = _part(referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    assert result["estimable"] is False
    assert result["nonEstimableReason"] == "INSUFFICIENT_CASES"
    assert result["items"] == []
    assert result["unresolvedParts"] == [
        {"partCode": "REAR_BUMPER", "damageType": "Scratched", "reason": "INSUFFICIENT_CASES"},
    ]


def test_removal_installation_rows_are_excluded_from_cost():
    # 같은 사례에 정상 수리 행과 탈착(REMOVE_INSTALL) 행이 섞여 있으면 탈착은 빠져야 한다.
    rows = [
        _row(1, "FRONT_DOOR", "WORK", work_code="REPAIR", part_cost=100000, labor_cost=50000),
        _row(1, "FRONT_DOOR", "WORK", work_code="REMOVE_INSTALL", labor_cost=7000),
        _row(2, "FRONT_DOOR", "WORK", work_code="REPAIR", part_cost=100000, labor_cost=50000),
        _row(3, "FRONT_DOOR", "WORK", work_code="REPAIR", part_cost=100000, labor_cost=50000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="FRONT_DOOR", damage_type="Crushed", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    # 탈착의 7000원이 섞였다면 case 1의 총액은 157000이 되어 분포가 달라진다.
    assert item["itemTotal"] == 150000
    assert item["costDistribution"] == {"p25": 150000, "median": 150000, "p75": 150000}


def test_excluded_work_codes_are_dropped_silently_but_unmapped_codes_warn(caplog):
    # _is_included_row가 REMOVE_INSTALL을 먼저 걸러내므로, _aggregate_case 자체의
    # 방어 로직(정책 제외 코드 vs 진짜 미매핑 코드 구분)을 보려면 그 필터를 우회해서
    # _aggregate_case를 직접 호출해야 한다.
    rows = [
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(1, "PART_A", "WORK", work_code="REMOVE_INSTALL", labor_cost=7000),
        _row(1, "PART_A", "WORK", work_code="TRULY_UNKNOWN", part_cost=99999),
    ]

    with caplog.at_level(logging.DEBUG, logger="app.services.estimate_service"):
        case_cost = _aggregate_case(1, "PART_A", rows)

    # 탈착 비용(7000)이 안 섞여야 한다.
    assert case_cost.total == 100000

    warning_records = [r for r in caplog.records if r.levelname == "WARNING"]
    debug_records = [r for r in caplog.records if r.levelname == "DEBUG"]
    assert len(warning_records) == 1
    assert "TRULY_UNKNOWN" in warning_records[0].getMessage()
    assert any("REMOVE_INSTALL" in r.getMessage() for r in debug_records)
    assert not any("REMOVE_INSTALL" in r.getMessage() for r in warning_records)


def test_multiple_rows_in_one_case_are_summed_exchange_part_price_plus_work_labor():
    # EXCHANGE WORK 행(공임만) + PART_PRICE 행(부품비만) → 같은 사례에서 합산되어야 한다.
    rows = [
        _row(1, "REAR_FENDER", "WORK", work_code="EXCHANGE", labor_cost=50000),
        _row(1, "REAR_FENDER", "PART_PRICE", part_cost=200000),
        _row(2, "REAR_FENDER", "WORK", work_code="EXCHANGE", labor_cost=55000),
        _row(2, "REAR_FENDER", "PART_PRICE", part_cost=210000),
        _row(3, "REAR_FENDER", "WORK", work_code="EXCHANGE", labor_cost=48000),
        _row(3, "REAR_FENDER", "PART_PRICE", part_cost=190000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="REAR_FENDER", damage_type="Separated", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    assert item["repairMethod"] == "exchange"
    assert item["refCaseCount"] == 3
    # case 1 total = 250000, case 2 = 265000, case 3 = 238000
    assert item["itemTotal"] == round(statistics.median([250000, 265000, 238000]))
    assert item["partCost"] == round(statistics.median([200000, 210000, 190000]))
    assert item["laborCost"] == round(statistics.median([50000, 55000, 48000]))


def test_mixed_repair_methods_in_one_case_are_summed_not_majority_voted():
    # case 1: 같은 사례·같은 부품에 판금(SHEET_METAL) + 도장(COATING) 행이 모두 있음.
    # 실제 수리가 여러 단계로 나뉜 정상 케이스이므로 둘 다 합산되어야 한다.
    rows = [
        _row(1, "PART_A", "WORK", work_code="SHEET_METAL", part_cost=100000, labor_cost=50000),
        _row(1, "PART_A", "WORK", work_code="COATING", paint_material_cost=60000, labor_cost=30000),
        _row(2, "PART_A", "WORK", work_code="SHEET_METAL", part_cost=110000, labor_cost=50000),
        _row(3, "PART_A", "WORK", work_code="SHEET_METAL", part_cost=120000, labor_cost=50000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", damage_type="Crushed", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    # case 1의 도장 몫(90000)이 다수결로 버려졌다면 case1 total은 150000이 된다.
    case_totals = [240000, 160000, 170000]
    assert item["refCaseCount"] == 3
    assert item["itemTotal"] == round(statistics.median(case_totals))
    assert item["repairMethod"] == "sheet_metal"  # exchange 없음 → sheet_metal 우선
    assert item["repairMethodReason"] == {
        "candidates": ["sheet_metal", "coating"],
        "mergedCandidates": ["sheet_metal", "coating"],
        "uncoveredMethods": [],
    }
    assert item["laborCost"] == round(statistics.median([80000, 50000, 50000]))
    assert item["paintMaterialCost"] == round(statistics.median([60000, 0, 0]))


def test_repair_method_priority_prefers_exchange_over_others_present():
    rows = [
        _row(1, "PART_A", "WORK", work_code="EXCHANGE", labor_cost=50000),
        _row(1, "PART_A", "PART_PRICE", part_cost=200000),
        _row(1, "PART_A", "WORK", work_code="SHEET_METAL", part_cost=30000, labor_cost=20000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    # repair는 2개 사례에, exchange는 1개 사례에만 등장한다. 비용 표본과
    # 같은 사례 단위 분포를 대표하므로 단 한 건의 exchange가 라벨을 덮지 않는다.
    assert item["repairMethod"] == "repair"
    assert item["repairMethodReason"] == {
        "candidates": ["exchange", "sheet_metal", "repair"],
        "mergedCandidates": ["exchange", "sheet_metal", "repair"],
        "uncoveredMethods": [],
    }
    # case 1 total = 50000(exchange labor) + 200000(part_price) + 30000+20000(sheet_metal) = 300000
    assert item["refCaseCount"] == 3


def test_repair_method_uses_case_frequency_not_work_row_count_or_priority_union():
    # case 1은 판금+도장, case 2·3은 도장만이다. 판금 행이 존재한다는 이유로
    # sheet_metal을 고르면 안 되고, 비용 중앙값과 같은 사례 표본의 최빈 방식인
    # coating이 대표 라벨이 되어야 한다.
    rows = [
        _row(1, "PART_A", "WORK", work_code="SHEET_METAL", part_cost=30000, labor_cost=20000),
        _row(1, "PART_A", "WORK", work_code="COATING", paint_material_cost=60000, labor_cost=30000),
        _row(2, "PART_A", "WORK", work_code="COATING", paint_material_cost=70000, labor_cost=30000),
        _row(3, "PART_A", "WORK", work_code="COATING", paint_material_cost=80000, labor_cost=30000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    assert item["repairMethod"] == "coating"
    assert item["repairMethodReason"] == {
        "candidates": ["sheet_metal", "coating"],
        "mergedCandidates": ["sheet_metal", "coating"],
        "uncoveredMethods": [],
    }


def test_part_price_without_sibling_work_row_is_dropped_not_guessed_as_exchange():
    # PART_PRICE 행만 있고 같은 case+part에 WORK 행이 하나도 없으면, exchange로
    # 추정하지 않고 그 행만 제외한다(폴백 금지 — 사용자 확인 사항).
    rows = [
        _row(1, "PART_A", "PART_PRICE", part_cost=200000),  # WORK 행 없음 → 제외
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(4, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3, 4])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    # case 1은 표본에서 완전히 빠져야 한다 — 포함됐다면 refCaseCount가 4가 된다.
    assert item["refCaseCount"] == 3
    assert 1 not in item["referencedCaseIds"]
    assert item["repairMethod"] == "repair"


def test_as_exchange_case_without_part_cost_is_excluded():
    # 2026-09-17 팀 합의: AS 교환 행은 부품비가 99.97% 결측이라, 공임만 있어도
    # 부품비 0인 채로 계산에 넣지 않고 사례 자체를 제외한다.
    rows = [_row(1, "PART_A", "WORK", work_code="EXCHANGE", labor_cost=50000)]

    result = _aggregate_case(1, "PART_A", rows)

    assert result is None


def test_sc_exchange_case_with_part_price_is_included():
    # SC는 PART_PRICE로 부품비가 채워지므로 제외 규칙에 걸리지 않아야 한다.
    rows = [
        _row(1, "PART_A", "WORK", work_code="EXCHANGE", source="AIHUB_SC",
             post_adjustment_part_cost=0, post_adjustment_labor_cost=50000),
        _row(1, "PART_A", "PART_PRICE", source="AIHUB_SC", post_adjustment_part_cost=200000),
    ]

    result = _aggregate_case(1, "PART_A", rows)

    assert result is not None
    assert result.part_cost == 200000
    assert result.labor_cost == 50000
    assert result.total == 250000


def test_all_as_exchange_cases_excluded_leads_to_insufficient_cases():
    rows = [
        _row(1, "PART_A", "WORK", work_code="EXCHANGE", labor_cost=50000),
        _row(2, "PART_A", "WORK", work_code="EXCHANGE", labor_cost=55000),
        _row(3, "PART_A", "WORK", work_code="EXCHANGE", labor_cost=48000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", damage_type="Separated", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    assert result["estimable"] is False
    assert result["nonEstimableReason"] == "INSUFFICIENT_CASES"
    assert result["unresolvedParts"] == [
        {"partCode": "PART_A", "damageType": "Separated", "reason": "INSUFFICIENT_CASES"},
    ]


def test_as_sheet_metal_and_coating_case_without_part_cost_is_not_excluded():
    # 제외 규칙은 exchange에만 걸려야 한다 — 판금+도장(부품비 없음)은 그대로 포함.
    rows = [
        _row(1, "PART_A", "WORK", work_code="SHEET_METAL", labor_cost=50000),
        _row(1, "PART_A", "WORK", work_code="COATING", paint_material_cost=60000, labor_cost=30000),
    ]

    result = _aggregate_case(1, "PART_A", rows)

    assert result is not None
    assert result.methods == frozenset({"sheet_metal", "coating"})
    assert result.part_cost == 0
    assert result.total == 140000


def test_part_price_not_attributed_when_work_row_exists_but_unmapped(caplog):
    # WORK 행이 존재해도 매핑에 실패해 methods가 비어 있으면 PART_PRICE를 귀속하지 않는다.
    rows = [
        _row(1, "PART_A", "WORK", work_code="TRULY_UNKNOWN", part_cost=99999),
        _row(1, "PART_A", "PART_PRICE", part_cost=200000),
    ]

    with caplog.at_level(logging.WARNING, logger="app.services.estimate_service"):
        result = _aggregate_case(1, "PART_A", rows)

    assert result is None  # 매핑된 비용이 없어 total이 0
    assert any("no mapped WORK repairMethod" in r.getMessage() for r in caplog.records)


def test_referenced_case_ids_as_strings_still_match_int_case_ids():
    rows = [
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=["1", "2", "3"])  # 문자열로 옴

    result = service.calculate(_request([part]))

    assert result["estimable"] is True
    item = result["items"][0]
    assert item["refCaseCount"] == 3
    assert set(item["referencedCaseIds"]) == {1, 2, 3}


def test_totals_sum_each_items_p25_median_p75_across_multiple_parts():
    rows = [
        # part A: REPAIR, totals 100000/110000/120000
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
        # part B: SHEET_METAL, totals 50000/60000/70000
        _row(4, "PART_B", "WORK", work_code="SHEET_METAL", part_cost=30000, labor_cost=20000),
        _row(5, "PART_B", "WORK", work_code="SHEET_METAL", part_cost=40000, labor_cost=20000),
        _row(6, "PART_B", "WORK", work_code="SHEET_METAL", part_cost=50000, labor_cost=20000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part_a = _part(part_code="PART_A", damage_type="Scratched", confidence=0.95,
                    referenced_case_ids=[1, 2, 3])
    part_b = _part(part_code="PART_B", damage_type="Crushed", confidence=0.5,
                    referenced_case_ids=[4, 5, 6])

    result = service.calculate(_request([part_a, part_b]))

    assert len(result["items"]) == 2
    item_a, item_b = result["items"]
    expected_min = item_a["costDistribution"]["p25"] + item_b["costDistribution"]["p25"]
    expected_median = item_a["costDistribution"]["median"] + item_b["costDistribution"]["median"]
    expected_max = item_a["costDistribution"]["p75"] + item_b["costDistribution"]["p75"]
    assert result["totals"] == {"min": expected_min, "median": expected_median, "max": expected_max}
    assert result["refCaseTotal"] == 6
    # part_b의 confidence(0.5)가 낮아 전체 등급은 두 항목 중 더 낮은 쪽을 따른다.
    assert result["confidenceGrade"] == min(
        item_a["confidenceGrade"], item_b["confidenceGrade"],
        key=lambda grade: {"LOW": 0, "MEDIUM": 1, "HIGH": 2}[grade],
    )


def test_partial_success_keeps_resolved_items_and_lists_unresolved_parts():
    rows = [
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    resolvable = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[1, 2, 3])
    unresolvable = _part(part_code="PART_B", damage_type="Crushed", referenced_case_ids=[99])

    result = service.calculate(_request([resolvable, unresolvable]))

    assert result["estimable"] is True
    assert len(result["items"]) == 1
    assert result["items"][0]["partCode"] == "PART_A"
    assert result["unresolvedParts"] == [
        {"partCode": "PART_B", "damageType": "Crushed", "reason": "INSUFFICIENT_CASES"},
    ]


@pytest.mark.parametrize("assessment_status", ["NOT_APPROVED"])
def test_not_approved_rows_are_excluded(assessment_status):
    rows = [
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000,
             assessment_status=assessment_status),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    # case 1이 불인정으로 빠지고 2·3만 남는다. 최소 사례 수가 바뀌어도
    # 이 테스트가 확인할 것은 "불인정 행이 빠졌는가" 하나다.
    assert result["estimable"] is True
    assert result["refCaseTotal"] == 2
    assert result["items"][0]["referencedCaseIds"] == [2, 3]

def test_reference_price_and_ancillary_rows_are_excluded():
    rows = [
        _row(1, "PART_A", "REFERENCE_PRICE", part_cost=999999),
        _row(1, "PART_A", "ANCILLARY", part_cost=999999),
        _row(1, "PART_A", "WORK", work_code="REPAIR", part_cost=60000, labor_cost=40000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", part_cost=70000, labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", part_cost=80000, labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    assert item["itemTotal"] == round(statistics.median([100000, 110000, 120000]))


def test_source_aware_columns_read_post_adjustment_for_sc():
    rows = [
        _row(1, "PART_A", "WORK", work_code="REPAIR", source="AIHUB_SC",
             part_cost=999999, labor_cost=999999,  # 손해사정전 값 — 쓰이면 안 된다
             post_adjustment_part_cost=60000, post_adjustment_labor_cost=40000),
        _row(2, "PART_A", "WORK", work_code="REPAIR", source="AIHUB_SC",
             post_adjustment_part_cost=70000, post_adjustment_labor_cost=40000),
        _row(3, "PART_A", "WORK", work_code="REPAIR", source="AIHUB_SC",
             post_adjustment_part_cost=80000, post_adjustment_labor_cost=40000),
    ]
    repository = InMemoryCostCaseRepository(rows)
    service = EstimateService(repository)
    part = _part(part_code="PART_A", referenced_case_ids=[1, 2, 3])

    result = service.calculate(_request([part]))

    item = result["items"][0]
    assert item["itemTotal"] == round(statistics.median([100000, 110000, 120000]))


def test_part_price_only_reference_is_separate_from_full_repair():
    rows = [
        _row(1, "PART_A", "PART_PRICE", part_cost=10000),
        _row(2, "PART_A", "PART_PRICE", part_cost=20000),
        _row(3, "PART_A", "PART_PRICE", part_cost=30000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows), enable_part_price_reference=True)
    part = _part(part_code="PART_A", referenced_case_ids=[],)
    part["partPriceCandidateCases"] = [
        {"caseId": 1, "similarity": 0.9, "corpusPartMatched": True, "corpusPartCode": "PART_A"},
        {"caseId": 2, "similarity": 0.8, "corpusPartMatched": True, "corpusPartCode": "PART_A"},
        {"caseId": 3, "similarity": 0.7, "corpusPartMatched": True, "corpusPartCode": "PART_A"},
    ]
    result = service.calculate(_request([part]))
    assert result["estimable"] is False
    assert result["totals"] is None
    assert result["partPriceReferences"][0]["referenceKind"] == "PART_PRICE_ONLY"
    assert result["partPriceReferences"][0]["costDistribution"] == {
        "p25": 15000, "median": 20000, "p75": 25000,
    }


def test_part_price_reference_requires_three_valid_cases_and_excludes_not_approved():
    rows = [
        _row(1, "PART_A", "PART_PRICE", part_cost=10000),
        _row(2, "PART_A", "PART_PRICE", part_cost=20000, assessment_status="NOT_APPROVED"),
        _row(3, "PART_A", "PART_PRICE", part_cost=0),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows), enable_part_price_reference=True)
    part = _part(part_code="PART_A")
    part["partPriceCandidateCases"] = [
        {"caseId": i, "similarity": 1 - i / 10, "corpusPartMatched": True, "corpusPartCode": "PART_A"}
        for i in (1, 2, 3)
    ]
    result = service.calculate(_request([part]))
    assert result["partPriceReferences"] == []


def test_part_price_reference_uses_sc_post_adjustment_part_cost():
    rows = [
        _row(i, "PART_A", "PART_PRICE", source="AIHUB_SC", part_cost=999999,
             post_adjustment_part_cost=value)
        for i, value in enumerate((10000, 20000, 30000), 1)
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows), enable_part_price_reference=True)
    part = _part(part_code="PART_A")
    part["partPriceCandidateCases"] = [
        {"caseId": i, "similarity": 1 - i / 10, "corpusPartMatched": True, "corpusPartCode": "PART_A"}
        for i in (1, 2, 3)
    ]
    result = service.calculate(_request([part]))
    assert result["partPriceReferences"][0]["costDistribution"]["median"] == 20000


def test_yolo_estimate_reference_selects_only_full_repair_cases():
    rows = []
    for case_id in (1, 2, 3):
        rows.append(_row(case_id, "PART_A", "WORK", work_code="REPAIR",
                         part_cost=50000 + case_id * 1000, labor_cost=30000))
    rows.append(_row(4, "PART_A", "PART_PRICE", part_cost=99999))
    service = EstimateService(InMemoryCostCaseRepository(rows), enable_yolo_estimate_references=True)
    candidates = [
        {"caseId": case_id, "similarity": 1 - case_id / 10,
         "corpusPartMatched": True, "corpusPartCode": "PART_A"}
        for case_id in (1, 2, 3, 4)
    ]
    selected = service.select_full_repair_reference_candidates(candidates, "PART_A")
    assert [row["caseId"] for row in selected] == [1, 2, 3]
    assert all(row["includedInDistribution"] for row in selected)


# ── 같은 부위 병합 (jobId=75: 앞범퍼가 두 줄로 나와 총액이 두 배) ──────────────


def _coating_rows(part_code, case_ids, *, amount):
    return [
        _row(case_id, part_code, "WORK", work_code="COATING",
             paint_material_cost=amount // 2, labor_cost=amount - amount // 2)
        for case_id in case_ids
    ]


def _exchange_rows(part_code, case_ids, *, amount):
    return [
        _row(case_id, part_code, "WORK", work_code="EXCHANGE",
             part_cost=amount - 100000, labor_cost=100000)
        for case_id in case_ids
    ]


def test_two_damages_on_one_part_become_one_item_and_are_not_double_counted():
    # 운영 jobId=75 재현: 앞범퍼 한 곳에 긁힘·깨짐이 잡혀 items에 FRONT_BUMPER가
    # 두 번 들어갔고 총액이 범퍼를 두 번 교체한 값이 됐다.
    rows = [
        *_coating_rows("FRONT_BUMPER", [1, 2, 3], amount=120000),
        *_exchange_rows("FRONT_BUMPER", [11, 12, 13], amount=550000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows))
    scratched = _part(part_code="FRONT_BUMPER", damage_type="Scratched",
                      referenced_case_ids=[1, 2, 3], detection_ids=["75:damage:d1"])
    breakage = _part(part_code="FRONT_BUMPER", damage_type="Breakage",
                     referenced_case_ids=[11, 12, 13], detection_ids=["75:damage:d2"])

    result = service.calculate(_request([scratched, breakage]))

    assert len(result["items"]) == 1
    item = result["items"][0]
    # 교환이 긁힘까지 함께 해결하므로 무거운 방식이 대표다.
    assert item["repairMethod"] == "exchange"
    assert item["damageType"] == "Breakage"
    assert item["costDistribution"]["median"] == 550000
    # 병합 전에는 120000 + 550000 = 670000 이 나갔다.
    assert result["totals"]["median"] == 550000
    assert result["refCaseTotal"] == 3
    # 사진의 박스 두 개는 모두 대표 항목이 가리킨다 — 입력 순서를 지킨다.
    assert item["detectionIds"] == ["75:damage:d1", "75:damage:d2"]
    assert item["mergedDamageTypes"] == ["Scratched", "Breakage"]


def test_representative_prefers_the_larger_amount_over_the_larger_sample():
    # repairMethod는 참조 사례 method의 합집합이라 양쪽이 exchange로 동률 나기 쉽다.
    # 그때 사례 수로 가르면 흔한 쪽(싼 쪽)이 대표가 되어 견적이 낮아진다.
    rows = [
        *_exchange_rows("PART_A", [1, 2, 3, 4, 5], amount=200000),
        *_exchange_rows("PART_A", [11, 12], amount=600000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows))
    common = _part(part_code="PART_A", damage_type="Scratched",
                   referenced_case_ids=[1, 2, 3, 4, 5])
    severe = _part(part_code="PART_A", damage_type="Breakage", referenced_case_ids=[11, 12])

    result = service.calculate(_request([common, severe]))

    assert len(result["items"]) == 1
    item = result["items"][0]
    assert item["repairMethod"] == "exchange"
    assert item["costDistribution"]["median"] == 600000
    assert item["refCaseCount"] == 2  # 사례가 적어도 비싼 쪽이 대표다


def test_merged_item_keeps_the_lowest_confidence_grade_of_the_group():
    # 대표 자체는 표본 20건이라 HIGH지만, 합칠지 말지를 표본 2건짜리 근거로
    # 판단했으므로 그 얇음이 등급에 남아야 한다.
    rows = [
        *_exchange_rows("PART_A", list(range(1, 21)), amount=500000),
        *_coating_rows("PART_A", [31, 32], amount=100000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows))
    severe = _part(part_code="PART_A", damage_type="Breakage",
                   referenced_case_ids=list(range(1, 21)))
    light = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[31, 32])

    result = service.calculate(_request([severe, light]))

    item = result["items"][0]
    assert item["refCaseCount"] == 20
    assert item["confidenceGrade"] == "LOW"
    assert result["confidenceGrade"] == "LOW"


def test_unresolved_is_dropped_when_another_damage_on_the_same_part_resolved():
    # "총액에 있다"와 "총액에서 뺐다"가 같은 부위에 함께 보이면 안 된다.
    rows = _exchange_rows("PART_A", [1, 2, 3], amount=500000)
    service = EstimateService(InMemoryCostCaseRepository(rows))
    resolvable = _part(part_code="PART_A", damage_type="Breakage", referenced_case_ids=[1, 2, 3])
    starved = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[98, 99])

    result = service.calculate(_request([resolvable, starved]))

    assert len(result["items"]) == 1
    assert result["unresolvedParts"] == []


def test_unresolved_keeps_one_entry_per_part_code_when_nothing_resolved():
    service = EstimateService(InMemoryCostCaseRepository([]))
    first = _part(part_code="PART_A", damage_type="Breakage", referenced_case_ids=[1, 2])
    second = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[3, 4])

    result = service.calculate(_request([first, second]))

    assert result["estimable"] is False
    assert result["unresolvedParts"] == [
        {"partCode": "PART_A", "damageType": "Breakage", "reason": "INSUFFICIENT_CASES"},
    ]


def test_part_price_reference_is_emitted_once_per_part_code_from_the_union():
    # 부품비는 손상 유형과 무관하다 — 두 엔트리의 후보 풀은 같은 모집단이라
    # 한쪽을 버리지 않고 합집합으로 한 번만 계산한다.
    rows = [
        _row(1, "PART_A", "PART_PRICE", part_cost=10000),
        _row(2, "PART_A", "PART_PRICE", part_cost=20000),
        _row(3, "PART_A", "PART_PRICE", part_cost=30000),
        _row(4, "PART_A", "PART_PRICE", part_cost=40000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows), enable_part_price_reference=True)

    def _candidate(case_id, similarity):
        return {"caseId": case_id, "similarity": similarity,
                "corpusPartMatched": True, "corpusPartCode": "PART_A"}

    scratched = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[])
    scratched["partPriceCandidateCases"] = [_candidate(1, 0.9), _candidate(2, 0.6)]
    breakage = _part(part_code="PART_A", damage_type="Breakage", referenced_case_ids=[])
    breakage["partPriceCandidateCases"] = [_candidate(3, 0.8), _candidate(4, 0.7)]

    references = service.calculate(_request([scratched, breakage]))["partPriceReferences"]

    assert len(references) == 1
    reference = references[0]
    assert reference["partCode"] == "PART_A"
    assert reference["refCaseCount"] == 4
    assert reference["referencedCaseIds"] == [1, 3, 4, 2]  # 유사도 내림차순
    amounts = [10000, 30000, 40000, 20000]
    q1, _median, q3 = statistics.quantiles(amounts, n=4, method="inclusive")
    assert reference["costDistribution"] == {
        "p25": round(q1), "median": round(statistics.median(amounts)), "p75": round(q3),
    }


def test_single_damage_part_does_not_carry_merged_damage_types():
    # 백엔드는 이 필드가 있는 것 자체를 "합쳤다" 로 읽는다. 합치지 않았으면 보내지 않는다.
    rows = _exchange_rows("PART_A", [1, 2, 3], amount=500000)
    service = EstimateService(InMemoryCostCaseRepository(rows))
    part = _part(part_code="PART_A", damage_type="Crushed", referenced_case_ids=[1, 2, 3])

    item = service.calculate(_request([part]))["items"][0]

    assert "mergedDamageTypes" not in item


def test_merged_reason_flags_a_method_the_representative_does_not_price(caplog):
    # 대표(교환) 사례에 도장 행이 하나도 없으면 도장은 값에서 통째로 빠진다.
    rows = [
        *_exchange_rows("PART_A", [1, 2, 3], amount=500000),
        *_coating_rows("PART_A", [11, 12], amount=100000),
    ]
    service = EstimateService(InMemoryCostCaseRepository(rows))
    severe = _part(part_code="PART_A", damage_type="Breakage", referenced_case_ids=[1, 2, 3])
    light = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[11, 12])

    with caplog.at_level(logging.WARNING):
        item = service.calculate(_request([severe, light]))["items"][0]

    reason = item["repairMethodReason"]
    assert reason["candidates"] == ["exchange"]
    assert reason["mergedCandidates"] == ["exchange", "coating"]
    assert reason["uncoveredMethods"] == ["coating"]
    assert "uncovered=['coating']" in caplog.text


def test_merged_reason_is_clean_when_the_representatives_cases_already_include_coating():
    # 실제 판금 사례는 도장까지 한 줄로 들어온다(부품명 매핑이 "후드판금" 을 같은 코드로
    # 접는다). 그러면 도장은 이미 대표의 총액 안에 있으므로 빠진 작업이 없다.
    rows = []
    for case_id in (1, 2, 3):
        rows.append(_row(case_id, "PART_A", "WORK", work_code="SHEET_METAL",
                         part_cost=100000, labor_cost=80000))
        rows.append(_row(case_id, "PART_A", "WORK", work_code="COATING",
                         paint_material_cost=60000, labor_cost=200000))
    rows.extend(_coating_rows("PART_A", [11, 12], amount=100000))
    service = EstimateService(InMemoryCostCaseRepository(rows))
    dented = _part(part_code="PART_A", damage_type="Crushed", referenced_case_ids=[1, 2, 3])
    light = _part(part_code="PART_A", damage_type="Scratched", referenced_case_ids=[11, 12])

    reason = service.calculate(_request([dented, light]))["items"][0]["repairMethodReason"]

    assert reason["candidates"] == ["sheet_metal", "coating"]
    assert reason["mergedCandidates"] == ["sheet_metal", "coating"]
    assert reason["uncoveredMethods"] == []
