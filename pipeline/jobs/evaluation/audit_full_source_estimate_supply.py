"""Read-only audit of the full AI-Hub estimate source supply.

The job deliberately does not load, update, or rebuild any corpus data.  It
reads ``aihub_estimate_raw`` in memory, applies the existing estimate item
standardization and ``EstimateService`` inclusion/aggregation rules, and
compares the resulting supply with the current DEV ``repair_case`` sample.
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import logging
import os
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
PROJECT_ROOT = REPO_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import (  # noqa: E402
    CostCaseRow,
    PostgresCostCaseRepository,
)
from AI.server.app.services.estimate_service import (  # noqa: E402
    _aggregate_case,
    _is_included_row,
    _percentiles,
)
from pipeline.standardization.estimate_items import normalize_estimate_item  # noqa: E402


DEFAULT_JSON = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-18" / "full_source_estimate_supply_audit.json"
DEFAULT_HTML = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-18" / "full_source_estimate_supply_audit.html"
THRESHOLDS = (2, 3, 5, 10, 20)
FOCUS_PARTS = (
    "FRONT_BUMPER", "REAR_BUMPER", "ROCKER_PANEL_L", "ROCKER_PANEL_R",
    "FENDER_L", "FENDER_R", "FRONT_WHEEL_L", "FRONT_WHEEL_R",
    "REAR_WHEEL_L", "REAR_WHEEL_R", "HEAD_LIGHT_L", "HEAD_LIGHT_R",
    "TAIL_LAMP_L", "TAIL_LAMP_R", "SIDE_MIRROR_L", "SIDE_MIRROR_R",
    "LAMP", "WHEEL", "FENDER",
)
GROUPS = ("EXCHANGE_INCLUDED", "REPAIR_FAMILY", "OTHER_VALID_WORK")
LOGGER = logging.getLogger(__name__)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--output-html", type=Path, default=DEFAULT_HTML)
    parser.add_argument("--limit", type=int, default=None, help="process only the first N raw estimate documents")
    parser.add_argument("--resume", action="store_true", help="continue from the JSON checkpoint")
    parser.add_argument("--batch-size", type=int, default=500)
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    if args.batch_size < 1:
        parser.error("batch-size must be positive")
    return args


def checkpoint_path(output: Path) -> Path:
    return output.with_suffix(output.suffix + ".checkpoint.json")


def empty_state() -> dict[str, Any]:
    return {
        "version": 1,
        "processedDocuments": 0,
        "rawRows": 0,
        "sourceDocuments": {},
        "sourceRows": {},
        "partStats": {},
        "unmappedRawItemNames": {},
        "normalizationErrors": {},
        "normalizationErrorRawNames": {},
        "mappingRows": 0,
        "mappingSuccessRows": 0,
        "mappingFailedRows": 0,
    }


def load_state(path: Path, resume: bool) -> dict[str, Any]:
    if not resume or not path.is_file():
        return empty_state()
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state.get("partStats"), dict):
        raise ValueError("invalid checkpoint: partStats must be an object")
    return state


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")


def db_inventory(dsn: str) -> dict[str, Any]:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT table_name, column_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema='public'
                   AND table_name IN ('aihub_estimate_raw','repair_case','repair_case_item')
                 ORDER BY table_name, ordinal_position
            """)
            schema = [
                {"table": row[0], "column": row[1], "type": row[2]}
                for row in cursor.fetchall()
            ]
            queries = {
                "rawDocuments": "SELECT count(*) FROM aihub_estimate_raw",
                "rawDistinctExternalRefs": "SELECT count(DISTINCT external_ref) FROM aihub_estimate_raw",
                "rawRows": "SELECT COALESCE(sum(jsonb_array_length(payload->'수리내역')),0) FROM aihub_estimate_raw",
                "repairCases": "SELECT count(*) FROM repair_case",
                "repairCaseItems": "SELECT count(*) FROM repair_case_item",
                "repairCaseDistinctExternalRefs": "SELECT count(DISTINCT external_ref) FROM repair_case",
                "linkedRawRows": "SELECT count(*) FROM aihub_estimate_raw r JOIN repair_case c ON c.external_ref=r.external_ref",
                "linkedRawRefs": "SELECT count(DISTINCT r.external_ref) FROM aihub_estimate_raw r JOIN repair_case c ON c.external_ref=r.external_ref",
            }
            counts: dict[str, int] = {}
            for name, query in queries.items():
                cursor.execute(query)
                counts[name] = int(cursor.fetchone()[0] or 0)
            cursor.execute("SELECT source, count(*) FROM aihub_estimate_raw GROUP BY source ORDER BY source")
            raw_by_source = {str(source): int(count) for source, count in cursor.fetchall()}
            cursor.execute("""
                SELECT source, COALESCE(sum(jsonb_array_length(payload->'수리내역')),0)
                  FROM aihub_estimate_raw GROUP BY source ORDER BY source
            """)
            raw_rows_by_source = {str(source): int(count) for source, count in cursor.fetchall()}
            cursor.execute("SELECT source, count(*) FROM repair_case GROUP BY source ORDER BY source")
            case_by_source = {str(source): int(count) for source, count in cursor.fetchall()}
    return {
        "tables": schema,
        "counts": counts,
        "rawDocumentsBySource": raw_by_source,
        "rawRowsBySource": raw_rows_by_source,
        "repairCasesBySource": case_by_source,
        "relationship": {
            "key": "aihub_estimate_raw.external_ref = repair_case.external_ref",
            "linkedDistinctRawRefs": counts["linkedRawRefs"],
            "unlinkedRawRefs": counts["rawDistinctExternalRefs"] - counts["linkedRawRefs"],
        },
        "interpretation": {
            "rawDocumentCount": counts["rawDocuments"],
            "rawEstimateRowCount": counts["rawRows"],
            "125006Meaning": "견적서 문서 수(=aihub_estimate_raw 행 수)이며, 견적 행 수가 아님",
        },
    }


def load_part_mapping(dsn: str) -> dict[str, str]:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT raw_name, part_code FROM part_name_mapping")
            return {str(raw): str(part) for raw, part in cursor.fetchall() if raw and part}


def new_part_stat() -> dict[str, Any]:
    return {
        "mappedRows": 0,
        "groups": {
            group: {"costs": [], "methods": {}} for group in GROUPS
        },
    }


def make_case_row(case_id: int, source: str, normalized: dict[str, Any]) -> CostCaseRow:
    return CostCaseRow(
        case_id=case_id,
        source=source,
        part_code=str(normalized["part_code"]),
        line_type=str(normalized["line_type"]),
        work_code=normalized.get("work_code"),
        assessment_status=normalized.get("assessment_status"),
        part_cost=normalized.get("part_cost"),
        paint_material_cost=normalized.get("paint_material_cost"),
        labor_cost=normalized.get("labor_cost"),
        post_adjustment_part_cost=normalized.get("post_part_cost"),
        post_adjustment_labor_cost=normalized.get("post_labor_cost"),
    )


def method_group(methods: Iterable[str]) -> str:
    values = set(methods)
    if "exchange" in values:
        return "EXCHANGE_INCLUDED"
    if values & {"coating", "sheet_metal", "repair"}:
        return "REPAIR_FAMILY"
    return "OTHER_VALID_WORK"


def add_valid_case(state: dict[str, Any], part_code: str, aggregate: Any) -> None:
    stat = state["partStats"].setdefault(part_code, new_part_stat())
    group = method_group(aggregate.methods)
    bucket = stat["groups"][group]
    bucket["costs"].append(int(aggregate.total))
    for method in sorted(aggregate.methods):
        bucket["methods"][method] = int(bucket["methods"].get(method, 0)) + 1


def process_document(state: dict[str, Any], source: str, payload: Any,
                     mapping: dict[str, str], ordinal: int) -> None:
    items = payload.get("수리내역") if isinstance(payload, dict) else None
    if not isinstance(items, list):
        state["normalizationErrors"]["missing_repair_items"] = int(state["normalizationErrors"].get("missing_repair_items", 0)) + 1
        return
    state["rawRows"] += len(items)
    grouped: dict[str, list[CostCaseRow]] = defaultdict(list)
    for item_index, raw_item in enumerate(items, 1):
        raw_name = str(raw_item.get("작업항목 및 부품명") or "").strip() if isinstance(raw_item, dict) else ""
        state["mappingRows"] += 1
        if raw_name not in mapping:
            state["mappingFailedRows"] += 1
            key = raw_name or "<EMPTY>"
            state["unmappedRawItemNames"][key] = int(state["unmappedRawItemNames"].get(key, 0)) + 1
        else:
            state["mappingSuccessRows"] += 1
        try:
            normalized = normalize_estimate_item(raw_item, source, mapping, item_index)
        except Exception as exc:
            error_name = str(exc).split(":", 1)[0]
            state["normalizationErrors"][error_name] = int(state["normalizationErrors"].get(error_name, 0)) + 1
            if raw_name:
                names = state["normalizationErrorRawNames"].setdefault(error_name, {})
                names[raw_name] = int(names.get(raw_name, 0)) + 1
            continue
        part_code = normalized.get("part_code")
        if not part_code:
            continue
        stat = state["partStats"].setdefault(str(part_code), new_part_stat())
        stat["mappedRows"] += 1
        grouped[str(part_code)].append(make_case_row(ordinal, source, normalized))
    for part_code, rows in grouped.items():
        included = [row for row in rows if _is_included_row(row)]
        # PART_PRICE_ONLY is deliberately not a FULL_REPAIR supply case.
        if not any(row.line_type == "WORK" for row in included):
            continue
        aggregate = _aggregate_case(ordinal, part_code, included)
        if aggregate is not None:
            add_valid_case(state, part_code, aggregate)


def iter_raw_documents(dsn: str) -> Iterable[tuple[str, str, Any]]:
    import psycopg

    connection = psycopg.connect(dsn, connect_timeout=10)
    cursor = connection.cursor(name="full_source_estimate_supply")
    cursor.execute("SELECT source, external_ref, payload FROM aihub_estimate_raw ORDER BY source, external_ref")
    try:
        while True:
            rows = cursor.fetchmany(500)
            if not rows:
                break
            yield from ((str(source), str(external_ref), payload) for source, external_ref, payload in rows)
    finally:
        cursor.close()
        connection.close()


def normalize_group_stat(bucket: dict[str, Any]) -> dict[str, Any]:
    costs = [int(value) for value in bucket.get("costs", [])]
    result: dict[str, Any] = {
        "caseCount": len(costs),
        "atLeast": {str(threshold): len(costs) >= threshold for threshold in THRESHOLDS},
        "methods": sorted(bucket.get("methods", {})),
        "methodCaseCounts": {str(key): int(value) for key, value in sorted(bucket.get("methods", {}).items())},
        "costDistribution": None,
    }
    if costs:
        p25, median, p75 = _percentiles(costs)
        result["costDistribution"] = {
            "count": len(costs), "min": min(costs), "p25": p25,
            "median": median, "p75": p75, "max": max(costs),
        }
    return result


def summarize_part_stats(raw_stats: dict[str, Any]) -> dict[str, Any]:
    output: dict[str, Any] = {}
    for part_code, stat in raw_stats.items():
        groups = {group: normalize_group_stat(stat.get("groups", {}).get(group, {})) for group in GROUPS}
        all_costs = [int(cost) for group in stat.get("groups", {}).values() for cost in group.get("costs", [])]
        all_distribution = None
        if all_costs:
            p25, median, p75 = _percentiles(all_costs)
            all_distribution = {
                "count": len(all_costs), "min": min(all_costs), "p25": p25,
                "median": median, "p75": p75, "max": max(all_costs),
            }
        output[part_code] = {
            "mappedRows": int(stat.get("mappedRows", 0)),
            "validFullRepairCaseCount": len(all_costs),
            "atLeast": {str(threshold): len(all_costs) >= threshold for threshold in THRESHOLDS},
            "costDistribution": all_distribution,
            "groups": groups,
        }
    return output


def dev_supply(dsn: str, part_codes: list[str]) -> dict[str, Any]:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT case_id FROM repair_case ORDER BY case_id")
            case_ids = [int(row[0]) for row in cursor.fetchall()]
    repository = PostgresCostCaseRepository(dsn, connect_timeout=10)
    rows = repository.fetch_case_items(case_ids, part_codes)
    grouped: dict[tuple[int, str], list[CostCaseRow]] = defaultdict(list)
    for row in rows:
        grouped[(row.case_id, row.part_code)].append(row)
    raw: dict[str, Any] = {part: new_part_stat() for part in part_codes}
    for (case_id, part_code), case_rows in grouped.items():
        included = [row for row in case_rows if _is_included_row(row)]
        if not any(row.line_type == "WORK" for row in included):
            continue
        aggregate = _aggregate_case(case_id, part_code, included)
        if aggregate is not None:
            add_valid_case({"partStats": raw}, part_code, aggregate)
    return summarize_part_stats(raw)


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.2f}"
    return f"{value:,}" if isinstance(value, int) else html.escape(str(value))


def render_html(payload: dict[str, Any]) -> str:
    inventory = payload["inventory"]
    full = payload["fullSourcePartStats"]
    dev = payload["devPartStats"]
    focus_parts = sorted(set(FOCUS_PARTS) | set(full) & set(FOCUS_PARTS))
    focus_rows = []
    for part in focus_parts:
        fs = full.get(part, {})
        ds = dev.get(part, {})
        focus_rows.append(
            f"<tr><td>{html.escape(part)}</td><td>{fmt(fs.get('validFullRepairCaseCount', 0))}</td>"
            f"<td>{fmt(fs.get('groups', {}).get('EXCHANGE_INCLUDED', {}).get('caseCount', 0))}</td>"
            f"<td>{fmt(fs.get('groups', {}).get('REPAIR_FAMILY', {}).get('caseCount', 0))}</td>"
            f"<td>{'YES' if fs.get('atLeast', {}).get('10') else 'NO'}</td>"
            f"<td>{fmt(ds.get('validFullRepairCaseCount', 0))}</td><td>{'YES' if ds.get('atLeast', {}).get('10') else 'NO'}</td>"
            f"<td>{fmt((fs.get('costDistribution') or {}).get('p25'))} / {fmt((fs.get('costDistribution') or {}).get('median'))} / {fmt((fs.get('costDistribution') or {}).get('p75'))}</td></tr>"
        )
    all_parts = []
    for part in sorted(set(full) | set(dev)):
        fs, ds = full.get(part, {}), dev.get(part, {})
        all_parts.append(
            f"<tr><td>{html.escape(part)}</td><td>{fmt(fs.get('validFullRepairCaseCount', 0))}</td>"
            f"<td>{fmt(fs.get('groups', {}).get('EXCHANGE_INCLUDED', {}).get('caseCount', 0))}</td>"
            f"<td>{fmt(fs.get('groups', {}).get('REPAIR_FAMILY', {}).get('caseCount', 0))}</td>"
            f"<td>{fmt(fs.get('groups', {}).get('OTHER_VALID_WORK', {}).get('caseCount', 0))}</td>"
            f"<td>{fmt(ds.get('validFullRepairCaseCount', 0))}</td><td>{'YES' if fs.get('atLeast', {}).get('10') else 'NO'}</td></tr>"
        )
    unmapped = sorted(payload["unmappedRawItemNames"].items(), key=lambda item: (-item[1], item[0]))[:100]
    unmapped_rows = "".join(f"<tr><td>{html.escape(name)}</td><td>{count:,}</td></tr>" for name, count in unmapped)
    counts = inventory["counts"]
    inv_rows = "".join(
        f"<tr><th>{html.escape(label)}</th><td>{fmt(value)}</td></tr>"
        for label, value in (
            ("aihub_estimate_raw 문서 수", counts["rawDocuments"]),
            ("aihub_estimate_raw distinct external_ref", counts["rawDistinctExternalRefs"]),
            ("원본 견적 행 수(수리내역 배열 원소)", counts["rawRows"]),
            ("repair_case 수", counts["repairCases"]),
            ("repair_case_item 수", counts["repairCaseItems"]),
            ("원본↔case 연결 distinct ref", counts["linkedRawRefs"]),
        )
    )
    return f"""<!doctype html><html lang='ko'><meta charset='utf-8'><title>full source estimate supply audit</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left;vertical-align:top}}th{{background:#eef2f7}}.warn{{background:#fff7ed;color:#92400e;padding:12px;border-radius:8px}}.focus{{background:#eff6ff}}.small{{color:#64748b}}</style>
<main><h1>AI-Hub 원본 견적서 전체 · 유효 FULL_REPAIR 공급량 audit</h1>
<p class='warn'>이 보고서는 DB 전체에 비용 사례가 존재하는지만 확인합니다. 원본 이미지/feature/embedding 검색이나 Top-100 유사성 안에 10건이 들어오는지는 판단하지 못하며, 전수 이미지 적재 후 별도 audit이 필요합니다. DB write, migration, API 변경은 없습니다.</p>
<section><h2>DB inventory</h2><table>{inv_rows}</table><p class='small'>원본 연결 키: <code>aihub_estimate_raw.external_ref = repair_case.external_ref</code>. 125,006은 견적서 문서 수이며 견적 행 수가 아닙니다.</p></section>
<section><h2>주요 부품 비교</h2><table><tr><th>partCode</th><th>원본 유효 case</th><th>EXCHANGE</th><th>REPAIR_FAMILY</th><th>원본 10+</th><th>DEV 유효 case</th><th>DEV 10+</th><th>원본 p25 / median / p75</th></tr>{''.join(focus_rows)}</table></section>
<section><h2>전체 canonical partCode 공급량</h2><table><tr><th>partCode</th><th>원본 유효 case</th><th>EXCHANGE</th><th>REPAIR_FAMILY</th><th>OTHER</th><th>DEV 유효 case</th><th>원본 10+</th></tr>{''.join(all_parts)}</table></section>
<section><h2>매핑 실패 raw_item_name 상위</h2><table><tr><th>raw_item_name</th><th>행 수</th></tr>{unmapped_rows}</table><p class='small'>표준화는 기존 <code>normalize_estimate_item</code> 및 기존 part_name_mapping을 재사용했습니다.</p></section>
<section><h2>운영 해석</h2><p>EXCHANGE_INCLUDED, REPAIR_FAMILY, OTHER_VALID_WORK는 같은 분포로 합치지 않고 별도로 집계했습니다. 유효 행은 기존 EstimateService의 NOT_APPROVED/제외 작업코드/WORK+PART_PRICE 및 source-aware 비용 규칙을 재사용했고, PART_PRICE만 있는 문서는 FULL_REPAIR에서 제외했습니다.</p></section></main></html>"""


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    inventory = db_inventory(dsn)
    mapping = load_part_mapping(dsn)
    state_file = checkpoint_path(args.output_json.resolve())
    state = load_state(state_file, args.resume)
    start_after = int(state.get("processedDocuments", 0)) if args.resume else 0
    process_limit = args.limit
    processed_this_run = 0
    for index, (source, external_ref, payload) in enumerate(iter_raw_documents(dsn)):
        if index < start_after:
            continue
        if process_limit is not None and processed_this_run >= process_limit:
            break
        state["processedDocuments"] = index + 1
        state["sourceDocuments"][source] = int(state["sourceDocuments"].get(source, 0)) + 1
        items = payload.get("수리내역") if isinstance(payload, dict) else None
        state["sourceRows"][source] = int(state["sourceRows"].get(source, 0)) + (len(items) if isinstance(items, list) else 0)
        process_document(state, source, payload, mapping, index + 1)
        processed_this_run += 1
        if processed_this_run % args.batch_size == 0:
            save_state(state_file, state)
    save_state(state_file, state)
    full_stats = summarize_part_stats(state["partStats"])
    canonical_parts = sorted(set(mapping.values()) | set(full_stats))
    dev_stats = dev_supply(dsn, canonical_parts)
    payload = {
        "status": "SUCCEEDED" if state["processedDocuments"] >= inventory["counts"]["rawDocuments"] else "PARTIAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "readOnly": True,
        "inventory": inventory,
        "processedDocuments": state["processedDocuments"],
        "mapping": {
            "mappingTableRows": len(mapping),
            "rawItemRows": state["mappingRows"],
            "successRows": state["mappingSuccessRows"],
            "failedRows": state["mappingFailedRows"],
            "normalizationErrors": state["normalizationErrors"],
            "normalizationErrorRawNames": state["normalizationErrorRawNames"],
        },
        "unmappedRawItemNames": state["unmappedRawItemNames"],
        "fullSourcePartStats": full_stats,
        "devPartStats": dev_stats,
        "verification": {
            "databaseWrites": False, "migrationChanged": False,
            "embeddingChanged": False, "featureChanged": False,
            "apiChanged": False, "pipelineActivationChanged": False,
            "repairHintUsed": False, "similarityEvaluated": False,
        },
        "interpretation": {
            "answersSupplyOnly": True,
            "cannotAnswerTop100Similarity": True,
            "nextStep": "원본 이미지/feature/embedding 전수 적재 후 검색 유사성 audit을 별도로 수행",
        },
    }
    args.output_json.resolve().parent.mkdir(parents=True, exist_ok=True)
    args.output_json.resolve().write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.resolve().parent.mkdir(parents=True, exist_ok=True)
    args.output_html.resolve().write_text(render_html(payload), encoding="utf-8")
    print(json.dumps({
        "status": payload["status"], "processedDocuments": state["processedDocuments"],
        "rawDocuments": inventory["counts"]["rawDocuments"],
        "rawRows": inventory["counts"]["rawRows"],
        "repairCases": inventory["counts"]["repairCases"],
        "repairCaseItems": inventory["counts"]["repairCaseItems"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
