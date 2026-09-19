"""평가용 query manifest 를 DB 에서 만든다.

기존 `outputs/ab_eval_2026-09-17/query_manifest.csv` 는 2.Validation 이미지 기준이라
`VALIDATION_ONLY` case 를 가리킨다. 그 case 들은 corpus(TRAIN_ONLY 39,676) 에 없으므로
같은 case 의 실제 비용을 DB 에서 정답으로 읽을 수 없다.

이 job 은 corpus 안에 있고 **같은 부품의 유효 FULL_REPAIR 비용을 가진** case 의 DAMAGE
이미지를 골라 manifest 로 쓴다. 평가에서는 `exclude_case_id` 로 본인을 빼므로
leave-one-case-out 이 성립한다.

    python pipeline/jobs/evaluation/build_eval_query_manifest.py \
      --part-codes FRONT_BUMPER,REAR_BUMPER --per-part 250 \
      --output-csv outputs/ab_eval_2026-09-20/eval_query_manifest.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import os
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-csv", type=Path, required=True)
    parser.add_argument("--part-codes", default="FRONT_BUMPER,REAR_BUMPER",
                        help="쉼표 구분. 비우면 부품 제한 없이 표본을 뽑는다")
    parser.add_argument("--per-part", type=int, default=250)
    parser.add_argument("--pipeline-version-id", type=int, default=2,
                        help="query ROI 가 존재하는 feature pipeline")
    parser.add_argument("--seed", default="a307-refcount-eval-v1")
    return parser.parse_args()


SQL = """
WITH scoped AS (
    SELECT c.case_id, c.external_ref, i.source_image_ref, i.image_type,
           row_number() OVER (
               PARTITION BY c.case_id
               ORDER BY md5(%(seed)s || i.source_image_ref)
           ) AS image_rank
      FROM repair_case c
      JOIN repair_case_image i ON i.case_id = c.case_id
     WHERE i.image_type = 'DAMAGE'
       AND EXISTS (
             SELECT 1 FROM repair_case_damage_feature f
              WHERE f.case_image_id = i.case_image_id
                AND f.pipeline_version_id = %(pipeline_version_id)s
                AND f.is_searchable
           )
       AND EXISTS (
             SELECT 1 FROM repair_case_item it
              WHERE it.case_id = c.case_id
                AND it.part_code = %(part_code)s
                AND it.line_type IN ('WORK', 'PART_PRICE')
                AND (it.assessment_status IS NULL OR it.assessment_status <> 'NOT_APPROVED')
           )
)
SELECT case_id, external_ref, source_image_ref, image_type
  FROM scoped
 WHERE image_rank = 1
 ORDER BY md5(%(seed)s || external_ref)
 LIMIT %(limit)s
"""


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    import psycopg

    part_codes = [code.strip() for code in args.part_codes.split(",") if code.strip()]
    rows: list[dict[str, str]] = []
    seen: set[str] = set()
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            for part_code in part_codes:
                cur.execute(SQL, {"seed": args.seed, "part_code": part_code,
                                  "pipeline_version_id": args.pipeline_version_id,
                                  "limit": args.per_part})
                for _case_id, external_ref, source_image_ref, image_type in cur.fetchall():
                    if external_ref in seen:
                        continue
                    seen.add(external_ref)
                    rows.append({
                        "case_id": external_ref,
                        "image_type": image_type,
                        "source_image_ref": source_image_ref,
                        "label_ref": "",
                        "image_file_name": Path(str(source_image_ref)).name,
                        "target_part_code": part_code,
                    })

    args.output_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.output_csv.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "case_id", "image_type", "source_image_ref", "label_ref",
            "image_file_name", "target_part_code"])
        writer.writeheader()
        writer.writerows(rows)

    print(json.dumps({"status": "SUCCEEDED", "rowCount": len(rows),
                      "partCodes": part_codes, "perPart": args.per_part,
                      "outputCsv": str(args.output_csv)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
