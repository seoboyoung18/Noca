"""Read-only evaluation of the separate v2 estimate reference list.

The full coverage audit already ran canonical query YOLO, v2 vector Top-100,
corpus YOLO matching, and the existing FULL_REPAIR cost policy. This job derives
the old Top-10 versus the proposed ``estimateReferencedCaseIds`` comparison
without rerunning or writing anything.
"""
from __future__ import annotations

import argparse
import html
import json
import os
import statistics
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-json", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    args = parser.parse_args()
    if args.top_k < 1:
        parser.error("top-k must be positive")
    return args


def percentiles(values: list[int]) -> dict[str, int]:
    if not values:
        return {}
    if len(values) == 1:
        return {"p25": values[0], "median": values[0], "p75": values[0]}
    q1, _, q3 = statistics.quantiles(values, n=4, method="inclusive")
    return {"p25": round(q1), "median": round(statistics.median(values)), "p75": round(q3)}


def evaluate_row(row: dict[str, Any], top_k: int) -> dict[str, Any]:
    paired = row.get("pairStatus") == "PAIRED" and bool(row.get("partCode"))
    candidates = list(row.get("validWorkCostCases") or [])[:10]
    totals = [int(candidate["total"]) for candidate in candidates]
    visible_ids = {int(item["caseId"]) for item in (row.get("yoloTop10") or [])[:top_k]}
    estimate_ids = [int(candidate["caseId"]) for candidate in candidates] if len(candidates) >= 3 else []
    return {
        "queryId": row.get("queryId"), "caseId": row.get("caseId"),
        "sourceImageRef": row.get("sourceImageRef"), "partCode": row.get("partCode"),
        "damageType": row.get("damageType"), "pairStatus": row.get("pairStatus"),
        "oldTop10FullRepairEstimable": bool(row.get("validWorkCostYoloTop10Count", 0) >= 3),
        "newEstimateReferencedCaseIds": estimate_ids,
        "newFullRepairEstimable": bool(paired and len(estimate_ids) >= 3),
        "estimateReferenceCandidateCount": len(estimate_ids),
        "estimateReferenceVisibleCaseCount": len(set(estimate_ids) & visible_ids),
        "costDistribution": percentiles(totals) if len(estimate_ids) >= 3 else {},
        "referenceCandidates": candidates if len(estimate_ids) >= 3 else [],
        "oldTop10CostCaseCount": int(row.get("validWorkCostYoloTop10Count", 0)),
        "paired": paired,
    }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    paired = [row for row in rows if row["paired"]]
    by_part: dict[str, dict[str, int]] = {}
    for row in paired:
        part = str(row.get("partCode") or "-")
        data = by_part.setdefault(part, {"queries": 0, "oldEstimable": 0, "newEstimable": 0, "increase": 0})
        data["queries"] += 1
        data["oldEstimable"] += int(row["oldTop10FullRepairEstimable"])
        data["newEstimable"] += int(row["newFullRepairEstimable"])
        data["increase"] += int(row["newFullRepairEstimable"] and not row["oldTop10FullRepairEstimable"])
    old_count = sum(row["oldTop10FullRepairEstimable"] for row in paired)
    new_count = sum(row["newFullRepairEstimable"] for row in paired)
    return {
        "queryCount": len(rows), "pairedQueryCount": len(paired),
        "oldTop10FullRepairEstimableQueries": old_count,
        "newEstimateReferencedFullRepairEstimableQueries": new_count,
        "increase": new_count - old_count,
        "byPart": by_part,
    }


def render_html(rows: list[dict[str, Any]], summary: dict[str, Any]) -> str:
    part_rows = "".join(
        f"<tr><td>{html.escape(part)}</td><td>{data['queries']}</td><td>{data['oldEstimable']}</td><td>{data['newEstimable']}</td><td>{data['increase']}</td></tr>"
        for part, data in sorted(summary["byPart"].items())
    )
    query_rows = "".join(
        f"<tr><td>{html.escape(str(row.get('caseId')))}</td><td>{html.escape(str(row.get('partCode') or '-'))}</td><td>{html.escape(str(row.get('damageType') or '-'))}</td>"
        f"<td>{row['oldTop10FullRepairEstimable']}</td><td>{row['newFullRepairEstimable']}</td><td>{row['estimateReferenceCandidateCount']}</td>"
        f"<td>{row['estimateReferenceVisibleCaseCount']}</td><td>{html.escape(json.dumps(row['costDistribution'], ensure_ascii=False))}</td></tr>"
        for row in rows if row["paired"]
    )
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>YOLO estimate reference integration evaluation</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}.summary,.section{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;padding:10px 14px;border-radius:8px}}.stat b{{font-size:22px;display:block}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:7px;text-align:left}}th{{background:#f1f4fa}}.notice{{background:#fff7ed;color:#92400e;padding:10px;border-radius:8px}}
</style><main><h1>v2 YOLO 견적 참조 사례 분리 평가</h1><p class="notice">화면 검색 Top-10과 별도 Top-100 견적 참조 목록의 FULL_REPAIR 표본 확보 가능성을 비교했습니다. repair hint와 DB write는 사용하지 않았습니다.</p>
<div class="summary"><h2>전체 요약</h2><div class="stats"><div class="stat"><b>{summary['queryCount']}</b>전체 query</div><div class="stat"><b>{summary['pairedQueryCount']}</b>PAIRED query</div><div class="stat"><b>{summary['oldTop10FullRepairEstimableQueries']}</b>기존 Top-10 estimable</div><div class="stat"><b>{summary['newEstimateReferencedFullRepairEstimableQueries']}</b>새 estimate refs estimable</div><div class="stat"><b>{summary['increase']:+d}</b>증가</div></div></div>
<section class="section"><h2>부품별 변화</h2><table><tr><th>partCode</th><th>query</th><th>기존 Top-10</th><th>새 estimate refs</th><th>증가</th></tr>{part_rows}</table></section>
<section class="section"><h2>query별 참조 case / 비용 분포</h2><table><tr><th>case</th><th>part</th><th>damage</th><th>기존 estimable</th><th>새 estimable</th><th>참조 case 수</th><th>Top-10 교집합</th><th>p25/median/p75</th></tr>{query_rows}</table></section></main></html>'''


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    # The evaluation itself consumes the completed audit artifact, but keep the
    # execution contract explicitly read-only against the configured local DB.
    import psycopg
    with psycopg.connect(dsn, connect_timeout=2) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            if cursor.fetchone() != (1,):
                raise RuntimeError("read-only database probe failed")
    payload = json.loads(args.audit_json.read_text(encoding="utf-8"))
    audits = payload.get("audits")
    if not isinstance(audits, list):
        raise ValueError("audit JSON has no audits")
    rows = [evaluate_row(row, args.top_k) for row in audits]
    summary = summarize(rows)
    result = {"status": "SUCCEEDED", "topK": args.top_k, "summary": summary, "queries": rows}
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.write_text(render_html(rows, summary), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", **summary,
                      "outputJson": str(args.output_json), "outputHtml": str(args.output_html)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
