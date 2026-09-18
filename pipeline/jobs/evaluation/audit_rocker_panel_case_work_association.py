"""Read-only audit of rocker-panel PART_PRICE to sibling WORK associations."""
from __future__ import annotations

import argparse
import html
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

DEFAULT_PART_CODES = "ROCKER_PANEL_L,ROCKER_PANEL_R"
ROCKER_TERMS = ("사이드실", "사이드 실", "실아우터", "실 아우터", "로커", "록커", "rocker", "side sill", "rocker panel")
CONFLICT_CODES = {
    "FRONT_BUMPER", "REAR_BUMPER", "FRONT_DOOR", "REAR_DOOR",
    "FRONT_FENDER", "REAR_FENDER", "BONNET", "TRUNK_LID", "FRONT_WHEEL_L",
    "FRONT_WHEEL_R", "REAR_WHEEL_L", "REAR_WHEEL_R", "HEAD_LIGHT_L", "HEAD_LIGHT_R",
}
CONFLICT_TERMS = ("범퍼", "bumper", "도어", "door", "펜더", "fender", "본넷", "bonnet", "트렁크", "wheel", "휠", "헤드램프", "헤드라이트")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--part-codes", default=DEFAULT_PART_CODES)
    parser.add_argument("--case-limit", type=int, default=None)
    args = parser.parse_args()
    args.part_codes = [value.strip() for value in args.part_codes.split(",") if value.strip()]
    if not args.part_codes:
        parser.error("part-codes must not be empty")
    if args.case_limit is not None and args.case_limit < 1:
        parser.error("case-limit must be positive")
    return args


def text(row: dict[str, Any]) -> str:
    return str(row.get("raw_item_name") or "").strip()


def side_of(value: str) -> str | None:
    if re.search(r"(^|[^a-z])(left|lhs|l)([^a-z]|$)|좌측|좌|운전석", value, re.I):
        return "L"
    if re.search(r"(^|[^a-z])(right|rhs|r)([^a-z]|$)|우측|우|조수석", value, re.I):
        return "R"
    return None


def has_rocker_term(value: str) -> bool:
    lowered = value.casefold()
    return any(term.casefold() in lowered for term in ROCKER_TERMS)


def classify(target_code: str, work_rows: list[dict[str, Any]]) -> tuple[str, list[str]]:
    target_side = target_code.rsplit("_", 1)[-1]
    direct, possible, conflict = [], [], []
    for row in work_rows:
        code = str(row.get("part_code") or "").strip()
        raw = text(row)
        row_side = side_of(raw)
        if code == target_code:
            direct.append("canonical part_code exact")
        elif has_rocker_term(raw) and row_side == target_side:
            direct.append("rocker raw name + explicit side")
        elif has_rocker_term(raw):
            possible.append("rocker raw name without unambiguous side")
        elif code in CONFLICT_CODES or any(term.casefold() in raw.casefold() for term in CONFLICT_TERMS):
            conflict.append("different canonical part/raw name")
    if direct:
        return "DIRECT_MATCH", sorted(set(direct))
    if possible:
        return "POSSIBLE_MATCH", sorted(set(possible))
    if conflict:
        return "CONFLICT", sorted(set(conflict))
    return "NO_WORK", ["no related WORK evidence"]


def fetch_rows(dsn: str, part_codes: list[str], case_limit: int | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    import psycopg
    from psycopg.rows import dict_row
    target_sql = """
    SELECT rci.*, rc.source, rc.external_ref, ar.source_file AS raw_source_file
      FROM repair_case_item rci
      JOIN repair_case rc ON rc.case_id=rci.case_id
      LEFT JOIN aihub_estimate_raw ar ON ar.source=rc.source AND ar.external_ref=rc.external_ref
     WHERE rci.part_code = ANY(%s) AND rci.line_type='PART_PRICE'
     ORDER BY rci.case_id, rci.case_item_id
    """
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            cur.execute(target_sql, (part_codes,)); targets = [dict(row) for row in cur.fetchall()]
            case_ids = sorted({int(row["case_id"]) for row in targets})
            if case_limit is not None:
                case_ids = case_ids[:case_limit]
            targets = [row for row in targets if int(row["case_id"]) in set(case_ids)]
            if not case_ids:
                return [], []
            cur.execute("""
                SELECT rci.*, rc.source, rc.external_ref, ar.source_file AS raw_source_file
                  FROM repair_case_item rci
                  JOIN repair_case rc ON rc.case_id=rci.case_id
                  LEFT JOIN aihub_estimate_raw ar ON ar.source=rc.source AND ar.external_ref=rc.external_ref
                 WHERE rci.case_id = ANY(%s)
                 ORDER BY rci.case_id, rci.case_item_id
            """, (case_ids,))
            siblings = [dict(row) for row in cur.fetchall()]
    return targets, siblings


def compact_row(row: dict[str, Any]) -> dict[str, Any]:
    fields = ("case_item_id", "case_id", "source", "external_ref", "source_item_key", "part_code", "raw_item_name", "line_type", "work_code", "assessment_status", "part_cost", "labor_cost", "paint_material_cost", "post_adjustment_part_cost", "post_adjustment_labor_cost", "item_total", "raw_source_file")
    return {field: row.get(field) for field in fields}


def build_result(targets: list[dict[str, Any]], siblings: list[dict[str, Any]]) -> dict[str, Any]:
    by_case: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in siblings:
        by_case[int(row["case_id"])].append(row)
    records = []
    for target in targets:
        case_rows = by_case[int(target["case_id"])]
        work_rows = [row for row in case_rows if row.get("line_type") == "WORK"]
        other_parts = [row for row in case_rows if row.get("line_type") == "PART_PRICE" and row["case_item_id"] != target["case_item_id"]]
        status, reasons = classify(str(target["part_code"]), work_rows) if work_rows else ("NO_WORK", ["same case has no WORK row"])
        records.append({"target": compact_row(target), "classification": status, "evidence": reasons, "sibling_work": [compact_row(row) for row in work_rows], "sibling_part_price": [compact_row(row) for row in other_parts]})
    case_summary = {}
    for record in records:
        case_id = str(record["target"]["case_id"]); current = case_summary.setdefault(case_id, {"case_id": int(case_id), "classifications": [], "direct_match": False})
        current["classifications"].append(record["classification"]); current["direct_match"] |= record["classification"] == "DIRECT_MATCH"
    counts = {name: sum(record["classification"] == name for record in records) for name in ("DIRECT_MATCH", "POSSIBLE_MATCH", "CONFLICT", "NO_WORK")}
    return {"part_codes": sorted({str(record["target"]["part_code"]) for record in records}), "target_part_price_rows": len(records), "target_case_count": len(case_summary), "classification_counts": counts, "direct_match_case_count": sum(item["direct_match"] for item in case_summary.values()), "case_summaries": list(case_summary.values()), "records": records}


def value(value: Any) -> str:
    return "-" if value is None else str(value)


def row_table(rows: list[dict[str, Any]], title: str) -> str:
    if not rows:
        return f"<h4>{title}</h4><p class=empty>없음</p>"
    body = "".join(f'<tr><td>{value(row.get("case_item_id"))}</td><td>{html.escape(value(row.get("part_code")))}</td><td>{html.escape(value(row.get("raw_item_name")))}</td><td>{html.escape(value(row.get("line_type")))}</td><td>{html.escape(value(row.get("work_code")))}</td><td>{html.escape(value(row.get("assessment_status")))}</td><td>{value(row.get("part_cost"))}</td><td>{value(row.get("labor_cost"))}</td><td>{value(row.get("paint_material_cost"))}</td></tr>' for row in rows)
    return f'<h4>{title}</h4><table><tr><th>item id</th><th>part_code</th><th>raw_item_name</th><th>line_type</th><th>work_code</th><th>status</th><th>part</th><th>labor</th><th>paint</th></tr>{body}</table>'


def render_html(result: dict[str, Any]) -> str:
    count = result["classification_counts"]
    sections = []
    for case in result["case_summaries"]:
        records = [record for record in result["records"] if int(record["target"]["case_id"]) == case["case_id"]]
        blocks = []
        for record in records:
            target = record["target"]; cls = record["classification"]
            blocks.append(f'<article class="{cls.lower()}"><h3>{html.escape(str(target.get("part_code")))} · {html.escape(str(target.get("raw_item_name") or "-"))} · <b>{cls}</b></h3><p>source_item_key: {html.escape(value(target.get("source_item_key")))} · source: {html.escape(value(target.get("source")))} · raw source: {html.escape(value(target.get("raw_source_file")))}</p>{row_table([target], "PART_PRICE")}{row_table(record["sibling_work"], "sibling WORK")}{row_table(record["sibling_part_price"], "sibling PART_PRICE") }<p>근거: {html.escape("; ".join(record["evidence"]))}</p><label>사람 검토 메모 (승인 가능 / 보류 / 불가)<textarea rows="3"></textarea></label></article>')
        sections.append(f'<details open><summary>case {case["case_id"]} · {", ".join(case["classifications"])} · direct={"YES" if case["direct_match"] else "NO"}</summary>{"".join(blocks)}</details>')
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>rocker panel case work association audit</title><style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section,details,article{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:14px;margin:14px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;padding:10px;border-radius:8px}}.stat b{{display:block;font-size:22px}}table{{width:100%;border-collapse:collapse;font-size:12px;margin:8px 0 14px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left}}th{{background:#eef2f7}}article.direct_match{{border-left:5px solid #16a34a}}article.possible_match{{border-left:5px solid #f59e0b}}article.conflict{{border-left:5px solid #dc2626}}article.no_work{{border-left:5px solid #94a3b8}}textarea{{display:block;width:100%;box-sizing:border-box;margin-top:5px}}summary{{cursor:pointer;font-weight:700}}.notice{{background:#fff7ed;padding:12px;border-radius:8px}}</style><main><h1>ROCKER_PANEL PART_PRICE ↔ sibling WORK audit</h1><p class=notice>이 보고서는 연결 가능성 검토용이며 DB 변경이나 견적 총액 산출이 아닙니다. DIRECT_MATCH만 사람 검토 후 정책 반영 후보입니다.</p><section><h2>전체 요약</h2><div class=stats><div class=stat><b>{result["target_part_price_rows"]}</b>대상 PART_PRICE 행</div><div class=stat><b>{result["target_case_count"]}</b>대상 case</div><div class=stat><b>{count["DIRECT_MATCH"]}</b>DIRECT_MATCH</div><div class=stat><b>{count["POSSIBLE_MATCH"]}</b>POSSIBLE_MATCH</div><div class=stat><b>{count["CONFLICT"]}</b>CONFLICT</div><div class=stat><b>{count["NO_WORK"]}</b>NO_WORK</div><div class=stat><b>{result["direct_match_case_count"]}</b>direct case</div></div></section>{"".join(sections)}</main></html>'''


def main() -> None:
    args = parse_args(); dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    targets, siblings = fetch_rows(dsn, args.part_codes, args.case_limit)
    result = build_result(targets, siblings)
    args.output_json.parent.mkdir(parents=True, exist_ok=True); args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True); args.output_html.write_text(render_html(result), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "target_cases": result["target_case_count"], "target_rows": result["target_part_price_rows"], **result["classification_counts"], "output_json": str(args.output_json), "output_html": str(args.output_html)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
