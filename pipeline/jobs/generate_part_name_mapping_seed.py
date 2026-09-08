"""표준화 매핑 워크북에서 `part_name_mapping` 기준 데이터 SQL을 생성한다.

확정 매핑(`MAPPED`, `MAPPED_EXTENDED`)만 포함한다. 사람 확인이 필요하거나
범위를 벗어난 항목(`REVIEW_CONFLICT`, `MAPPED_SIDE_UNKNOWN`, `MAPPED_SPLIT`,
`OUT_OF_SCOPE_PART`, `NOT_A_PART`)은 억지로 확정하지 않고 제외한다.

실행 예시:
  python pipeline/jobs/generate_part_name_mapping_seed.py \
    --workbook "<표준화 매핑 워크북>.xlsx" \
    --output "<결과를 쓸 경로>/A307_PART_NAME_MAPPING_SEED.sql"
"""

from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd


ELIGIBLE_STATUSES = ("MAPPED", "MAPPED_EXTENDED")
EXCLUDED_STATUSES = ("MAPPED_SIDE_UNKNOWN", "MAPPED_SPLIT", "REVIEW_CONFLICT", "OUT_OF_SCOPE_PART", "NOT_A_PART")
RAW_NAME_MAX_LEN = 200
BATCH_SIZE = 1000


def pg_literal(value: str) -> str:
    """한글·원문 견적명을 그대로 담을 수 있는 PostgreSQL E-string 리터럴."""
    text = str(value)
    text = (
        text.replace("\\", "\\\\")
        .replace("'", "''")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )
    return f"E'{text}'"


def resolve_workbook(workbook: Path | None, workbook_dir: Path | None) -> Path:
    if workbook:
        if not workbook.is_file():
            raise FileNotFoundError(f"워크북을 찾을 수 없습니다: {workbook}")
        return workbook
    workbooks = sorted(workbook_dir.glob("*.xlsx"), key=lambda path: path.stat().st_mtime)
    if not workbooks:
        raise FileNotFoundError(f"워크북이 없습니다: {workbook_dir}")
    return workbooks[-1]


def main() -> None:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--workbook", type=Path, help="표준화 매핑 워크북(.xlsx)")
    source.add_argument("--workbook-dir", type=Path, help="워크북 폴더. 가장 최근 수정된 .xlsx를 쓴다")
    parser.add_argument("--output", type=Path, required=True, help="생성할 seed SQL 경로")
    args = parser.parse_args()

    workbook = resolve_workbook(args.workbook, args.workbook_dir)
    mapping = pd.read_excel(workbook, sheet_name="부품명 전체 매핑", header=3)
    required = {"기존 이름", "표준 코드", "매핑 상태"}
    missing = required - set(mapping.columns)
    if missing:
        raise ValueError(f"Missing columns: {sorted(missing)}")

    eligible = mapping[
        mapping["매핑 상태"].isin(ELIGIBLE_STATUSES)
        & mapping["표준 코드"].notna()
    ][["기존 이름", "표준 코드", "매핑 상태"]].copy()
    eligible["기존 이름"] = eligible["기존 이름"].astype(str)
    eligible["표준 코드"] = eligible["표준 코드"].astype(str)

    if eligible["기존 이름"].duplicated().any():
        raise ValueError("part_name_mapping requires unique raw names")
    if (eligible["기존 이름"].str.len() > RAW_NAME_MAX_LEN).any():
        raise ValueError(f"A raw name exceeds part_name_mapping.raw_name VARCHAR({RAW_NAME_MAX_LEN})")

    rows = list(eligible.itertuples(index=False, name=None))
    sql = [
        "-- ============================================================",
        "-- A307 · 바른견적 — part_name_mapping 기준 데이터",
        f"-- Source workbook: {workbook.name}",
        f"-- Included statuses: {', '.join(ELIGIBLE_STATUSES)}",
        f"-- Included rows: {len(rows):,}",
        f"-- Excluded: {', '.join(EXCLUDED_STATUSES)}",
        "-- ============================================================",
        "",
        "BEGIN;",
        "",
    ]

    for start in range(0, len(rows), BATCH_SIZE):
        batch = rows[start : start + BATCH_SIZE]
        sql.append("INSERT INTO part_name_mapping (raw_name, part_code)")
        sql.append("VALUES")
        values = [f"    ({pg_literal(raw_name)}, {pg_literal(part_code)})" for raw_name, part_code, _status in batch]
        sql.append(",\n".join(values))
        sql.append("ON CONFLICT (raw_name) DO UPDATE")
        sql.append("SET part_code = EXCLUDED.part_code;")
        sql.append("")

    sql.extend(["COMMIT;", ""])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(sql), encoding="utf-8", newline="\n")
    print(f"workbook={workbook}")
    print(f"rows={len(rows)}")
    print(f"output={args.output}")


if __name__ == "__main__":
    main()
