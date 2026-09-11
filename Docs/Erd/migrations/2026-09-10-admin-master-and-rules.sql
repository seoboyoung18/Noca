-- S15P21A307 · 관리자 마스터·판정 규칙·변경 이력
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-10-admin-master-and-rules.sql
--
-- 이 파일은 기존 데이터를 지우지 않는다. 컬럼 추가와 테이블 추가만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. 낙관적 잠금 — vehicle_model · part_code
-- ═══════════════════════════════════════════════════════════════════════════
-- 관리자 둘이 같은 행을 동시에 고치면 나중 저장이 앞의 변경을 조용히 덮는다(lost update).
-- JPA @Version 이 UPDATE ... WHERE version = ? 로 바꿔 주고, 어긋나면 409 로 끊는다.
-- 기존 행은 0 에서 시작한다.

ALTER TABLE vehicle_model ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE part_code     ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. part_code — AI 핵심 32종 / 견적 확장 코드 구분
-- ═══════════════════════════════════════════════════════════════════════════
-- 지금까지 두 집합을 가르는 유일한 단서가 display_order(1~32 vs 101~124) 였다.
-- 그것은 표시 순서일 뿐이라 관리자가 순서를 바꾸면 구분이 조용히 무너진다.
-- 명시적 속성으로 옮긴다.
--
-- backfill 근거: Docs/Erd/A307_part_code_seed.sql 머리말
--   "YOLO 핵심 32종 + 견적 전용 확장 코드 24종" 이며 시드가 1~32 / 101~124 로 나눠 넣었다.
--   이 UPDATE 는 그 시드 상태를 그대로 옮기는 **일회성** 이관이다.
--   이후로는 display_order 로 판단하지 않는다.

ALTER TABLE part_code ADD COLUMN IF NOT EXISTS code_scope VARCHAR(20) NOT NULL DEFAULT 'EXTENDED';

UPDATE part_code SET code_scope = 'AI_LABEL' WHERE display_order BETWEEN 1 AND 32;

-- 이관 결과 확인 — 32 가 아니면 시드가 예상과 다르므로 커밋 전에 멈춘다.
DO $$
DECLARE ai_count INTEGER;
BEGIN
    SELECT count(*) INTO ai_count FROM part_code WHERE code_scope = 'AI_LABEL';
    IF ai_count <> 32 THEN
        RAISE EXCEPTION 'AI_LABEL 이관 결과가 32종이 아니다 (실제 %). 시드를 확인하라.', ai_count;
    END IF;
END $$;

ALTER TABLE part_code DROP CONSTRAINT IF EXISTS ck_pc_scope;
ALTER TABLE part_code ADD  CONSTRAINT ck_pc_scope CHECK (code_scope IN ('AI_LABEL','EXTENDED'));

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. repair_code — canonical code 의 "표시층"
-- ═══════════════════════════════════════════════════════════════════════════
-- 수리 방식 4종과 손상 유형 4종은 Java enum·DDL CHECK·통계 쿼리가 같은 문자열을 쓴다.
-- 관리자가 새 canonical code 를 추가할 수 있게 만들면 DB 행만 늘고 계산이 실패한다.
-- 그래서 **코드 자체는 불변**으로 두고, 표시명·순서·활성 상태만 관리한다.
--
-- CHECK 를 FK 로 바꾸지 않는다. damaged_part·estimate_item·repair_cost_stat 등 여러 테이블이
-- 이 값을 문자열로 들고 있고, FK 전환은 적용 순서와 고아 데이터 검사가 필요한 별도 작업이다.
-- 이 테이블은 그 위에 얹는 표시층이며, 소비 경로는 repair_method_rule 검증이다.

CREATE TABLE IF NOT EXISTS repair_code (
    code_type     VARCHAR(20) NOT NULL,
    code          VARCHAR(20) NOT NULL,
    display_name  VARCHAR(50) NOT NULL,
    display_order SMALLINT    NOT NULL DEFAULT 0,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (code_type, code),
    CONSTRAINT ck_rcode_type CHECK (code_type IN ('REPAIR_METHOD','DAMAGE_TYPE'))
);

-- 현재 enum·CHECK 값에서 그대로 이관한다. 표시명은 WorkType 의 한글 displayName 을 따른다.
INSERT INTO repair_code (code_type, code, display_name, display_order, is_active) VALUES
    ('REPAIR_METHOD','exchange',   '교환', 1, TRUE),
    ('REPAIR_METHOD','sheet_metal','판금', 2, TRUE),
    ('REPAIR_METHOD','coating',    '도장', 3, TRUE),
    ('REPAIR_METHOD','repair',     '수리', 4, TRUE),
    ('DAMAGE_TYPE',  'Scratched',  '스크래치',   1, TRUE),
    ('DAMAGE_TYPE',  'Separated',  '이격',       2, TRUE),
    ('DAMAGE_TYPE',  'Crushed',    '찌그러짐',   3, TRUE),
    ('DAMAGE_TYPE',  'Breakage',   '파손',       4, TRUE)
ON CONFLICT (code_type, code) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. repair_method_rule — 심각도 → 수리 방식
-- ═══════════════════════════════════════════════════════════════════════════
-- ⚠️ severity_score 의 값 범위는 이 저장소 어디에도 확정되어 있지 않다.
--    damaged_part.severity_score 는 NUMERIC(6,2) 이고 그 값을 쓰는 코드가 아직 없다.
--    (AI-Hub 원본의 severity_level 1~4 는 pipeline/sql/003_aihub_staging.sql 의 별개 값이다.)
--    그래서 0~1 이나 0~100 을 강제하지 않는다. 범위는 관리자가 정하고, DB 는
--    "하한 < 상한" 과 컬럼 타입만 강제한다. 범위가 확정되면 CHECK 를 좁히면 된다.
--
-- 구간 경계: [min, max) 가 기본이고 max_inclusive = TRUE 면 [min, max] 다.
-- 마지막 구간만 상한을 포함시켜야 경계값이 두 규칙에 동시에 잡히지 않는다.

CREATE TABLE IF NOT EXISTS repair_method_rule (
    rule_id       BIGSERIAL    PRIMARY KEY,
    damage_type   VARCHAR(20)  NOT NULL,
    part_code     VARCHAR(50)  REFERENCES part_code(part_code) ON DELETE RESTRICT,
    severity_min  NUMERIC(6,2) NOT NULL,
    severity_max  NUMERIC(6,2) NOT NULL,
    max_inclusive BOOLEAN      NOT NULL DEFAULT FALSE,
    repair_method VARCHAR(20)  NOT NULL,
    priority      SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_rmr_range  CHECK (severity_min < severity_max),
    CONSTRAINT ck_rmr_damage CHECK (damage_type   IN ('Scratched','Separated','Crushed','Breakage')),
    CONSTRAINT ck_rmr_method CHECK (repair_method IN ('coating','sheet_metal','exchange','repair'))
);

CREATE INDEX IF NOT EXISTS ix_rmr_lookup ON repair_method_rule (damage_type, is_active, priority);

-- ═══════════════════════════════════════════════════════════════════════════
-- 5. estimate_validation_rule — 이상 탐지 임계값 (버전 있는 불변 세트)
-- ═══════════════════════════════════════════════════════════════════════════
-- 단일 행 덮어쓰기를 쓰지 않는다. 과거 검증이 어떤 임계값으로 판정됐는지 되짚을 수 없게 된다.
-- 행은 만들어진 뒤 바뀌지 않고, **현재 규칙 = rule_version 이 가장 큰 행**이다.
-- 활성 플래그를 두지 않아 "활성이 둘" 같은 상태가 원천적으로 생기지 않는다.
--
-- presigned_url_minutes 는 여기 넣지 않는다 — 파일 URL 인프라 설정이지 판정 규칙이 아니다.
-- 그 값은 application.properties 에 그대로 남는다.

CREATE TABLE IF NOT EXISTS estimate_validation_rule (
    rule_version                        INTEGER      PRIMARY KEY,
    reference_percentile                SMALLINT     NOT NULL,
    severe_over_p75_multiplier          NUMERIC(5,2) NOT NULL,
    caution_total_difference_ratio      NUMERIC(5,4) NOT NULL,
    needs_review_total_difference_ratio NUMERIC(5,4) NOT NULL,
    needs_review_item_count             SMALLINT     NOT NULL,
    changed_by                          BIGINT       REFERENCES member(member_id) ON DELETE SET NULL,
    change_note                         VARCHAR(200),
    created_at                          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_evr_version CHECK (rule_version >= 1),
    CONSTRAINT ck_evr_pct     CHECK (reference_percentile BETWEEN 1 AND 100),
    CONSTRAINT ck_evr_mult    CHECK (severe_over_p75_multiplier > 1.0),
    CONSTRAINT ck_evr_caution CHECK (caution_total_difference_ratio >= 0),
    CONSTRAINT ck_evr_review  CHECK (needs_review_total_difference_ratio >= caution_total_difference_ratio),
    CONSTRAINT ck_evr_count   CHECK (needs_review_item_count >= 1)
);

-- 버전 1 = 현재 application.properties 의 배포 기본값. 값이 바뀌는 것이 아니라 옮겨 오는 것이다.
INSERT INTO estimate_validation_rule (
    rule_version, reference_percentile, severe_over_p75_multiplier,
    caution_total_difference_ratio, needs_review_total_difference_ratio,
    needs_review_item_count, changed_by, change_note)
VALUES (1, 75, 1.50, 0.1000, 0.2000, 3, NULL, 'application.properties 배포 기본값 이관')
ON CONFLICT (rule_version) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- 6. estimate_validation.rule_version — 어떤 규칙으로 판정했는지
-- ═══════════════════════════════════════════════════════════════════════════
-- nullable 이다. 이 컬럼이 생기기 전에 끝난 검증은 어떤 버전을 썼는지 알 수 없고,
-- 추측해서 1 로 채우면 사실이 아닌 근거가 남는다.
-- ON DELETE SET NULL 이지만 규칙 행을 지우는 API 는 만들지 않는다.

ALTER TABLE estimate_validation ADD COLUMN IF NOT EXISTS rule_version INTEGER;

ALTER TABLE estimate_validation DROP CONSTRAINT IF EXISTS fk_ev_rule_version;
ALTER TABLE estimate_validation ADD  CONSTRAINT fk_ev_rule_version
    FOREIGN KEY (rule_version) REFERENCES estimate_validation_rule(rule_version) ON DELETE SET NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- 7. audit_log 조회 인덱스
-- ═══════════════════════════════════════════════════════════════════════════
-- 정본에 이미 ix_audit_actor · ix_audit_target · ix_audit_created 가 있다.
-- 관리자 목록이 action_type 으로도 거르므로 그 조합만 추가한다.

-- audit_log 의 JSONB·INET 을 TEXT·VARCHAR 로 바꾼다.
-- Java 쪽은 평범한 String 이라 ddl-auto=validate 가 jsonb ↔ varchar 를 불일치로 잡고
-- 애플리케이션이 기동하지 않는다. 이 두 컬럼을 검색 조건으로 쓰지 않기로 했으므로
-- JSONB 의 이점(인덱스·연산자)을 포기해도 잃는 것이 없다.
-- estimate_validation.llm_summary 가 같은 이유로 TEXT 다.
-- 기존 행이 있어도 JSONB → TEXT 는 값 손실 없이 변환된다.
ALTER TABLE audit_log ALTER COLUMN before_data TYPE TEXT        USING before_data::text;
ALTER TABLE audit_log ALTER COLUMN after_data  TYPE TEXT        USING after_data::text;
ALTER TABLE audit_log ALTER COLUMN ip_address  TYPE VARCHAR(45) USING ip_address::text;

-- 변경 사유. nullable 이다 — 지금 사유를 필수로 받는 경로는 부품명 매핑의 등록·수정·삭제뿐이고,
-- NOT NULL 로 잠그면 아직 사유를 받지 않는 관리자 경로가 전부 깨진다. 기존 행도 NULL 로 남는다.
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS change_reason VARCHAR(500);

CREATE INDEX IF NOT EXISTS ix_audit_action ON audit_log (action_type, created_at DESC);

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤 아래를 실행한다.
-- 추가한 것만 지우므로 기존 데이터는 그대로다.
--
-- BEGIN;
-- ALTER TABLE estimate_validation DROP CONSTRAINT IF EXISTS fk_ev_rule_version;
-- ALTER TABLE estimate_validation DROP COLUMN IF EXISTS rule_version;
-- DROP INDEX IF EXISTS ix_audit_action;
-- ALTER TABLE audit_log DROP COLUMN IF EXISTS change_reason;
-- ALTER TABLE audit_log ALTER COLUMN before_data TYPE JSONB USING before_data::jsonb;
-- ALTER TABLE audit_log ALTER COLUMN after_data  TYPE JSONB USING after_data::jsonb;
-- ALTER TABLE audit_log ALTER COLUMN ip_address  TYPE INET  USING ip_address::inet;
-- DROP TABLE IF EXISTS estimate_validation_rule;
-- DROP INDEX IF EXISTS ix_rmr_lookup;
-- DROP TABLE IF EXISTS repair_method_rule;
-- DROP TABLE IF EXISTS repair_code;
-- ALTER TABLE part_code DROP CONSTRAINT IF EXISTS ck_pc_scope;
-- ALTER TABLE part_code DROP COLUMN IF EXISTS code_scope;
-- ALTER TABLE part_code     DROP COLUMN IF EXISTS version;
-- ALTER TABLE vehicle_model DROP COLUMN IF EXISTS version;
-- COMMIT;
