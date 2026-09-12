-- S15P21A307-496 · 분석 진행 단계 테이블 analysis_stage
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    그 API 만 죽는 것이 아니라 앱 전체가 뜨지 않는다 — AnalysisStage 엔티티가 붙었기 때문이다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   analysis_stage 는 정본 DDL(Docs/Erd/A307_ddl_final.sql)에 처음부터 있었다. 다만 엔티티도
--   소비자도 없어 이미 떠 있는 DB 에는 만들어지지 않았을 수 있다. S15P21A307-496 이 엔티티를
--   붙이면서 그 상태가 기동 실패로 드러난다. 이 파일은 떠 있는 DB 를 정본과 같게 맞춘다.
--   테스트 스키마(backend/src/test/resources/schema-h2.sql)에는 같은 작업에서 함께 추가했다.
--
-- 무엇을 담는가
--   분석 작업 하나의 4단계 진행 상태다. 화면의 "부품을 연결하고 있어요 · 3/4" 가 이 행들이다.
--   정본 DDL 주석이 "화면의 4단계 체크리스트" 라고 직접 적어 두었다 — 화면을 보고 설계한 테이블이다.
--
-- 이미 있으면
--   IF NOT EXISTS 를 붙이지 않았다. 지금 확인하려는 것이 "있느냐 없느냐" 이기 때문이다.
--   이미 있으면 에러로 알려주는 편이 낫다. 그때는 이 파일을 건너뛰고 기록으로만 남긴다.
--   확인: psql "$DATABASE_URL" -c "\d analysis_stage"
--
-- 위험
--   낮다. 새 테이블 하나를 만들 뿐 기존 테이블을 건드리지 않는다. 옮길 데이터가 없다.
--   analysis_job 에 FK 가 걸리는데 그 테이블은 이미 있다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-12-analysis-stage.sql
--
-- 되돌리기
--   DROP TABLE analysis_stage;
--   (이 테이블을 참조하는 다른 테이블이 없다. 단, 되돌리려면 애플리케이션을 먼저 내려야 한다 —
--    엔티티가 살아 있는 채로 테이블을 지우면 다음 기동이 깨진다.)

BEGIN;

-- 화면의 4단계 체크리스트. 단계가 늘어도 스키마 불변
CREATE TABLE analysis_stage (
    stage_id    BIGSERIAL   PRIMARY KEY,
    job_id      BIGINT      NOT NULL REFERENCES analysis_job(job_id) ON DELETE CASCADE,
    stage       VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    detail      VARCHAR(100),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uk_as        UNIQUE (job_id, stage),
    CONSTRAINT ck_as_stage  CHECK (stage  IN ('PREPROCESS','DETECT','MATCH','ESTIMATE')),
    CONSTRAINT ck_as_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED'))
);

COMMIT;
