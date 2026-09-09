"""검색 기본 데이터 표본 적재 후 무결성 검증.

전수 적재 전에 readiness 확정 사례 중 일부를 ``repair_case``에 적재하고,
원천·검색 테이블·이미지의 연결과 재실행 멱등성을 확인한다. 이미 전수 적재된
DB에서 실행하는 경우에도 ``--before-*``로 적재 전 건수를 넣으면 같은 검사를
재현할 수 있다.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from typing import Any

CASE_RE = re.compile(r"^(?:as|sc)-\d+$", re.IGNORECASE)
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}
ALLOWED_CLASSES = ("CityCar", "Compact", "Mid-size", "Full-size")


def truth(value: Any) -> bool:
    return str(value).strip().lower() in {"true", "1", "yes"}


def source_for_case(case_id: str) -> str:
    return SOURCE_BY_PREFIX[case_id[:2].lower()]


def sample_keys(readiness_csv: Path, sample_cases: int) -> list[tuple[str, str]]:
    keys: list[tuple[str, str]] = []
    with readiness_csv.open(encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            case_id = str(row.get("case_id") or "").strip()
            if CASE_RE.fullmatch(case_id) and truth(row.get("is_final_searchable_case")):
                keys.append((source_for_case(case_id), case_id))
                if len(keys) == sample_cases:
                    break
    return keys


def scalar(cur: Any, query: str, params: tuple[Any, ...] = ()) -> int:
    cur.execute(query, params)
    return int(cur.fetchone()[0])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dsn", required=True)
    parser.add_argument("--readiness-csv", type=Path, required=True)
    parser.add_argument("--sample-cases", type=int, default=1000)
    parser.add_argument("--expected-raw-cases", type=int, default=125006)
    parser.add_argument("--before-case-count", type=int)
    parser.add_argument("--before-image-count", type=int)
    args = parser.parse_args()
    if args.sample_cases < 1:
        parser.error("--sample-cases must be positive")

    expected = sample_keys(args.readiness_csv.resolve(), args.sample_cases)
    if len(expected) != args.sample_cases:
        raise SystemExit(
            f"readiness에서 표본을 충분히 찾지 못했습니다: {len(expected)}/{args.sample_cases}"
        )

    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit('psycopg가 필요합니다: pip install "psycopg[binary]"') from exc

    checks: dict[str, int] = {}
    with psycopg.connect(args.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TEMP TABLE expected_search_sample(
                    source varchar(20) NOT NULL,
                    external_ref varchar(50) NOT NULL,
                    PRIMARY KEY (source, external_ref)
                ) ON COMMIT DROP
                """
            )
            cur.executemany(
                "INSERT INTO expected_search_sample(source, external_ref) VALUES (%s, %s)",
                expected,
            )

            checks["expected_sample_cases"] = len(expected)
            checks["raw_total"] = scalar(cur, "SELECT count(*) FROM aihub_estimate_raw")
            checks["sample_missing_case_ids"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM expected_search_sample s
                  LEFT JOIN repair_case r
                    ON r.source = s.source AND r.external_ref = s.external_ref
                 WHERE r.case_id IS NULL
                """,
            )
            checks["sample_loaded_case_ids"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM expected_search_sample s
                  JOIN repair_case r
                    ON r.source = s.source AND r.external_ref = s.external_ref
                """,
            )
            checks["sample_invalid_or_missing_car_class"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM repair_case r
                  JOIN expected_search_sample s
                    ON r.source = s.source AND r.external_ref = s.external_ref
                 WHERE r.car_class IS NULL
                    OR r.car_class NOT IN ('CityCar', 'Compact', 'Mid-size', 'Full-size')
                """,
            )
            checks["sample_cases_without_image"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM expected_search_sample s
                  JOIN repair_case r
                    ON r.source = s.source AND r.external_ref = s.external_ref
             LEFT JOIN repair_case_image i ON i.case_id = r.case_id
                 WHERE i.case_image_id IS NULL
                """,
            )
            checks["sample_image_count"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM repair_case_image i
                  JOIN repair_case r ON r.case_id = i.case_id
                  JOIN expected_search_sample s
                    ON r.source = s.source AND r.external_ref = s.external_ref
                """,
            )
            checks["duplicate_repair_case_keys"] = scalar(
                cur,
                """
                SELECT count(*) FROM (
                    SELECT source, external_ref
                      FROM repair_case
                     GROUP BY source, external_ref
                    HAVING count(*) > 1
                ) d
                """,
            )
            checks["duplicate_source_image_refs"] = scalar(
                cur,
                """
                SELECT count(*) FROM (
                    SELECT source_image_ref
                      FROM repair_case_image
                     GROUP BY source_image_ref
                    HAVING count(*) > 1
                ) d
                """,
            )
            checks["empty_source_image_refs"] = scalar(
                cur,
                """
                SELECT count(*) FROM repair_case_image
                 WHERE btrim(source_image_ref) = ''
                """,
            )
            checks["invalid_repair_case_storage_keys"] = scalar(
                cur,
                """
                SELECT count(*) FROM repair_case_image
                 WHERE storage_key !~ '^repair-cases/[0-9]+/images/[0-9]+/original\\.jpg$'
                """,
            )
            checks["raw_without_repair_case"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM aihub_estimate_raw raw
             LEFT JOIN repair_case r
                    ON r.source = raw.source AND r.external_ref = raw.external_ref
                 WHERE r.case_id IS NULL
                """,
            )
            checks["raw_expected_unlinked_by_scope"] = checks["raw_total"] - scalar(
                cur, "SELECT count(*) FROM repair_case"
            )
            checks["raw_unlinked_scope_mismatch"] = (
                checks["raw_without_repair_case"] - checks["raw_expected_unlinked_by_scope"]
            )
            checks["sample_raw_without_repair_case"] = scalar(
                cur,
                """
                SELECT count(*)
                  FROM expected_search_sample s
             LEFT JOIN aihub_estimate_raw raw
                    ON raw.source = s.source AND raw.external_ref = s.external_ref
             LEFT JOIN repair_case r
                    ON r.source = s.source AND r.external_ref = s.external_ref
                 WHERE raw.external_ref IS NULL OR r.case_id IS NULL
                """,
            )
            checks["repair_case_total"] = scalar(cur, "SELECT count(*) FROM repair_case")
            checks["repair_case_image_total"] = scalar(
                cur, "SELECT count(*) FROM repair_case_image"
            )
            if args.before_case_count is not None:
                checks["repair_case_count_delta_after_rerun"] = (
                    checks["repair_case_total"] - args.before_case_count
                )
            if args.before_image_count is not None:
                checks["repair_case_image_count_delta_after_rerun"] = (
                    checks["repair_case_image_total"] - args.before_image_count
                )

    failures = {
        "raw_total": checks["raw_total"] != args.expected_raw_cases,
        "sample_loaded_case_ids": checks["sample_loaded_case_ids"] != args.sample_cases,
        "sample_missing_case_ids": checks["sample_missing_case_ids"] != 0,
        "sample_invalid_or_missing_car_class": checks["sample_invalid_or_missing_car_class"] != 0,
        "sample_cases_without_image": checks["sample_cases_without_image"] != 0,
        "duplicate_repair_case_keys": checks["duplicate_repair_case_keys"] != 0,
        "duplicate_source_image_refs": checks["duplicate_source_image_refs"] != 0,
        "empty_source_image_refs": checks["empty_source_image_refs"] != 0,
        "invalid_repair_case_storage_keys": checks["invalid_repair_case_storage_keys"] != 0,
        # Raw 125,006건 중 검색 대상에서 제외한 사례는 repair_case와 연결되지 않는다.
        # 따라서 미연결 건수 자체가 아니라, 정책상 예상한 범위 차이와 일치하는지를 본다.
        "raw_unlinked_scope_mismatch": checks["raw_unlinked_scope_mismatch"] != 0,
        "sample_raw_without_repair_case": checks["sample_raw_without_repair_case"] != 0,
    }
    if args.before_case_count is not None:
        failures["repair_case_count_delta_after_rerun"] = (
            checks["repair_case_count_delta_after_rerun"] != 0
        )
    if args.before_image_count is not None:
        failures["repair_case_image_count_delta_after_rerun"] = (
            checks["repair_case_image_count_delta_after_rerun"] != 0
        )

    result = {"checks": checks, "failures": [name for name, failed in failures.items() if failed]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["failures"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
