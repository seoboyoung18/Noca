-- S15P21A307-503 · 마이그레이션 적용 여부 확인
--
-- 무엇을 하나
--   Docs/Erd/migrations/ 의 파일 8개가 이 DB 에 반영됐는지 파일별로 판정한다.
--   각 파일이 만드는 대표 객체(테이블·컬럼·시드 행)의 존재로 판정한다.
--
-- 왜 필요한가
--   spring.jpa.hibernate.ddl-auto=validate 라, 하나라도 빠지면 그 API 만 죽는 것이
--   아니라 앱 전체가 기동하지 않는다. 적용 전에 무엇이 빠졌는지 알아야 한다.
--
-- 안전한가
--   그렇다. SELECT 만 한다. 아무것도 만들거나 고치지 않는다.
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/check-applied.sql
--
--   psql 이 PATH 에 없으면 컨테이너 안의 것을 쓴다.
--   docker exec -i a307-db psql -U "$DB_USERNAME" -d a307 < Docs/Erd/migrations/check-applied.sql
--
-- ⚠️ 2026-09-12-analysis-stage.sql 은 멱등이 아니다
--   CREATE TABLE 에 IF NOT EXISTS 가 없어, 이미 적용된 DB 에 다시 돌리면
--   ERROR: relation "analysis_stage" already exists 로 멈춘다.
--   이 스크립트가 "적용됨" 으로 판정하면 그 파일은 건너뛴다.

\pset border 2
\pset title '마이그레이션 적용 여부 (O = 적용됨, X = 미적용)'

WITH probe(seq, migration, object_kind, detail, applied) AS (
    VALUES
    (1, '2026-09-10-accident-image-angle-code.sql', '컬럼',
        'accident_image.angle_code',
        EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'accident_image' AND column_name = 'angle_code')),

    (2, '2026-09-10-admin-master-and-rules.sql', '테이블 3 + 컬럼 5',
        'repair_code · repair_method_rule · estimate_validation_rule',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_code')
        AND EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_method_rule')
        AND EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'estimate_validation_rule')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'audit_log' AND column_name = 'change_reason')),

    (3, '2026-09-12-analysis-stage.sql', '테이블 1',
        'analysis_stage  ⚠️ 멱등 아님 — 적용됨이면 건너뛸 것',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'analysis_stage')),

    (4, '2026-09-14-analysis-result-ingest.sql', '컬럼 4',
        'analysis_job.request_id · pipeline_version_id · analysis_image_result.detections · estimate_item.paint_material_cost',
        EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'analysis_job' AND column_name = 'request_id')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'analysis_job' AND column_name = 'pipeline_version_id')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'analysis_image_result' AND column_name = 'detections')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'estimate_item' AND column_name = 'paint_material_cost')),

    (5, '2026-09-14-estimate-notice.sql', '테이블 1 + 시드 1행',
        'estimate_notice + LEGAL_NOTICE',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'estimate_notice')),

    (6, '2026-09-14-repair-checklist.sql', '테이블 3',
        'repair_checklist · repair_checklist_item · repair_checklist_common_item',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_checklist')
        AND EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_checklist_item')
        AND EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_checklist_common_item')),

    (7, '2026-09-15-guidance-notice.sql', '시드 1행',
        'estimate_notice · GUIDANCE_LIMIT_NOTICE',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'estimate_notice')),

    (8, '2026-09-15-repair-question.sql', '테이블 2',
        'repair_question · repair_question_item',
        EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_question')
        AND EXISTS (SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'repair_question_item'))
)
SELECT seq                                AS "#",
       CASE WHEN applied THEN 'O' ELSE 'X' END AS "적용",
       migration                          AS "파일",
       object_kind                        AS "만드는 것",
       detail                             AS "판정 근거"
  FROM probe
 ORDER BY seq;

-- ═══════════════════════════════════════════════════════════════════════════
-- 시드 행은 테이블 존재와 따로 본다 — 테이블만 있고 행이 없는 경우가 실제로 있었다
-- ═══════════════════════════════════════════════════════════════════════════
\pset title '고지 문구 시드 (estimate_notice)'

SELECT code                                     AS "코드",
       is_active                                AS "활성",
       length(message)                          AS "문구 길이",
       CASE code
           WHEN 'LEGAL_NOTICE'          THEN '2026-09-14-estimate-notice.sql'
           WHEN 'GUIDANCE_LIMIT_NOTICE' THEN '2026-09-15-guidance-notice.sql'
           ELSE '(운영자가 직접 넣은 행)'
       END                                      AS "출처"
  FROM estimate_notice
 ORDER BY display_order, code;

-- 행이 하나도 안 나오면 시드가 빠진 것이다.
-- LEGAL_NOTICE 가 없으면 리포트 전체가 500 이 난다 (2026-09-14 에 실제로 났다).
-- GUIDANCE_LIMIT_NOTICE 가 없으면 체크리스트 조회의 notice 와 리포트의
-- guidanceNotice 가 null 이 된다 (앱은 정상 기동한다).

-- ═══════════════════════════════════════════════════════════════════════════
-- 전체 테이블 수 — 정본과 맞는지 눈으로 확인
-- ═══════════════════════════════════════════════════════════════════════════
\pset title '테이블 수'

SELECT count(*) AS "public 스키마 테이블 수"
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE';

-- 정본(Docs/Erd/A307_ddl_final.sql)은 2026-09-15 기준 41개다.
--   grep -c '^CREATE TABLE' Docs/Erd/A307_ddl_final.sql
-- 이보다 많으면 Spring Batch 메타 테이블 등이 섞인 것이다 — 그 자체는 문제가 아니다.
-- 적으면 빠진 것이 있다. 위 표에서 X 를 찾는다.
