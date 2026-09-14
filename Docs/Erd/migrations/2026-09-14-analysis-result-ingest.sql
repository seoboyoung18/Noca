-- S15P21A307-155 · 분석 결과 수신을 위한 DDL 보강
--
-- ⚠️ 이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.
--    **이 SQL 을 운영 DB 에 먼저 적용하지 않으면 애플리케이션이 기동하지 않는다.**
--    배포 순서: (1) 이 파일 적용 → (2) 애플리케이션 배포
--
-- 적용
--   psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-14-analysis-result-ingest.sql
--
-- 이 파일은 기존 데이터를 지우지 않는다. 컬럼 추가와 NOT NULL 완화만 한다.
-- 여러 번 돌려도 결과가 같다. 롤백 절차는 파일 맨 끝에 있다.
--
-- ── 왜 필요한가 ──────────────────────────────────────────────
--   Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md (2차 수정본, 2026-09-12) 와
--   Docs/Api/AI 서버 오류·재시도 처리 명세.md 가 요구하는 값 중 **저장할 자리가
--   없는 것**들이다. 계약 문서가 "DDL 에 자리가 없는 것 … 별도 티켓으로 잡아야
--   합니다" 라고 직접 지목했고, 여기에 멱등성용 request_id 를 더했다.
--
--   이 마이그레이션만으로는 화면이 달라지지 않는다. S15P21A307-157(결과 수신)이
--   이 자리를 채운다. 엔티티 매핑도 그쪽에서 붙인다 — 지금 매핑하면 쓰지도 않는
--   필드가 늘 뿐이다.

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. analysis_job.request_id — callback 멱등성
-- ═══════════════════════════════════════════════════════════════════════════
-- 오류·재시도 명세의 "중복 방지" 가 이 컬럼 없이는 성립하지 않는다.
--
--   "같은 requestId 로 callback 이 다시 도착하면 백엔드는 새 견적 버전을 만들지
--    않고 기존 처리 결과를 유지한 채 200 을 반환한다"
--   "본문 jobId 가 해당 requestId 에 연결된 분석 작업인지 확인"
--
-- 저장하지 않으면 "이미 처리한 요청" 인지 알 수 없고, AI 서버의 callback 재시도
-- (1초 → 5초 → 20초, 최대 3회)마다 견적 버전이 하나씩 늘어난다.
--
-- **작업당 한 개만 둔다(이력 테이블을 만들지 않는다).** 재분석(S15P21A307-161)으로
-- 새 requestId 가 발급되면 덮어쓴다. 그러면 이전 시도의 늦은 callback 은 값이
-- 달라 거부되는데, 그것이 옳은 동작이다 — 철 지난 결과가 최신 견적을 덮으면 안 된다.
--
-- 길이 64 는 audit_log.request_id 와 맞춘 것이다. 계약 예시는 12자리 hex 다.
-- nullable 인 이유는 이 컬럼이 생기기 전에 만들어진 작업과, 아직 AI 에 보내지
-- 않은 QUEUED 작업에는 값이 없기 때문이다. 추측해서 채우면 사실이 아닌 근거가 남는다.

ALTER TABLE analysis_job ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);

-- 부분 유니크. NULL 은 서로 충돌하지 않으므로 값이 있는 행만 겹치지 않게 한다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_aj_request ON analysis_job (request_id)
    WHERE request_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. analysis_job.pipeline_version_id — 버전 조합 추적
-- ═══════════════════════════════════════════════════════════════════════════
-- 계약이 성공·실패 callback 양쪽에 pipelineVersionId 를 싣는데 받을 자리가 없었다.
-- model_version(VARCHAR) 하나로는 "이 견적이 어느 버전 조합으로 나왔나" 를 되짚을
-- 수 없다 — 모델·정규화·ROI 규칙이 각각 바뀐다.
--
-- FK 를 걸지 않는다. feature_pipeline_version 은 파이프라인(S15P21A307-232)이
-- 관리하는 테이블이고 서비스 DB 와 적재 시점이 다르다. FK 를 걸면 AI 가 보낸 버전이
-- 아직 적재되지 않았을 때 결과 수신 자체가 실패한다. 값 보존이 목적이다.

ALTER TABLE analysis_job ADD COLUMN IF NOT EXISTS pipeline_version_id BIGINT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. analysis_image_result.detections — 프론트가 다시 그릴 좌표
-- ═══════════════════════════════════════════════════════════════════════════
-- 오버레이 이미지를 없애고 좌표를 JSON 으로 주기로 하면서(2026-09-11 수정 ①)
-- 좌표를 보관할 자리가 필요해졌다. 없으면 사용자가 견적을 다시 열었을 때 손상
-- 영역을 다시 그릴 수 없다.
--
-- 좌표는 (이미지 × 검출) 단위인데 그 단위의 테이블이 없다. damaged_part 는
-- UNIQUE (job_id, part_code) 로 부품당 1행이고 estimate_item.damaged_part_id 가
-- NOT NULL FK 로 그 단위를 참조하므로 바꿀 수 없다. analysis_image_result 는 이미
-- UNIQUE (job_id, image_id) 라 "어느 이미지의 좌표인지" 가 JSONB 안이 아니라 행으로
-- 해결된다.
--
-- **받은 detections[] 를 키 이름·구조를 바꾸지 말고 그대로 넣는다.** 2차 수정본이
-- 더한 pairStatus(PAIRED·UNPAIRED·AMBIGUOUS) 와 searchability(STRICT·VECTOR_ONLY
-- ·EXCLUDED) 도 이 안에 들어온다. 필드를 덜어내면 그리기가 깨진다.
--
-- 컬럼 제약으로 구조를 강제하지 않는다. 계약이 바뀔 때마다 DDL 을 따라 고치게 되고,
-- 그 검증은 수신 계층(AnalysisDocumentReader)이 이미 한다.

ALTER TABLE analysis_image_result ADD COLUMN IF NOT EXISTS detections JSONB;

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. estimate_item.paint_material_cost — 도장 재료비
-- ═══════════════════════════════════════════════════════════════════════════
-- 계약 items[] 가 laborCost 와 paintMaterialCost 를 나눠 보내는데 받을 자리가
-- 없었다. 샘플 리포트가 "공임 315,000원 + 도장 재료비 185,000원" 으로 나눠 보여 주고,
-- repair_case_item 에는 paint_material_cost 가 이미 있다 — 사례 쪽과 견적 쪽의
-- 표현을 맞춘다.
--
-- nullable 이다. 도장이 없는 작업(교환·판금)에는 이 값이 없고, 0 으로 채우면
-- "도장을 했는데 재료비가 0" 과 구분되지 않는다.

ALTER TABLE estimate_item ADD COLUMN IF NOT EXISTS paint_material_cost INTEGER;

-- ═══════════════════════════════════════════════════════════════════════════
-- 5. damaged_part.repair_method — NOT NULL 완화
-- ═══════════════════════════════════════════════════════════════════════════
-- 손상 4종 중 3종(Separated · Crushed · Breakage)은 수리 방식 후보가 둘이고,
-- 교환과 판금·수리를 가르는 심각도 파생 규칙이 2026-09-09 에 롤백됐다
-- (S15P21A307-197). 규칙이 서기 전까지 이 값을 확정할 수 없다.
--
-- NOT NULL 이면 후보가 둘인 손상을 **저장 자체를 못 한다.** 실제로 지금
-- AnalysisResultIngestService 가 그런 검출을 AMBIGUOUS_WORK_CANDIDATE 로 버리고 있다.
-- 버리면 화면에 손상 부위조차 못 띄운다.
--
-- UNDETERMINED 값을 더하는 대신 NULL 을 허용한다. 값을 더하면 enum·repair_code
-- 시드·관리자 화면·estimate_item 의 같은 CHECK 까지 번지는데, "아직 모른다" 는
-- 다섯 번째 수리 방식이 아니라 값의 부재다.
--
-- CHECK 는 그대로 둔다 — 값이 있을 때는 여전히 네 값 중 하나여야 한다.
-- S15P21A307-196 규칙이 서면 UPDATE 로 채우면 된다.

ALTER TABLE damaged_part ALTER COLUMN repair_method DROP NOT NULL;

-- 적용 결과 확인 — 하나라도 어긋나면 커밋 전에 멈춘다.
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(expected, ', ') INTO missing
      FROM (VALUES
            ('analysis_job.request_id'),
            ('analysis_job.pipeline_version_id'),
            ('analysis_image_result.detections'),
            ('estimate_item.paint_material_cost')) AS t(expected)
     WHERE NOT EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name  = split_part(expected, '.', 1)
               AND column_name = split_part(expected, '.', 2));

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '컬럼 추가 실패: %', missing;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'damaged_part'
                  AND column_name = 'repair_method'
                  AND is_nullable = 'NO') THEN
        RAISE EXCEPTION 'damaged_part.repair_method 가 여전히 NOT NULL 이다';
    END IF;
END $$;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 적용 후 확인
-- ═══════════════════════════════════════════════════════════════════════════
--   \d analysis_job          → request_id · pipeline_version_id
--   \d analysis_image_result → detections
--   \d estimate_item         → paint_material_cost
--   \d damaged_part          → repair_method 가 not null 이 아닐 것
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 롤백
-- ═══════════════════════════════════════════════════════════════════════════
-- 애플리케이션을 이전 버전으로 되돌린 뒤에 실행한다.
--
-- ⚠️ 5번은 되돌릴 때 조건이 있다. NULL 인 행이 남아 있으면 NOT NULL 복원이
--    실패한다. 그 행들을 먼저 지우거나 값을 채워야 하는데, 어느 쪽이든 데이터
--    손실이라 사람이 판단해야 한다.
--
--   DROP INDEX IF EXISTS ux_aj_request;
--   ALTER TABLE analysis_job          DROP COLUMN IF EXISTS request_id;
--   ALTER TABLE analysis_job          DROP COLUMN IF EXISTS pipeline_version_id;
--   ALTER TABLE analysis_image_result DROP COLUMN IF EXISTS detections;
--   ALTER TABLE estimate_item         DROP COLUMN IF EXISTS paint_material_cost;
--   -- SELECT count(*) FROM damaged_part WHERE repair_method IS NULL;  먼저 확인
--   -- ALTER TABLE damaged_part ALTER COLUMN repair_method SET NOT NULL;
