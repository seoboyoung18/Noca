-- S15P21A307-570 · 사용자가 고른 부위로 다시 분석
--   analysis_job.selected_part_code
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    AnalysisJob 엔티티가 이 열을 매핑하기 때문이다.
--    배포 순서: (1) 이 파일 적용 → (2) AI 배포(고른 부위 처리) → (3) 애플리케이션 배포
--
-- 왜 필요한가
--   부품을 찾지 못해 산정하지 못한 견적(PART_NOT_RESOLVED)에서 사용자가 부위를 골라 다시
--   분석한다. 접수는 작업을 QUEUED 로 만들 뿐이고 AI 호출은 워커가 나중에 한다 — 고른 부위를
--   작업에 적어 두지 않으면 워커가 AI 요청에 실을 값이 없다.
--
-- 왜 작업에 두나 (damaged_part 에 "사용자가 고름" 표시를 두지 않고)
--   고른 부위는 "이 분석을 어떻게 요청했나" 다. 요청 조건은 작업의 몫이다. 결과 쪽 표시는
--   화면에 "직접 선택" 을 보이지 않기로 해서(2026-09-22 결정) 만들지 않는다.
--
-- 왜 FK 를 거나
--   damaged_part.part_code 와 같은 이유다. 마스터에 없는 코드가 AI 로 나가면 AI 는 모르는
--   부위로 사례를 찾는다. 접수가 활성 AI 라벨 부위인지까지 보지만 그것은 애플리케이션 규칙이고,
--   DB 는 존재만 강제한다.
--
-- 인덱스를 만들지 않는 이유
--   이 열로 찾는 조회가 없다. 작업을 읽을 때 함께 읽을 뿐이다.
--
-- 되돌리기
--   ALTER TABLE analysis_job DROP COLUMN selected_part_code;

ALTER TABLE analysis_job ADD COLUMN selected_part_code VARCHAR(50)
    REFERENCES part_code(part_code) ON DELETE RESTRICT;
