"""Read-only full audit of repair-item part-code and valid-cost coverage."""
from __future__ import annotations

import argparse
import html
import json
import logging
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.services.estimate_service import _aggregate_case, _is_included_row
from shared.vision.catalog import PARTS

logging.getLogger("AI.server.app.services.estimate_service").setLevel(logging.ERROR)

FOCUS_PARTS = (
    "ROCKER_PANEL_L", "ROCKER_PANEL_R", "FRONT_BUMPER", "REAR_BUMPER",
    "HEAD_LIGHT_L", "HEAD_LIGHT_R", "FRONT_WHEEL_L", "FRONT_WHEEL_R",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    return parser.parse_args()


def norm(value: str) -> str:
    return re.sub(r"[^a-z0-9가-힣]", "", value.casefold())


def build_proposal(raw: str | None) -> tuple[str | None, str, str, bool]:
    """Return canonical suggestion, evidence, confidence, auto-apply flag.

    Only an explicit side or an exact canonical synonym is HIGH.  Generic rocker,
    lamp, wheel, door and fender names intentionally remain ambiguous.
    """
    if not raw or not raw.strip():
        return None, "raw_item_name 없음", "LOW", False
    text = raw.strip(); key = norm(text)
    exact = {norm(code): code for code in PARTS}
    exact.update({norm(values[0]): code for code, values in PARTS.items()})
    exact.update({norm(values[1]): code for code, values in PARTS.items()})
    if key in exact:
        code = exact[key]
        return code, "canonical code/name exact match", "HIGH", True
    side = None
    if re.search(r"(^|[^a-z])(left|l|lhs)([^a-z]|$)|좌측|좌|운전석", text, re.I):
        side = "L"
    if re.search(r"(^|[^a-z])(right|r|rhs)([^a-z]|$)|우측|우|조수석", text, re.I):
        if side:
            return None, "좌우 표기가 동시에 존재", "LOW", False
        side = "R"
    base = None
    if any(token in key for token in ("rockerpanel", "로커패널", "사이드실")):
        base = "ROCKER_PANEL"
    elif any(token in key for token in ("frontbumper", "앞범퍼", "전범퍼")):
        base = "FRONT_BUMPER"
    elif any(token in key for token in ("rearbumper", "뒤범퍼", "리어범퍼")):
        base = "REAR_BUMPER"
    elif any(token in key for token in ("headlight", "headlamp", "헤드라이트", "헤드램프")):
        base = "HEAD_LIGHT"
    elif any(token in key for token in ("frontwheel", "앞휠", "전륜")):
        base = "FRONT_WHEEL"
    elif any(token in key for token in ("rearwheel", "뒤휠", "후륜")):
        base = "REAR_WHEEL"
    if base and side:
        code = f"{base}_{side}"
        if code in PARTS:
            return code, "부품명과 좌우 표기가 명시됨", "HIGH", True
    if base:
        return None, "부품명은 식별되지만 좌우가 불명확", "LOW", False
    return None, "canonical part synonym을 확인할 수 없음", "LOW", False


def fetch_rows(dsn: str) -> tuple[list[dict[str, Any]], list[str]]:
    import psycopg
    from psycopg.rows import dict_row
    sql = """
    SELECT rci.case_item_id, rci.case_id, rc.source, rci.part_code,
           rci.raw_item_name, rci.line_type, rci.work_code,
           rci.assessment_status, rci.part_cost, rci.paint_material_cost,
           rci.labor_cost, rci.post_adjustment_part_cost,
           rci.post_adjustment_labor_cost
      FROM repair_case_item rci
      JOIN repair_case rc ON rc.case_id=rci.case_id
     ORDER BY rci.case_item_id
    """
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            cur.execute(sql)
            rows = [dict(row) for row in cur.fetchall()]
            cur.execute("SELECT part_code FROM part_code ORDER BY part_code")
            parts = [str(row["part_code"]) for row in cur.fetchall()]
    return rows, parts


def audit(rows: list[dict[str, Any]], canonical_parts: list[str]) -> dict[str, Any]:
    groups: dict[tuple[int, str], list[Any]] = defaultdict(list)
    for row in rows:
        if row.get("part_code"):
            groups[(int(row["case_id"]), str(row["part_code"]))].append(row)
    valid_groups: dict[tuple[int, str], Any] = {}
    for key, group in groups.items():
        included = [row_obj(row) for row in group if _is_included_row(row_obj(row))]
        case_cost = _aggregate_case(key[0], key[1], included)
        if case_cost is not None:
            valid_groups[key] = case_cost

    by_part: dict[str, list[dict[str, Any]]] = defaultdict(list)
    raw_counter = Counter(); unmapped_counter = Counter(); plan_counter: dict[str, dict[str, Any]] = {}
    rocker_counter = Counter()
    for row in rows:
        part = str(row["part_code"]) if row.get("part_code") else "NULL"
        by_part[part].append(row)
        if str(row.get("part_code") or "") in {"ROCKER_PANEL_L", "ROCKER_PANEL_R"} and str(row.get("raw_item_name") or "").strip():
            rocker_counter[str(row["raw_item_name"]).strip()] += 1
        raw = (str(row.get("raw_item_name") or "")).strip()
        if raw:
            raw_counter[raw] += 1
            if not row.get("part_code"):
                unmapped_counter[raw] += 1
                proposal, evidence, confidence, auto = build_proposal(raw)
                entry = plan_counter.setdefault(raw, {"raw_item_name": raw, "current_part_code": None, "proposed_part_code": proposal, "evidence": evidence, "confidence": confidence, "auto_apply": auto, "row_count": 0, "case_ids": set()})
                entry["row_count"] += 1; entry["case_ids"].add(int(row["case_id"]))
                # any ambiguity downgrades the aggregate plan
                if entry["proposed_part_code"] != proposal:
                    entry["proposed_part_code"] = None; entry["evidence"] = "동일 raw_item_name에 상충하는 해석"; entry["confidence"] = "LOW"; entry["auto_apply"] = False

    part_stats: dict[str, Any] = {}
    all_parts = sorted(set(canonical_parts) | {part for part in by_part if part != "NULL"})
    for part in all_parts + (["NULL"] if any(row.get("part_code") is None for row in rows) else []):
        part_rows = by_part.get(part, [])
        included_rows = [row for row in part_rows if _is_included_row(row_obj(row))]
        valid_cases = {case_id for case_id, code in valid_groups if code == part}
        part_stats[part] = {
            "total_rows": len(part_rows),
            "part_code_null_rows": sum(row.get("part_code") is None for row in part_rows),
            "raw_name_present_part_null_rows": sum(row.get("part_code") is None and bool(str(row.get("raw_item_name") or "").strip()) for row in part_rows),
            "valid_cost_rows": sum(1 for row in included_rows if (int(row["case_id"]), part) in valid_groups),
            "valid_cost_cases": len(valid_cases),
            "work_rows": sum(row.get("line_type") == "WORK" for row in part_rows),
            "part_price_rows": sum(row.get("line_type") == "PART_PRICE" for row in part_rows),
            "excluded_rows": len(part_rows) - len(included_rows),
            "source_counts": dict(Counter(str(row.get("source")) for row in part_rows)),
        }
    zero_valid = [part for part in canonical_parts if part_stats.get(part, {}).get("valid_cost_cases", 0) == 0]
    plan = []
    for entry in sorted(plan_counter.values(), key=lambda item: (-item["row_count"], item["raw_item_name"])):
        entry = dict(entry); entry["case_count"] = len(entry.pop("case_ids")); plan.append(entry)
    return {"row_count": len(rows), "part_stats": part_stats, "zero_valid_cost_part_codes": zero_valid, "raw_item_name_top": [{"raw_item_name": name, "count": count} for name, count in raw_counter.most_common(100)], "unmapped_raw_item_name_top": [{"raw_item_name": name, "count": count} for name, count in unmapped_counter.most_common(100)], "rocker_raw_top": [{"raw_item_name": name, "count": count} for name, count in rocker_counter.most_common(10)], "backfill_plan": plan, "focus_parts": {part: part_stats.get(part, {"total_rows": 0, "valid_cost_cases": 0}) for part in FOCUS_PARTS}}


def row_obj(row: dict[str, Any]):
    from AI.server.app.infrastructure.cost_repository import CostCaseRow
    return CostCaseRow(case_id=int(row["case_id"]), source=str(row["source"]), part_code=str(row.get("part_code") or ""), line_type=str(row["line_type"]), work_code=row.get("work_code"), assessment_status=row.get("assessment_status"), part_cost=row.get("part_cost"), paint_material_cost=row.get("paint_material_cost"), labor_cost=row.get("labor_cost"), post_adjustment_part_cost=row.get("post_adjustment_part_cost"), post_adjustment_labor_cost=row.get("post_adjustment_labor_cost"))


def render_html(result: dict[str, Any]) -> str:
    focus = "".join(f'<tr><td>{part}</td><td>{result["focus_parts"][part].get("total_rows",0)}</td><td>{result["focus_parts"][part].get("valid_cost_cases",0)}</td><td>{result["focus_parts"][part].get("valid_cost_rows",0)}</td><td>{result["focus_parts"][part].get("part_code_null_rows",0)}</td></tr>' for part in FOCUS_PARTS)
    stats = "".join(f'<tr><td>{html.escape(part)}</td><td>{data["total_rows"]}</td><td>{data["part_code_null_rows"]}</td><td>{data["raw_name_present_part_null_rows"]}</td><td>{data["valid_cost_rows"]}</td><td>{data["valid_cost_cases"]}</td><td>{data["work_rows"]}</td><td>{data["part_price_rows"]}</td><td>{data["excluded_rows"]}</td><td>{html.escape(str(data["source_counts"]))}</td></tr>' for part, data in result["part_stats"].items())
    unmapped = "".join(f'<tr><td>{html.escape(row["raw_item_name"])}</td><td>{row["count"]}</td></tr>' for row in result["unmapped_raw_item_name_top"][:50])
    rocker = "".join(f'<tr><td>{html.escape(row["raw_item_name"])}</td><td>{row["count"]}</td></tr>' for row in result["rocker_raw_top"])
    plan = "".join(f'<tr><td>{html.escape(row["raw_item_name"])}</td><td>{html.escape(str(row["proposed_part_code"] or "-"))}</td><td>{html.escape(row["evidence"])}</td><td>{row["row_count"]}</td><td>{row["case_count"]}</td><td>{row["confidence"]}</td><td>{"YES" if row["auto_apply"] else "NO"}</td></tr>' for row in result["backfill_plan"][:100])
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>repair_case_item part cost coverage audit</title><style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:14px;margin:18px 0}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left}}th{{background:#eef2f7}}.warn{{background:#fff7ed}}</style><main><h1>repair_case_item part/cost coverage audit</h1><p>전체 행 {result["row_count"]}개. 비용 유효성은 기존 EstimateService 정책을 재사용했습니다. repair hint·YOLO·DB write는 사용하지 않았습니다.</p><section><h2>주요 부품</h2><table><tr><th>partCode</th><th>rows</th><th>valid cases</th><th>valid rows</th><th>NULL part rows</th></tr>{focus}</table></section><section><h2>ROCKER_PANEL 원시 항목명 상위 10개</h2><table><tr><th>raw_item_name</th><th>count</th></tr>{rocker}</table></section><section><h2>partCode별 전체 커버리지</h2><table><tr><th>partCode</th><th>전체</th><th>NULL</th><th>raw+NULL</th><th>유효 비용 행</th><th>유효 case</th><th>WORK</th><th>PART_PRICE</th><th>제외</th><th>source</th></tr>{stats}</table></section><section class="warn"><h2>raw_item_name 매핑 미완료 상위</h2><table><tr><th>raw_item_name</th><th>count</th></tr>{unmapped}</table></section><section><h2>backfill 계획 상위</h2><table><tr><th>raw_item_name</th><th>제안 partCode</th><th>근거</th><th>행</th><th>case</th><th>confidence</th><th>자동 적용</th></tr>{plan}</table></section></main></html>'''


def main() -> None:
    args = parse_args(); dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    rows, parts = fetch_rows(dsn); result = audit(rows, parts)
    args.output_json.parent.mkdir(parents=True, exist_ok=True); args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    plan_path = args.output_json.parent / "repair_case_item_part_code_backfill_plan.json"
    plan_path.write_text(json.dumps(result["backfill_plan"], ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True); args.output_html.write_text(render_html(result), encoding="utf-8")
    high = sum(item["row_count"] for item in result["backfill_plan"] if item["confidence"] == "HIGH" and item["auto_apply"])
    print(json.dumps({"status": "SUCCEEDED", "rows": result["row_count"], "zero_valid_parts": result["zero_valid_cost_part_codes"], "raw_name_null_rows": sum(item["raw_name_present_part_null_rows"] for item in result["part_stats"].values()), "high_auto_rows": high, "rocker_top10": result["rocker_raw_top"], "output_json": str(args.output_json), "output_html": str(args.output_html), "plan_json": str(plan_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
