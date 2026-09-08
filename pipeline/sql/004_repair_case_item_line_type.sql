-- A307 검색 표본 적재 계약 004 — 견적 행 종류 재설계 (S15P21A307-414)
-- 기존 로컬 DB를 line_type 4종·손해사정 상태 컬럼·도장 재료비 분리 구조로 올린다.
--
-- 002가 둔 line_type 2종(WORK / PART_PRICE)은 출처를 모르면 해석되지 않았다.
-- AS의 PART_PRICE는 정산에 포함되지 않는 참고 정가였고 SC의 PART_PRICE는
-- 정산에 포함되는 부품 명세였다. 아래 4종은 출처와 무관하게 해석된다.
--
--   WORK            공임이 붙는 수리 작업. 정산 포함
--   PART_PRICE      손해사정에 반영된 부품 명세. 정산 포함
--   REFERENCE_PRICE AS 견적서의 `신품가` 참고 정가. 정산 제외, item_total 미합산
--   ANCILLARY       견인·구난 등 수리가 아닌 부대 비용. 정산 포함
--
-- `탁송`은 `작업` 필드 값이 아니다. 원천 1,716,713행에 `작업=탁송`은 0건이며
-- `탁송비`는 `작업항목 및 부품명`으로 나타나고 그 행의 `작업`은 견인 또는 구난이다.
-- 따라서 ANCILLARY는 TOWING·RESCUE 두 코드로만 만들어진다.

BEGIN;

-- ── 손해사정 상태 ───────────────────────────────────────────────────────────
-- `불인정`은 작업 유형이 아니라 손해사정 결과다. line_type에 넣으면 그 행의
-- 부품명과 손해사정전 공임을 잃으므로 별도 상태 컬럼으로 보존한다.
--
-- 주의 — 원천이 `작업` 필드를 `불인정`으로 **덮어써서** 그 행의 원래 작업 유형
-- (판금·도장 등)은 복구할 수 없다. work_type·work_code가 NULL인 것은 적재 누락이
-- 아니라 원천에 정보가 없다는 뜻이다. 원문 `불인정`은 assessment_status로 복구된다.
--
--   NULL          손해사정 개념이 없는 출처(AIHUB_AS) 또는 미지정
--   APPROVED      손해사정을 거쳐 인정된 행(AIHUB_SC)
--   NOT_APPROVED  원천 `작업`이 `불인정`인 행. 손해사정후 금액이 없다
ALTER TABLE repair_case_item
    ADD COLUMN IF NOT EXISTS assessment_status VARCHAR(20);

-- ── 도장 재료비 분리 ────────────────────────────────────────────────────────
-- 원천 `부품가격`은 `작업=도장`인 행에서 부품비가 아니라 도장 재료비다. 정산상
-- 공임 측 `재료대`에 들어가므로 부품비로 합산하면 부품 비용 통계가 틀어진다.
-- 무작위 표본 3,000건씩으로 확인: SUM(도장 행 부품가격) = 정산.공임.재료대가
-- AS 3000/3000(100.00%), SC는 손해사정후 기준 2997/3000(99.90%) 일치했다.
-- `작업=도장`만 옮기는 것이 가장 정확하다 — 도장+수리+판금으로 넓히면 AS 81.90%,
-- 전체 행으로 넓히면 AS 65.57%로 떨어진다.
ALTER TABLE repair_case_item
    ADD COLUMN IF NOT EXISTS paint_material_cost INTEGER;

-- ── 제약 해제 ───────────────────────────────────────────────────────────────
ALTER TABLE repair_case_item
    DROP CONSTRAINT IF EXISTS ck_rci_line_type,
    DROP CONSTRAINT IF EXISTS ck_rci_work,
    DROP CONSTRAINT IF EXISTS ck_rci_part_code,
    DROP CONSTRAINT IF EXISTS ck_rci_work_code,
    DROP CONSTRAINT IF EXISTS ck_rci_assessment;

-- ANCILLARY 행에는 part_code가 없다. 원문 부품명이 `견인비`·`구난료`·`탁송비`처럼
-- 부품이 아니어서 표준 부품 코드로 매핑되지 않는다(표본 매핑률 견인 10.6%,
-- 구난 2.8%, 견인비·구난비 0.0%). 검색 대상 행 종류에는 NOT NULL을 유지한다.
ALTER TABLE repair_case_item
    ALTER COLUMN part_code DROP NOT NULL;

-- ── 기존 행 이관 ────────────────────────────────────────────────────────────
-- 제약을 새로 걸기 전에 옮긴다. 002의 ck_rci_line_type은 2종만 받으므로 이 순서를
-- 뒤집으면 REFERENCE_PRICE UPDATE가 제약 위반으로 실패한다.

-- 도장 행의 part_cost는 부품비가 아니라 재료비였다.
UPDATE repair_case_item
SET paint_material_cost = part_cost,
    part_cost           = NULL
WHERE work_code = 'COATING'
  AND part_cost IS NOT NULL
  AND paint_material_cost IS NULL;

-- 002의 PART_PRICE 중 AS 참고 정가였던 행을 REFERENCE_PRICE로 바꾼다.
UPDATE repair_case_item AS i
SET line_type = 'REFERENCE_PRICE'
FROM repair_case AS c
WHERE i.case_id = c.case_id
  AND i.line_type = 'PART_PRICE'
  AND c.source = 'AIHUB_AS';

-- SC는 손해사정을 거친 출처다. 기존 행에 상태를 채운다. AS는 NULL로 남긴다.
UPDATE repair_case_item AS i
SET assessment_status = 'APPROVED'
FROM repair_case AS c
WHERE i.case_id = c.case_id
  AND c.source = 'AIHUB_SC'
  AND i.assessment_status IS NULL;

-- ── 제약 재작성 ─────────────────────────────────────────────────────────────
ALTER TABLE repair_case_item
    ADD CONSTRAINT ck_rci_line_type CHECK (
        line_type IN ('WORK', 'PART_PRICE', 'REFERENCE_PRICE', 'ANCILLARY')
    ),
    ADD CONSTRAINT ck_rci_assessment CHECK (
        assessment_status IS NULL OR assessment_status IN ('APPROVED', 'NOT_APPROVED')
    ),
    ADD CONSTRAINT ck_rci_part_code CHECK (
        line_type = 'ANCILLARY' OR part_code IS NOT NULL
    ),
    -- 작업 어휘 자체는 standardization.ESTIMATE_WORKS가 단일 기준이다. 여기서는
    -- 코드 집합만 검사하고 (work_type, work_code) 쌍은 열거하지 않는다. work_type은
    -- 별칭이 흔들리는 원문(`견인비`→TOWING)이라 쌍으로 묶으면 별칭마다 migration이
    -- 필요해진다. NOT_APPROVED는 assessment_status로 가므로 여기 없다.
    ADD CONSTRAINT ck_rci_work_code CHECK (
        work_code IS NULL OR work_code IN (
            'COATING', 'REPAIR', 'SHEET_METAL', 'EXCHANGE', 'REMOVE_INSTALL', 'OVERHAUL',
            'OVERHAUL_HALF', 'OVERHAUL_THIRD', 'OVERHAUL_QUARTER', 'ADJUSTMENT',
            'TOWING', 'RESCUE'
        )
    ),
    ADD CONSTRAINT ck_rci_work CHECK (
        (line_type IN ('PART_PRICE', 'REFERENCE_PRICE')
         AND work_type IS NULL AND work_code IS NULL)
        OR
        (line_type = 'ANCILLARY' AND work_code IN ('TOWING', 'RESCUE'))
        OR
        -- 불인정 행은 원래 작업 유형이 복구 불가능하므로 work_code 없이 허용한다.
        (line_type = 'WORK'
         AND (work_code IS NOT NULL OR assessment_status = 'NOT_APPROVED'))
    );

COMMIT;
