-- A-9b: replace PK-based repair-case object keys with stable source references.
-- This updates database keys only. It does not move, delete, or upload files.
-- The key is derived from the immutable AI-Hub source reference so it remains
-- stable when the local database is recreated and BIGSERIAL values change.
BEGIN;

UPDATE repair_case_image AS image
   SET storage_key = format(
       'repair-cases/%s/%s/%s/original.jpg',
       repair.source,
       repair.external_ref,
       substring(image.source_image_ref FROM '([0-9]+)_[^/]+$')
   )
  FROM repair_case AS repair
 WHERE repair.case_id = image.case_id
   AND repair.source IN ('AIHUB_AS', 'AIHUB_SC')
   AND repair.external_ref ~ '^(as|sc)-[0-9]+$'
   AND image.source_image_ref ~ '(^|/)[0-9]+_(as|sc)-[0-9]+[.][A-Za-z0-9]+$';

COMMIT;

-- Post-migration checks:
-- SELECT count(*) AS invalid_key_count
--   FROM repair_case_image AS image
--   JOIN repair_case AS repair ON repair.case_id = image.case_id
--  WHERE image.storage_key !~
--        '^repair-cases/(AIHUB_AS|AIHUB_SC)/(as|sc)-[0-9]+/[0-9]+/original[.]jpg$'
--     OR image.storage_key <> format(
--        'repair-cases/%s/%s/%s/original.jpg',
--        repair.source,
--        repair.external_ref,
--        substring(image.source_image_ref FROM '([0-9]+)_[^/]+$')
--     );
-- SELECT storage_key, count(*)
--   FROM repair_case_image
--  GROUP BY storage_key
-- HAVING count(*) > 1;
