-- S15P21A307-509 · 정비 체크리스트 스키마 (repair_checklist 3종)
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    지금은 엔티티가 없어 당장 깨지지는 않지만, S15P21A307-460 이 엔티티를 붙이는 순간
--    그 API 만이 아니라 앱 전체가 뜨지 않는다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   정비 체크리스트를 담을 테이블이 정본 DDL 어디에도 없었다. 그래서 -460 · -461 · -463 ·
--   -483 이 착수되지 않는다. 이 파일은 그 자리를 만든다. API 는 만들지 않는다.
--
-- 무엇을 담는가
--   repair_checklist_common_item  공통 확인 항목 마스터 6종 (S15P21A307-462 · -463)
--   repair_checklist              사고 한 건의 체크리스트 머리 · 생성 상태 · 재생성 추적
--   repair_checklist_item         항목 · 출처(AI/공통/사용자) · 완료 여부 · 메모 · 정렬 순서
--
--   사고 현장 체크리스트(S15P21A307-121, backend/src/main/resources/checklist.json)와는
--   다른 기능이다. 그쪽은 정적 12항목 · 무인증 · 저장 없음이고, 이 파일은 손대지 않는다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. 테이블과 인덱스는 IF NOT EXISTS, 시드 INSERT 는 ON CONFLICT (code) DO NOTHING
--   이다. 신규 설치(docker compose 로 A307_ddl_final.sql 을 갓 적용한 DB)에도 그대로
--   실행한다 — 그 경우 테이블은 이미 있고 시드만 들어간다.
--   2026-09-14-estimate-notice.sql 과 같은 방식이다.
--
--   2026-09-12-analysis-stage.sql 은 반대로 IF NOT EXISTS 를 일부러 뺐다. 그때는 "있느냐
--   없느냐" 자체가 확인 대상이었기 때문이다. 여기는 시드가 함께 들어가 재실행이 정상
--   경로라서 붙인다.
--
-- 이 파일은 기존 데이터를 지우지 않는다. 테이블 3개 · 인덱스 1개 · 행 6건 추가만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-14-repair-checklist.sql
--
--   psql 이 PATH 에 없으면 컨테이너 안의 것을 쓴다.
--   docker exec -i a307-db psql -U "$DB_USERNAME" -d a307 < Docs/Erd/migrations/2026-09-14-repair-checklist.sql

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. 공통 확인 항목 마스터
-- ═══════════════════════════════════════════════════════════════════════════
-- 형태는 estimate_notice 를 그대로 따랐다. 성격이 같은 문구 마스터이고, 관리자 API 없이
-- psql UPDATE 로 고친다. 동시 편집 경로가 없어 version(낙관적 잠금)도 두지 않는다.
--
-- 항목 테이블이 이 테이블을 참조하므로 먼저 만든다 — FK 때문에 CREATE 는 정순이다.

CREATE TABLE IF NOT EXISTS repair_checklist_common_item (
    code          VARCHAR(30)  PRIMARY KEY,
    message       VARCHAR(500) NOT NULL,
    display_order SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 문안은 S15P21A307-462 본문에 확정돼 있다. 백엔드가 창작하지도, 다듬지도 않는다.
-- -462 본문의 괄호 안 나열을 그대로 여섯 조각으로 끊은 것이다.
--
--   "파손 부위와 무관한 공통 항목(견적서 서면 수령·항목별 금액 확인, 부품 등급(순정/OEM/
--    재생/중고)과 부품 번호 확인, 작업 전·후 사진 요청, 교체된 부품 실물 확인, 보증 기간·
--    보증서 발급, 예상 소요 기간과 대차 여부)을 체크리스트에 포함한다."
--
-- ⚠️ S15P21A307-509 본문은 같은 6종을 조금 다듬어 옮겨 적었다 — 1 · 2 · 5번이 다르다
--    ("수령과 항목별", "순정·OEM·재생·중고", "보증 기간과 보증서"). -509 완료 조건이
--    "문안이 S15P21A307-462 본문과 글자까지 같다" 이므로 -462 쪽을 정본으로 삼았다.
--    화면 문구를 확정할 때 어느 쪽을 쓸지 정해야 한다 — answer62 6장.

INSERT INTO repair_checklist_common_item (code, message, display_order, is_active) VALUES
    ('ESTIMATE_DOCUMENT', '견적서 서면 수령·항목별 금액 확인',              1, TRUE),
    ('PART_GRADE',        '부품 등급(순정/OEM/재생/중고)과 부품 번호 확인', 2, TRUE),
    ('WORK_PHOTO',        '작업 전·후 사진 요청',                          3, TRUE),
    ('REPLACED_PART',     '교체된 부품 실물 확인',                         4, TRUE),
    ('WARRANTY',          '보증 기간·보증서 발급',                         5, TRUE),
    ('DURATION_LOANER',   '예상 소요 기간과 대차 여부',                    6, TRUE)
ON CONFLICT (code) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. 체크리스트 머리
-- ═══════════════════════════════════════════════════════════════════════════
-- accident 에 FK 가 걸린다. 그 테이블은 이미 있다.
-- member_id 는 두지 않았다 — accident → vehicle → member 로 도달 가능한 이행적 종속이고,
-- accident 자신이 같은 이유로 member_id 를 두지 않았다.

CREATE TABLE IF NOT EXISTS repair_checklist (
    checklist_id   BIGSERIAL   PRIMARY KEY,
    accident_id    BIGINT      NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    generation_no  SMALLINT    NOT NULL DEFAULT 1,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    regenerated_at TIMESTAMPTZ,
    CONSTRAINT uk_rcl_accident   UNIQUE (accident_id),
    CONSTRAINT ck_rcl_status     CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_rcl_done       CHECK (completed_at IS NULL OR status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_rcl_generation CHECK (generation_no >= 1),
    CONSTRAINT ck_rcl_regen      CHECK (regenerated_at IS NULL OR generation_no > 1)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. 체크리스트 항목
-- ═══════════════════════════════════════════════════════════════════════════
-- 머리와 마스터를 둘 다 참조하므로 마지막이다.

CREATE TABLE IF NOT EXISTS repair_checklist_item (
    item_id       BIGSERIAL    PRIMARY KEY,
    checklist_id  BIGINT       NOT NULL REFERENCES repair_checklist(checklist_id) ON DELETE CASCADE,
    source        VARCHAR(20)  NOT NULL,
    common_code   VARCHAR(30)  REFERENCES repair_checklist_common_item(code) ON DELETE RESTRICT,
    content       VARCHAR(500) NOT NULL,
    is_checked    BOOLEAN      NOT NULL DEFAULT FALSE,
    memo          VARCHAR(500),
    display_order SMALLINT     NOT NULL DEFAULT 0,
    checked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rcli_common  UNIQUE (checklist_id, common_code),
    CONSTRAINT ck_rcli_source  CHECK (source IN ('AI','COMMON','USER')),
    CONSTRAINT ck_rcli_link    CHECK ((source =  'COMMON' AND common_code IS NOT NULL)
                                   OR (source <> 'COMMON' AND common_code IS NULL)),
    CONSTRAINT ck_rcli_checked CHECK (checked_at IS NULL OR is_checked = TRUE)
);

CREATE INDEX IF NOT EXISTS ix_rcli_checklist ON repair_checklist_item (checklist_id, display_order);

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. 적재 결과 확인 — 6 이 아니면 시드가 덜 들어간 것이므로 커밋 전에 멈춘다
-- ═══════════════════════════════════════════════════════════════════════════
DO $$
DECLARE common_count INTEGER;
BEGIN
    SELECT count(*) INTO common_count
      FROM repair_checklist_common_item
     WHERE is_active;

    IF common_count <> 6 THEN
        RAISE EXCEPTION '공통 확인 항목 활성 행이 % 건이다. 6 이어야 한다', common_count;
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   \d repair_checklist
--   \d repair_checklist_item
--   SELECT code, display_order, is_active, message
--     FROM repair_checklist_common_item ORDER BY display_order;
--
--   한글이 '?' 로 보이면 클라이언트 인코딩 문제다. PowerShell 의 Get-Content 파이프로
--   넣으면 그렇게 깨진다 — 정본 DDL 머리말이 같은 경고를 달고 있다.
--
-- 문구를 바꿀 때 (재배포 불필요)
--   UPDATE repair_checklist_common_item
--      SET message = '새 문구', updated_at = now()
--    WHERE code = 'WARRANTY';
--
--   ※ 이미 만들어진 체크리스트의 항목 문안은 바뀌지 않는다. repair_checklist_item.content
--     가 생성 시점 문안을 복사해 두기 때문이다 (accident 의 스냅샷 컬럼과 같은 방식).
--     의도한 동작이다 — 지난 체크리스트가 나중 개정으로 소급해 바뀌면 안 된다.
--
-- 항목을 잠시 내릴 때
--   UPDATE repair_checklist_common_item SET is_active = FALSE, updated_at = now() WHERE code = '...';
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤에 실행한다. FK 때문에 DROP 은 역순이다.
--
--   DROP TABLE IF EXISTS repair_checklist_item;
--   DROP TABLE IF EXISTS repair_checklist;
--   DROP TABLE IF EXISTS repair_checklist_common_item;
--
--   (엔티티가 살아 있는 채로 테이블을 지우면 다음 기동이 ddl-auto=validate 에서 깨진다.)
