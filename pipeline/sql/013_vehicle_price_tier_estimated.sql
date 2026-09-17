-- ============================================================
-- 013 — 차량 마스터 중 코퍼스 미측정 21종의 price_tier 보정
--
-- 012 는 실측이다. 견적서 코퍼스에 사례가 충분해 바스켓 가격지수를 직접
-- 계산한 30종만 담았다. 이 파일은 나머지 21종을 채운다 — 값의 출처가
-- 다르므로 파일을 나눴다. 나중에 사례가 쌓이면 이 파일만 다시 만들면 된다.
--
-- 출처는 두 가지이고, 각 UPDATE 옆에 표시했다.
--   [저표본]  코퍼스에 사례는 있으나 012 임계값(차량 15종 이상 공유 바스켓
--             5개 이상)에 못 미쳐 제외됐던 모델. 임계값을 10종/4바스켓으로
--             낮춰 다시 측정했다. 측정값이지 추정값이 아니다.
--   [추정]    코퍼스에 사례가 아예 없는 모델. 같은 (제조사, 차급) 실측
--             중앙값을, 표본이 3종 미만이면 같은 차급 전체 중앙값을 썼다.
--
-- ── 한계 두 가지. 읽고 넘어갈 것 ─────────────────────────────
--
-- 1) 수입차 지수는 사실상 '공임 지수'다.
--    가격지수는 같은 부품·같은 작업끼리만 비교해서 만든다. 그런데 수입차는
--    부품명 체계가 국산과 달라 부품가격 바스켓이 거의 겹치지 않는다
--    (BMW 3시리즈·5시리즈·아우디 A6 는 공유 부품가격 바스켓 0개, 벤츠
--    C/E클래스·폭스바겐 티구안은 1개). 결국 공임 쪽만 남아 지수가 1.00
--    근처로 수렴한다. "수입차 수리비가 국산과 비슷하다"는 뜻이 아니라
--    "이 방법으로는 수입차의 부품값을 측정할 수 없다"는 뜻이다.
--    수입차 9종이 모두 P2~P3 로 나온 것을 실측 결론으로 읽으면 안 된다.
--
-- 2) 이 파일의 정보량은 낮다. 21종 중 14종이 P3 다.
--    추정의 바탕이 차급 중앙값이고 소·중·대 중앙값이 서로 6% 안에 있으니
--    당연한 결과다. price_tier 로 얻는 실익은 P1(경차)·P4 분리에 있고,
--    나머지는 model_id 백필로 MODEL 단계가 살아나야 해결된다.
--    → backfill_vehicle_model.py, Docs/Erd/A307_VEHICLE_AXIS.md
--
-- 선행: 012_vehicle_price_tier.sql (컬럼·제약·인덱스 생성)
-- ============================================================

-- ── P1 저가  (< 0.94) ─────────────────────────────
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '캐스퍼';          -- 현대 캐스퍼 · 0.833  [추정] CityCar 중앙값(n=3)
UPDATE vehicle_model SET price_tier = 'P1' WHERE model_name = '트레일블레이저';  -- 쉐보레 트레일블레이저 · 0.908  [저표본] 임계값 완화 재측정

-- ── P2 중저  (0.94 ~ 1.00) ─────────────────────────────
UPDATE vehicle_model SET price_tier = 'P2' WHERE model_name = '3시리즈';         -- BMW 3시리즈 · 0.963  [저표본] 임계값 완화 재측정

-- ── P3 중고  (1.00 ~ 1.07) ─────────────────────────────
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '5시리즈';         -- BMW 5시리즈 · 1.046  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'A6';              -- 아우디 A6 · 1.006  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'CR-V';            -- 혼다 CR-V · 1.006  [추정] Compact 중앙값(n=6)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'C클래스';         -- 벤츠 C클래스 · 1.027  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'E클래스';         -- 벤츠 E클래스 · 1.031  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'G90';             -- 제네시스 G90 · 1.069  [추정] Full-size 중앙값(n=9)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'GLC';             -- 벤츠 GLC · 1.018  [추정] Mid-size 중앙값(n=12)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'GV80';            -- 제네시스 GV80 · 1.007  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'K8';              -- 기아 K8 · 1.067  [추정] 기아 Full-size 중앙값(n=4)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'X3';              -- BMW X3 · 1.000  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = 'XM3';             -- 르노코리아 XM3 · 1.018  [추정] Mid-size 중앙값(n=12)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '봉고3';           -- 기아 봉고3 · 1.067  [추정] 기아 Full-size 중앙값(n=4)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '스타리아';        -- 현대 스타리아 · 1.055  [추정] 현대 Full-size 중앙값(n=3)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '캠리';            -- 도요타 캠리 · 1.069  [추정] Full-size 중앙값(n=9)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '토레스';          -- KG모빌리티 토레스 · 1.006  [추정] Compact 중앙값(n=6)
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '티구안';          -- 폭스바겐 티구안 · 1.039  [저표본] 임계값 완화 재측정
UPDATE vehicle_model SET price_tier = 'P3' WHERE model_name = '포터2';           -- 현대 포터2 · 1.055  [추정] 현대 Full-size 중앙값(n=3)

-- ── P4 고가  (>= 1.07) ─────────────────────────────
UPDATE vehicle_model SET price_tier = 'P4' WHERE model_name = 'GV70';            -- 제네시스 GV70 · 1.187  [저표본] 임계값 완화 재측정

-- ── 확인 ─────────────────────────────────────────────────
-- 마스터 51종이 모두 채워졌는지. 0 이 나와야 한다.
--   SELECT count(*) FROM vehicle_model WHERE price_tier IS NULL;
--
-- 분포. P3 로 쏠리는 것이 정상이다(위 한계 2 참고).
--   SELECT price_tier, count(*) FROM vehicle_model GROUP BY 1 ORDER BY 1;
