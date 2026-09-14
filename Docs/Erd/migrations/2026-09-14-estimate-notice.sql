-- S15P21A307-288 · 견적 한계 고지 문구 테이블화
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-14-estimate-notice.sql
--
-- 신규 설치(docker compose 로 A307_ddl_final.sql 을 갓 적용한 DB)도 이 파일을 그대로
-- 실행한다. 테이블은 IF NOT EXISTS 이고 INSERT 는 ON CONFLICT DO NOTHING 이라
-- 두 경우 모두 안전하고, 여러 번 돌려도 결과가 같다.
--
-- 이 파일은 기존 데이터를 지우지 않는다. 테이블 추가와 행 1건 삽입만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- estimate_notice — 견적 화면·리포트·PDF 가 함께 쓰는 고지 문구
-- ═══════════════════════════════════════════════════════════════════════════
-- 지금까지 문구는 EstimateValidationService.LEGAL_NOTICE 상수 하나였다. 상수는
-- 문구를 고칠 때마다 재배포를 요구하는데, 이 문장은 법무·기획 사정으로 바뀌는 값이라
-- 배포 일정에 묶이면 안 된다. "코드 수정 없이 변경 가능하도록" 이 -288 의 요구다.
--
-- 관리자 API 를 두지 않았다. 바꿀 일이 드물고, 관리자 마스터 관리(-362~-365)는 별도
-- 범위다. 값 변경은 psql UPDATE 로 한다 — 그것만으로 요구가 충족된다.
--
-- version(낙관적 잠금) 컬럼을 두지 않은 것도 같은 이유다. repair_code 가 그 컬럼을
-- 가진 것은 관리자 둘이 화면에서 동시에 고칠 수 있기 때문인데, 여기는 그 경로가 없다.

CREATE TABLE IF NOT EXISTS estimate_notice (
    code          VARCHAR(30)  PRIMARY KEY,
    message       VARCHAR(500) NOT NULL,
    display_order SMALLINT     NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- LEGAL_NOTICE = 현재 코드에 박혀 있는 문구 그대로다. 값이 바뀌는 것이 아니라
-- 자리만 옮긴다 — estimate_validation_rule 이 application.properties 기본값을
-- 옮겨 온 것과 같은 방식이다(2026-09-10-admin-master-and-rules.sql §5).
--
-- 문구를 여기서 손대지 않는다. 이관과 개정을 한 커밋에 섞으면 나중에 "언제부터
-- 다른 문장이 나갔나" 를 되짚을 수 없다.
INSERT INTO estimate_notice (code, message, display_order, is_active) VALUES
    ('LEGAL_NOTICE',
     '이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.',
     1, TRUE)
ON CONFLICT (code) DO NOTHING;

-- 이관 결과 확인 — 1 이 아니면 INSERT 가 먹지 않았으므로 커밋 전에 멈춘다.
DO $$
DECLARE notice_count INTEGER;
BEGIN
    SELECT count(*) INTO notice_count
      FROM estimate_notice
     WHERE code = 'LEGAL_NOTICE' AND is_active;

    IF notice_count <> 1 THEN
        RAISE EXCEPTION 'estimate_notice 활성 LEGAL_NOTICE 가 % 건이다. 1 이어야 한다', notice_count;
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   SELECT code, is_active, display_order, left(message, 30) AS head
--     FROM estimate_notice ORDER BY display_order, code;
--
-- 문구를 바꿀 때 (재배포 불필요)
--   UPDATE estimate_notice
--      SET message = '새 문구', updated_at = now()
--    WHERE code = 'LEGAL_NOTICE';
--
--   ※ 앱은 요청마다 이 테이블을 읽는다. 캐시가 없으므로 UPDATE 즉시 반영된다.
--
-- 문구를 잠시 내릴 때
--   UPDATE estimate_notice SET is_active = FALSE, updated_at = now() WHERE code = '...';
--
--   ※ LEGAL_NOTICE 를 내리면 리포트 조회가 500 이 된다 — EstimateReportService
--     .requireSections() 가 고지 문구를 필수로 보기 때문이다(S15P21A307-338).
--     의도한 동작이다. 고지 없는 리포트가 사용자 손에 나가는 것보다 낫다.
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤에 실행한다. 순서를 지키지 않으면
-- 현재 버전이 테이블을 읽지 못해 견적 조회·리포트가 죽는다.
--
--   DROP TABLE IF EXISTS estimate_notice;
