# v2 YOLO 견적 참조 사례 계약

`ENABLE_YOLO_ESTIMATE_REFERENCES=true`인 v2 DAMAGE strict/PAIRED 검색에서만
견적 계산용 사례를 별도로 선정한다.

- `referencedCaseIds`: 화면에 표시하는 기존 YOLO rerank Top-10 유사 사례
- `estimateReferencedCaseIds`: 동일 vector pool Top-200에서 query partCode와 corpus
  primary PAIRED YOLO partCode가 일치하고, 기존 `EstimateService` FULL_REPAIR 정책을
  통과한 사례(최대 30개, 최소 5개 미만이면 빈 배열)
- 두 목록은 다를 수 있다. `estimateReferenceVisibleCaseCount`는 두 목록의 교집합이다.
- `estimateReferenceReason`는 `FEATURE_DISABLED`, `PIPELINE_NOT_V2`,
  `QUERY_PART_UNRESOLVED`, `INSUFFICIENT_FULL_REPAIR_CASES`,
  `YOLO_PART_MATCHED_FULL_REPAIR_POOL` 중 하나다.

견적 참조 사례는 화면 유사 사례와 별도의 근거이며, repair hint와 PART_PRICE-only
행은 사용하지 않는다. v1과 flag-off 경로는 기존 `referencedCaseIds` 기반 동작을 유지한다.
