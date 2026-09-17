-- S15P21A307-534 · 부분 견적에서 산정하지 못한 부위 보관 (estimate.unresolved_parts)
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    Estimate 엔티티가 이 열을 매핑하기 때문이다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   AI 계약(2026-09-17, S15P21A307-524)에 unresolvedParts[] 가 생겼다. 부위 여럿 중 일부만
--   산정하지 못하면 AI 는 estimable=true 를 유지하고 totals 를 산정한 항목만으로 합산한다.
--   지금까지 백엔드는 이 목록을 버렸다. 그러면 화면·PDF 의 총액이 "전체 수리비" 처럼 보이는데
--   실제로는 빠진 부위가 있다 — 사용자가 보험사·정비소에 들고 가는 문서에서 틀린 인상을 준다.
--
-- 왜 estimate 의 JSONB 열인가
--   (1) 산정 결과의 일부라 estimate 버전과 함께 움직여야 한다. 재분석으로 새 버전이 생기면
--       빠진 부위도 그 버전 기준으로 바뀐다.
--   (2) estimate_item 에 넣을 수 없다. estimate_item 은 damaged_part 를 NOT NULL 로 참조하고
--       금액 열을 가진 "산정된 항목" 이다. 금액이 없는 행을 섞으면 합계·정렬 쿼리가 모두 조건을
--       새로 달아야 한다.
--   (3) 조회는 견적 한 건 단위로만 한다. 부위별로 검색하거나 집계할 일이 없어 새 테이블은 과하다.
--
-- 값 모양
--   [{"partCode":"HEAD_LAMP_L","damageType":"Crushed","reason":"INSUFFICIENT_CASES"}, ...]
--   백엔드가 저장 전에 거른다 — part_code 마스터에 없는 부위, items[] 에 이미 있는 부위,
--   같은 부위의 중복은 버린다. 그래서 이 열에는 화면에 그대로 보여도 되는 값만 남는다.
--
-- 왜 NULL 을 허용하는가
--   이 변경 이전에 저장된 견적은 빠진 부위를 알 방법이 없다 — 추측해서 채우지 않는다.
--   조회 API 는 NULL 과 빈 배열을 똑같이 unresolvedParts: [] 로 내보낸다.
--
-- 위험
--   낮다. 기본값 없는 NULL 열 추가라 PostgreSQL 은 테이블을 다시 쓰지 않는다. 옮길 데이터가 없다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. ADD COLUMN IF NOT EXISTS 다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-17-estimate-unresolved-parts.sql
--
-- 되돌리기
--   ALTER TABLE estimate DROP COLUMN IF EXISTS unresolved_parts;
--   (다른 테이블이 참조하지 않는다. 단, 애플리케이션을 먼저 이전 버전으로 내려야 한다 —
--    엔티티가 이 열을 매핑한 채로 지우면 다음 기동이 깨진다.)

BEGIN;

ALTER TABLE estimate
    ADD COLUMN IF NOT EXISTS unresolved_parts JSONB;

COMMIT;

-- 적용 후 확인
--   SELECT column_name, data_type, is_nullable
--     FROM information_schema.columns
--    WHERE table_name = 'estimate' AND column_name = 'unresolved_parts';
--   → unresolved_parts | jsonb | YES
