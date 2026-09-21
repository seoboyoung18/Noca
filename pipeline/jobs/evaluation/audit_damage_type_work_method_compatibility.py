"""Read-only audit of damage type versus FULL_REPAIR work-method compatibility.

The canonical 994-query/Top-100 corpus evidence is taken from the completed
``audit_yolo_full_repair_coverage`` artifact.  That artifact already performed
the canonical query inference, case-level vector-pool de-duplication, YOLO
primary matching, and self-case exclusion.  This job re-checks the cost rows
through the existing ``PostgresCostCaseRepository`` and
``EstimateService`` aggregation helpers, then compares the current all-method
policy with a draft damage-type compatibility policy.  No repair-hint table is
read and no database write is performed.
"""
from __future__ import annotations

import argparse
import base64
import csv
import html
import json
import mimetypes
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from io import BytesIO
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
from AI.server.app.services.estimate_service import _aggregate_case, _is_included_row
from pipeline.jobs.evaluation.render_query_comparison import data_url


METHODS = ("coating", "repair", "sheet_metal", "exchange")
DRAFT_POLICY = {
    "SCRATCHED": frozenset({"coating", "repair", "sheet_metal"}),
    "CRUSHED": frozenset({"sheet_metal", "repair", "coating"}),
    "SEPARATED": frozenset({"exchange", "repair"}),
    "BREAKAGE": frozenset({"exchange", "repair"}),
}


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
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    if args.batch_size < 1 or args.candidate_k < args.top_k or args.top_k < 1:
        parser.error("batch-size/top-k must be positive and candidate-k >= top-k")
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


def recompute_case_costs(
    repository: PostgresCostCaseRepository,
    candidates: list[dict[str, Any]],
    part_code: str,
) -> dict[int, dict[str, Any]]:
    """Re-apply EstimateService's inclusion and case aggregation policy."""
    ids = []
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
    output: dict[int, dict[str, Any]] = {}
    for case_id in ids:
        included = [row for row in rows_by_case.get(case_id, []) if _is_included_row(row)]
        aggregate = _aggregate_case(case_id, part_code, included)
        if aggregate is not None:
            output[case_id] = {
                "total": int(aggregate.total),
                "methods": sorted(str(method) for method in aggregate.methods),
            }
    return output


def analyze_audit(
    audit: dict[str, Any], repository: PostgresCostCaseRepository,
    *, top_k: int, candidate_k: int,
) -> dict[str, Any]:
    part_code = audit.get("partCode")
    damage_type = str(audit.get("damageType") or "").upper()
    paired = audit.get("pairStatus") == "PAIRED" and bool(part_code)
    pool = list(audit.get("validWorkCostCases") or [])[:candidate_k]
    recomputed = recompute_case_costs(repository, pool, str(part_code)) if paired else {}
    vector_top_ids = {int(row["caseId"]) for row in (audit.get("vectorTop10") or [])[:top_k]}
    yolo_top_ids = {int(row["caseId"]) for row in (audit.get("yoloTop10") or [])[:top_k]}
    candidate_by_id = {int(row["caseId"]): row for row in pool if str(row.get("caseId")).isdigit()}
    rows: list[dict[str, Any]] = []
    allowed = DRAFT_POLICY.get(damage_type, frozenset())
    for case_id, cost in recomputed.items():
        source = candidate_by_id.get(case_id, {})
        methods = cost["methods"]
        compatible = bool(allowed) and set(methods).issubset(allowed)
        rows.append({
            "caseId": case_id,
            "vectorSimilarity": source.get("similarity"),
            "sourceImageRef": source.get("sourceImageRef"),
            "methods": methods,
            "costTotal": cost["total"],
            "inVectorTop10": case_id in vector_top_ids,
            "inRerankTop10": case_id in yolo_top_ids,
            "draftCompatible": compatible,
            "partCode": part_code,
            "corpusPartConfidence": source.get("corpusPartConfidence"),
            "corpusPartOverlap": source.get("corpusPartOverlap"),
        })
    rows.sort(key=lambda row: (-(float(row.get("vectorSimilarity") or 0.0)), row["caseId"]))
    method_union = sorted({method for row in rows for method in row["methods"]})
    mixed_method = len(method_union) > 1
    mixed_case = any(len(row["methods"]) > 1 for row in rows)
    return {
        "queryId": audit.get("queryId"),
        "caseId": audit.get("caseId"),
        "sourceImageRef": audit.get("sourceImageRef"),
        "partCode": part_code,
        "damageType": damage_type,
        "pairStatus": audit.get("pairStatus"),
        "partConfidence": audit.get("partConfidence"),
        "damageConfidence": audit.get("damageConfidence"),
        "bbox": audit.get("bbox"),
        "vectorPoolCaseCount": int(audit.get("vectorPoolCount") or 0),
        "validCostCaseCount": len(rows),
        "currentEstimable": len(rows) >= 3,
        "draftCompatibleCaseCount": sum(bool(row["draftCompatible"]) for row in rows),
        "draftEstimable": sum(bool(row["draftCompatible"]) for row in rows) >= 3,
        "methodUnion": method_union,
        "mixedMethodQuery": mixed_method,
        "mixedMethodCase": mixed_case,
        "cases": rows,
        "top10RerankCaseIds": sorted(yolo_top_ids),
        "top10VectorCaseIds": sorted(vector_top_ids),
    }


def summarize(rows: list[dict[str, Any]], *, manifest_count: int, candidate_k: int, top_k: int) -> dict[str, Any]:
    paired = [row for row in rows if row["pairStatus"] == "PAIRED" and row["partCode"]]
    valid = [row for row in paired if row["validCostCaseCount"] > 0]
    summary: dict[str, Any] = {
        "queryCount": len(rows),
        "manifestDamageImageCount": manifest_count,
        "pairedQueryCount": len(paired),
        "candidateK": candidate_k,
        "topK": top_k,
        "currentAllWorkEstimableQueries": sum(row["currentEstimable"] for row in paired),
        "draftCompatibleEstimableQueries": sum(row["draftEstimable"] for row in paired),
        "estimableDifference": sum(row["draftEstimable"] for row in paired)
        - sum(row["currentEstimable"] for row in paired),
        "validCostQueries": len(valid),
        "mixedMethodQueries": sum(row["mixedMethodQuery"] for row in valid),
        "mixedMethodQueryRate": (sum(row["mixedMethodQuery"] for row in valid) / len(valid)) if valid else 0.0,
        "mixedMethodCaseQueries": sum(row["mixedMethodCase"] for row in valid),
        "scratchedExchangeMixed": _damage_metric(valid, "SCRATCHED", lambda row: "exchange" in row["methodUnion"]),
        "breakageNoExchange": _damage_metric(valid, "BREAKAGE", lambda row: "exchange" not in row["methodUnion"]),
        "separatedNoExchange": _damage_metric(valid, "SEPARATED", lambda row: "exchange" not in row["methodUnion"]),
    }
    by_damage: dict[str, dict[str, Any]] = {}
    by_part: dict[str, dict[str, Any]] = {}
    method_distribution: dict[str, dict[str, int]] = {}
    method_combinations: dict[str, Counter[str]] = defaultdict(Counter)
    for group, key in ((by_damage, "damageType"), (by_part, "partCode")):
        for row in paired:
            name = str(row.get(key) or "-")
            item = group.setdefault(name, {"queries": 0, "validCostQueries": 0, "currentEstimable": 0,
                                           "draftEstimable": 0, "mixedMethodQueries": 0, "methodCaseCounts": {}})
            item["queries"] += 1
            item["validCostQueries"] += int(row["validCostCaseCount"] > 0)
            item["currentEstimable"] += int(row["currentEstimable"])
            item["draftEstimable"] += int(row["draftEstimable"])
            item["mixedMethodQueries"] += int(row["mixedMethodQuery"])
            for case in row["cases"]:
                for method in case["methods"]:
                    item["methodCaseCounts"][method] = item["methodCaseCounts"].get(method, 0) + 1
    for row in valid:
        distribution = method_distribution.setdefault(row["damageType"], {method: 0 for method in METHODS})
        for case in row["cases"]:
            for method in case["methods"]:
                distribution.setdefault(method, 0)
                distribution[method] += 1
            if len(case["methods"]) > 1:
                method_combinations[row["damageType"]]["+".join(case["methods"])] += 1
    summary["byDamageType"] = by_damage
    summary["byPart"] = by_part
    summary["methodDistributionByDamageType"] = method_distribution
    summary["methodCombinationByDamageType"] = {
        damage: dict(counts.most_common()) for damage, counts in method_combinations.items()
    }
    combinations = [
        {"damageType": damage, "methods": combo, "caseCount": count}
        for damage, counts in method_combinations.items()
        for combo, count in counts.items()
    ]
    summary["mostCommonMixedCombination"] = max(
        combinations, key=lambda item: item["caseCount"], default=None,
    )
    summary["mixedMethodTop20"] = sorted(
        (row for row in valid if row["mixedMethodQuery"]),
        key=lambda row: (-len(row["methodUnion"]), -row["validCostCaseCount"], str(row["queryId"])),
    )[:20]
    summary["draftPolicyDropTop20"] = sorted(
        (row for row in paired if row["currentEstimable"] and not row["draftEstimable"]),
        key=lambda row: (-row["validCostCaseCount"], str(row["queryId"])),
    )[:20]
    return summary


def _damage_metric(rows: list[dict[str, Any]], damage: str, predicate: Any) -> dict[str, Any]:
    group = [row for row in rows if row["damageType"] == damage]
    count = sum(bool(predicate(row)) for row in group)
    return {"count": count, "denominator": len(group), "rate": count / len(group) if group else 0.0}


def _data_url(path: Path) -> str:
    if not path.is_file():
        return ""
    return data_url(path, roi_box=None)


def _case_table(row: dict[str, Any], root: Path) -> str:
    body = []
    for case in row["cases"][:10]:
        ref = resolve_image_path(root, str(case.get("sourceImageRef") or ""))
        image = _data_url(ref)
        methods = ", ".join(case["methods"]) or "-"
        top = "rerank Top-10" if case["inRerankTop10"] else "pool 밖 Top-10 밖"
        body.append(
            f"<tr><td>{case['caseId']}</td><td>{case.get('vectorSimilarity') or '-'}</td>"
            f"<td>{html.escape(methods)}</td><td>{case['costTotal']:,}</td><td>{top}</td>"
            f"<td>{'호환' if case['draftCompatible'] else '초안 정책 제외'}<br>"
            f"<img class='ref-img' src='{image}' alt='case {case['caseId']}'></td></tr>"
        )
    return "".join(body) or "<tr><td colspan='6'>유효 비용 사례 없음</td></tr>"


def _representative_card(row: dict[str, Any], root: Path, title: str) -> str:
    query_path = resolve_image_path(root, str(row.get("sourceImageRef") or ""))
    query_image = _data_url(query_path)
    methods = ", ".join(row.get("methodUnion") or []) or "-"
    return f"""<article class='card'><h3>{html.escape(title)}</h3>
<p><b>{html.escape(str(row.get('partCode') or '-'))}</b> · {html.escape(str(row.get('damageType') or '-'))} · pair {html.escape(str(row.get('pairStatus') or '-'))}</p>
<p>valid cost {row.get('validCostCaseCount', 0)} · current estimable {'YES' if row.get('currentEstimable') else 'NO'} · draft estimable {'YES' if row.get('draftEstimable') else 'NO'}<br>method union: {html.escape(methods)}</p>
<img class='query-img' src='{query_image}' alt='query image'>
<table><tr><th>case</th><th>similarity</th><th>repairMethod</th><th>cost</th><th>Top-10</th><th>image / policy</th></tr>{_case_table(row, root)}</table>
<label>이 작업 방식 조합을 허용할지: <textarea rows='2' placeholder='사람 검토 메모'></textarea></label></article>"""


def render_html(rows: list[dict[str, Any]], summary: dict[str, Any], root: Path) -> str:
    method_rows = []
    for damage, counts in sorted(summary["methodDistributionByDamageType"].items()):
        total = sum(counts.values()) or 1
        cells = "".join(f"<td>{counts.get(method, 0)} ({counts.get(method, 0) / total:.1%})</td>" for method in METHODS)
        method_rows.append(f"<tr><td>{html.escape(damage)}</td>{cells}</tr>")
    coverage_rows = []
    for damage, data in sorted(summary["byDamageType"].items()):
        coverage_rows.append(
            f"<tr><td>{html.escape(damage)}</td><td>{data['queries']}</td><td>{data['validCostQueries']}</td>"
            f"<td>{data['currentEstimable']}</td><td>{data['draftEstimable']}</td><td>{data['mixedMethodQueries']}</td></tr>"
        )
    combination_rows = []
    for damage, combinations in sorted(summary.get("methodCombinationByDamageType", {}).items()):
        for combo, count in combinations.items():
            combination_rows.append(f"<tr><td>{html.escape(damage)}</td><td>{html.escape(combo)}</td><td>{count}</td></tr>")
    mixed = summary.get("mixedMethodTop20") or []
    dropped = summary.get("draftPolicyDropTop20") or []
    mixed_cards = "".join(_representative_card(row, root, "혼합 방식 query") for row in mixed) or "<p>없음</p>"
    dropped_cards = "".join(_representative_card(row, root, "초안 정책 적용 시 탈락 query") for row in dropped) or "<p>없음</p>"
    return f"""<!doctype html><html lang='ko'><meta charset='utf-8'><title>damageType/work repairMethod compatibility audit</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}section,.card{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;border-radius:8px;padding:10px 14px}}.stat b{{font-size:22px;display:block}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left;vertical-align:top}}th{{background:#f1f4fa}}.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(480px,1fr));gap:12px}}.query-img{{width:100%;height:220px;object-fit:contain;background:#eef2f7}}.ref-img{{width:120px;height:80px;object-fit:cover;background:#eef2f7}}textarea{{width:100%;margin-top:10px;box-sizing:border-box}}.note{{background:#fff7ed;padding:10px;border-radius:8px;color:#92400e}}.bar{{height:10px;background:#635bff;display:inline-block;margin-right:5px}}</style>
<main><h1>v2 DAMAGE · damageType / WORK repairMethod 호환성 read-only audit</h1>
<p class='note'>Top-{summary['candidateK']}는 기존 canonical full audit의 case-level vector pool을 재사용했습니다. 비용은 기존 EstimateService의 유효행 필터와 FULL_REPAIR case aggregation을 다시 적용했습니다. 초안 정책은 비교용이며 운영에는 적용하지 않았습니다. repair hint, DB write, API/프론트 수정은 없습니다.</p>
<section><h2>전체 요약</h2><div class='stats'><div class='stat'><b>{summary['queryCount']}</b>처리 query</div><div class='stat'><b>{summary['pairedQueryCount']}</b>PAIRED</div><div class='stat'><b>{summary['currentAllWorkEstimableQueries']}</b>현재 estimable</div><div class='stat'><b>{summary['draftCompatibleEstimableQueries']}</b>초안 estimable</div><div class='stat'><b>{summary['estimableDifference']:+d}</b>변화</div><div class='stat'><b>{summary['mixedMethodQueries']}</b>혼합 방식 query ({summary['mixedMethodQueryRate']:.1%})</div></div></section>
<section><h2>damageType별 coverage 변화</h2><table><tr><th>damageType</th><th>query</th><th>유효 비용 query</th><th>현재 estimable</th><th>초안 estimable</th><th>혼합 방식 query</th></tr>{''.join(coverage_rows)}</table></section>
<section><h2>유효 비용 case repairMethod matrix</h2><table><tr><th>damageType</th><th>coating</th><th>repair</th><th>sheet_metal</th><th>exchange</th></tr>{''.join(method_rows)}</table><p>각 셀은 유효 FULL_REPAIR case 수와 damageType 내 비중입니다.</p></section>
<section><h2>혼합 repairMethod 조합</h2><table><tr><th>damageType</th><th>method 조합</th><th>case 수</th></tr>{''.join(combination_rows) or '<tr><td colspan="3">-</td></tr>'}</table></section>
<section><h2>특수 지표</h2><table><tr><th>지표</th><th>건수</th><th>분모</th><th>비율</th></tr><tr><td>SCRATCHED에 EXCHANGE 포함</td><td>{summary['scratchedExchangeMixed']['count']}</td><td>{summary['scratchedExchangeMixed']['denominator']}</td><td>{summary['scratchedExchangeMixed']['rate']:.1%}</td></tr><tr><td>BREAKAGE에 EXCHANGE 없음</td><td>{summary['breakageNoExchange']['count']}</td><td>{summary['breakageNoExchange']['denominator']}</td><td>{summary['breakageNoExchange']['rate']:.1%}</td></tr><tr><td>SEPARATED에 EXCHANGE 없음</td><td>{summary['separatedNoExchange']['count']}</td><td>{summary['separatedNoExchange']['denominator']}</td><td>{summary['separatedNoExchange']['rate']:.1%}</td></tr></table></section>
<section><h2>혼합 방식이 심한 query 20개</h2><div class='cards'>{mixed_cards}</div></section>
<section><h2>초안 호환 정책 적용 시 estimable에서 탈락하는 query 20개</h2><div class='cards'>{dropped_cards}</div></section>
<section><h2>검토 메모</h2><p>각 카드의 “이 작업 방식 조합을 허용할지” 칸에 사람이 직접 기록하세요. 이 audit은 자동 정답/오답 판정이 아닙니다.</p></section></main></html>"""


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    audit_payload = json.loads(args.audit_json.resolve().read_text(encoding="utf-8"))
    audits = list(audit_payload.get("audits") or [])
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
        state["completed"][key] = analyze_audit(
            audit, repository, top_k=args.top_k, candidate_k=args.candidate_k,
        )
        processed += 1
        if processed % args.batch_size == 0:
            save_checkpoint(state_path, state)
    save_checkpoint(state_path, state)
    rows = list(state["completed"].values())
    summary = summarize(rows, manifest_count=state["manifestCount"], candidate_k=args.candidate_k, top_k=args.top_k)
    payload = {
        "status": "SUCCEEDED", "generatedAt": datetime.now(timezone.utc).isoformat(),
        "readOnly": True, "sourceAudit": str(args.audit_json.resolve()),
        "queryManifest": str(args.query_manifest.resolve()),
        "draftPolicy": {key: sorted(value) for key, value in DRAFT_POLICY.items()},
        "audits": rows, "summary": summary,
        "verification": {"databaseWrites": False, "repairHintUsed": False,
                          "migrationChanged": False, "embeddingRebuilt": False,
                          "featureReloaded": False, "pipelineActivationChanged": False},
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.write_text(render_html(rows, summary, args.dataset_root.resolve()), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "processed": processed,
                      "completed": len(rows), **summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
