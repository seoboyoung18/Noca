-- S15P21A307-510 · 정비소 확인 질문 스키마 (repair_question 2종)
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    지금은 엔티티가 없어 당장 깨지지는 않지만, S15P21A307-477 이 엔티티를 붙이는 순간
--    그 API 만이 아니라 앱 전체가 뜨지 않는다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   정비소에 물어볼 질문을 담을 테이블이 정본 DDL 어디에도 없었다. 그래서 S15P21A307-476 ·
--   -477 이 착수되지 않는다. 이 파일은 그 자리를 만든다. API 는 만들지 않는다.
--
--   기존 estimate_validation_question 은 쓸 수 없다. validation_id 가 NOT NULL 이라 견적서를
--   올려 검증한 경우에만 행이 생기는데, -476 은 견적서 없이 사고 분석 결과와 예상 견적만으로
--   질문을 만드는 기능이다. 그 테이블은 견적서 검증 전용으로 그대로 둔다 — 손대지 않았다.
--
-- 무엇을 담는가
--   repair_question       사고 한 건의 질문 목록 머리 · 생성 상태 · 재생성 추적
--   repair_question_item  질문 문안 · 근거(부품 코드·이름 사본·손상 유형·수리 방법) · 정렬 순서
--
--   구조와 상태값 어휘는 2026-09-14-repair-checklist.sql(S15P21A307-509)과 같게 맞췄다.
--   다른 점은 둘뿐이다 — 공통 문안 마스터가 없어 시드 INSERT 가 없고(질문은 전부 LLM 생성),
--   항목이 근거 네 컬럼을 들고 있다. 정본 DDL 12-2 절 주석에 이유를 적어 뒀다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. 테이블과 인덱스가 IF NOT EXISTS 이고 넣을 시드가 없어 INSERT 자체가 없다.
--   신규 설치(docker compose 로 A307_ddl_final.sql 을 갓 적용한 DB)에도 그대로 실행한다 —
--   그 경우 두 테이블이 이미 있어 아무것도 하지 않고 끝난다.
--
--   IF NOT EXISTS 는 "이름이 같은 테이블" 만 보고 넘어가므로, 정의가 다른 옛 테이블이 남아
--   있으면 조용히 지나친다. 그래서 맨 끝 4장이 제약 10개가 실제로 붙어 있는지 이름으로 확인하고
--   하나라도 없으면 커밋 전에 멈춘다. 2026-09-14-repair-checklist.sql 의 시드 건수 확인과
--   같은 자리이고, 시드가 없는 이 파일에서는 그것이 확인할 거리다.
--
-- 이 파일은 기존 데이터를 지우지 않는다. 테이블 2개 · 인덱스 1개 추가만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-15-repair-question.sql
--
--   psql 이 PATH 에 없으면 컨테이너 안의 것을 쓴다.
--   docker exec -i a307-db psql -U "$DB_USERNAME" -d a307 < Docs/Erd/migrations/2026-09-15-repair-question.sql

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. 질문 목록 머리
-- ═══════════════════════════════════════════════════════════════════════════
-- accident 에 FK 가 걸린다. 그 테이블은 이미 있다.
-- member_id 는 두지 않았다 — accident → vehicle → member 로 도달 가능한 이행적 종속이고,
-- accident 자신과 repair_checklist 가 같은 이유로 두지 않았다.
-- 상태값 네 개와 CHECK 네 개는 repair_checklist 와 글자까지 같다.

CREATE TABLE IF NOT EXISTS repair_question (
    question_id    BIGSERIAL   PRIMARY KEY,
    accident_id    BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    generation_no  SMALLINT    NOT NULL DEFAULT 1,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    regenerated_at TIMESTAMPTZ,
    CONSTRAINT uk_rq_accident   UNIQUE (accident_id),
    CONSTRAINT ck_rq_status     CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_rq_done       CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_rq_generation CHECK (generation_no >= 1),
    CONSTRAINT ck_rq_regen      CHECK (regenerated_at IS NULL OR generation_no > 1)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. 질문 항목
-- ═══════════════════════════════════════════════════════════════════════════
-- 머리와 부품 마스터를 둘 다 참조하므로 마지막이다. part_code 는 이미 있다.
--
-- 근거를 damaged_part FK 로 걸지 않고 값으로 복사해 둔 이유 — damaged_part 는 job_id 에
-- 매여 있고 ON DELETE CASCADE 라, 재분석하거나 job 이 지워지면 질문까지 따라 사라진다.
-- 질문 목록은 job 이 아니라 사고 한 건에 붙는다.
--
-- part_code 는 마스터 FK 로 남기되 ON DELETE RESTRICT 다. 부품이 마스터에서 빠졌다고
-- 사용자가 받아 둔 질문이 같이 사라지면 안 된다(-510 본문). damaged_part.part_code ·
-- repair_checklist_item.common_code 와 같은 동작이고, 관리자 API 에 부품 삭제 경로는
-- 애초에 없다(is_active 토글뿐).

CREATE TABLE IF NOT EXISTS repair_question_item (
    item_id       BIGSERIAL    PRIMARY KEY,
    question_id   BIGINT       NOT NULL REFERENCES repair_question(question_id) ON DELETE CASCADE,
    source        VARCHAR(20)  NOT NULL,
    content       VARCHAR(500) NOT NULL,
    part_code     VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE RESTRICT,
    snapshot_part_name VARCHAR(50),
    damage_type   VARCHAR(20),
    repair_method VARCHAR(20),
    display_order SMALLINT     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rqi_source CHECK (source IN ('AI','USER')),
    CONSTRAINT ck_rqi_damage CHECK (damage_type   IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_rqi_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair')),
    CONSTRAINT ck_rqi_basis  CHECK ((part_code IS NULL) = (snapshot_part_name IS NULL)),
    CONSTRAINT ck_rqi_part   CHECK (part_code IS NOT NULL
                                 OR (damage_type IS NULL AND repair_method IS NULL))
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. 인덱스
-- ═══════════════════════════════════════════════════════════════════════════
-- 질문 목록은 늘 한 사고 것을 정렬해 읽는다. ix_rcli_checklist 와 같은 형태다.
-- ix_rq_accident 는 만들지 않는다 — uk_rq_accident 가 그대로 커버한다.

CREATE INDEX IF NOT EXISTS ix_rqi_question ON repair_question_item (question_id, display_order);

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. 적용 결과 확인 — 제약이 하나라도 빠졌으면 커밋 전에 멈춘다
-- ═══════════════════════════════════════════════════════════════════════════
-- CREATE TABLE IF NOT EXISTS 는 이름만 보고 넘어간다. 정의가 다른 옛 테이블이 남아 있으면
-- 여기서 걸린다 — 제약이 없는 채로 배포되면 잘못된 행이 조용히 쌓인다.
DO $$
DECLARE
    expected TEXT[] := ARRAY[
        'uk_rq_accident', 'ck_rq_status', 'ck_rq_done', 'ck_rq_generation', 'ck_rq_regen',
        'ck_rqi_source', 'ck_rqi_damage', 'ck_rqi_method', 'ck_rqi_basis', 'ck_rqi_part'];
    missing  TEXT[];
BEGIN
    SELECT array_agg(name ORDER BY name) INTO missing
      FROM unnest(expected) AS name
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint c
             JOIN pg_class      t ON t.oid = c.conrelid
            WHERE c.conname = name
              AND t.relname IN ('repair_question', 'repair_question_item'));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '제약이 빠졌다: %. 이름이 같고 정의가 다른 옛 테이블이 남아 있는지 확인할 것', missing;
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   \d repair_question
--   \d repair_question_item
--
--   한글 주석이 '?' 로 보이면 클라이언트 인코딩 문제다. PowerShell 의 Get-Content 파이프로
--   넣으면 그렇게 깨진다 — 정본 DDL 머리말이 같은 경고를 달고 있다.
--
-- 질문 문안은 시드로 넣지 않는다
--   생성은 LLM 이 한다(-510 "질문 문안을 시드로 넣지 않는다"). -476 본문의 예시 두 줄은
--   형태를 가늠하는 용도이지 데이터가 아니다.
--
-- 근거가 없는 질문
--   part_code · snapshot_part_name · damage_type · repair_method 가 전부 NULL 이면 특정
--   부품에 매이지 않은 질문이다 ("순정 외 부품 사용 시 금액 차이는 얼마인가요?").
--   ck_rqi_part 가 "부품 없이 판정만 있는" 행만 막는다.
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤에 실행한다. FK 때문에 DROP 은 역순이다.
--
--   DROP TABLE IF EXISTS repair_question_item;
--   DROP TABLE IF EXISTS repair_question;
--
--   (엔티티가 살아 있는 채로 테이블을 지우면 다음 기동이 ddl-auto=validate 에서 깨진다.)
