-- ============================================================
-- 012 — 차량 가격대(price_tier) 축 추가
--
-- 배경: car_class(경·소·중·대)는 배기량·전장 기준이라 수리비를 설명하지 못한다.
--       실측 결과 소·중·대의 가격지수 중앙값이 1.01 / 0.99 / 1.05 로 6% 이내이고
--       범위가 완전히 겹친다. CityCar 만 분리된다.
--       근거: Docs/Erd/A307_VEHICLE_AXIS.md · Docs/Erd/차량명칭_표준화_매핑표.xlsx
--
-- 가격지수: 같은 부품·같은 작업끼리만 비교한 차량별 상대 수리비. 1.00 = 전체 중앙값.
--           견적서 약 4.2만 건 표본, 105종 산출(전체 건수의 96.0%).
--
-- 구간(105종 사분위)
--   P1 저가  < 0.94      P2 중저  0.94 ~ 1.00
--   P3 중고  1.00 ~ 1.07 P4 고가  >= 1.07
-- ============================================================

ALTER TABLE vehicle_model
    ADD COLUMN IF NOT EXISTS price_tier VARCHAR(2);

ALTER TABLE repair_case
    ADD COLUMN IF NOT EXISTS price_tier VARCHAR(2);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_vm_tier') THEN
        ALTER TABLE vehicle_model
            ADD CONSTRAINT ck_vm_tier CHECK (price_tier IN ('P1','P2','P3','P4'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_rc_tier') THEN
        ALTER TABLE repair_case
            ADD CONSTRAINT ck_rc_tier CHECK (price_tier IN ('P1','P2','P3','P4'));
    END IF;
END $$;

-- 검색 1차 필터. model_id 와 같은 부분 인덱스 전략을 쓴다
CREATE INDEX IF NOT EXISTS ix_rc_tier
    ON repair_case (price_tier) WHERE price_tier IS NOT NULL;

-- ── 마스터 가격대 부여 ──────────────────────────────────
-- 코퍼스에서 지수가 산출된 30종. 나머지 21종은 표본이 없어 비워 둔다.
-- 신규 등록 차량의 기본값은 같은 제조사·같은 차급의 지수 중앙값이 속한 구간으로 정한다.
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '모닝';
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '베뉴';
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '스파크';
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '아반떼';
UPDATE vehicle_model SET price_tier = 'P2' WHERE model_name = '레이';
UPDATE vehicle_model SET price_tier = 'P2' WHERE model_name = '코나';
UPDATE vehicle_model SET price_tier = 'P2' WHERE model_name = '코란도';
UPDATE vehicle_model SET price_tier = 'P2' WHERE model_name = '티볼리';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'G70';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'K3';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'K5';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '그랜저';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '니로';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '모하비';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '셀토스';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '스포티지';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '싼타페';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '쏘나타';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '쏘렌토';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '투싼';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '트랙스';
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '팰리세이드';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'G80';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'K9';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'QM3';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'QM6';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'SM6';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = '렉스턴';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = '말리부';
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = '카니발';

-- 확인용
-- SELECT price_tier, count(*) FROM vehicle_model GROUP BY price_tier ORDER BY 1;
-- SELECT price_tier, count(*) FROM repair_case   GROUP BY price_tier ORDER BY 1;
