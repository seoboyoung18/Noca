-- S15P21A307-537 · 견적 리포트 LLM 요약 (estimate_narrative)
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    EstimateNarrative 엔티티가 이 테이블을 매핑하기 때문이다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   리포트는 숫자와 표만 있고, "이 견적을 어떻게 읽어야 하는가" 를 말해 주는 문장이 없다.
--   사용자는 그 표를 들고 정비소·보험사에 간다.
--
-- 왜 별도 테이블인가 — estimate 에 열을 붙이지 않았다
--   (1) 이것은 **큐**다. status·실패 사유·선점 시각이 따라붙고, 대기 건만 훑는 인덱스가 필요하다.
--       견적 본문 테이블에 큐 열 넷을 섞으면 견적을 읽는 모든 쿼리가 그 열을 함께 진다.
--   (2) 생성은 견적 저장보다 <b>나중에, 다른 트랜잭션에서</b> 끝난다. 실패해도 견적은 그대로다.
--   (3) repair_checklist 가 같은 모양이다 — 이 저장소에 큐 패턴을 두 가지 만들지 않는다.
--
-- 왜 PK 가 estimate_id 인가
--   견적 한 건에 요약 한 행이다. 별도 시퀀스와 UNIQUE 를 두는 대신 PK 를 FK 로 삼으면
--   1:1 이 스키마로 강제되고, 견적이 지워지면 CASCADE 로 함께 사라진다.
--   견적은 재산정할 때마다 **새 행**이므로(uk_est) 요약도 버전마다 따로 생긴다.
--
-- content 에 무엇이 들어가나
--   {"summary":"…","cautions":["…"],"basisNotes":[{"partCode":"FRONT_BUMPER","text":"…"}]}
--   문장만 담는다. 금액·등급·판정은 여기 없다 — 그 값들은 이미 estimate·estimate_item 이
--   가지고 있고, LLM 이 만든 숫자가 협상 근거가 되면 안 된다. 서버가 저장 전에 한 번 더
--   거른다: 원문에 없던 숫자가 섞인 문장은 버리고 규칙이 만든 문장을 그대로 쓴다.
--
-- 왜 JSONB 인가
--   문장 묶음을 통째로 읽고 통째로 쓴다. 부위별로 검색하거나 집계할 일이 없어 자식 테이블은
--   과하다 (estimate.unresolved_parts 와 같은 판단, S15P21A307-534).
--
-- 기존 견적
--   행을 만들지 않는다. 이 변경 이전 견적에는 요약이 없고, 리포트는 그 자리를 비운다.
--   필요하면 나중에 한 줄로 채울 수 있다:
--     INSERT INTO estimate_narrative (estimate_id) SELECT estimate_id FROM estimate
--      WHERE estimate_id NOT IN (SELECT estimate_id FROM estimate_narrative);
--   (그 순간 견적 수만큼 LLM 을 부른다 — 크레딧을 보고 결정할 것)
--
-- 위험
--   낮다. 새 테이블 하나와 부분 인덱스 하나뿐이고, 기존 테이블을 건드리지 않는다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. CREATE TABLE IF NOT EXISTS · CREATE INDEX IF NOT EXISTS 다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-18-estimate-narrative.sql
--
-- 되돌리기
--   DROP TABLE IF EXISTS estimate_narrative;
--   (애플리케이션을 먼저 이전 버전으로 내려야 한다.)

BEGIN;

CREATE TABLE IF NOT EXISTS estimate_narrative (
    -- PK 이자 FK. 견적 한 건에 한 행이고, 견적이 지워지면 함께 사라진다.
    estimate_id    BIGINT      PRIMARY KEY REFERENCES estimate(estimate_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    -- 문장 묶음. 완료 전에는 NULL 이다.
    content        JSONB,
    -- 실패 코드. 한글 문장이 아니다 — 화면 문구는 FE 가 정한다.
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 선점·완료 시각. 워커가 멈춘 PROCESSING 건을 되찾는 기준이다.
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_en_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    -- 완료인데 문장이 없으면 화면이 빈 요약을 그린다. 그 상태를 스키마가 막는다.
    CONSTRAINT ck_en_done   CHECK (status <> 'COMPLETED' OR content IS NOT NULL)
);

-- 대기·처리 중인 건만 담는 부분 인덱스. 완료가 쌓여도 인덱스는 대기 건수만큼만 커진다
-- (ix_job_queue · ix_ev_queue 와 같은 형태다).
CREATE INDEX IF NOT EXISTS ix_en_queue
    ON estimate_narrative (updated_at, estimate_id)
 WHERE status IN ('QUEUED','PROCESSING');

COMMIT;

-- 적용 후 확인
--   \d estimate_narrative
--   → PK(estimate_id) · ck_en_status · ck_en_done · ix_en_queue 가 보여야 한다
--
--   SELECT status, count(*) FROM estimate_narrative GROUP BY status;
--   → 이 시점에는 0행이다. 다음 견적이 저장될 때 QUEUED 가 생긴다
