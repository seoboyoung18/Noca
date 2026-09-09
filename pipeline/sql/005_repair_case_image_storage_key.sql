-- AI-Hub 검색 사례 이미지 key 규칙 정착
-- 기존 적재분의 로컬 상대 경로 key를 repair-cases/{caseId}/images/{caseImageId}/original.jpg로 바꾼다.
-- 실제 이미지 파일은 이동하지 않는다. 개발 환경에서는 source_image_ref로 원본을 읽는다.

BEGIN;

UPDATE repair_case_image
   SET storage_key = format(
           'repair-cases/%s/images/%s/original.jpg',
           case_id,
           case_image_id
       ),
       quality_status = NULL
 WHERE storage_key NOT LIKE 'repair-cases/%'
   AND storage_key NOT LIKE 'accidents/%';

COMMIT;
