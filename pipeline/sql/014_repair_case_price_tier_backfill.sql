-- ============================================================
-- 014 — repair_case.price_tier 채우기
--
-- 012 가 컬럼과 인덱스를, 013 이 vehicle_model 쪽 값을 만들었다. 이 파일은
-- 그것을 사례 테이블로 내린다. 검색의 PRICE_TIER 단계가 보는 것은
-- repair_case.price_tier 이고, 이 UPDATE 전까지 그 컬럼은 전부 NULL 이다.
--
-- ── 실행 순서. 어기면 검색이 조용히 나빠진다 ─────────────────
--   1. 012, 013                         vehicle_model.price_tier 확정
--   2. backfill_vehicle_model.py        repair_case.model_id 채우기
--   3. 이 파일                            model_id 를 타고 tier 전파
--   4. AI 서버 배포 (PRICE_TIER 단계)
--
-- 3 을 건너뛰고 4 를 하면 MODEL·PRICE_TIER 두 단계가 모두 빈 결과를 내고
-- 전부 ALL 로 떨어진다. 차급으로 좁히던 지금보다 나쁜 상태다.
--
-- 비정규화인 것은 의도다. 조인 대신 컬럼을 두는 이유는 이 조건이 pgvector
-- 스캔의 WHERE 에 들어가야 해서다 — 조인으로 만들면 ix_rc_tier 부분 인덱스를
-- 쓰지 못한다. 대신 vehicle_model.price_tier 를 다시 측정하면 이 파일을
-- 다시 돌려야 한다.
-- ============================================================

UPDATE repair_case c
   SET price_tier = m.price_tier
  FROM vehicle_model m
 WHERE c.model_id = m.model_id
   AND m.price_tier IS NOT NULL
   AND c.price_tier IS DISTINCT FROM m.price_tier;

-- ── 확인 ─────────────────────────────────────────────────
-- model_id 는 있는데 tier 가 안 붙은 사례. 마스터에 tier 가 없는 모델뿐이어야 한다.
--   SELECT count(*) FROM repair_case WHERE model_id IS NOT NULL AND price_tier IS NULL;
--
-- 전체 커버리지. model_id 백필률의 상한을 넘을 수 없다.
--   SELECT count(*) FILTER (WHERE price_tier IS NOT NULL)::float / count(*)
--     FROM repair_case;
--
-- 분포.
--   SELECT price_tier, count(*) FROM repair_case GROUP BY 1 ORDER BY 1;
