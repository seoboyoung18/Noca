"""Read-only stratification audit for v2 Top-100 FULL_REPAIR costs.

The completed canonical full-repair audit supplies the v2 case-level Top-100
pool and already excludes the query's own case.  This job re-fetches cost rows
through ``PostgresCostCaseRepository`` and applies the existing
``EstimateService`` inclusion/aggregation helpers.  It never reads repair
hints and never writes to PostgreSQL.
"""
from __future__ import annotations

import argparse
import base64
import csv
import html
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
PROJECT_ROOT = REPO_ROOT.parent
DEFAULT_AUDIT = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-18" / "v2_yolo_full_repair_coverage_audit.json"
DEFAULT_MANIFEST = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-17" / "query_manifest.csv"
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.services.estimate_service import _aggregate_case, _is_included_row, _percentiles
from pipeline.jobs.evaluation.render_query_comparison import data_url


EXCHANGE = "EXCHANGE_INCLUDED"
REPAIR = "REPAIR_FAMILY"
OTHER = "OTHER_VALID_WORK"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--audit-json", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--min-case-count", type=int, default=2)
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    if args.batch_size < 1 or args.candidate_k < args.top_k or args.top_k < 1:
        parser.error("batch-size/top-k must be positive and candidate-k >= top-k")
    if args.min_case_count < 1:
        parser.error("min-case-count must be positive")
    return args


def checkpoint_path(output: Path) -> Path:
    return output.with_suffix(output.suffix + ".checkpoint.json")


def load_checkpoint(path: Path, resume: bool) -> dict[str, Any]:
    if not resume or not path.is_file():
        return {"version": 1, "completed": {}, "manifestCount": 0}
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state.get("completed"), dict):
        raise ValueError("invalid checkpoint: completed must be an object")
    return state


def save_checkpoint(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def read_manifest_count(path: Path) -> int:
    if not path.is_file():
        return 0
    with path.open(encoding="utf-8-sig", newline="") as fp:
        return sum(1 for row in csv.DictReader(fp) if row.get("image_type", "DAMAGE") == "DAMAGE")


def resolve_image_path(root: Path, source_ref: str) -> Path:
    direct = root / source_ref
    if direct.is_file():
        return direct
    normalized = source_ref.replace("\\", "/")
    for prefix in ("01.데이터_견적서보유/", "01.데이터/"):
        if normalized.startswith(prefix):
            candidate = root / normalized[len(prefix):]
            if candidate.is_file():
                return candidate
    matches = list(root.rglob(Path(normalized).name))
    return matches[0] if matches else direct


def verify_db_read_only(dsn: str) -> None:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            if cursor.fetchone() != (1,):
                raise RuntimeError("read-only DB probe failed")


def recompute_costs(
    repository: PostgresCostCaseRepository,
    candidates: list[dict[str, Any]],
    part_code: str,
) -> dict[int, dict[str, Any]]:
    ids: list[int] = []
    seen: set[int] = set()
    for candidate in candidates:
        try:
            case_id = int(candidate["caseId"])
        except (KeyError, TypeError, ValueError):
            continue
        if case_id not in seen:
            ids.append(case_id)
            seen.add(case_id)
    rows_by_case: dict[int, list[Any]] = defaultdict(list)
    for row in repository.fetch_case_items(ids, [part_code]):
        rows_by_case[int(row.case_id)].append(row)
    result: dict[int, dict[str, Any]] = {}
    for case_id in ids:
        included = [row for row in rows_by_case.get(case_id, []) if _is_included_row(row)]
        aggregate = _aggregate_case(case_id, part_code, included)
        if aggregate is None:
            continue
        result[case_id] = {
            "total": int(aggregate.total),
            "methods": sorted(str(method) for method in aggregate.methods),
        }
    return result


def classify(methods: list[str]) -> str:
    if "exchange" in methods:
        return EXCHANGE
    if any(method in {"coating", "sheet_metal", "repair"} for method in methods):
        return REPAIR
    return OTHER


def distribution(cases: list[dict[str, Any]], min_count: int) -> dict[str, Any]:
    totals = [int(case["costTotal"]) for case in cases]
    methods = sorted({method for case in cases for method in case["methods"]})
    if len(totals) < min_count:
        return {"caseCount": len(totals), "estimable": False, "p25": None, "median": None,
                "p75": None, "methods": methods}
    p25, median, p75 = _percentiles(totals)
    return {"caseCount": len(totals), "estimable": True, "p25": p25, "median": median,
            "p75": p75, "methods": methods}


def stratify(audit: dict[str, Any], repository: PostgresCostCaseRepository, *, candidate_k: int, top_k: int,
             min_count: int) -> dict[str, Any] | None:
    if audit.get("pairStatus") != "PAIRED" or not audit.get("partCode"):
        return None
    part_code = str(audit["partCode"])
    candidates = list(audit.get("validWorkCostCases") or [])[:candidate_k]
    costs = recompute_costs(repository, candidates, part_code)
    by_id = {int(candidate["caseId"]): candidate for candidate in candidates}
    vector_top = {int(row["caseId"]) for row in (audit.get("vectorTop10") or [])[:top_k]}
    rerank_top = {int(row["caseId"]) for row in (audit.get("yoloTop10") or [])[:top_k]}
    groups: dict[str, list[dict[str, Any]]] = {EXCHANGE: [], REPAIR: [], OTHER: []}
    all_cases: list[dict[str, Any]] = []
    for case_id, cost in costs.items():
        source = by_id.get(case_id, {})
        row = {
            "caseId": case_id,
            "vectorSimilarity": source.get("similarity"),
            "sourceImageRef": source.get("sourceImageRef"),
            "methods": cost["methods"],
            "costTotal": cost["total"],
            "group": classify(cost["methods"]),
            "inVectorTop10": case_id in vector_top,
            "inRerankTop10": case_id in rerank_top,
            "corpusPartConfidence": source.get("corpusPartConfidence"),
            "corpusPartOverlap": source.get("corpusPartOverlap"),
        }
        groups[row["group"]].append(row)
        all_cases.append(row)
    for group in groups.values():
        group.sort(key=lambda row: (-(float(row.get("vectorSimilarity") or 0.0)), row["caseId"]))
    all_cases.sort(key=lambda row: (-(float(row.get("vectorSimilarity") or 0.0)), row["caseId"]))
    exchange_2 = distribution(groups[EXCHANGE], min_count)
    repair_2 = distribution(groups[REPAIR], min_count)
    exchange_3 = distribution(groups[EXCHANGE], 3)
    repair_3 = distribution(groups[REPAIR], 3)
    integrated_2 = distribution(all_cases, min_count)
    integrated_3 = distribution(all_cases, 3)
    return {
        "queryId": audit.get("queryId"), "caseId": audit.get("caseId"),
        "sourceImageRef": audit.get("sourceImageRef"), "partCode": part_code,
        "damageType": str(audit.get("damageType") or "").upper(),
        "partConfidence": audit.get("partConfidence"), "damageConfidence": audit.get("damageConfidence"),
        "pairStatus": audit.get("pairStatus"), "vectorPoolCaseCount": int(audit.get("vectorPoolCount") or 0),
        "integrated": {"min2": integrated_2, "min3": integrated_3},
        "groups": {EXCHANGE: {"min2": exchange_2, "min3": exchange_3},
                   REPAIR: {"min2": repair_2, "min3": repair_3},
                   OTHER: {"min2": distribution(groups[OTHER], min_count),
                           "min3": distribution(groups[OTHER], 3)}},
        "bothGroupsMin2": exchange_2["estimable"] and repair_2["estimable"],
        "bothGroupsMin3": exchange_3["estimable"] and repair_3["estimable"],
        "integratedButNeitherGroupMin2": integrated_2["estimable"] and not exchange_2["estimable"] and not repair_2["estimable"],
        "costOnlyOutsideRerankTop10": bool(all_cases) and not any(case["inRerankTop10"] for case in all_cases),
        "medianDifferenceMin2": (
            exchange_2["median"] - repair_2["median"]
            if exchange_2["estimable"] and repair_2["estimable"] else None
        ),
        "cases": all_cases,
    }


def summarize(rows: list[dict[str, Any]], *, manifest_count: int, candidate_k: int, top_k: int,
              min_count: int) -> dict[str, Any]:
    def count(path: str) -> int:
        return sum(bool(_get(row, path)) for row in rows)

    paired = rows
    differences = [row["medianDifferenceMin2"] for row in rows if row["medianDifferenceMin2"] is not None]
    diff_distribution = distribution([{"costTotal": value, "methods": []} for value in differences], 1)
    by_part: dict[str, dict[str, Any]] = {}
    for row in rows:
        item = by_part.setdefault(row["partCode"], {
            "queries": 0, "integratedEstimableMin2": 0, "integratedEstimableMin3": 0,
            "exchangeEstimableMin2": 0, "exchangeEstimableMin3": 0,
            "repairEstimableMin2": 0, "repairEstimableMin3": 0,
            "bothGroupsMin2": 0, "bothGroupsMin3": 0,
            "integratedButNeitherGroupMin2": 0,
        })
        item["queries"] += 1
        item["integratedEstimableMin2"] += int(row["integrated"]["min2"]["estimable"])
        item["integratedEstimableMin3"] += int(row["integrated"]["min3"]["estimable"])
        item["exchangeEstimableMin2"] += int(row["groups"][EXCHANGE]["min2"]["estimable"])
        item["exchangeEstimableMin3"] += int(row["groups"][EXCHANGE]["min3"]["estimable"])
        item["repairEstimableMin2"] += int(row["groups"][REPAIR]["min2"]["estimable"])
        item["repairEstimableMin3"] += int(row["groups"][REPAIR]["min3"]["estimable"])
        item["bothGroupsMin2"] += int(row["bothGroupsMin2"])
        item["bothGroupsMin3"] += int(row["bothGroupsMin3"])
        item["integratedButNeitherGroupMin2"] += int(row["integratedButNeitherGroupMin2"])
    group_cases = {EXCHANGE: 0, REPAIR: 0, OTHER: 0}
    for row in rows:
        for case in row["cases"]:
            group_cases[case["group"]] += 1
    return {
        "queryCount": len(rows), "manifestDamageImageCount": manifest_count,
        "pairedQueryCount": len(paired), "candidateK": candidate_k, "topK": top_k,
        "minCaseCount": min_count,
        "integratedEstimableMin2": sum(row["integrated"]["min2"]["estimable"] for row in rows),
        "integratedEstimableMin3": sum(row["integrated"]["min3"]["estimable"] for row in rows),
        "exchangeEstimableMin2": sum(row["groups"][EXCHANGE]["min2"]["estimable"] for row in rows),
        "exchangeEstimableMin3": sum(row["groups"][EXCHANGE]["min3"]["estimable"] for row in rows),
        "repairEstimableMin2": sum(row["groups"][REPAIR]["min2"]["estimable"] for row in rows),
        "repairEstimableMin3": sum(row["groups"][REPAIR]["min3"]["estimable"] for row in rows),
        "bothGroupsMin2": sum(row["bothGroupsMin2"] for row in rows),
        "bothGroupsMin3": sum(row["bothGroupsMin3"] for row in rows),
        "integratedButNeitherGroupMin2": sum(row["integratedButNeitherGroupMin2"] for row in rows),
        "costOnlyOutsideRerankTop10": sum(row["costOnlyOutsideRerankTop10"] for row in rows),
        "groupCaseCounts": group_cases,
        "medianDifferenceMin2": diff_distribution,
        "byPart": by_part,
        "reviewBothGroupsMin2": sorted(
            (row for row in rows if row["bothGroupsMin2"]),
            key=lambda row: (-row["integrated"]["min2"]["caseCount"], str(row["queryId"])),
        )[:24],
    }


def _get(row: dict[str, Any], path: str) -> Any:
    value: Any = row
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    return value


def _image(root: Path, ref: str) -> str:
    path = resolve_image_path(root, ref)
    return data_url(path) if path.is_file() else ""


def render_case_table(row: dict[str, Any], root: Path) -> str:
    rows = []
    for case in row["cases"][:20]:
        image = _image(root, str(case.get("sourceImageRef") or ""))
        top = "rerank Top-10" if case["inRerankTop10"] else "Top-10 밖"
        rows.append(
            f"<tr class='{case['group'].lower()}'><td>{case['caseId']}</td><td>{case.get('vectorSimilarity') or '-'}</td>"
            f"<td>{html.escape(case['group'])}</td><td>{html.escape(', '.join(case['methods']))}</td>"
            f"<td>{case['costTotal']:,}</td><td>{top}</td><td><img class='ref' src='{image}' alt='case {case['caseId']}'></td></tr>"
        )
    return "".join(rows) or "<tr><td colspan='7'>유효 비용 사례 없음</td></tr>"


def render_review_card(row: dict[str, Any], root: Path) -> str:
    query_image = _image(root, str(row.get("sourceImageRef") or ""))
    ex = row["groups"][EXCHANGE]["min2"]
    rp = row["groups"][REPAIR]["min2"]
    integrated = row["integrated"]["min2"]
    return f"""<article class='card'><h3>{html.escape(str(row.get('queryId')))}</h3>
<p><b>{html.escape(str(row.get('partCode')))}</b> · {html.escape(str(row.get('damageType')))} · part conf {row.get('partConfidence')} · damage conf {row.get('damageConfidence')}</p>
<img class='query' src='{query_image}' alt='query image'>
<table><tr><th>분포</th><th>case 수</th><th>p25</th><th>median</th><th>p75</th><th>methods</th></tr>
<tr><td>통합(참고)</td><td>{integrated['caseCount']}</td><td>{integrated['p25']}</td><td>{integrated['median']}</td><td>{integrated['p75']}</td><td>{html.escape(', '.join(integrated['methods']))}</td></tr>
<tr class='exchange'><td>EXCHANGE_INCLUDED</td><td>{ex['caseCount']}</td><td>{ex['p25']}</td><td>{ex['median']}</td><td>{ex['p75']}</td><td>{html.escape(', '.join(ex['methods']))}</td></tr>
<tr class='repair'><td>REPAIR_FAMILY</td><td>{rp['caseCount']}</td><td>{rp['p25']}</td><td>{rp['median']}</td><td>{rp['p75']}</td><td>{html.escape(', '.join(rp['methods']))}</td></tr></table>
<p>EXCHANGE median - REPAIR median: {row.get('medianDifferenceMin2')}</p>
<table><tr><th>case</th><th>similarity</th><th>group</th><th>methods</th><th>cost</th><th>Top-10</th><th>image</th></tr>{render_case_table(row, root)}</table>
<label>사람 검토 메모: <textarea rows='2' placeholder='두 그룹을 별도 비용 근거로 볼 수 있는지 기록'></textarea></label></article>"""


def render_html(rows: list[dict[str, Any]], summary: dict[str, Any], root: Path) -> str:
    part_rows = []
    for part, item in sorted(summary["byPart"].items()):
        part_rows.append(
            f"<tr><td>{html.escape(part)}</td><td>{item['queries']}</td><td>{item['integratedEstimableMin2']}</td><td>{item['integratedEstimableMin3']}</td>"
            f"<td>{item['exchangeEstimableMin2']}/{item['exchangeEstimableMin3']}</td><td>{item['repairEstimableMin2']}/{item['repairEstimableMin3']}</td>"
            f"<td>{item['bothGroupsMin2']}/{item['bothGroupsMin3']}</td><td>{item['integratedButNeitherGroupMin2']}</td></tr>"
        )
    cards = "".join(render_review_card(row, root) for row in summary["reviewBothGroupsMin2"]) or "<p>두 그룹이 모두 최소 표본을 만족하는 query 없음</p>"
    return f"""<!doctype html><html lang='ko'><meta charset='utf-8'><title>estimate method stratification audit</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}section,.card{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;border-radius:8px;padding:10px 14px}}.stat b{{font-size:22px;display:block}}.warning{{background:#fff7ed;color:#92400e;padding:12px;border-radius:8px}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left;vertical-align:top}}th{{background:#f1f4fa}}.exchange td,.exchange{{background:#fff0f0}}.repair td,.repair{{background:#effcf3}}.other td,.other{{background:#f5f5f5}}.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(520px,1fr));gap:12px}}.query{{width:100%;height:220px;object-fit:contain;background:#eef2f7}}.ref{{width:110px;height:75px;object-fit:cover;background:#eef2f7}}textarea{{width:100%;box-sizing:border-box;margin-top:8px}}</style>
<main><h1>v2 + YOLO Top-100 견적 작업 방식 분리 audit</h1><p class='warning'>통합 비용은 참고용이며, 서로 다른 작업 방식 비용을 합산한 값일 수 있습니다. EXCHANGE_INCLUDED와 REPAIR_FAMILY는 별도 분포로 계산했으며, DAMAGE type ↔ WORK method 하드 필터는 적용하지 않았습니다.</p>
<section><h2>전체 요약</h2><div class='stats'><div class='stat'><b>{summary['queryCount']}</b>PAIRED query</div><div class='stat'><b>{summary['integratedEstimableMin2']}</b>통합 estimable (2)</div><div class='stat'><b>{summary['integratedEstimableMin3']}</b>통합 estimable (3)</div><div class='stat'><b>{summary['exchangeEstimableMin2']}/{summary['exchangeEstimableMin3']}</b>EXCHANGE (2/3)</div><div class='stat'><b>{summary['repairEstimableMin2']}/{summary['repairEstimableMin3']}</b>REPAIR (2/3)</div><div class='stat'><b>{summary['bothGroupsMin2']}/{summary['bothGroupsMin3']}</b>두 그룹 충족 (2/3)</div><div class='stat'><b>{summary['integratedButNeitherGroupMin2']}</b>통합만 가능</div><div class='stat'><b>{summary['costOnlyOutsideRerankTop10']}</b>비용 후보 Top-10 밖</div></div></section>
<section><h2>부품별 수치</h2><table><tr><th>partCode</th><th>query</th><th>통합(2)</th><th>통합(3)</th><th>EXCHANGE(2/3)</th><th>REPAIR(2/3)</th><th>두 그룹(2/3)</th><th>통합만 가능</th></tr>{''.join(part_rows)}</table></section>
<section><h2>동일 query median 차이</h2><p>EXCHANGE_INCLUDED median − REPAIR_FAMILY median (두 그룹 모두 최소 {summary['minCaseCount']}건인 query만).</p><table><tr><th>count</th><th>p25</th><th>median</th><th>p75</th></tr><tr><td>{summary['medianDifferenceMin2']['caseCount']}</td><td>{summary['medianDifferenceMin2']['p25']}</td><td>{summary['medianDifferenceMin2']['median']}</td><td>{summary['medianDifferenceMin2']['p75']}</td></tr></table></section>
<section><h2>사람 검토용: 두 그룹 모두 최소 표본 충족 query 최대 24개</h2><div class='cards'>{cards}</div></section>
<section><h2>검토 원칙</h2><p>이 화면은 작업 방식 분리 가능성과 비용 분포를 검토하기 위한 자료입니다. 정답/오답 판정과 repair hint 기반 평가는 포함하지 않습니다.</p></section></main></html>"""


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    source = json.loads(args.audit_json.resolve().read_text(encoding="utf-8"))
    audits = list(source.get("audits") or [])
    if not audits:
        raise SystemExit("audit JSON에 audits가 없습니다")
    verify_db_read_only(dsn)
    repository = PostgresCostCaseRepository(dsn)
    state_path = checkpoint_path(args.output_json.resolve())
    state = load_checkpoint(state_path, args.resume)
    state["manifestCount"] = read_manifest_count(args.query_manifest.resolve())
    selected = audits if args.limit is None or args.resume else audits[:args.limit]
    processed = 0
    for audit in selected:
        key = str(audit.get("queryId") or audit.get("sourceImageRef"))
        if args.resume and key in state["completed"]:
            continue
        state["completed"][key] = stratify(
            audit, repository, candidate_k=args.candidate_k, top_k=args.top_k,
            min_count=args.min_case_count,
        )
        processed += 1
        if processed % args.batch_size == 0:
            save_checkpoint(state_path, state)
    save_checkpoint(state_path, state)
    rows = [row for row in state["completed"].values() if row is not None]
    summary = summarize(rows, manifest_count=state["manifestCount"], candidate_k=args.candidate_k,
                        top_k=args.top_k, min_count=args.min_case_count)
    payload = {
        "status": "SUCCEEDED", "generatedAt": datetime.now(timezone.utc).isoformat(),
        "readOnly": True, "sourceAudit": str(args.audit_json.resolve()),
        "queryManifest": str(args.query_manifest.resolve()),
        "groupDefinitions": {
            EXCHANGE: "유효 WORK에 EXCHANGE가 하나라도 있음",
            REPAIR: "EXCHANGE 없음 + COATING/SHEET_METAL/REPAIR/OVERHAUL 계열",
            OTHER: "위 두 그룹에 속하지 않는 유효 WORK",
        },
        "audits": rows, "summary": summary,
        "verification": {"databaseWrites": False, "repairHintUsed": False,
                          "migrationChanged": False, "apiFrontendChanged": False,
                          "embeddingRebuilt": False, "pipelineActivationChanged": False},
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.write_text(render_html(rows, summary, args.dataset_root.resolve()), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "processed": processed,
                      "completed": len(rows), **summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
