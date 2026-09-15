-- S15P21A307-513 · 사고 데이터·피드백 검수 스키마 (accident_review 1종)
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    지금은 엔티티가 없어 당장 깨지지는 않지만, S15P21A307-350 이 엔티티를 붙이는 순간
--    그 API 만이 아니라 앱 전체가 뜨지 않는다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   관리자가 사고 데이터를 검수해 승인·반려한 결과를 담을 테이블이 정본 DDL 어디에도 없었다.
--   그래서 S15P21A307-350(검수 대기 큐 적재) · -352(승인·반려 처리)가 착수되지 않는다.
--   이 파일은 그 자리를 만든다. API 는 만들지 않는다.
--
--   이미 있는 구멍이기도 하다. pipeline/jobs/load_service_accidents.py (S15P21A307-223) 가
--   실제 수리비가 입력된 사고를 repair_case 에 source='SERVICE' 로 적재하는데, 그 행은
--   조회에서 통째로 빠진다(rc.source <> 'SERVICE'). 그 파일 머리말이 "공개 정책이 정해지기
--   전까지 AI-Hub 사례만 연다" 고 적어 두었고, 이 테이블이 그 공개 정책의 자리다.
--
-- 무엇을 담는가
--   accident_review  사고 한 건의 검수 상태 · 무엇을 보고 판정했는지 · 반려 사유
--
--   항목별 검수 테이블은 만들지 않았다. damaged_part · estimate_item 에는 사용자 수정 경로도,
--   "누가 고쳤다" 를 담을 열도 없어 검수할 재료가 없다. 담을 것이 정해지지 않은 테이블을
--   미리 만들지 않는다 — 필요해지면 그때 review_id 를 FK 로 붙인다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. 테이블과 인덱스가 IF NOT EXISTS 이고 넣을 시드가 없어 INSERT 자체가 없다.
--   신규 설치(docker compose 로 A307_ddl_final.sql 을 갓 적용한 DB)에도 그대로 실행한다 —
--   그 경우 테이블은 이미 있고 아무것도 하지 않는다.
--
--   IF NOT EXISTS 는 "이름이 같은 테이블" 만 보고 넘어가므로, 정의가 다른 옛 테이블이 남아
--   있으면 조용히 지나친다. 그래서 맨 끝 3장이 제약 6개가 실제로 붙어 있는지 이름으로 확인하고
--   하나라도 없으면 커밋 전에 멈춘다. 2026-09-15-repair-question.sql 과 같은 방식이다.
--
-- 이 파일은 기존 데이터를 지우지 않는다. 테이블 1개 · 인덱스 1개 추가만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-15-accident-review.sql
--
--   psql 이 PATH 에 없으면 컨테이너 안의 것을 쓴다. 한글 주석이 있으니 인코딩을 지정한다.
--   docker exec -i -e PGCLIENTENCODING=UTF8 a307-db psql -U "$DB_USERNAME" -d a307 \
--     < Docs/Erd/migrations/2026-09-15-accident-review.sql

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. 검수 상태
-- ═══════════════════════════════════════════════════════════════════════════
-- accident · analysis_job · member 셋을 참조하므로 그 뒤다. 셋 다 이미 있다.
--
-- 본문은 정본 DDL 12-3 절과 글자까지 같다. 다른 것은 IF NOT EXISTS 뿐이다.
-- 열마다의 이유는 정본 쪽 주석에 적어 두었다 — 사본을 두 벌 두지 않는다.

CREATE TABLE IF NOT EXISTS accident_review (
    review_id          BIGSERIAL    PRIMARY KEY,
    accident_id        BIGINT       NOT NULL REFERENCES accident(accident_id) ON DELETE CASCADE,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_job_id    BIGINT       REFERENCES analysis_job(job_id) ON DELETE SET NULL,
    snapshot_actual_repair_cost INTEGER,
    reviewer_member_id BIGINT       REFERENCES member(member_id) ON DELETE SET NULL,
    reject_reason      VARCHAR(500),
    queued_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at        TIMESTAMPTZ,
    CONSTRAINT uk_ar_accident UNIQUE (accident_id),
    CONSTRAINT ck_ar_status   CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_ar_done     CHECK ((status =  'PENDING') = (reviewed_at IS NULL)),
    CONSTRAINT ck_ar_reject   CHECK ((status =  'REJECTED') = (reject_reason IS NOT NULL)),
    CONSTRAINT ck_ar_cost     CHECK (snapshot_actual_repair_cost IS NULL
                                  OR snapshot_actual_repair_cost > 0)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. 인덱스
-- ═══════════════════════════════════════════════════════════════════════════
-- 관리자 화면이 늘 "대기" 만 오래된 순으로 읽는다. 부분 인덱스라 판정이 끝난 행은 빠진다.
-- ix_ar_accident 는 만들지 않는다 — uk_ar_accident 가 그대로 커버한다.

CREATE INDEX IF NOT EXISTS ix_ar_queue ON accident_review (status, queued_at)
    WHERE status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. 적용 결과 확인 — 제약이 하나라도 빠졌으면 커밋 전에 멈춘다
-- ═══════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    expected TEXT[] := ARRAY[
        'uk_ar_accident', 'ck_ar_status', 'ck_ar_done', 'ck_ar_reject', 'ck_ar_cost'];
    missing  TEXT[];
BEGIN
    SELECT array_agg(name ORDER BY name) INTO missing
      FROM unnest(expected) AS name
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint c
             JOIN pg_class      t ON t.oid = c.conrelid
            WHERE c.conname = name
              AND t.relname = 'accident_review');

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '제약이 빠졌다: %. 이름이 같고 정의가 다른 옛 테이블이 남아 있는지 확인할 것', missing;
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   \d accident_review
--
--   대기 목록이 이렇게 나온다 (S15P21A307-351 이 쓸 모양이다)
--   SELECT r.review_id, r.accident_id, r.queued_at, a.actual_repair_cost
--     FROM accident_review r JOIN accident a USING (accident_id)
--    WHERE r.status = 'PENDING' ORDER BY r.queued_at;
--
--   승인 뒤 금액이 바뀐 건 — 재검수가 필요한 행이다 (S15P21A307-353 이 대조할 조건이다)
--   SELECT r.accident_id FROM accident_review r JOIN accident a USING (accident_id)
--    WHERE r.status = 'APPROVED'
--      AND a.actual_repair_cost IS DISTINCT FROM r.snapshot_actual_repair_cost;
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤에 실행한다.
--
--   DROP TABLE IF EXISTS accident_review;
--
--   (엔티티가 살아 있는 채로 테이블을 지우면 다음 기동이 ddl-auto=validate 에서 깨진다.)
