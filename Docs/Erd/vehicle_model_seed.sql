-- ============================================================
-- 차량 마스터 시드 — vehicle_model
-- Jira Task 1/4 [BE] 차량 마스터(제조사·모델·차급 코드표) 데이터 구축
--
-- 출처: AI Hub「차량 수리비 산출을 위한 차량파손 이미지」데이터구축가이드라인 p.20
--       (= 자동차관리법 시행규칙 별표1 승용차 구분과 동일 체계)
--
-- 차급 정의 (가이드라인 원문)
--   경형 CityCar    전장3.6 전폭1.6 전고2.0(m) 이하, 배기량 1,000cc 이하   예) 기아 올뉴모닝
--   소형 Compact    전장4.7 전폭1.7 전고2.0(m) 이내, 엔진 1,600cc 미만     예) QM3
--   중형 Mid-size   소형 크기기준 초과, 엔진 1,600cc 이상 2,000cc 미만     예) 현대 아반떼MD(13)
--   대형 Full-size  소형 크기기준 초과, 엔진 2,000cc 이상                  예) 기아 오피러스(06)
--
-- ★ 적용 기준 : 세그먼트(차체 크기) 통념을 따르되 배기량을 참고한다.
--   '배기량 단독' 이 아니다. 2026-09-05 확정 (answer15.md 8-2 → (가)).
--
--   왜 배기량 단독이 아닌가 —
--   가이드라인 자신이 "중형 = 1,600cc 이상" 이라 써 놓고 중형 예시로 아반떼MD 를 든다.
--   그런데 아반떼는 1,598cc 다. 즉 배기량 숫자만으로는 가이드라인을 재현할 수 없다.
--   크기기준도 마찬가지다 — 소형 예시 QM3 의 전폭 1,780mm 는 소형 기준(1,700mm)을 넘는다.
--   두 기준 모두 엄격히 적용되지 않았으므로, 통념을 기준으로 삼고 배기량을 참고값으로 둔다.
--
--   ※ 경계 예외 7종 — 1,600cc 에 6cc 못 미치지만 중형(Mid-size)으로 둔다.
--     현대 아반떼·코나·투싼(1598) · 기아 K3·스포티지(1598) · 르노코리아 XM3(1598)
--     KG모빌리티 티볼리(1597)
--     근거: 가이드라인이 아반떼MD 를 중형 예시로 명시했고, 나머지 6종은 같은 세그먼트다.
--     바로 아래 구간의 베뉴·셀토스(1591)·니로(1580)는 소형(Compact)으로 남는다.
--     → 셀토스와 스포티지가 7cc 차이로 다른 차급이 된다. 의도된 결과다.
--
--   상세는 Docs/Claude/Answer/answer1_차량마스터.md · answer15.md 3장 참고
--
-- ★ 한국 통념의 '준중형'은 이 체계에 없다. 아반떼는 중형(Mid-size)이다.
--
-- ★ VAN·TRUCK 4종(스타리아·카니발·포터2·봉고3)은 학습 데이터셋에서 제외된 차종이다
--   (가이드라인 p.5 "버스, 화물차 등 제외"). 등록은 허용하되 분석 정확도가 낮을 수 있음을
--   화면에서 안내한다. 2026-09-05 확정 (answer15.md 8-3 → (c)).
--   판별: vehicle_type IN ('VAN','TRUCK')
--
-- 각 행 뒤 주석은 분류 근거가 된 대표 배기량이다.
-- 트림이 여러 개인 모델은 국내 판매 주력 트림 기준 — 검토 후 확정할 것.
-- ============================================================

INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type, car_class) VALUES

-- ── 경형 CityCar : 1,000cc 미만 ─────────────────────────
('현대',       '캐스퍼',        'SUV',   'CityCar'),    --  998cc
('기아',       '모닝',          'SEDAN', 'CityCar'),    --  998cc  가이드라인 예시
('기아',       '레이',          'SEDAN', 'CityCar'),    --  998cc
('쉐보레',     '스파크',        'SEDAN', 'CityCar'),    --  999cc

-- ── 소형 Compact : 1,000 ~ 1,600cc 미만 ─────────────────
('현대',       '베뉴',          'SUV',   'Compact'),    -- 1591cc
('기아',       '셀토스',        'SUV',   'Compact'),    -- 1591cc
('기아',       '니로',          'SUV',   'Compact'),    -- 1580cc
('르노코리아', 'QM3',           'SUV',   'Compact'),    -- 1461cc  가이드라인 예시
('KG모빌리티', '코란도',        'SUV',   'Compact'),    -- 1497cc
('KG모빌리티', '토레스',        'SUV',   'Compact'),    -- 1497cc
('쉐보레',     '트랙스',        'SUV',   'Compact'),    -- 1341cc
('쉐보레',     '트레일블레이저','SUV',   'Compact'),    -- 1341cc
('혼다',       'CR-V',          'SUV',   'Compact'),    -- 1498cc

-- ── 중형 Mid-size : 1,600 ~ 2,000cc 미만 ────────────────
('현대',       '아반떼',        'SEDAN', 'Mid-size'),   -- 1598cc  가이드라인 예시
('현대',       '코나',          'SUV',   'Mid-size'),   -- 1598cc
('현대',       '투싼',          'SUV',   'Mid-size'),   -- 1598cc
('현대',       '쏘나타',        'SEDAN', 'Mid-size'),   -- 1999cc
('기아',       'K3',            'SEDAN', 'Mid-size'),   -- 1598cc
('기아',       '스포티지',      'SUV',   'Mid-size'),   -- 1598cc
('기아',       'K5',            'SEDAN', 'Mid-size'),   -- 1999cc
('제네시스',   'G70',           'SEDAN', 'Mid-size'),   -- 1998cc
('르노코리아', 'XM3',           'SUV',   'Mid-size'),   -- 1598cc
('르노코리아', 'QM6',           'SUV',   'Mid-size'),   -- 1998cc
('르노코리아', 'SM6',           'SEDAN', 'Mid-size'),   -- 1998cc
('KG모빌리티', '티볼리',        'SUV',   'Mid-size'),   -- 1597cc
('쉐보레',     '말리부',        'SEDAN', 'Mid-size'),   -- 1998cc
('벤츠',       'C클래스',       'SEDAN', 'Mid-size'),   -- 1991cc
('벤츠',       'E클래스',       'SEDAN', 'Mid-size'),   -- 1991cc
('벤츠',       'GLC',           'SUV',   'Mid-size'),   -- 1991cc
('BMW',        '3시리즈',       'SEDAN', 'Mid-size'),   -- 1998cc
('BMW',        '5시리즈',       'SEDAN', 'Mid-size'),   -- 1998cc
('BMW',        'X3',            'SUV',   'Mid-size'),   -- 1998cc
('아우디',     'A6',            'SEDAN', 'Mid-size'),   -- 1984cc
('폭스바겐',   '티구안',        'SUV',   'Mid-size'),   -- 1968cc

-- ── 대형 Full-size : 2,000cc 이상 ───────────────────────
('현대',       '그랜저',        'SEDAN', 'Full-size'),  -- 2497cc
('현대',       '싼타페',        'SUV',   'Full-size'),  -- 2497cc
('현대',       '팰리세이드',    'SUV',   'Full-size'),  -- 2497cc
('현대',       '스타리아',      'VAN',   'Full-size'),  -- 2199cc  ※ 데이터셋 제외 차종
('현대',       '포터2',         'TRUCK', 'Full-size'),  -- 2497cc  ※ 데이터셋 제외 차종
('기아',       'K8',            'SEDAN', 'Full-size'),  -- 2497cc
('기아',       'K9',            'SEDAN', 'Full-size'),  -- 3778cc
('기아',       '쏘렌토',        'SUV',   'Full-size'),  -- 2497cc
('기아',       '모하비',        'SUV',   'Full-size'),  -- 2959cc
('기아',       '카니발',        'VAN',   'Full-size'),  -- 3470cc  ※ 데이터셋 제외 차종
('기아',       '봉고3',         'TRUCK', 'Full-size'),  -- 2497cc  ※ 데이터셋 제외 차종
('제네시스',   'G80',           'SEDAN', 'Full-size'),  -- 2497cc
('제네시스',   'G90',           'SEDAN', 'Full-size'),  -- 3470cc
('제네시스',   'GV70',          'SUV',   'Full-size'),  -- 2497cc
('제네시스',   'GV80',          'SUV',   'Full-size'),  -- 2497cc
('KG모빌리티', '렉스턴',        'SUV',   'Full-size'),  -- 2157cc
('도요타',     '캠리',          'SEDAN', 'Full-size');  -- 2487cc

-- 확인용
-- SELECT car_class, count(*) FROM vehicle_model GROUP BY car_class ORDER BY 2 DESC;
-- SELECT manufacturer, count(*) FROM vehicle_model GROUP BY manufacturer ORDER BY 2 DESC;
