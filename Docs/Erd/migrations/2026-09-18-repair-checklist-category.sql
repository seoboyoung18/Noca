-- S15P21A307-544 · 체크리스트 항목의 부위·분류와 한 줄 요약
--   repair_checklist_item.category · part_code · reason
--   repair_checklist.summary
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    RepairChecklistItem · RepairChecklist 엔티티가 이 열들을 매핑하기 때문이다.
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 왜 필요한가
--   체크리스트 상세 화면은 공통 / 부품별 / 함께 점검 세 탭이다. 지금 항목이 가진 것은
--   source(AI·COMMON·USER)와 문장뿐이라 **부품별 그룹과 "함께 점검" 탭을 만들 수 없다.**
--   생성 단계부터 없다 — LLM 응답 스키마가 items:[문장] 하나였다.
--
-- 열 넷
--   category  COMMON · PART · HIDDEN. PART 는 사진에서 확인된 부위 관련,
--             HIDDEN 은 사진에 보이지 않지만 함께 점검을 권하는 항목이다.
--             ('AI 항목인가' 는 source 가 이미 답하므로 여기 값으로 두지 않는다)
--   part_code 그 항목이 가리키는 부위. 마스터 FK 다 — LLM 이 지어낸 코드를 서버가 NULL 로
--             바꾸지만, 그 판단이 한 곳에서만 도는 것보다 DB 가 한 겹 더 막는 편이 낫다.
--             ON DELETE RESTRICT 인 이유는 repair_question_item.part_code 와 같다:
--             부품이 마스터에서 빠졌다고 사용자가 받아 둔 체크리스트가 사라지면 안 된다.
--   reason    HIDDEN 항목이 "왜 이것도 보라고 하는가" 한 문장. PART·COMMON 은 NULL 이다.
--   summary   AI 한 줄 요약(사고 성격 + 중점 확인 권장). 생성은 200자 이내로 지시하고
--             열은 300 으로 둔다 — 모델이 조금 넘겨도 저장이 깨지지 않고(서버가 자른다),
--             나중에 문안을 늘려도 마이그레이션을 다시 하지 않는다.
--
-- 왜 category 에 CHECK 를 거는가
--   source(ck_rcli_source) · damage_type(ck_dp_damage) 과 같은 방식이다. 이 저장소는 값이
--   정해진 열을 enum 테이블로 만들지 않고 CHECK 로 막는다.
--
-- 왜 reason 에 조건 제약을 걸지 않는가
--   "HIDDEN 이면 reason 이 있어야 한다" 는 생성기에는 있지만 DB 에는 두지 않는다.
--   사용자가 직접 추가하는 항목(POST …/items)은 category 만 보내고 reason 을 보내지 않는다 —
--   제약을 걸면 그 정상적인 요청이 500 이 된다.
--
-- 기존 데이터
--   이미 만들어진 항목은 category 기본값 'PART' 로 채워지고, 그중 공통 6종만 아래 UPDATE 가
--   'COMMON' 으로 옮긴다. **기존 AI 항목의 부위는 채우지 않는다** — 어느 부위에서 나온
--   문장인지 되짚을 근거가 없다. 지어내지 않고 NULL 로 두며, 재생성하면 채워진다.
--
-- 위험
--   낮다. NOT NULL DEFAULT 열 추가는 PostgreSQL 11+ 에서 테이블을 다시 쓰지 않는다.
--   UPDATE 대상은 체크리스트당 6행뿐이다(공통 항목 수).
--
-- 여러 번 돌려도 안전한가
--   그렇다. 열은 ADD COLUMN IF NOT EXISTS, 제약은 pg_constraint 를 보고 없을 때만 만든다.
--   UPDATE 도 이미 옮긴 행을 다시 건드리지 않는다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-18-repair-checklist-category.sql
--
-- 되돌리기
--   ALTER TABLE repair_checklist_item
--       DROP CONSTRAINT IF EXISTS ck_rcli_category,
--       DROP COLUMN IF EXISTS category,
--       DROP COLUMN IF EXISTS part_code,
--       DROP COLUMN IF EXISTS reason;
--   ALTER TABLE repair_checklist DROP COLUMN IF EXISTS summary;
--   (애플리케이션을 먼저 이전 버전으로 내려야 한다 — 엔티티가 매핑한 채로 지우면 다음
--    기동이 깨진다.)

BEGIN;

ALTER TABLE repair_checklist
    ADD COLUMN IF NOT EXISTS summary VARCHAR(300);

ALTER TABLE repair_checklist_item
    ADD COLUMN IF NOT EXISTS category  VARCHAR(10) NOT NULL DEFAULT 'PART',
    ADD COLUMN IF NOT EXISTS part_code VARCHAR(50) REFERENCES part_code(part_code) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS reason    VARCHAR(300);

-- ADD CONSTRAINT 에는 IF NOT EXISTS 가 없다. 이 파일을 두 번 돌려도 멈추지 않게 직접 본다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                     FROM pg_constraint c
                     JOIN pg_class      t ON t.oid = c.conrelid
                    WHERE c.conname = 'ck_rcli_category'
                      AND t.relname = 'repair_checklist_item') THEN
        ALTER TABLE repair_checklist_item
            ADD CONSTRAINT ck_rcli_category CHECK (category IN ('COMMON','PART','HIDDEN'));
    END IF;
END $$;

-- 이미 들어 있는 공통 6종은 COMMON 이다. 기본값 'PART' 로 남으면 화면이 공통 항목을
-- 부품별 탭에 그린다. source 가 정본이므로 그것을 보고 옮긴다.
UPDATE repair_checklist_item
   SET category = 'COMMON'
 WHERE source = 'COMMON'
   AND category <> 'COMMON';

COMMIT;

-- 적용 후 확인
--   SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
--     FROM information_schema.columns
--    WHERE table_name = 'repair_checklist_item'
--      AND column_name IN ('category','part_code','reason')
--    ORDER BY column_name;
--   → category | character varying | 10 | NO | 'PART'::character varying
--     part_code | character varying | 50 | YES |
--     reason    | character varying | 300 | YES |
--
--   SELECT category, count(*) FROM repair_checklist_item GROUP BY category;
--   → COMMON 행 수가 (체크리스트 수 × 6) 과 맞아야 한다
