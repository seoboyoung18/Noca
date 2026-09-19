"""Read-only accuracy audit for 10/15/20/30 estimate references from a K=200 audit.

Validation queries are not part of the TRAIN_ONLY corpus.  Their target cost is
therefore read from the exact ``aihub_estimate_raw`` source/external_ref row,
normalized with the same ingestion helpers, and aggregated with the existing
EstimateService policy.  The K=200 candidate list is reused verbatim; no new
YOLO/DINO inference or vector search is performed.
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import os
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2].parent
PROJECT_ROOT = REPO_ROOT.parent
for root in (REPO_ROOT, REPO_ROOT / "pipeline"):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import CostCaseRow  # noqa: E402
from AI.server.app.services.estimate_service import _aggregate_case, _is_included_row, _percentiles  # noqa: E402
from pipeline.jobs.ingestion.load_search_data import source_for_case  # noqa: E402
from pipeline.standardization.estimate_items import normalize_estimate_item  # noqa: E402


DEFAULT_AUDIT = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-20" / "v2_full_yolo_full_repair_coverage_k200.json"
DEFAULT_JSON = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-20" / "estimate_reference_count_accuracy_k200_raw_truth.json"
DEFAULT_CSV = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-20" / "estimate_reference_count_accuracy_k200_raw_truth.csv"
DEFAULT_HTML = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-20" / "estimate_reference_count_accuracy_k200_raw_truth.html"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-json", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--output-json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--output-csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--output-html", type=Path, default=DEFAULT_HTML)
    parser.add_argument("--reference-counts", default="10,15,20,30")
    parser.add_argument("--min-reference-count", type=int, default=5)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--batch-size", type=int, default=100)
    args = parser.parse_args()
    try:
        args.reference_counts = tuple(sorted({int(value.strip()) for value in args.reference_counts.split(",") if value.strip()}))
    except ValueError as exc:
        parser.error(f"invalid --reference-counts: {exc}")
    if not args.reference_counts or any(value < 1 for value in args.reference_counts):
        parser.error("reference-counts must contain positive integers")
    if args.min_reference_count < 1 or args.batch_size < 1:
        parser.error("min-reference-count and batch-size must be positive")
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    return args


def checkpoint_path(output: Path) -> Path:
    return output.with_suffix(output.suffix + ".checkpoint.json")


def load_state(path: Path, resume: bool) -> dict[str, Any]:
    if not resume or not path.is_file():
        return {"version": 1, "processed": 0, "results": {}}
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state.get("results"), dict):
        raise ValueError("invalid checkpoint: results must be an object")
    return state


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")


def db_case_map(dsn: str, refs: list[str]) -> dict[str, int]:
    import psycopg

    if not refs:
        return {}
    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT external_ref, case_id FROM repair_case WHERE external_ref = ANY(%s)", (refs,))
            return {str(external_ref): int(case_id) for external_ref, case_id in cursor.fetchall()}


def query_rows(source: dict[str, Any]) -> list[dict[str, Any]]:
    audits = [row for row in source.get("audits", []) if row.get("pairStatus") == "PAIRED" and row.get("partCode")]
    chosen: dict[tuple[str, str], dict[str, Any]] = {}
    for row in audits:
        key = (str(row.get("externalRef") or row.get("caseId") or ""), str(row["partCode"]))
        previous = chosen.get(key)
        score = (float(row.get("partConfidence") or 0), float(row.get("damageConfidence") or 0))
        old_score = (float(previous.get("partConfidence") or 0), float(previous.get("damageConfidence") or 0)) if previous else None
        query_id = str(row.get("queryId") or "")
        old_query_id = str(previous.get("queryId") or "") if previous else ""
        if previous is None or score > old_score or (score == old_score and query_id < old_query_id):
            chosen[key] = row
    return sorted(chosen.values(), key=lambda row: str(row.get("queryId") or row.get("externalRef")))


def load_part_mapping(dsn: str) -> dict[str, str]:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT raw_name, part_code FROM part_name_mapping")
            return {str(raw_name): str(part_code) for raw_name, part_code in cursor.fetchall() if raw_name and part_code}


def target_costs_from_raw(dsn: str, rows: list[dict[str, Any]],
                          case_map: dict[str, int]) -> tuple[dict[tuple[str, str], dict[str, Any]], dict[str, Any]]:
    """Resolve the query's own part cost from the exact raw source document.

    ``case_map`` is used only for self-match leakage checks.  It is never used
    as the target-cost source because validation cases are intentionally absent
    from the TRAIN_ONLY repair_case corpus.
    """
    import psycopg

    refs = sorted({str(row.get("externalRef") or row.get("caseId") or "") for row in rows})
    mapping = load_part_mapping(dsn)
    expected_sources: dict[str, str | None] = {}
    for ref in refs:
        try:
            expected_sources[ref] = source_for_case(ref)
        except (KeyError, IndexError):
            expected_sources[ref] = None
    raw_by_key: dict[tuple[str, str], dict[str, Any]] = {}
    with psycopg.connect(dsn, connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT source, external_ref, payload FROM aihub_estimate_raw WHERE external_ref = ANY(%s)", (refs,))
            for source, external_ref, payload in cursor.fetchall():
                key = (str(source), str(external_ref))
                if expected_sources.get(str(external_ref)) == str(source):
                    raw_by_key[key] = payload

    result: dict[tuple[str, str], dict[str, Any]] = {}
    diagnostics: dict[str, Any] = {
        "rawRowsFound": 0, "rawRowsMissing": 0, "sourceMismatchRows": 0,
        "normalizationErrors": {}, "normalizationErrorRawNames": {},
    }
    synthetic_case_id = 1
    for row in rows:
        external_ref = str(row.get("externalRef") or row.get("caseId") or "")
        part_code = str(row["partCode"])
        source = expected_sources.get(external_ref)
        payload = raw_by_key.get((source, external_ref)) if source else None
        key = (external_ref, part_code)
        if payload is None:
            diagnostics["rawRowsMissing"] += 1
            result[key] = {"status": "RAW_ESTIMATE_MISSING", "reason": "RAW_SOURCE_ROW_NOT_FOUND", "source": source}
            continue
        diagnostics["rawRowsFound"] += 1
        items = payload.get("수리내역") if isinstance(payload, dict) else None
        grouped: list[CostCaseRow] = []
        if not isinstance(items, list):
            result[key] = {"status": "NO_VALID_FULL_REPAIR_TARGET", "reason": "RAW_REPAIR_ITEMS_MISSING", "source": source}
            continue
        for ordinal, raw_item in enumerate(items, 1):
            if not isinstance(raw_item, dict):
                continue
            try:
                normalized = normalize_estimate_item(raw_item, source, mapping, ordinal)
            except Exception as exc:
                reason = str(exc).split(":", 1)[0]
                diagnostics["normalizationErrors"][reason] = int(diagnostics["normalizationErrors"].get(reason, 0)) + 1
                raw_name = str(raw_item.get("작업항목 및 부품명") or "").strip()
                if raw_name:
                    names = diagnostics["normalizationErrorRawNames"].setdefault(reason, {})
                    names[raw_name] = int(names.get(raw_name, 0)) + 1
                continue
            if normalized.get("part_code") != part_code:
                continue
            grouped.append(CostCaseRow(
                case_id=synthetic_case_id, source=source, part_code=part_code,
                line_type=str(normalized["line_type"]), work_code=normalized.get("work_code"),
                assessment_status=normalized.get("assessment_status"),
                part_cost=normalized.get("part_cost"), paint_material_cost=normalized.get("paint_material_cost"),
                labor_cost=normalized.get("labor_cost"), post_adjustment_part_cost=normalized.get("post_part_cost"),
                post_adjustment_labor_cost=normalized.get("post_labor_cost"),
            ))
        synthetic_case_id += 1
        included = [item for item in grouped if _is_included_row(item)]
        if not any(item.line_type == "WORK" for item in included):
            result[key] = {"status": "NO_VALID_FULL_REPAIR_TARGET", "reason": "NO_VALID_FULL_REPAIR_WORK", "source": source}
            continue
        aggregate = _aggregate_case(synthetic_case_id, part_code, included)
        if aggregate is None:
            result[key] = {"status": "NO_VALID_FULL_REPAIR_TARGET", "reason": "NO_POSITIVE_FULL_REPAIR_COST", "source": source}
        else:
            result[key] = {
                "status": "TARGET_COST_AVAILABLE", "source": source,
                "actualPartFullRepairCost": int(aggregate.total), "methods": sorted(aggregate.methods),
            }
        synthetic_case_id += 1
    return result, diagnostics


def numeric(value: Any) -> float | None:
    try:
        return None if value is None else float(value)
    except (TypeError, ValueError):
        return None


def evaluate_reference_count(candidates: list[dict[str, Any]], actual: int | None, n: int,
                             min_reference_count: int, *, target_status: str,
                             target_reason: str | None) -> dict[str, Any]:
    ordered = sorted(candidates, key=lambda candidate: (-float(candidate.get("similarity") or 0), int(candidate.get("caseId"))))
    selected = ordered[:n]
    out: dict[str, Any] = {
        "referenceCount": n, "availableReferenceCount": len(ordered),
        "usedReferenceCount": len(selected), "targetStatus": target_status,
        "targetReason": target_reason, "status": target_status,
        "predictedP25": None, "predictedMedian": None, "predictedP75": None,
        "actualPartFullRepairCost": actual, "absoluteError": None, "percentError": None,
        "withinP25P75": None, "direction": None, "mapeExcluded": False,
    }
    if target_status != "TARGET_COST_AVAILABLE":
        return out
    if len(selected) < min_reference_count:
        out["status"] = "INSUFFICIENT_REFERENCES"
        return out
    costs = [int(candidate["total"]) for candidate in selected]
    p25, median, p75 = _percentiles(costs)
    out.update({"status": "EVALUATED", "predictedP25": p25, "predictedMedian": median, "predictedP75": p75})
    if actual is None:
        out["status"] = target_status
        return out
    out["absoluteError"] = abs(median - actual)
    if actual <= 0:
        out["mapeExcluded"] = True
    else:
        out["percentError"] = abs(median - actual) / actual * 100.0
    out["withinP25P75"] = bool(p25 <= actual <= p75)
    out["direction"] = "UNDER" if median < actual else "OVER" if median > actual else "EXACT"
    return out


def summarize_metric(rows: list[dict[str, Any]], n: int, min_reference_count: int) -> dict[str, Any]:
    evaluations = [row["byReferenceCount"][str(n)] for row in rows]
    target_rows = [row for row in rows if row["targetStatus"] == "TARGET_COST_AVAILABLE"]
    evaluated = [item for item in evaluations if item["status"] == "EVALUATED"]
    actual_positive = [item for item in evaluated if item["actualPartFullRepairCost"] is not None and item["actualPartFullRepairCost"] > 0]
    abs_errors = [float(item["absoluteError"]) for item in evaluated if item["absoluteError"] is not None]
    percent_errors = [float(item["percentError"]) for item in actual_positive if item["percentError"] is not None]
    actuals = [float(item["actualPartFullRepairCost"]) for item in actual_positive]
    within = [item for item in evaluated if item["withinP25P75"] is True]
    under = [item for item in evaluated if item["direction"] == "UNDER"]
    over = [item for item in evaluated if item["direction"] == "OVER"]
    def dist(values: list[float]) -> dict[str, Any] | None:
        if not values:
            return None
        ordered = sorted(values)
        p25, median, p75 = _percentiles([round(value) for value in ordered])
        return {"count": len(ordered), "min": ordered[0], "p25": p25, "median": median, "p75": p75, "max": ordered[-1]}
    return {
        "referenceCount": n,
        "targetCostQueryCount": len(target_rows),
        "targetCostMissingQueryCount": len(rows) - len(target_rows),
        "evaluatedQueryCount": len(evaluated),
        "insufficientReferenceQueryCount": sum(item["status"] == "INSUFFICIENT_REFERENCES" for item in evaluations),
        "coverage": len(evaluated) / len(target_rows) if target_rows else 0.0,
        "coveragePercent": (len(evaluated) / len(target_rows) * 100.0) if target_rows else 0.0,
        "mae": (sum(abs_errors) / len(abs_errors)) if abs_errors else None,
        "mdape": statistics.median(percent_errors) if percent_errors else None,
        "wape": (sum(abs_errors) / sum(actuals) * 100.0) if actuals and sum(actuals) else None,
        "withinP25P75Rate": len(within) / len(evaluated) if evaluated else 0.0,
        "withinP25P75Percent": (len(within) / len(evaluated) * 100.0) if evaluated else 0.0,
        "underRate": len(under) / len(evaluated) if evaluated else 0.0,
        "overRate": len(over) / len(evaluated) if evaluated else 0.0,
        "mapeExcludedCount": sum(item["mapeExcluded"] for item in evaluations),
        "absoluteErrorDistribution": dist(abs_errors),
        "percentErrorDistribution": dist(percent_errors),
        "actualPartFullRepairCostDistribution": dist(actuals),
    }


def grouped_summaries(rows: list[dict[str, Any]], field: str, counts: tuple[int, ...], minimum: int) -> dict[str, Any]:
    output: dict[str, Any] = {}
    for value in sorted({str(row.get(field) or "UNKNOWN") for row in rows}):
        subset = [row for row in rows if str(row.get(field) or "UNKNOWN") == value]
        output[value] = {str(n): summarize_metric(subset, n, minimum) for n in counts}
    return output


def render_csv(path: Path, rows: list[dict[str, Any]], counts: tuple[int, ...]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["queryId", "externalRef", "sourceImageRef", "partCode", "damageType", "targetStatus", "targetReason", "actualPartFullRepairCost"]
    for n in counts:
        fields.extend([f"n{n}_status", f"n{n}_available", f"n{n}_used", f"n{n}_predictedMedian", f"n{n}_absoluteError", f"n{n}_percentError", f"n{n}_withinP25P75"])
    with path.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            out = {field: row.get(field) for field in fields if field in row}
            for n in counts:
                item = row["byReferenceCount"][str(n)]
                out.update({f"n{n}_status": item["status"], f"n{n}_available": item["availableReferenceCount"], f"n{n}_used": item["usedReferenceCount"], f"n{n}_predictedMedian": item["predictedMedian"], f"n{n}_absoluteError": item["absoluteError"], f"n{n}_percentError": item["percentError"], f"n{n}_withinP25P75": item["withinP25P75"]})
            writer.writerow(out)


def render_html(payload: dict[str, Any], counts: tuple[int, ...], minimum: int) -> str:
    summary = payload["summaryByReferenceCount"]
    top_rows = "".join(
        f"<tr><td>N={n}</td><td>{s['targetCostQueryCount']}</td><td>{s['evaluatedQueryCount']}</td><td>{s['coveragePercent']:.1f}%</td><td>{fmt(s['mdape'])}</td><td>{fmt(s['wape'])}%</td><td>{s['withinP25P75Percent']:.1f}%</td><td>{s['insufficientReferenceQueryCount']}</td></tr>"
        for n, s in ((n, summary[str(n)]) for n in counts)
    )
    rows_html = []
    for row in payload["results"]:
        cells = []
        for n in counts:
            item = row["byReferenceCount"][str(n)]
            cells.append(f"<td>{item['status']}<br>median {fmt(item['predictedMedian'])}<br>abs {fmt(item['absoluteError'])}<br>% {fmt(item['percentError'])}</td>")
        rows_html.append(f"<tr><td>{html.escape(str(row.get('partCode')))}</td><td>{html.escape(str(row.get('damageType')))}</td><td>{html.escape(str(row.get('externalRef')))}</td><td>{html.escape(str(row.get('sourceImageRef')))}</td><td>{row.get('targetStatus')}<br>{html.escape(str(row.get('targetReason') or ''))}</td><td>{fmt(row.get('actualPartFullRepairCost'))}</td>{''.join(cells)}</tr>")
    error_rows = "".join(rows_html)
    extremes = []
    for n in counts:
        evaluated = [(row, row["byReferenceCount"][str(n)]) for row in payload["results"] if row["byReferenceCount"][str(n)]["status"] == "EVALUATED" and row["byReferenceCount"][str(n)]["absoluteError"] is not None]
        low = min(evaluated, key=lambda pair: pair[1]["absoluteError"], default=None)
        high = max(evaluated, key=lambda pair: pair[1]["absoluteError"], default=None)
        extremes.append(f"<tr><td>N={n}</td><td>{extreme_cell(low)}</td><td>{extreme_cell(high)}</td></tr>")
    by_part = payload["byPart"]
    part_sections = []
    for part, metrics in by_part.items():
        metric_cells = "".join(
            f"<td>{metrics[str(n)]['coveragePercent']:.1f}% / {fmt(metrics[str(n)]['mdape'])} / {fmt(metrics[str(n)]['wape'])}%</td>"
            for n in counts
        )
        part_sections.append(f"<tr><td>{html.escape(part)}</td>{metric_cells}</tr>")
    return f"""<!doctype html><html lang='ko'><meta charset='utf-8'><title>K=200 estimate reference count accuracy</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left;vertical-align:top}}th{{background:#eef2f7}}.warn{{background:#fff7ed;color:#92400e;padding:12px;border-radius:8px}}.missing{{background:#f8fafc;color:#64748b}}</style>
<main><h1>K=200 견적 참조 개수 정확도 audit</h1><p class='warn'>운영 N을 자동 확정하지 않습니다. K=200 audit의 검증된 후보만 사용했으며, query 자신의 동일 partCode FULL_REPAIR 비용과 비교한 read-only 결과입니다. repair hint와 DB write는 사용하지 않았습니다.</p>
<section><h2>N별 요약 (최소 참조 {minimum}건)</h2><table><tr><th>N</th><th>정답 보유 query</th><th>평가 query</th><th>coverage</th><th>MdAPE</th><th>WAPE</th><th>p25–p75 적중률</th><th>참조 부족</th></tr>{top_rows}</table></section>
<section><h2>최저/최고 절대오차</h2><table><tr><th>N</th><th>최저 오차</th><th>최고 오차</th></tr>{''.join(extremes)}</table></section>
<section><h2>부품별 coverage / MdAPE / WAPE</h2><table><tr><th>partCode</th>{''.join(f'<th>N={n}</th>' for n in counts)}</tr>{''.join(part_sections)}</table></section>
<section><h2>query별 비용 비교</h2><p>원본 정답이 없거나 유효 FULL_REPAIR가 없는 query는 RAW_ESTIMATE_MISSING / NO_VALID_FULL_REPAIR_TARGET으로 분리했고 오차 계산에서 제외했습니다. 각 N의 median은 similarity 내림차순 후보의 비용 중앙값입니다.</p><table><tr><th>part</th><th>damage</th><th>externalRef</th><th>source path</th><th>target 상태</th><th>actualPartFullRepairCost</th>{''.join(f'<th>N={n}<br>상태 / median / abs / %</th>' for n in counts)}</tr>{error_rows}</table></section>
<section><h2>판정 범위</h2><p>이 audit은 K=200 후보 중 참조 개수별 비용 중앙값의 수치 비교입니다. 유사 이미지의 외관·손상 타당성이나 운영 candidate K를 자동 결정하지 않으며, query 비용 정답이 없는 경우에는 정확도 지표를 산출하지 않습니다.</p></section></main></html>"""


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.2f}"
    return f"{value:,}" if isinstance(value, int) else html.escape(str(value))


def extreme_cell(pair: Any) -> str:
    if not pair:
        return "-"
    row, metric = pair
    return f"{html.escape(str(row.get('externalRef')))} · {html.escape(str(row.get('sourceImageRef')))}<br>actual {fmt(metric.get('actualPartFullRepairCost'))}, median {fmt(metric.get('predictedMedian'))}, abs {fmt(metric.get('absoluteError'))}"


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    source = json.loads(args.audit_json.resolve().read_text(encoding="utf-8"))
    if int(source.get("candidateK", 0)) < max(args.reference_counts):
        raise SystemExit("audit-json candidateK가 reference-counts보다 작습니다")
    base_rows = query_rows(source)
    refs = sorted({str(row.get("externalRef") or row.get("caseId") or "") for row in base_rows})
    case_map = db_case_map(dsn, refs)
    targets, raw_diagnostics = target_costs_from_raw(dsn, base_rows, case_map)
    checkpoint = checkpoint_path(args.output_json.resolve())
    state = load_state(checkpoint, args.resume)
    selected = base_rows if args.limit is None or args.resume else base_rows[:args.limit]
    run_count = 0
    for index, row in enumerate(selected):
        key = f"{row.get('externalRef') or row.get('caseId')}|{row.get('partCode')}"
        if args.resume and key in state["results"]:
            continue
        target = targets.get((str(row.get("externalRef") or row.get("caseId") or ""), str(row["partCode"])), {"status": "RAW_ESTIMATE_MISSING", "reason": "TARGET_LOOKUP_MISSING"})
        query_case_id = case_map.get(str(row.get("externalRef") or row.get("caseId") or ""))
        listed_ids = []
        for field in ("validWorkCostCases", "vectorTop10", "yoloTop10"):
            listed_ids.extend(int(candidate["caseId"]) for candidate in row.get(field, []) if candidate.get("caseId") is not None)
        self_match = query_case_id is not None and query_case_id in listed_ids
        result = {
            "queryId": row.get("queryId"), "externalRef": row.get("externalRef"), "sourceImageRef": row.get("sourceImageRef"),
            "partCode": row.get("partCode"), "damageType": row.get("damageType"), "pairStatus": row.get("pairStatus"),
            "partConfidence": row.get("partConfidence"), "damageConfidence": row.get("damageConfidence"),
            "queryCaseId": query_case_id, "targetStatus": target.get("status"), "targetReason": target.get("reason"),
            "actualSource": target.get("source"),
            "actualPartFullRepairCost": target.get("actualPartFullRepairCost"), "actualMethods": target.get("methods", []),
            "selfMatchInAuditCandidates": self_match, "selfMatchVerificationScope": ["validWorkCostCases", "vectorTop10", "yoloTop10"],
            "availableReferenceCount": len(row.get("validWorkCostCases", [])), "candidates": row.get("validWorkCostCases", []),
            "byReferenceCount": {},
        }
        candidate_rows = row.get("validWorkCostCases", [])
        for n in args.reference_counts:
            metric = evaluate_reference_count(candidate_rows, target.get("actualPartFullRepairCost"), n, args.min_reference_count, target_status=target.get("status", "RAW_ESTIMATE_MISSING"), target_reason=target.get("reason"))
            if self_match:
                metric["status"] = "SELF_MATCH_NOT_EXCLUDED"
            result["byReferenceCount"][str(n)] = metric
        state["results"][key] = result
        state["processed"] = index + 1
        run_count += 1
        if run_count % args.batch_size == 0:
            save_state(checkpoint, state)
    save_state(checkpoint, state)
    results = list(state["results"].values())
    for result in results:
        target_key = (str(result.get("externalRef") or ""), str(result.get("partCode") or ""))
        result.setdefault("actualSource", targets.get(target_key, {}).get("source"))
    summary = {str(n): summarize_metric(results, n, args.min_reference_count) for n in args.reference_counts}
    complete = len(results) >= len(base_rows)
    payload = {
        "status": "SUCCEEDED" if complete else "PARTIAL", "generatedAt": datetime.now(timezone.utc).isoformat(), "readOnly": True,
        "sourceAudit": str(args.audit_json.resolve()), "candidateK": source.get("candidateK"),
        "referenceCounts": list(args.reference_counts), "minReferenceCount": args.min_reference_count,
        "inputAuditQueryCount": len(source.get("audits", [])), "pairedDeduplicatedQueryCount": len(base_rows),
        "deduplication": "same externalRef/case_id + partCode: highest partConfidence, then damageConfidence",
        "queryCaseMappedCount": sum(row["queryCaseId"] is not None for row in results),
        "rawTruthDiagnostics": raw_diagnostics,
        "selfMatchViolationCount": sum(row["selfMatchInAuditCandidates"] for row in results),
        "summaryByReferenceCount": summary, "results": results,
        "byPart": grouped_summaries(results, "partCode", args.reference_counts, args.min_reference_count),
        "byDamageType": grouped_summaries(results, "damageType", args.reference_counts, args.min_reference_count),
        "verification": {"databaseWrites": False, "migrationChanged": False, "embeddingChanged": False, "featureChanged": False, "pipelineActivationChanged": False, "repairHintUsed": False},
    }
    args.output_json.resolve().parent.mkdir(parents=True, exist_ok=True)
    args.output_json.resolve().write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    render_csv(args.output_csv.resolve(), results, args.reference_counts)
    args.output_html.resolve().parent.mkdir(parents=True, exist_ok=True)
    args.output_html.resolve().write_text(render_html(payload, args.reference_counts, args.min_reference_count), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "processed": run_count, "queries": len(results), "coverage": {str(n): {"coverage": summary[str(n)]["coverage"], "mdape": summary[str(n)]["mdape"], "wape": summary[str(n)]["wape"], "withinP25P75Percent": summary[str(n)]["withinP25P75Percent"]} for n in args.reference_counts}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
