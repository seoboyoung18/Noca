-- S15P21A307-487 · 안내 한계 고지 문구 1행
--
-- 왜 필요한가
--   체크리스트·질문 화면 상단과 리포트에 항상 표시할 고지 문구를 담을 자리가 없었다.
--   문안은 S15P21A307-487 본문에 확정돼 있고 백엔드가 창작하지도, 다듬지도 않는다.
--   이 파일은 행 하나만 넣는다. 테이블도 컬럼도 만들지 않는다.
--
-- ⚠️ 왜 estimate_notice 인가 — 이름이 어긋난다는 것을 알고 넣는다
--   이 테이블은 구조가 범용(code · message · display_order · is_active)이라 그대로 담긴다.
--   새 테이블을 만드는 것은 스키마 작업이라 -487(1pt)의 범위를 넘는다.
--
--   대가가 하나 있다. EstimateNoticeProvider.activeNotices() 는 "활성 행 전부" 를
--   GET /api/estimates/{id} 의 notices[] 로 내보낸다. 이 행을 그냥 두면 견적 화면에
--   "본 체크리스트와 질문은 …" 이 나가 있지도 않은 섹션을 가리킨다.
--   그래서 그 메서드가 이 코드를 제외한다(NON_ESTIMATE_CODES).
--
--   제대로 고치려면 scope 열이나 별도 notice 테이블이 필요하다. 별도 티켓이다.
--
-- 여러 번 돌려도 안전한가
--   그렇다. ON CONFLICT (code) DO NOTHING 이라 이미 있으면 아무것도 하지 않는다.
--   2026-09-14-estimate-notice.sql 과 같은 방식이다.
--
--   ⚠️ 이미 들어 있는 행의 문구를 이 파일이 덮어쓰지 않는다. 운영자가 psql UPDATE 로
--     문구를 고쳤다면 그 값이 그대로 남는다 — 그것이 S15P21A307-288 이 원한 동작이다.
--
-- 이 파일은 기존 데이터를 지우지 않는다. 행 1건 추가만 한다.
-- 롤백 절차는 파일 맨 끝에 있다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-15-guidance-notice.sql
--
--   psql 이 PATH 에 없으면 컨테이너 안의 것을 쓴다. 한글이 들어 있으므로 인코딩을 지정한다.
--   docker exec -i -e PGCLIENTENCODING=UTF8 a307-db psql -U "$DB_USERNAME" -d a307 \
--     < Docs/Erd/migrations/2026-09-15-guidance-notice.sql

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. 안내 한계 고지 문구
-- ═══════════════════════════════════════════════════════════════════════════
-- 문안은 S15P21A307-487 본문에서 그대로 옮긴 것이다. 마침표를 붙이거나 조사를 다듬지 않았다.
-- prompt62 가 -462 문안을 옮겨 적다가 3건 틀린 적이 있다(answer62 6장) — 대조는 코드가 아니라
-- 사람이 해야 하고, 그 결과를 answer70 에 적어 두었다.

INSERT INTO estimate_notice (code, message, display_order, is_active) VALUES
    ('GUIDANCE_LIMIT_NOTICE',
     '본 체크리스트와 질문은 AI 분석 결과에 기반한 참고용 안내이며, 실제 정비 범위와 방식은 정비 전문가의 점검 결과에 따라 달라질 수 있습니다',
     1, TRUE)
ON CONFLICT (code) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. 적재 결과 확인 — 없으면 커밋 전에 멈춘다
-- ═══════════════════════════════════════════════════════════════════════════
DO $$
DECLARE notice_count INTEGER;
BEGIN
    SELECT count(*) INTO notice_count
      FROM estimate_notice
     WHERE code = 'GUIDANCE_LIMIT_NOTICE' AND is_active;

    IF notice_count <> 1 THEN
        RAISE EXCEPTION '안내 한계 고지 활성 행이 % 건이다. 1 이어야 한다', notice_count;
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   SELECT code, display_order, is_active, message
--     FROM estimate_notice ORDER BY display_order, code;
--
--   한글이 '?' 로 보이면 클라이언트 인코딩 문제다. PowerShell 의 Get-Content 파이프로
--   넣으면 그렇게 깨진다 — 정본 DDL 머리말이 같은 경고를 달고 있다.
--
-- 문구를 바꿀 때 (재배포 불필요)
--   UPDATE estimate_notice SET message = '새 문구', updated_at = now()
--    WHERE code = 'GUIDANCE_LIMIT_NOTICE';
--
-- 잠시 내릴 때 — 체크리스트 조회와 리포트가 그 자리를 null 로 받는다. 둘 다 죽지 않는다.
--   UPDATE estimate_notice SET is_active = FALSE, updated_at = now()
--    WHERE code = 'GUIDANCE_LIMIT_NOTICE';
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
--   DELETE FROM estimate_notice WHERE code = 'GUIDANCE_LIMIT_NOTICE';
--
--   (지워도 앱은 뜬다. 체크리스트 조회의 notice 와 리포트의 guidanceNotice 가 null 이 될 뿐이다.)
