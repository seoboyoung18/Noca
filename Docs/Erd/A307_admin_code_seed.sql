-- A307 관리자 기준 데이터 — canonical code 표시층과 이상 탐지 규칙 초기 버전.
--
-- 신규 설치용이다. 이미 떠 있는 DB 에는
-- Docs/Erd/migrations/2026-09-10-admin-master-and-rules.sql 이 같은 값을 넣는다.
--
-- repair_code 는 **행이 늘지 않는다.** 수리 방식 4종과 손상 유형 4종은 Java enum 과
-- DDL CHECK 가 함께 고정한 canonical code 이고, 관리자는 표시명·순서·활성 상태만 바꾼다.
-- 새 코드를 넣으려면 enum·CHECK·계산 로직을 함께 바꿔야 한다.

BEGIN;

INSERT INTO repair_code (code_type, code, display_name, display_order, is_active) VALUES
    ('REPAIR_METHOD','exchange',   '교환',     1, TRUE),
    ('REPAIR_METHOD','sheet_metal','판금',     2, TRUE),
    ('REPAIR_METHOD','coating',    '도장',     3, TRUE),
    ('REPAIR_METHOD','repair',     '수리',     4, TRUE),
    ('DAMAGE_TYPE',  'Scratched',  '스크래치', 1, TRUE),
    ('DAMAGE_TYPE',  'Separated',  '이격',     2, TRUE),
    ('DAMAGE_TYPE',  'Crushed',    '찌그러짐', 3, TRUE),
    ('DAMAGE_TYPE',  'Breakage',   '파손',     4, TRUE)
ON CONFLICT (code_type, code) DO NOTHING;

-- 버전 1 = application.properties 의 배포 기본값을 그대로 옮긴 것이다.
-- 이 행이 없으면 견적서 검증이 판정 규칙을 찾지 못해 실패한다.
INSERT INTO estimate_validation_rule (
    rule_version, reference_percentile, severe_over_p75_multiplier,
    caution_total_difference_ratio, needs_review_total_difference_ratio,
    needs_review_item_count, changed_by, change_note)
VALUES (1, 75, 1.50, 0.1000, 0.2000, 3, NULL, 'application.properties 배포 기본값 이관')
ON CONFLICT (rule_version) DO NOTHING;

-- repair_method_rule 은 시드하지 않는다.
-- damaged_part.severity_score 의 값 범위가 아직 확정되지 않아 임계값을 지어낼 근거가 없다.
-- 관리자가 POST /api/admin/repair-method-rules 로 등록한다.

COMMIT;
