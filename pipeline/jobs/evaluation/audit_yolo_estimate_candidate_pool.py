"""Read-only audit of estimateable cases hidden in the v2 YOLO candidate pool."""
from __future__ import annotations

import argparse
import html
import json
import os
import statistics
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from PIL import Image

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from AI.server.app.services.estimate_service import EstimateService, _aggregate_case, _is_included_row
from pipeline.jobs.evaluation.render_query_comparison import data_url
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import resolve_image_path, rerank
from pipeline.jobs.evaluation.render_yolo_rerank_rank_diff_review import cost_profile
from pipeline.jobs.ingestion.embed_search_corpus import roi_from_feature_box
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--selection-json", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--max-estimate-cases", type=int, default=10)
    parser.add_argument("--min-similarity", type=float, default=None,
                        help="reported for reproducibility; no filter is applied")
    args = parser.parse_args()
    if args.candidate_k < 1 or args.max_estimate_cases < 1:
        parser.error("candidate-k and max-estimate-cases must be positive")
    return args


def load_selection(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fp:
        payload = json.load(fp)
    if not isinstance(payload.get("selected"), list) or not payload["selected"]:
        raise ValueError("selection JSON has no selected queries")
    return payload


def similarity_stats(hits: list[SearchHit]) -> dict[str, float | None]:
    values = sorted(float(hit.vector_similarity) for hit in hits if hit.vector_similarity is not None)
    if not values:
        return {"min": None, "p25": None, "median": None, "p75": None, "max": None}
    if len(values) == 1:
        return {key: values[0] for key in ("min", "p25", "median", "p75", "max")}
    quartiles = statistics.quantiles(values, n=4, method="inclusive")
    return {"min": values[0], "p25": quartiles[0], "median": statistics.median(values), "p75": quartiles[2], "max": values[-1]}


def cost_totals_for_hits(repository: PostgresCostCaseRepository, hits: list[SearchHit], part_code: str) -> dict[int, int]:
    """Use the repository and the service's established policy helpers, never raw cost SQL."""
    case_ids = sorted({int(hit.case_id) for hit in hits})
    rows = repository.fetch_case_items(case_ids, [part_code])
    by_case: dict[int, list[Any]] = {}
    for row in rows:
        by_case.setdefault(int(row.case_id), []).append(row)
    totals: dict[int, int] = {}
    for case_id, case_rows in by_case.items():
        included = [row for row in case_rows if _is_included_row(row)]
        case_cost = _aggregate_case(case_id, part_code, included)
        if case_cost is not None:
            totals[case_id] = int(case_cost.total)
    return totals


def candidate_json(hit: SearchHit, totals: dict[int, int]) -> dict[str, Any]:
    return {
        "case_id": int(hit.case_id),
        "vector_similarity": float(hit.vector_similarity),
        "corpus_part_code": hit.corpus_part_code,
        "corpus_part_confidence": hit.corpus_part_confidence,
        "corpus_part_overlap": hit.corpus_part_overlap,
        "cost_included": int(hit.case_id) in totals,
        "cost_total": totals.get(int(hit.case_id)),
    }


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def render_candidate_table(candidates: list[dict[str, Any]]) -> str:
    if not candidates:
        return '<p class="empty">조건을 만족하는 Top-100 비용 후보가 없습니다.</p>'
    rows = "".join(
        f'<tr><td>{i}</td><td>{c["case_id"]}</td><td>{fmt(c["vector_similarity"])}</td>'
        f'<td>{html.escape(fmt(c["corpus_part_code"]))}</td><td>{fmt(c["corpus_part_confidence"])}</td>'
        f'<td>{fmt(c["corpus_part_overlap"])}</td><td>{"YES" if c["cost_included"] else "NO"}</td>'
        f'<td>{fmt(c["cost_total"])}</td></tr>'
        for i, c in enumerate(candidates, 1))
    return '<table><tr><th>rank in pool</th><th>case ID</th><th>vector similarity</th><th>corpus part</th><th>confidence</th><th>overlap</th><th>cost included</th><th>cost total</th></tr>' + rows + '</table>'


def render_html(args: argparse.Namespace, rows: list[dict[str, Any]]) -> str:
    paired = sum(bool(row["query"]["pair_status"] == "PAIRED" and row["query"].get("part_code")) for row in rows)
    top_estimable = sum(row["current_cost"]["estimable"] for row in rows)
    pool_estimable = sum(row["prospective_cost"]["estimable"] for row in rows)
    at_least_three = sum(row["prospective_cost"]["included_count"] >= 3 for row in rows)
    counts = [row["prospective_cost"]["connected_count"] for row in rows if row["prospective_cost"]["eligible"]]
    count_dist = similarity_stats([SearchHit(case_id=i, similarity=float(v), repair_year=None, item_total=None, vector_similarity=float(v)) for i, v in enumerate(counts)]) if counts else {"min": None, "median": None, "max": None, "p25": None, "p75": None}
    sections = []
    for row in rows:
        q = row["query"]
        path = resolve_image_path(args.dataset_root.resolve(), str(q["source_image_ref"]))
        qimg = data_url(path) if path.is_file() else ""
        current = row["current_cost"]; prospective = row["prospective_cost"]
        if not prospective["eligible"]:
            cost_table = '<p class="unavailable">비용 비교 불가: query part 미확정</p>'
        else:
            bdist = current["distribution"]; pdist = prospective["distribution"]
            cost_table = f'<table class="summary-table"><tr><th>항목</th><th>현재 Top-10</th><th>Top-100 비용 후보</th></tr><tr><td>견적 연결 가능 사례</td><td>{current["connected_count"]} / 10</td><td>{prospective["connected_count"]}</td></tr><tr><td>비용 산출 포함 사례</td><td>{current["included_count"]}</td><td>{prospective["included_count"]}</td></tr><tr><td>p25 / median / p75</td><td>{fmt(bdist.get("p25"))} / {fmt(bdist.get("median"))} / {fmt(bdist.get("p75"))}</td><td>{fmt(pdist.get("p25"))} / {fmt(pdist.get("median"))} / {fmt(pdist.get("p75"))}</td></tr><tr><td>신규 확보 비용 사례</td><td>-</td><td>{prospective["new_connected"]}</td></tr></table>'
        sections.append(f'<section><h2>{html.escape(str(q.get("external_ref") or q.get("case_id")))}</h2><div class="query"><img src="{qimg}" alt="query"><dl><dt>source path</dt><dd>{html.escape(str(q.get("source_image_ref")))}</dd><dt>damageType</dt><dd>{html.escape(str(q.get("damage_type")))}</dd><dt>partCode</dt><dd>{html.escape(str(q.get("part_code") or "-"))}</dd><dt>pairStatus</dt><dd>{html.escape(str(q.get("pair_status")))}</dd><dt>pool size</dt><dd>{row["pool_size"]}</dd><dt>corpus YOLO part match</dt><dd>{row["matched_count"]}</dd></dl></div><h3>현재 Top-10 방식 vs Top-100 비용 후보 방식</h3>{cost_table}<p>유효 비용 후보 similarity 분포: min {fmt(row["similarity_distribution"].get("min"))} · p25 {fmt(row["similarity_distribution"].get("p25"))} · median {fmt(row["similarity_distribution"].get("median"))} · p75 {fmt(row["similarity_distribution"].get("p75"))} · max {fmt(row["similarity_distribution"].get("max"))}</p><p>EstimateService 최소 표본 3건 충족: <strong>{"YES" if prospective["included_count"] >= 3 else "NO"}</strong> · 산출 가능: <strong>{"YES" if prospective["estimable"] else "NO"}</strong></p>{render_candidate_table(row["candidates"])} </section>')
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>v2 YOLO estimate candidate pool audit</title><style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section,.summary,.query{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:14px;margin:18px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;padding:10px;border-radius:8px}}.stat b{{display:block;font-size:22px}}.query{{display:flex;gap:16px;max-width:960px}}.query img{{width:340px;max-height:250px;object-fit:contain;background:#eef2f7}}dl{{display:grid;grid-template-columns:150px 1fr;gap:4px}}dt{{color:#64748b}}dd{{margin:0;overflow-wrap:anywhere}}table{{width:100%;border-collapse:collapse;background:white;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left}}th{{background:#f1f4fa}}.unavailable,.empty{{background:#fff7ed;padding:12px;border-radius:8px;color:#9a3412}}@media(max-width:900px){{.query{{display:block}}.query img{{width:100%}}}}</style><main><h1>v2 DAMAGE · YOLO estimate candidate pool audit</h1><p>이 audit은 검색 랭킹 품질이나 정답률이 아니라, 동일 vector Top-{args.candidate_k} 안에서 견적 표본을 확보할 수 있는지 검사합니다. 비용은 기존 EstimateService / CostCaseRepository 정책으로만 계산했습니다.</p><div class="summary"><div class="stats"><div class="stat"><b>{len(rows)}</b>query 수</div><div class="stat"><b>{paired}</b>PAIRED query</div><div class="stat"><b>{top_estimable}</b>현재 Top-10 estimable</div><div class="stat"><b>{pool_estimable}</b>Top-100 후보 estimable</div><div class="stat"><b>{at_least_three}</b>3건 이상 비용 표본</div><div class="stat"><b>{fmt(count_dist.get("min"))} / {fmt(count_dist.get("median"))} / {fmt(count_dist.get("max"))}</b>유효 후보 수 min / median / max</div></div></div>{''.join(sections)}</main></html>'''


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    if not args.query_manifest.resolve().is_file():
        raise SystemExit(f"query manifest not found: {args.query_manifest}")
    selection = load_selection(args.selection_json.resolve())
    vector_repository = VectorRepository(dsn, expected_model_name="facebook/dinov2-base", expected_model_version="f9e44c8-pooler-pad20-lb224gray", yolo_corpus_part_boost=0.0)
    cost_repository = PostgresCostCaseRepository(dsn)
    estimate_service = EstimateService(cost_repository)
    embedder = DinoV2Embedder(EmbeddingSpec())
    rows: list[dict[str, Any]] = []
    root = args.dataset_root.resolve()
    for query in selection["selected"]:
        path = resolve_image_path(root, str(query["source_image_ref"]))
        if not path.is_file():
            raise FileNotFoundError(path)
        with Image.open(path) as image:
            roi = roi_from_feature_box(image, query.get("roi_box"))
            vector = embedder.embed([roi])[0]
        query_part = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
        _, pool = vector_repository.search(vector=vector, pipeline_version_id=2, damage_type=str(query["damage_type"]), part_code=query_part, limit=args.candidate_k, exclude_case_id=(int(query["case_id"]) if str(query.get("case_id", "")).isdigit() else None))
        baseline = rerank(pool, None, 0.0, 10)
        reranked = rerank(pool, query_part, 0.05, 10)
        current_cost = cost_profile(estimate_service, cost_repository, query, reranked)
        current_cost["connected_count"] = len(current_cost["connected_ids"]); current_cost["included_count"] = len(current_cost["included_ids"]); current_cost["estimable"] = bool(current_cost.get("estimable"))
        matched = [hit for hit in pool if query_part and hit.corpus_part_matched]
        matched_cost = cost_profile(estimate_service, cost_repository, query, matched)
        valid = [hit for hit in matched if hit.case_id in matched_cost["connected_ids"]]
        valid.sort(key=lambda hit: (-float(hit.vector_similarity), hit.case_id))
        prospective_hits = valid[:args.max_estimate_cases]
        prospective_cost = cost_profile(estimate_service, cost_repository, query, prospective_hits)
        prospective_cost["connected_count"] = len(prospective_cost["connected_ids"]); prospective_cost["included_count"] = len(prospective_cost["included_ids"]); prospective_cost["estimable"] = bool(prospective_cost.get("estimable")); prospective_cost["new_connected"] = len(set(prospective_cost["connected_ids"]) - set(current_cost["connected_ids"]))
        totals = matched_cost["cost_totals"]
        rows.append({"query": query, "pool_size": len(pool), "matched_count": len(matched), "cost_candidate_count": len(valid), "similarity_distribution": similarity_stats(valid), "current_cost": current_cost, "prospective_cost": prospective_cost, "candidates": [candidate_json(hit, totals) for hit in prospective_hits]})
    json_rows = json.loads(json.dumps(rows, default=lambda value: sorted(value) if isinstance(value, set) else value))
    payload = {"status": "SUCCEEDED", "candidate_k": args.candidate_k, "max_estimate_cases": args.max_estimate_cases, "min_similarity": args.min_similarity, "selection_json": str(args.selection_json.resolve()), "queries": json_rows}
    args.output_json.parent.mkdir(parents=True, exist_ok=True); args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True); args.output_html.write_text(render_html(args, rows), encoding="utf-8")
    paired = sum(bool(row["query"]["pair_status"] == "PAIRED" and row["query"].get("part_code")) for row in rows)
    top_estimable = sum(row["current_cost"]["estimable"] for row in rows); pool_estimable = sum(row["prospective_cost"]["estimable"] for row in rows)
    print(json.dumps({"status": "SUCCEEDED", "output_json": str(args.output_json), "output_html": str(args.output_html), "paired_queries": paired, "top10_estimable_queries": top_estimable, "pool_estimable_queries": pool_estimable, "three_plus_queries": sum(row["prospective_cost"]["included_count"] >= 3 for row in rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
