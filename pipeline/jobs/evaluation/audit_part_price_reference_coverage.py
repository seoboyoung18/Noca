"""Audit FULL_REPAIR versus read-only PART_PRICE_ONLY reference coverage.

This job deliberately does not enable the production fallback or write query
inference to PostgreSQL.  It reuses the selected query manifest, the v2 vector
pool, ``PostgresCostCaseRepository`` and ``EstimateService``'s existing full
repair policy, while keeping the part-only reference calculation separate.
"""
from __future__ import annotations

import argparse
import html
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from AI.server.app.services.estimate_service import (
    EstimateService, _percentiles, _source_aware_part_price,
)
from pipeline.jobs.evaluation.render_query_comparison import data_url
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    resolve_image_path, rerank,
)
from pipeline.jobs.ingestion.embed_search_corpus import roi_from_feature_box
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--selection-json", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--max-estimate-cases", type=int, default=10)
    parser.add_argument("--min-similarity", type=float, default=None)
    args = parser.parse_args()
    if args.candidate_k < 1 or args.max_estimate_cases < 1:
        parser.error("candidate-k and max-estimate-cases must be positive")
    return args


def load_selection(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("selected")
    if not isinstance(rows, list) or not rows:
        raise ValueError("selection JSON has no selected queries")
    return rows


def resolve_case_id(dsn: str, query: dict[str, Any]) -> int | None:
    value = query.get("case_id")
    if isinstance(value, int) or (isinstance(value, str) and value.isdigit()):
        return int(value)
    external_ref = query.get("external_ref") or value
    if not external_ref:
        return None
    import psycopg
    with psycopg.connect(dsn) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT case_id FROM repair_case WHERE external_ref=%s", (str(external_ref),))
            row = cursor.fetchone()
            return int(row[0]) if row else None


def source_part_cost(row: Any) -> int:
    return _source_aware_part_price(row)


def part_price_candidates(
    repository: PostgresCostCaseRepository, pool: list[SearchHit],
    part_code: str | None, *, max_cases: int, min_similarity: float | None,
) -> list[dict[str, Any]]:
    if not part_code:
        return []
    matched = [
        hit for hit in pool
        if hit.corpus_part_matched and hit.corpus_part_code == part_code
        and (min_similarity is None or (hit.vector_similarity or hit.similarity) >= min_similarity)
    ]
    matched.sort(key=lambda hit: (-(hit.vector_similarity or hit.similarity), hit.case_id))
    rows = repository.fetch_case_items(
        [hit.case_id for hit in matched], [part_code],
    )
    by_case: dict[int, list[Any]] = {}
    for row in rows:
        by_case.setdefault(int(row.case_id), []).append(row)
    output: list[dict[str, Any]] = []
    for hit in matched:
        amounts = [
            source_part_cost(row)
            for row in by_case.get(int(hit.case_id), [])
            if row.part_code == part_code
            and row.line_type == "PART_PRICE"
            and row.assessment_status != "NOT_APPROVED"
            and source_part_cost(row) > 0
        ]
        amount = sum(amounts)
        if amount <= 0:
            continue
        output.append({
            "caseId": int(hit.case_id),
            "similarity": round(float(hit.vector_similarity or hit.similarity), 6),
            "corpusPartCode": hit.corpus_part_code,
            "corpusPartConfidence": hit.corpus_part_confidence,
            "corpusPartOverlap": hit.corpus_part_overlap,
            "partPriceAmount": amount,
            "costIncluded": True,
        })
        if len(output) >= max_cases:
            break
    return output


def full_repair_summary(
    service: EstimateService, query: dict[str, Any], hits: list[SearchHit],
) -> dict[str, Any]:
    part_code = query.get("part_code")
    if query.get("pair_status") != "PAIRED" or not part_code:
        return {"estimable": False, "reason": "query part 미확정", "includedCaseIds": [], "distribution": {}}
    request = SimpleNamespace(parts=[{
        "partCode": part_code,
        "damageType": query.get("damage_type"),
        "searchability": "STRICT",
        "pairStatus": "PAIRED",
        "confidence": query.get("part_confidence"),
        "referencedCaseIds": [int(hit.case_id) for hit in hits],
    }])
    estimate = service.calculate(request)
    item = (estimate.get("items") or [None])[0]
    return {
        "estimable": bool(estimate.get("estimable")),
        "reason": estimate.get("nonEstimableReason"),
        "includedCaseIds": [int(v) for v in (item or {}).get("referencedCaseIds", [])],
        "distribution": dict((item or {}).get("costDistribution") or {}),
    }


def query_audit(
    query: dict[str, Any], pool: list[SearchHit], repository: PostgresCostCaseRepository,
    estimate_service: EstimateService, args: argparse.Namespace, root: Path,
) -> dict[str, Any]:
    part_code = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
    baseline = rerank(pool, None, 0.0, 10)
    reranked = rerank(pool, part_code, 0.05, 10)
    refs = part_price_candidates(
        repository, pool, part_code, max_cases=args.max_estimate_cases,
        min_similarity=args.min_similarity,
    )
    full_baseline = full_repair_summary(estimate_service, query, baseline)
    full_reranked = full_repair_summary(estimate_service, query, reranked)
    full_any = full_baseline if full_baseline["estimable"] else full_reranked
    return {
        "query": query,
        "queryImage": str(resolve_image_path(root, str(query.get("source_image_ref")))),
        "vectorPoolCount": len(pool),
        "vectorPoolCases": [
            {"caseId": int(hit.case_id),
             "similarity": round(float(hit.vector_similarity or hit.similarity), 6),
             "corpusPartMatched": bool(hit.corpus_part_matched),
             "corpusPartCode": hit.corpus_part_code,
             "corpusPartConfidence": hit.corpus_part_confidence,
             "corpusPartOverlap": hit.corpus_part_overlap}
            for hit in pool
        ],
        "corpusYoloPartPairedMatchCount": sum(
            bool(hit.corpus_part_matched and hit.corpus_part_code == part_code) for hit in pool
        ) if part_code else 0,
        "partPriceReferenceCases": refs,
        "partPriceReferencePossible": len(refs) >= 3,
        "fullRepair": {"baseline": full_baseline, "reranked": full_reranked},
        "fullRepairEstimable": bool(full_any["estimable"]),
        "estimateSampleAtLeast3": len(refs) >= 3,
        "baselineTop10CostCaseCount": len(full_baseline["includedCaseIds"]),
        "rerankedTop10CostCaseCount": len(full_reranked["includedCaseIds"]),
        "candidateCostDistribution": (
            dict(zip(("p25", "median", "p75"), _percentiles([r["partPriceAmount"] for r in refs])))
            if len(refs) >= 3 else {}
        ),
    }


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def render_html(audits: list[dict[str, Any]], root: Path, args: argparse.Namespace) -> str:
    full_count = sum(a["fullRepairEstimable"] for a in audits)
    ref_count = sum(a["partPriceReferencePossible"] for a in audits)
    either_count = sum(a["fullRepairEstimable"] or a["partPriceReferencePossible"] for a in audits)
    paired = sum(a["query"].get("pair_status") == "PAIRED" and bool(a["query"].get("part_code")) for a in audits)
    sections: list[str] = []
    for audit in audits:
        q = audit["query"]
        path = Path(audit["queryImage"])
        image = data_url(path) if path.is_file() else ""
        full = audit["fullRepair"]
        refs = audit["partPriceReferenceCases"]
        dist = audit["candidateCostDistribution"]
        rows = "".join(
            f"<tr><td>{r['caseId']}</td><td>{r['similarity']}</td><td>{r['corpusPartCode']}</td>"
            f"<td>{fmt(r['corpusPartConfidence'])}</td><td>{fmt(r['corpusPartOverlap'])}</td>"
            f"<td>{r['partPriceAmount']:,}</td><td>YES</td></tr>" for r in refs
        ) or '<tr><td colspan="7">유효한 PART_PRICE_ONLY 후보 없음</td></tr>'
        pool_rows = "".join(
            f"<tr><td>{r['caseId']}</td><td>{r['similarity']}</td><td>{'YES' if r['corpusPartMatched'] else 'NO'}</td>"
            f"<td>{html.escape(fmt(r['corpusPartCode']))}</td><td>{fmt(r['corpusPartConfidence'])}</td><td>{fmt(r['corpusPartOverlap'])}</td></tr>"
            for r in audit["vectorPoolCases"]
        )
        sections.append(f'''<section class="query"><h2>{html.escape(str(q.get("external_ref") or q.get("case_id")))}</h2>
<div class="query-meta"><img src="{image}" alt="query"><dl>
<dt>source path</dt><dd>{html.escape(str(q.get("source_image_ref")))}</dd>
<dt>partCode / damageType</dt><dd>{html.escape(fmt(q.get("part_code")))} / {html.escape(fmt(q.get("damage_type")))}</dd>
<dt>pairStatus</dt><dd>{html.escape(fmt(q.get("pair_status")))}</dd><dt>bbox</dt><dd>{html.escape(fmt(q.get("bbox")))}</dd>
<dt>vector pool</dt><dd>{audit["vectorPoolCount"]}</dd><dt>corpus YOLO PAIRED match</dt><dd>{audit["corpusYoloPartPairedMatchCount"]}</dd></dl></div>
<div class="columns"><div class="full"><h3>현재 Top-10 · FULL_REPAIR</h3><p>baseline estimable={full['baseline']['estimable']} · reranked estimable={full['reranked']['estimable']}</p>
<pre>{html.escape(json.dumps(full, ensure_ascii=False, indent=2))}</pre></div><div class="part"><h3>Top-100 · PART_PRICE_ONLY 참고</h3>
<p>reference 가능: <b>{audit["partPriceReferencePossible"]}</b> · 표본 {len(refs)}건 · p25/median/p75: {fmt(dist.get("p25"))} / {fmt(dist.get("median"))} / {fmt(dist.get("p75"))}</p>
<table><tr><th>case</th><th>similarity</th><th>part</th><th>conf.</th><th>overlap</th><th>PART_PRICE</th><th>유효</th></tr>{rows}</table>
<details><summary>공유된 vector Top-{audit["vectorPoolCount"]} 후보 보기</summary><table><tr><th>case</th><th>similarity</th><th>YOLO match</th><th>part</th><th>conf.</th><th>overlap</th></tr>{pool_rows}</table></details>
<p class="notice">부품비만의 참고 범위입니다. 작업비·도장비·총 수리비는 포함하지 않습니다.</p></div></div></section>''')
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>PART_PRICE_ONLY coverage audit</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}.summary,.query{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;padding:10px 14px;border-radius:8px}}.stat b{{font-size:22px;display:block}}.query{{border-top:4px solid #5968ee}}.query-meta{{display:flex;gap:16px}}.query-meta img{{width:320px;max-height:240px;object-fit:contain;background:#eef2f7}}dl{{display:grid;grid-template-columns:160px 1fr;gap:5px;flex:1}}dt{{color:#64748b}}dd{{margin:0;overflow-wrap:anywhere}}.columns{{display:grid;grid-template-columns:1fr 1fr;gap:14px}}.full,.part{{padding:12px;border-radius:8px}}.full{{background:#f8fafc}}.part{{background:#fff7ed}}pre{{white-space:pre-wrap;font-size:12px}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left}}th{{background:#f1f4fa}}.notice{{color:#9a3412;font-weight:600}}@media(max-width:900px){{.columns{{grid-template-columns:1fr}}.query-meta{{display:block}}.query-meta img{{width:100%}}}}</style><main><h1>v2 견적 연결성 · FULL_REPAIR vs PART_PRICE_ONLY</h1><p>읽기 전용 audit입니다. 검색 랭킹 품질이 아니라 비용 데이터 연결성/부품비 참고 표본 확보 가능성을 검사합니다. repair hint와 DB write는 사용하지 않았습니다.</p>
<div class="summary"><h2>전체 요약</h2><div class="stats"><div class="stat"><b>{len(audits)}</b>query</div><div class="stat"><b>{paired}</b>PAIRED query</div><div class="stat"><b>{full_count}</b>FULL_REPAIR 가능</div><div class="stat"><b>{ref_count}</b>PART_PRICE_ONLY 참고 가능</div><div class="stat"><b>{either_count}</b>둘 중 하나라도 제공</div></div></div>{''.join(sections)}</main></html>'''


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    if not args.query_manifest.is_file():
        raise SystemExit(f"query manifest not found: {args.query_manifest}")
    selection = load_selection(args.selection_json.resolve())
    repository = VectorRepository(
        dsn, expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray", yolo_corpus_part_boost=0.0,
    )
    cost_repository = PostgresCostCaseRepository(dsn)
    estimate_service = EstimateService(cost_repository)
    embedder = DinoV2Embedder(EmbeddingSpec())
    root = args.dataset_root.resolve()
    audits: list[dict[str, Any]] = []
    for query in selection:
        path = resolve_image_path(root, str(query["source_image_ref"]))
        if not path.is_file():
            raise FileNotFoundError(path)
        with Image.open(path) as image:
            roi = roi_from_feature_box(image, query.get("roi_box"))
            vector = embedder.embed([roi])[0]
        query_part = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
        _, pool = repository.search(
            vector=vector, pipeline_version_id=2, damage_type=str(query["damage_type"]),
            part_code=query_part, limit=args.candidate_k,
            exclude_case_id=resolve_case_id(dsn, query),
        )
        audits.append(query_audit(query, pool, cost_repository, estimate_service, args, root))
    payload = {"status": "SUCCEEDED", "candidate_k": args.candidate_k, "audits": audits,
               "summary": {"query_count": len(audits),
                           "full_repair_baseline_queries": sum(a["fullRepair"]["baseline"]["estimable"] for a in audits),
                           "full_repair_reranked_queries": sum(a["fullRepair"]["reranked"]["estimable"] for a in audits),
                           "full_repair_estimable_queries": sum(a["fullRepairEstimable"] for a in audits),
                           "part_price_only_reference_queries": sum(a["partPriceReferencePossible"] for a in audits),
                           "either_cost_information_queries": sum(a["fullRepairEstimable"] or a["partPriceReferencePossible"] for a in audits)}}
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.write_text(render_html(audits, root, args), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output_json": str(args.output_json),
                      "output_html": str(args.output_html), **payload["summary"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
