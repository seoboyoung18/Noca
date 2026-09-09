-- =============================================================================
--  A307 마이그레이션 — estimate_validation_report 큐 인덱스 추가
--  대상 이슈 : S15P21A307-399 (검증 리포트 상태 전이 · 큐)
--  작성일    : 2026-09-09
--  적용 대상 : 운영/개발 PostgreSQL. 정본 DDL(A307_ddl_final.sql)에는 이미 반영돼 있다.
-- =============================================================================
--
--  왜 필요한가
--  ----------
--  estimate_validation_report 의 PK 는 validation_id 하나뿐이라 status·created_at 으로
--  들어가는 조회를 덮지 못한다. PDF 생성 워커의 조회 3개가 정확히 그 형태다.
--
--    findQueuedIds          status = 'QUEUED'     + retry_count < 3   ORDER BY created_at, validation_id
--    findExhaustedIds       status = 'QUEUED'     + retry_count >= 3  ORDER BY created_at
--    findStaleProcessingIds status = 'PROCESSING' + created_at < ?    ORDER BY created_at
--
--  (claimQueued 는 validation_id 로 찾으므로 PK 를 탄다 — 대상이 아니다.)
--
--  자매 테이블 estimate_report 에는 같은 목적의 ix_er_queue 가 이미 있고,
--  analysis_job 에는 ix_job_queue 가 있다. 이 인덱스는 그 둘과 같은 형태다.
--
--  급하지 않다
--  ----------
--  기능 결함이 아니라 성능 항목이다. 지금은 행이 적어 체감되지 않는다.
--  검증이 수만 건 쌓이고 워커가 10초마다 도는 상황에서 의미가 생긴다.
--
--  부분 인덱스인 이유
--  ----------------
--  COMPLETED·FAILED 로 끝난 행은 WHERE 조건에서 빠져 인덱스에 남지 않는다.
--  그래서 검증이 아무리 쌓여도 인덱스 크기는 "큐에 남아 있는 건수" 만큼만 커진다.
--
--  retry_count 를 넣지 않은 이유
--  --------------------------
--  0~3 네 값뿐이라 선택도가 낮다. 인덱스만 커지고 거르는 효과가 거의 없다.
--  status 조건이 이미 큐로 좁힌 뒤이므로 나머지는 스캔이 싸다.
--
-- =============================================================================

-- ── 적용 ──────────────────────────────────────────────────────────────────────
-- IF NOT EXISTS 를 붙였다. 두 번 실행해도 안전하고, 이미 적용된 환경에서 오류로 멈추지 않는다.

CREATE INDEX IF NOT EXISTS ix_evr_queue
    ON estimate_validation_report (status, created_at)
    WHERE status IN ('QUEUED','PROCESSING');


-- ── 운영 DB 에 이미 트래픽이 있다면 위 대신 아래를 쓴다 ────────────────────────
--
--  일반 CREATE INDEX 는 해당 테이블에 쓰기 잠금을 건다 — 만드는 동안 INSERT·UPDATE 가 막힌다.
--  CONCURRENTLY 는 잠금을 거의 걸지 않지만 제약이 둘 있다.
--    · 트랜잭션 블록 안에서 실행할 수 없다 (BEGIN…COMMIT 으로 감싸면 안 된다)
--    · 실패하면 INVALID 인덱스가 남아 DROP 으로 직접 치워야 한다
--
--  이 테이블은 기능이 방금 열려 행이 거의 없으므로 위의 일반 문으로 순간에 끝난다.
--  판단이 서지 않으면 아래를 쓰는 편이 안전하다. 어느 쪽이든 결과 인덱스는 같다.
--
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_evr_queue
--     ON estimate_validation_report (status, created_at)
--     WHERE status IN ('QUEUED','PROCESSING');


-- ── 확인 ─────────────────────────────────────────────────────────────────────
-- 만들어졌는지:
--   SELECT indexname, indexdef FROM pg_indexes
--    WHERE tablename = 'estimate_validation_report';
--
-- CONCURRENTLY 로 만들다 실패했는지 (indisvalid = false 면 치우고 다시 만든다):
--   SELECT c.relname, i.indisvalid
--     FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid
--    WHERE c.relname = 'ix_evr_queue';


-- ── 되돌리기 ──────────────────────────────────────────────────────────────────
-- DROP INDEX IF EXISTS ix_evr_queue;
-- (운영 중이면 DROP INDEX CONCURRENTLY IF EXISTS ix_evr_queue;)
