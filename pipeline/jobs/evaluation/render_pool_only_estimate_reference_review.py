"""Render a human review set for FULL_REPAIR candidates found only in Top-100.

The renderer consumes the completed read-only coverage audit. It does not rerun
YOLO, write PostgreSQL, or use repair hints. Candidate rank is deliberately
reported as the order among valid FULL_REPAIR candidates retained by the audit;
the audit JSON does not persist every non-cost pool hit needed to reconstruct an
absolute pgvector rank.
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import os
import random
import statistics
import sys
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    data_url, resolve_image_path,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--audit-json", type=Path, required=True)
    parser.add_argument("--selection-output", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sample-count", type=int, default=24)
    parser.add_argument("--seed", type=int, default=307)
    args = parser.parse_args()
    if args.sample_count < 1:
        parser.error("sample-count must be positive")
    return args


def load_audit(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    audits = payload.get("audits")
    if not isinstance(audits, list):
        raise ValueError("audit JSON has no audits array")
    pool_only = [row for row in audits if row.get("status") == "ESTIMABLE_IN_POOL_ONLY"]
    if not pool_only:
        raise ValueError("audit JSON has no ESTIMABLE_IN_POOL_ONLY queries")
    return payload, pool_only


def manifest_refs(path: Path) -> set[str]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        return {str(row.get("source_image_ref")) for row in csv.DictReader(fp)
                if row.get("source_image_ref")}


def stratified_selection(
    candidates: list[dict[str, Any]], count: int, seed: int,
) -> list[dict[str, Any]]:
    rng = random.Random(seed)
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for candidate in candidates:
        key = (str(candidate.get("partCode") or "-"), str(candidate.get("damageType") or "-"))
        groups.setdefault(key, []).append(candidate)
    for values in groups.values():
        rng.shuffle(values)
    keys = sorted(groups, key=lambda key: (-len(groups[key]), key))
    selected: list[dict[str, Any]] = []
    used_cases: set[str] = set()
    while len(selected) < min(count, len(candidates)) and keys:
        progressed = False
        for key in keys:
            while groups[key] and str(groups[key][0].get("caseId")) in used_cases:
                groups[key].pop(0)
            if not groups[key]:
                continue
            row = groups[key].pop(0)
            used_cases.add(str(row.get("caseId")))
            selected.append(row)
            progressed = True
            if len(selected) >= count:
                break
        if not progressed:
            break
    # If case de-duplication left gaps, fill from remaining cases deterministically.
    if len(selected) < count:
        remaining = [row for row in candidates if str(row.get("caseId")) not in used_cases]
        rng.shuffle(remaining)
        selected.extend(remaining[:count - len(selected)])
    output: list[dict[str, Any]] = []
    for row in selected[:count]:
        item = dict(row)
        item["selectionReason"] = "ESTIMABLE_IN_POOL_ONLY_stratified"
        item["selectionStratum"] = f"{item.get('partCode') or '-'} / {item.get('damageType') or '-'}"
        output.append(item)
    return output


def percentiles(values: list[int]) -> dict[str, int]:
    if not values:
        return {}
    if len(values) == 1:
        return {"p25": values[0], "median": values[0], "p75": values[0]}
    q1, _, q3 = statistics.quantiles(values, n=4, method="inclusive")
    return {"p25": round(q1), "median": round(statistics.median(values)), "p75": round(q3)}


def enrich_query(query: dict[str, Any]) -> dict[str, Any]:
    refs = sorted(
        query.get("validWorkCostCases") or [],
        key=lambda item: (-(float(item.get("similarity") or 0.0)), int(item.get("caseId", 0))),
    )[:10]
    for index, item in enumerate(refs, 1):
        item["poolValidCostRank"] = index
    yolo_ranks = {
        int(item.get("caseId")): index
        for index, item in enumerate(query.get("yoloTop10") or [], 1)
    }
    for item in refs:
        item["yoloTop10Rank"] = yolo_ranks.get(int(item["caseId"]))
        item["inYoloTop10"] = item["yoloTop10Rank"] is not None
        item["includedInDistribution"] = True
    query = dict(query)
    query["referenceCases"] = refs
    query["referenceDistribution"] = percentiles([int(item["total"]) for item in refs])
    query["referenceCount"] = len(refs)
    query["top10OutsideReferenceRanks"] = [
        int(item["poolValidCostRank"]) for item in refs if not item["inYoloTop10"]
    ]
    return query


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def image_tag(root: Path, source_ref: str, cache: dict[str, str], alt: str) -> str:
    if source_ref not in cache:
        path = resolve_image_path(root, source_ref)
        cache[source_ref] = data_url(path, max_size=(420, 270)) if path.is_file() else ""
    src = cache[source_ref]
    return f'<img class="card-image" src="{src}" alt="{html.escape(alt)}">' if src else '<div class="missing">image missing</div>'


def result_card(root: Path, item: dict[str, Any], rank: int, cache: dict[str, str], *,
                reference: bool, reference_case_ids: set[int] | None = None) -> str:
    ref_rank = item.get("poolValidCostRank")
    yolo_rank = item.get("yoloTop10Rank")
    if reference:
        rank_text = f"유효비용 후보 #{ref_rank}"
        outside = (f"YOLO Top-10 밖 · 후보 순위 #{ref_rank}" if yolo_rank is None
                   else f"YOLO Top-10 #{yolo_rank}")
        details = (
            ("vector similarity", item.get("similarity")),
            ("YOLO part confidence", item.get("corpusPartConfidence")),
            ("YOLO overlap", item.get("corpusPartOverlap")),
            ("FULL_REPAIR total", f"{int(item['total']):,}"),
            ("distribution included", "YES" if item.get("includedInDistribution") else "NO"),
            ("pool position", outside),
        )
        cls = "reference-card"
    else:
        rank_text = f"YOLO rerank #{rank}"
        ref_case_ids = reference_case_ids or set()
        details = (
            ("source image path", item.get("sourceImageRef")),
            ("vector similarity", item.get("similarity")),
            ("corpus part", item.get("corpusPartCode")),
            ("YOLO part confidence", item.get("corpusPartConfidence")),
            ("YOLO overlap", item.get("corpusPartOverlap")),
            ("cost reference", "YES" if int(item.get("caseId", -1)) in ref_case_ids else "NO"),
        )
        cls = "search-card"
    rows = "".join(
        f"<dt>{html.escape(str(label))}</dt><dd>{html.escape(fmt(value))}</dd>"
        for label, value in details
    )
    return (f'<article class="card {cls}"><h4>{html.escape(rank_text)} · case {item.get("caseId")}</h4>'
            f'{image_tag(root, str(item.get("sourceImageRef") or ""), cache, rank_text)}'
            f'<p class="path">{html.escape(str(item.get("sourceImageRef") or "-"))}</p><dl>{rows}</dl></article>')


def render_query(root: Path, query: dict[str, Any], cache: dict[str, str]) -> str:
    qref = str(query.get("sourceImageRef") or "")
    qdetails = (
        ("source path", qref), ("damageType", query.get("damageType")),
        ("YOLO partCode", query.get("partCode")), ("part confidence", query.get("partConfidence")),
        ("damage confidence", query.get("damageConfidence")), ("pairStatus", query.get("pairStatus")),
        ("bbox", query.get("bbox")), ("selection", query.get("selectionReason")),
    )
    meta = "".join(f"<dt>{html.escape(str(k))}</dt><dd>{html.escape(fmt(v))}</dd>" for k, v in qdetails)
    reference_case_ids = {int(item.get("caseId")) for item in query.get("referenceCases", [])}
    search_cards = "".join(
        result_card(root, item, rank, cache, reference=False, reference_case_ids=reference_case_ids)
        for rank, item in enumerate(query.get("yoloTop10") or [], 1)
    )
    reference_cards = "".join(
        result_card(root, item, rank, cache, reference=True)
        for rank, item in enumerate(query.get("referenceCases") or [], 1)
    )
    dist = query.get("referenceDistribution") or {}
    return f'''<section class="query-section"><h2>{html.escape(str(query.get("externalRef") or query.get("caseId")))}</h2>
<div class="query-header">{image_tag(root, qref, cache, "query")}<dl>{meta}</dl></div>
<div class="columns"><div><h3>YOLO flat 0.05 검색 Top-10</h3><div class="cards">{search_cards}</div></div>
<div><h3>Top-100 FULL_REPAIR 견적 참조 후보</h3><p>참조 {query.get('referenceCount', 0)}건 · p25 {fmt(dist.get('p25'))} · median {fmt(dist.get('median'))} · p75 {fmt(dist.get('p75'))}</p><p class="notice">검색 표시 사례와 견적 참조 사례는 다를 수 있음</p><div class="cards">{reference_cards}</div></div></div>
<div class="notes"><strong>사람 검토 메모</strong><label>견적 참조 사례가 시각적으로 납득되는가?<textarea rows="2"></textarea></label><label>Top-10 밖 사례를 비용 근거로 써도 되는가?<textarea rows="2"></textarea></label><label>유사도 하한이 더 필요한가?<textarea rows="2"></textarea></label></div></section>'''


def render_html(selected: list[dict[str, Any]], root: Path) -> str:
    cache: dict[str, str] = {}
    all_refs = [item for query in selected for item in query.get("referenceCases", [])]
    outside_ranks = [rank for query in selected for rank in query.get("top10OutsideReferenceRanks", [])]
    median_similarity = statistics.median([float(item.get("similarity") or 0.0) for item in all_refs]) if all_refs else None
    avg_outside_rank = statistics.mean(outside_ranks) if outside_ranks else None
    part_counts: dict[str, int] = {}
    damage_counts: dict[str, int] = {}
    for query in selected:
        part_counts[str(query.get("partCode") or "-")] = part_counts.get(str(query.get("partCode") or "-"), 0) + 1
        damage_counts[str(query.get("damageType") or "-")] = damage_counts.get(str(query.get("damageType") or "-"), 0) + 1
    approval_rows = "".join(
        f"<tr><td>{html.escape(str(query.get('externalRef') or query.get('caseId')))}</td><td>{html.escape(str(query.get('partCode') or '-'))}</td><td>{html.escape(str(query.get('damageType') or '-'))}</td><td contenteditable='true'></td><td contenteditable='true'></td>"
        f"<td contenteditable='true'></td></tr>" for query in selected
    )
    sections = "".join(render_query(root, query, cache) for query in selected)
    part_summary = ", ".join(f"{html.escape(k)}: {v}" for k, v in sorted(part_counts.items()))
    damage_summary = ", ".join(f"{html.escape(k)}: {v}" for k, v in sorted(damage_counts.items()))
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>Pool-only FULL_REPAIR reference review</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3,h4{{margin:0 0 10px}}.summary,.query-section,.review-table{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:18px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;padding:10px 14px;border-radius:8px}}.stat b{{font-size:22px;display:block}}.query-header{{display:flex;gap:16px;margin-bottom:14px}}.query-header>.card-image,.query-header>.missing{{width:340px;height:240px;object-fit:contain;background:#eef2f7}}dl{{display:grid;grid-template-columns:155px 1fr;gap:4px;flex:1}}dt{{color:#64748b}}dd{{margin:0;overflow-wrap:anywhere;font-size:12px}}.columns{{display:grid;grid-template-columns:1fr 1fr;gap:16px}}.columns>div{{background:#f8fafc;border-radius:8px;padding:12px}}.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px}}.card{{background:#fff;border:1px solid #d8deea;border-radius:8px;padding:9px}}.reference-card{{border-left:4px solid #d97706}}.search-card{{border-left:4px solid #64748b}}.card-image,.missing{{width:100%;height:145px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}.path{{font-size:11px;overflow-wrap:anywhere;color:#475569;min-height:30px}}.card dl{{grid-template-columns:125px 1fr}}.card dd{{font-size:11px}}.notice{{background:#fff7ed;color:#92400e;padding:8px;border-radius:6px}}.notes{{margin-top:16px;background:#f8fafc;padding:12px;border-radius:8px}}.notes label{{display:block;margin-top:8px}}textarea{{width:100%;box-sizing:border-box;margin-top:4px}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:7px;text-align:left;min-width:80px}}th{{background:#f1f4fa}}td[contenteditable='true']{{height:28px;background:#fffdf2}}@media(max-width:1000px){{.columns{{grid-template-columns:1fr}}.query-header{{display:block}}.query-header>.card-image{{width:100%}}}}
</style><main><h1>v2 YOLO · Top-100 FULL_REPAIR 견적 참조 후보 사람 검토</h1><p class="notice">자동 판정은 포함하지 않았습니다. 검색 화면 Top-10과 견적 참조 후보는 서로 다를 수 있으며, 본 화면은 운영 연결 전 시각 검토용입니다.</p>
<div class="summary"><h2>전체 요약</h2><div class="stats"><div class="stat"><b>{len(selected)}</b>선택 query</div><div class="stat"><b>{len(all_refs)}</b>견적 참조 후보</div><div class="stat"><b>{fmt(median_similarity)}</b>참조 후보 median similarity</div><div class="stat"><b>{fmt(avg_outside_rank)}</b>Top-10 밖 평균 rank<br><small>유효 WORK 후보 순위</small></div></div><p>부품별: {part_summary}</p><p>damageType별: {damage_summary}</p></div>
<div class="review-table"><h2>사람 승인 기록</h2><table><tr><th>query</th><th>partCode</th><th>damageType</th><th>승인 가능 / 보류 / 불가</th><th>근거 메모</th><th>추가 검토</th></tr>{approval_rows}</table></div>{sections}</main></html>'''


def main() -> None:
    args = parse_args()
    if not os.environ.get("DATABASE_URL"):
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    if not args.query_manifest.is_file():
        raise SystemExit(f"query manifest not found: {args.query_manifest}")
    refs = manifest_refs(args.query_manifest.resolve())
    _, pool_only = load_audit(args.audit_json.resolve())
    missing_refs = [row for row in pool_only if row.get("sourceImageRef") not in refs]
    if missing_refs:
        raise SystemExit(f"audit source paths missing from query manifest: {len(missing_refs)}")
    selected = [enrich_query(row) for row in stratified_selection(pool_only, args.sample_count, args.seed)]
    if len({str(row.get("caseId")) for row in selected}) != len(selected):
        raise SystemExit("selection contains duplicate case_id")
    selection_payload = {
        "status": "SUCCEEDED", "seed": args.seed, "sourceStatus": "ESTIMABLE_IN_POOL_ONLY",
        "candidateCount": len(pool_only), "selectedCount": len(selected),
        "auditJson": str(args.audit_json.resolve()), "queryManifest": str(args.query_manifest.resolve()),
        "selected": selected,
    }
    args.selection_output.parent.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.selection_output.write_text(json.dumps(selection_payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.write_text(render_html(selected, args.dataset_root.resolve()), encoding="utf-8")
    all_refs = [item for query in selected for item in query.get("referenceCases", [])]
    outside = [rank for query in selected for rank in query.get("top10OutsideReferenceRanks", [])]
    print(json.dumps({
        "status": "SUCCEEDED", "selectedQueries": len(selected),
        "referenceMedianSimilarity": statistics.median([float(item.get("similarity") or 0.0) for item in all_refs]) if all_refs else None,
        "top10OutsideAverageRank": statistics.mean(outside) if outside else None,
        "selectionOutput": str(args.selection_output), "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
