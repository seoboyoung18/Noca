"""Tunable values for MVP cost estimation that are not yet finalized by the cost owner.

Kept in one place per `Docs/AI/견적 산출 입출력 형식_update.md` §4 so they can be
revisited without touching calculation logic.
"""
from __future__ import annotations

# 현재 corpus에서 비용 정책을 통과한 두 사례로도 견적을 산정한다.
# confidenceGrade는 표본 수에 따라 LOW로 남겨, 낮은 표본 신뢰도를 결과에 드러낸다.
MIN_CASE_COUNT = 2

# confidenceGrade — 탐지 신뢰도 등급 임계값.
CONFIDENCE_HIGH_THRESHOLD = 0.85
CONFIDENCE_MEDIUM_THRESHOLD = 0.70

# confidenceGrade — 참조 사례 수(표본 크기) 등급 임계값.
SAMPLE_SIZE_HIGH_THRESHOLD = 20
SAMPLE_SIZE_MEDIUM_THRESHOLD = 10

# A307_COST_POLICY.md §2-6: SC(손해사정후)는 AS와 다른 컬럼(post_adjustment_*)을 읽는다.
# False로 두면 출처를 무시하고 모든 행에서 part_cost/labor_cost를 그대로 읽는다
# (update.md §4.1 예시 쿼리와 동일한 단순 동작 — 정책 확정 전 비교용).
USE_SOURCE_AWARE_COST_COLUMNS = True

# A307_COST_POLICY.md §4-2(탈착)·§4-4(조정/견인/구난) — 이미 확정된 정책 제외
# 대상이라 "미확정 값"은 아니지만, work_code 목록을 한곳에서 관리하기 위해 둔다.
# 원천 데이터에 탈착만 46만 건이라 매핑 실패 취급하면 안 된다(estimate_service의
# _aggregate_case가 이 목록과 "진짜 미매핑 코드"를 로그 레벨로 구분한다).
EXCLUDED_WORK_CODES = frozenset({"REMOVE_INSTALL", "ADJUSTMENT", "TOWING", "RESCUE"})
