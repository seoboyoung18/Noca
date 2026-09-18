"""Render only the ranking changes between v2 vector and YOLO flat reranking."""
from __future__ import annotations

import argparse
import html
import json
import os
import sys
from types import SimpleNamespace
from pathlib import Path
from typing import Any

from PIL import Image

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.services.estimate_service import EstimateService, _aggregate_case, _is_included_row
from pipeline.jobs.evaluation.render_query_comparison import data_url
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    resolve_image_path,
    rerank,
)
from pipeline.jobs.ingestion.embed_search_corpus import roi_from_feature_box
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True,
                        help="kept for reproducibility and source-path validation")
    parser.add_argument("--selection-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--flat-boost", type=float, default=0.05)
    args = parser.parse_args()
    if args.top_k < 1 or args.candidate_k < args.top_k:
        parser.error("candidate-k must be >= top-k >= 1")
    if args.flat_boost < 0:
        parser.error("flat-boost must be non-negative")
    return args


def load_selection(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fp:
        payload = json.load(fp)
    selected = payload.get("selected")
    if not isinstance(selected, list) or not selected:
        raise ValueError("selection JSON has no selected query rows")
    return payload


def hit_map(hits: list[SearchHit]) -> dict[int, tuple[int, SearchHit]]:
    return {hit.case_id: (rank, hit) for rank, hit in enumerate(hits, 1)}


def diff_rows(baseline: list[SearchHit], reranked: list[SearchHit]) -> list[dict[str, Any]]:
    before, after = hit_map(baseline), hit_map(reranked)
    rows: list[dict[str, Any]] = []
    for case_id in sorted(set(before) | set(after), key=lambda value: (
        after.get(value, before.get(value))[0], value)):
        old = before.get(case_id)
        new = after.get(case_id)
        old_rank = old[0] if old else None
        new_rank = new[0] if new else None
        if old_rank == new_rank:
            continue
        hit = new[1] if new else old[1]
        if old_rank is None:
            kind, change = "NEW", f"NEW → #{new_rank}"
        elif new_rank is None:
            kind, change = "OUT", f"#{old_rank} → OUT"
        else:
            kind, change = "MOVED", f"#{old_rank} → #{new_rank} ({old_rank - new_rank:+d})"
        rerank_hit = new[1] if new else None
        rows.append({
            "case_id": case_id, "kind": kind, "change": change,
            "baseline_rank": old_rank, "rerank_rank": new_rank,
            "hit": hit, "rerank_hit": rerank_hit,
            "boosted": bool(rerank_hit and rerank_hit.ranking_reason == "YOLO_PART_BOOSTED"),
        })
    return rows


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def cost_profile(
    service: EstimateService,
    repository: PostgresCostCaseRepository,
    query: dict[str, Any],
    hits: list[SearchHit],
) -> dict[str, Any]:
    """Use EstimateService plus its CostCaseRepository for one Top-10 list."""
    part_code = query.get("part_code")
    if query.get("pair_status") != "PAIRED" or not part_code:
        return {"eligible": False, "reason": "query part 미확정", "connected_ids": set(),
                "included_ids": set(), "cost_totals": {}, "distribution": {}}
    case_ids = sorted({int(hit.case_id) for hit in hits})
    # EstimateService only consumes the request's ``parts`` attribute.  A
    # lightweight request keeps this read-only renderer runnable without the
    # HTTP server's optional Pydantic dependency.
    request = SimpleNamespace(parts=[{
            "partCode": part_code,
            "damageType": query.get("damage_type"),
            "searchability": "STRICT",
            "pairStatus": "PAIRED",
            "confidence": query.get("part_confidence"),
            "referencedCaseIds": case_ids,
        }])
    estimate = service.calculate(request)
    item = (estimate.get("items") or [None])[0]
    included_ids = set(int(case_id) for case_id in (item or {}).get("referencedCaseIds", []))
    distribution = dict((item or {}).get("costDistribution") or {})
    rows = repository.fetch_case_items(case_ids, [str(part_code)])
    by_case: dict[int, list[Any]] = {}
    for row in rows:
        by_case.setdefault(int(row.case_id), []).append(row)
    cost_totals: dict[int, int] = {}
    for case_id, case_rows in by_case.items():
        included_rows = [row for row in case_rows if _is_included_row(row)]
        case_cost = _aggregate_case(case_id, str(part_code), included_rows)
        if case_cost is not None:
            cost_totals[case_id] = int(case_cost.total)
    return {
        "eligible": True,
        "reason": None if estimate.get("estimable") else estimate.get("nonEstimableReason"),
        "connected_ids": set(cost_totals),
        "included_ids": included_ids,
        "cost_totals": cost_totals,
        "distribution": distribution,
        "estimable": bool(estimate.get("estimable")),
    }


def cost_summary(baseline: dict[str, Any], reranked: dict[str, Any], top_k: int) -> dict[str, Any]:
    baseline_dist, rerank_dist = baseline.get("distribution", {}), reranked.get("distribution", {})
    b_median, r_median = baseline_dist.get("median"), rerank_dist.get("median")
    return {
        "eligible": bool(baseline.get("eligible") and reranked.get("eligible")),
        "baseline_connected": len(baseline.get("connected_ids", set())),
        "rerank_connected": len(reranked.get("connected_ids", set())),
        "baseline_ratio": len(baseline.get("connected_ids", set())) / top_k,
        "rerank_ratio": len(reranked.get("connected_ids", set())) / top_k,
        "baseline_included": len(baseline.get("included_ids", set())),
        "rerank_included": len(reranked.get("included_ids", set())),
        "baseline_distribution": baseline_dist,
        "rerank_distribution": rerank_dist,
        "median_delta": (r_median - b_median) if b_median is not None and r_median is not None else None,
        "new_connected": len(set(reranked.get("connected_ids", set())) - set(baseline.get("connected_ids", set()))),
        "dropped_connected": len(set(baseline.get("connected_ids", set())) - set(reranked.get("connected_ids", set()))),
    }


def query_meta(query: dict[str, Any]) -> str:
    pairs = (
        ("source path", query.get("source_image_ref")),
        ("damageType", query.get("damage_type")),
        ("partCode", query.get("part_code")),
        ("pairStatus", query.get("pair_status")),
        ("part confidence", query.get("part_confidence")),
        ("damage confidence", query.get("damage_confidence")),
        ("bbox", query.get("bbox")),
    )
    return "".join(f"<dt>{html.escape(label)}</dt><dd>{html.escape(fmt(value))}</dd>"
                   for label, value in pairs)


def change_card(row: dict[str, Any], root: Path) -> str:
    hit: SearchHit = row["hit"]
    path = resolve_image_path(root, str(hit.source_image_ref or ""))
    image = data_url(path) if path.is_file() else ""
    image_body = (f'<img src="{image}" alt="case {hit.case_id}">' if image
                  else '<div class="missing">image missing</div>')
    status = "YOLO_PART_BOOSTED" if row["boosted"] else "VECTOR_ONLY / DISPLACED"
    cls = "boosted" if row["boosted"] else "displaced"
    rerank_hit: SearchHit | None = row["rerank_hit"]
    rerank_similarity = (rerank_hit.reranked_similarity if rerank_hit else None)
    cost_total = row.get("cost_total")
    cost_included = row.get("cost_included")
    values = (
        ("source image path", hit.source_image_ref),
        ("corpusPartCode", hit.corpus_part_code),
        ("corpusPartConfidence", hit.corpus_part_confidence),
        ("corpusPartOverlap", hit.corpus_part_overlap),
        ("vector similarity", hit.vector_similarity),
        ("reranked similarity", rerank_similarity),
        ("ranking reason", rerank_hit.ranking_reason if rerank_hit else "VECTOR_ONLY_FALLBACK"),
        ("cost included", "YES" if cost_included else "NO"),
        ("estimate total", cost_total),
    )
    details = "".join(f"<dt>{html.escape(label)}</dt><dd>{html.escape(fmt(value))}</dd>"
                      for label, value in values)
    return (f'<article class="change-card {cls}"><div class="change">{html.escape(row["change"])}</div>'
            f'<span class="badge">{status}</span><h3>case {hit.case_id}</h3>{image_body}'
            f'<dl>{details}</dl></article>')


def render_query_section(item: dict[str, Any], root: Path, top_k: int, flat_boost: float) -> str:
    query = item["query"]
    qpath = resolve_image_path(root, str(query["source_image_ref"]))
    qimg = data_url(qpath) if qpath.is_file() else ""
    rows: list[dict[str, Any]] = item["diffs"]
    cost = item["cost_summary"]
    paired = query.get("pair_status") == "PAIRED" and query.get("part_code")
    if paired:
        badge = f'<span class="badge active">Boost 적용 · flat {flat_boost:g}</span>'
    else:
        reason = query.get("pair_status") or "PART_NOT_DETECTED"
        badge = f'<span class="badge fallback">Vector-only fallback · {html.escape(str(reason))}; baseline/rerank 동일</span>'
    rows_html = "".join(
        f'<tr><td>{html.escape(str(r["baseline_rank"] or "NEW"))}</td>'
        f'<td>{html.escape(str(r["rerank_rank"] or "OUT"))}</td>'
        f'<td><strong>{html.escape(r["change"])}</strong></td><td>{r["case_id"]}</td>'
        f'<td>{html.escape(fmt(r["hit"].corpus_part_code))}</td>'
        f'<td>{html.escape(fmt(r["hit"].corpus_part_confidence))}</td>'
        f'<td>{html.escape(fmt(r["hit"].corpus_part_overlap))}</td>'
        f'<td>{html.escape(fmt(r["hit"].vector_similarity))}</td>'
        f'<td>{html.escape(fmt(r["rerank_hit"].reranked_similarity if r["rerank_hit"] else None))}</td>'
        f'<td>{html.escape(r["rerank_hit"].ranking_reason if r["rerank_hit"] else "VECTOR_ONLY_FALLBACK")}</td></tr>'
        for r in rows)
    table = ("<p class=unchanged>Top-10 순위 변화 없음</p>" if not rows else
             '<table><tr><th>baseline</th><th>rerank</th><th>변화</th><th>case ID</th><th>corpus part</th><th>conf.</th><th>overlap</th><th>vector</th><th>reranked</th><th>reason</th></tr>' + rows_html + "</table>")
    cards = "".join(change_card(row, root) for row in rows)
    if not cost["eligible"]:
        cost_table = '<p class="cost-unavailable">비용 비교 불가: query part 미확정</p>'
    else:
        bd, rd = cost["baseline_distribution"], cost["rerank_distribution"]
        def dist(data: dict[str, Any], key: str) -> str:
            return fmt(data.get(key))
        cost_table = (f'<table class="cost-table"><tr><th>항목</th><th>Pure vector</th><th>YOLO rerank</th><th>변화</th></tr>'
                      f'<tr><td>Top-10 견적 연결 가능 사례 수</td><td>{cost["baseline_connected"]}</td><td>{cost["rerank_connected"]}</td><td>{cost["rerank_connected"]-cost["baseline_connected"]:+d}</td></tr>'
                      f'<tr><td>비용 산출 포함 사례 수</td><td>{cost["baseline_included"]}</td><td>{cost["rerank_included"]}</td><td>{cost["rerank_included"]-cost["baseline_included"]:+d}</td></tr>'
                      f'<tr><td>p25</td><td>{dist(bd,"p25")}</td><td>{dist(rd,"p25")}</td><td>{(rd.get("p25")-bd.get("p25")) if bd.get("p25") is not None and rd.get("p25") is not None else "-"}</td></tr>'
                      f'<tr><td>median</td><td>{dist(bd,"median")}</td><td>{dist(rd,"median")}</td><td>{fmt(cost["median_delta"])}</td></tr>'
                      f'<tr><td>p75</td><td>{dist(bd,"p75")}</td><td>{dist(rd,"p75")}</td><td>{(rd.get("p75")-bd.get("p75")) if bd.get("p75") is not None and rd.get("p75") is not None else "-"}</td></tr></table>'
                      f'<p class="cost-note">연결 가능 비율: {cost["baseline_ratio"]:.0%} → {cost["rerank_ratio"]:.0%} · 신규 연결 {cost["new_connected"]} · 이탈 연결 {cost["dropped_connected"]}</p>')
    return (f'<section class="query-section"><div class="query-heading"><h2>{html.escape(str(query.get("external_ref") or query.get("case_id")))}</h2>{badge}</div>'
            f'<div class="query"><img src="{qimg}" alt="query"><dl>{query_meta(query)}</dl></div>'
            f'<h3>순위 변화 · {len(rows)}건</h3>{table}<h3>견적 연결 전/후</h3>{cost_table}<div class="changes">{cards}</div>'
            '<div class="notes"><strong>사람 검토 메모</strong><p>이동이 자연스러운가?</p><textarea rows="2"></textarea><p>부품 판정이 납득되는가?</p><textarea rows="2"></textarea><p>색상/차종 유사도만으로 이동한 것으로 보이는가?</p><textarea rows="2"></textarea></div></section>')


def render_html(args: argparse.Namespace, items: list[dict[str, Any]]) -> str:
    total_changes = sum(len(item["diffs"]) for item in items)
    new_count = sum(sum(row["kind"] == "NEW" for row in item["diffs"]) for item in items)
    out_count = sum(sum(row["kind"] == "OUT" for row in item["diffs"]) for item in items)
    paired = sum(bool(item["query"].get("pair_status") == "PAIRED" and item["query"].get("part_code")) for item in items)
    boost_queries = sum(any(row["boosted"] for row in item["diffs"]) for item in items)
    unchanged = sum(not item["diffs"] for item in items)
    cost_items = [item["cost_summary"] for item in items if item["cost_summary"]["eligible"]]
    avg_baseline_ratio = sum(item["baseline_ratio"] for item in cost_items) / len(cost_items) if cost_items else 0.0
    avg_rerank_ratio = sum(item["rerank_ratio"] for item in cost_items) / len(cost_items) if cost_items else 0.0
    cost_up = sum(item["rerank_connected"] > item["baseline_connected"] for item in cost_items)
    cost_down = sum(item["rerank_connected"] < item["baseline_connected"] for item in cost_items)
    cost_same = sum(item["rerank_connected"] == item["baseline_connected"] for item in cost_items)
    sections = "".join(render_query_section(item, args.dataset_root.resolve(), args.top_k, args.flat_boost) for item in items)
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>v2 YOLO rank diff review</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}.summary,.query,.query-section,article{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:14px}}.summary{{margin:16px 0}}.stats{{display:flex;flex-wrap:wrap;gap:10px}}.stat{{background:#eef2ff;border-radius:8px;padding:10px 14px}}.stat b{{font-size:22px;display:block}}.query-section{{margin-top:28px;border-top:4px solid #5968ee}}.query-heading{{display:flex;justify-content:space-between;align-items:center;gap:12px}}.query{{display:flex;gap:16px;max-width:960px;margin:12px 0}}.query img{{width:340px;max-height:250px;object-fit:contain;background:#eef2f7}}.badge{{display:inline-block;border-radius:999px;padding:5px 9px;background:#e5e7eb;color:#374151;font-weight:600;font-size:12px}}.badge.active{{background:#dcfce7;color:#166534}}.badge.fallback{{background:#fef3c7;color:#92400e}}table{{width:100%;border-collapse:collapse;background:#fff;font-size:12px}}th,td{{border:1px solid #d8deea;padding:7px;text-align:left}}th{{background:#f1f4fa}}.changes{{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;margin-top:14px}}article.boosted{{border:2px solid #22a06b}}article.displaced{{border-left:5px solid #94a3b8}}article img,.missing{{width:100%;height:200px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}.change{{font-size:22px;font-weight:800;color:#1d4ed8}}.badge+.change{{margin-top:4px}}dl{{display:grid;grid-template-columns:145px 1fr;gap:4px;margin-top:10px}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere;font-size:12px}}textarea{{width:100%;box-sizing:border-box;margin:4px 0 8px}}.notes{{margin-top:16px;background:#f8fafc;padding:12px;border-radius:8px}}.unchanged{{padding:14px;background:#f8fafc;border-radius:8px}}.cost-unavailable{{padding:12px;background:#fff7ed;border-radius:8px;color:#9a3412}}.cost-note{{color:#475569}}@media(max-width:900px){{.query{{display:block}}.query img{{width:100%}}}}
</style><main><h1>v2 DAMAGE · YOLO flat {args.flat_boost:g} rank diff review</h1><p>동일 vector candidate pool {args.candidate_k}개에서 pure vector와 flat rerank를 비교했습니다. 화면에는 이동한 결과만 표시하며 자동 판정은 사용하지 않았습니다.</p><div class="summary"><h2>전체 요약</h2><div class="stats"><div class="stat"><b>{len(items)}</b>query 수</div><div class="stat"><b>{paired}</b>PAIRED</div><div class="stat"><b>{len(items)-paired}</b>fallback</div><div class="stat"><b>{boost_queries}</b>boost 적용 query</div><div class="stat"><b>{total_changes}</b>순위 변경 카드</div><div class="stat"><b>{new_count}</b>NEW</div><div class="stat"><b>{out_count}</b>OUT</div><div class="stat"><b>{unchanged}</b>변경 없음 query</div><div class="stat"><b>{len(cost_items)}</b>비용 비교 가능 query</div><div class="stat"><b>{avg_baseline_ratio:.0%} → {avg_rerank_ratio:.0%}</b>평균 견적 연결 비율</div><div class="stat"><b>{cost_up}</b>연결 증가 query</div><div class="stat"><b>{cost_down}</b>연결 감소 query</div><div class="stat"><b>{cost_same}</b>연결 동일 query</div></div><p>비용 median 변화는 검색 품질 정답률이 아니라 상위 결과의 견적 데이터 연결성 변화입니다.</p></div>{sections}</main></html>'''


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    selection = load_selection(args.selection_json.resolve())
    if not args.query_manifest.resolve().is_file():
        raise SystemExit(f"query manifest not found: {args.query_manifest}")
    repository = VectorRepository(
        dsn, expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray",
        yolo_corpus_part_boost=0.0,
    )
    cost_repository = PostgresCostCaseRepository(dsn)
    estimate_service = EstimateService(cost_repository)
    embedder = DinoV2Embedder(EmbeddingSpec())
    items = []
    root = args.dataset_root.resolve()
    for query in selection["selected"]:
        path = resolve_image_path(root, str(query["source_image_ref"]))
        if not path.is_file():
            raise FileNotFoundError(path)
        with Image.open(path) as image:
            roi = roi_from_feature_box(image, query.get("roi_box"))
            vector = embedder.embed([roi])[0]
        query_part = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
        _, pool = repository.search(
            vector=vector, pipeline_version_id=2,
            damage_type=str(query["damage_type"]), part_code=query_part,
            limit=args.candidate_k,
            exclude_case_id=(int(query["case_id"]) if str(query.get("case_id", "")).isdigit() else None),
        )
        baseline = rerank(pool, None, 0.0, args.top_k)
        reranked = rerank(pool, query_part, args.flat_boost, args.top_k)
        baseline_cost = cost_profile(estimate_service, cost_repository, query, baseline)
        reranked_cost = cost_profile(estimate_service, cost_repository, query, reranked)
        diffs = diff_rows(baseline, reranked)
        for row in diffs:
            profile = reranked_cost if row["rerank_hit"] is not None else baseline_cost
            row["cost_included"] = row["case_id"] in profile.get("included_ids", set())
            row["cost_total"] = profile.get("cost_totals", {}).get(row["case_id"])
        items.append({
            "query": query,
            "diffs": diffs,
            "cost_summary": cost_summary(baseline_cost, reranked_cost, args.top_k),
        })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_html(args, items), encoding="utf-8")
    total = sum(len(item["diffs"]) for item in items)
    new = sum(sum(row["kind"] == "NEW" for row in item["diffs"]) for item in items)
    out = sum(sum(row["kind"] == "OUT" for row in item["diffs"]) for item in items)
    boost_queries = sum(any(row["boosted"] for row in item["diffs"]) for item in items)
    unchanged = sum(not item["diffs"] for item in items)
    cost_items = [item["cost_summary"] for item in items if item["cost_summary"]["eligible"]]
    cost_up = sum(item["rerank_connected"] > item["baseline_connected"] for item in cost_items)
    cost_down = sum(item["rerank_connected"] < item["baseline_connected"] for item in cost_items)
    cost_same = sum(item["rerank_connected"] == item["baseline_connected"] for item in cost_items)
    avg_b = sum(item["baseline_ratio"] for item in cost_items) / len(cost_items) if cost_items else 0.0
    avg_r = sum(item["rerank_ratio"] for item in cost_items) / len(cost_items) if cost_items else 0.0
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output), "query_count": len(items), "rank_change_cards": total, "new": new, "out": out, "boost_queries": boost_queries, "unchanged_queries": unchanged, "cost_comparable_queries": len(cost_items), "avg_baseline_cost_link_ratio": avg_b, "avg_rerank_cost_link_ratio": avg_r, "cost_link_increased_queries": cost_up, "cost_link_decreased_queries": cost_down, "cost_link_same_queries": cost_same}, ensure_ascii=False))


if __name__ == "__main__":
    main()
