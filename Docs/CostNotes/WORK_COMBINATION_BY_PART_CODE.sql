-- 부위 코드 기준 작업 조합 — 대표 항목 선정의 전제 검증 (S15P21A307-569)
--
-- 묻는 것: 같은 부위에 손상이 두 곳일 때 견적 항목을 하나로 합치면서 "가장 무거운
-- 쪽" 하나만 남기는데, 그 하나의 총액에 나머지 작업(특히 도장)이 이미 들어 있는가?
--
-- 들어 있다면 대표를 고르는 것이 맞다 — 더하면 도장을 두 번 낸다.
-- 안 들어 있다면 그만큼 과소 견적이고, 대표 선정 규칙을 다시 봐야 한다.
--
-- 왜 이 질문을 다시 하나:
--   WORK_COMBINATION_NOTES.md 는 원천 JSON 의 `작업항목 및 부품명` 문자열로 묶어
--   "도장 단독 99.55%" 를 얻었지만, 같은 노트가 그 수치를 스스로 과대추정이라고
--   적어 뒀다 — 도장 라인 469,560건 중 51.4%(241,323건)가 이름 자체에 교환·판금
--   같은 작업어를 달고 있어("후드판금", "후론트 범퍼교환") 기본 손상 라인과 따로
--   묶였기 때문이다. 그 노트가 후속 과제로 남긴 2차 정규화를 부품명 매핑 테이블이
--   이미 하고 있다(A307_part_name_mapping_seed.sql: 후드·후드판금·후드교환 →
--   BONNET, 작업어 포함 매핑 2,623행). 그래서 DB 의 part_code 기준으로 다시 센다.
--
-- 행 필터는 estimate_service._is_included_row 와 같다 — 정산에 들어가는 행만 본다.
-- 읽기 전용. 어떤 테이블도 바꾸지 않는다.

\set ON_ERROR_STOP on

CREATE TEMP VIEW included_work AS
SELECT rc.case_id,
       rci.part_code,
       rc.source,
       CASE rci.work_code
           WHEN 'COATING'          THEN 'coating'
           WHEN 'SHEET_METAL'      THEN 'sheet_metal'
           WHEN 'EXCHANGE'         THEN 'exchange'
           WHEN 'REPAIR'           THEN 'repair'
           WHEN 'OVERHAUL'         THEN 'repair'
           WHEN 'OVERHAUL_HALF'    THEN 'repair'
           WHEN 'OVERHAUL_THIRD'   THEN 'repair'
           WHEN 'OVERHAUL_QUARTER' THEN 'repair'
       END AS method,
       -- A307_COST_POLICY.md §2-6: SC 는 post_adjustment_* 를 읽는다.
       COALESCE(CASE WHEN rc.source = 'AIHUB_SC' THEN rci.post_adjustment_part_cost
                     ELSE rci.part_cost END, 0)
     + COALESCE(CASE WHEN rc.source = 'AIHUB_SC' THEN rci.post_adjustment_labor_cost
                     ELSE rci.labor_cost END, 0)
     + COALESCE(rci.paint_material_cost, 0) AS row_amount
FROM repair_case_item rci
JOIN repair_case rc ON rc.case_id = rci.case_id
WHERE rci.line_type = 'WORK'
  AND rci.part_code IS NOT NULL
  AND (rci.assessment_status IS NULL OR rci.assessment_status <> 'NOT_APPROVED')
  AND (rci.work_code IS NULL
       OR rci.work_code NOT IN ('REMOVE_INSTALL', 'ADJUSTMENT', 'TOWING', 'RESCUE'));

CREATE TEMP VIEW part_group AS
SELECT case_id,
       part_code,
       ARRAY_AGG(DISTINCT method ORDER BY method) AS methods,
       SUM(row_amount)                            AS group_total
FROM included_work
WHERE method IS NOT NULL
GROUP BY case_id, part_code;

-- 1) 작업 조합 빈도와 조합별 총액 중앙값.
--    WORK_COMBINATION_NOTES.md §1 과 같은 표를 part_code 기준으로 다시 낸 것이다.
--    두 표의 {도장, 판금} 건수 차이가 곧 매핑 테이블이 회수한 양이다.
SELECT methods,
       COUNT(*)                                                      AS group_count,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY group_total)) AS median_total
FROM part_group
GROUP BY methods
ORDER BY group_count DESC
LIMIT 30;

-- 2) 결정적인 수치 — 어떤 작업을 한 부위 그룹 중 도장이 함께 잡힌 비율.
--    판금 행이 높게 나오면 "판금 사례의 총액에 도장이 이미 들어 있다" 가 확인되고,
--    대표 하나를 고르는 현재 규칙이 맞다. 낮게 나오면 그 조합에서 도장이 통째로
--    빠지고 있다는 뜻이라 규칙을 다시 봐야 한다.
SELECT m.method,
       COUNT(*)                                                    AS groups_with_method,
       COUNT(*) FILTER (WHERE 'coating' = ANY (pg.methods))        AS also_coating,
       ROUND(100.0 * COUNT(*) FILTER (WHERE 'coating' = ANY (pg.methods))
             / NULLIF(COUNT(*), 0), 1)                             AS pct_with_coating
FROM part_group pg
CROSS JOIN LATERAL UNNEST(pg.methods) AS m(method)
WHERE m.method <> 'coating'
GROUP BY m.method
ORDER BY groups_with_method DESC;

-- 3) 도장이 빠진 쪽의 금액 감각 — 같은 method 를 도장과 함께 한 그룹과
--    단독으로 한 그룹의 총액 중앙값 차이. 이 차이가 2) 의 비율만큼 흔하게
--    누락된다면 그 금액이 과소 견적의 크기다.
SELECT m.method,
       'coating' = ANY (pg.methods)                                   AS with_coating,
       COUNT(*)                                                       AS group_count,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY pg.group_total)) AS median_total
FROM part_group pg
CROSS JOIN LATERAL UNNEST(pg.methods) AS m(method)
WHERE m.method <> 'coating'
GROUP BY m.method, with_coating
ORDER BY m.method, with_coating;
