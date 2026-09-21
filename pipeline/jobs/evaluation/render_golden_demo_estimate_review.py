"""Render one visually-reviewed YOLO search query with its cost references.

This is a read-only renderer over the completed FULL_REPAIR coverage audit.
It deliberately uses only valid FULL_REPAIR cases already present in the
YOLO-reranked visible Top-K.  A cost range is shown only when at least the
requested number of references is available.
"""
from __future__ import annotations

import argparse
import html
import json
import math
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
    data_url,
    resolve_image_path,
)


DEFAULT_QUERY_ID = (
    "01.데이터_견적서보유/2.Validation/1.원천데이터/VS_damage/damage/"
    "0507522_sc-195094.jpg#0"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-json", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--query-id", default=DEFAULT_QUERY_ID)
    parser.add_argument("--min-reference-count", type=int, default=5)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument(
        "--reference-source", choices=("yolo-top", "pool"), default="yolo-top",
        help="yolo-top: visible reranked results; pool: valid FULL_REPAIR cases in candidate pool",
    )
    args = parser.parse_args()
    if args.min_reference_count < 1:
        parser.error("--min-reference-count must be positive")
    if args.top_k < args.min_reference_count:
        parser.error("--top-k must be at least --min-reference-count")
    return args


def money(value: int | float | None) -> str:
    return "-" if value is None else f"{int(round(value)):,}원"


def number(value: Any, digits: int = 4) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def percentiles(values: list[int]) -> dict[str, int]:
    ordered = sorted(values)
    if not ordered:
        return {}

    def percentile(p: float) -> int:
        if len(ordered) == 1:
            return ordered[0]
        position = (len(ordered) - 1) * p
        lower, upper = math.floor(position), math.ceil(position)
        if lower == upper:
            return ordered[lower]
        return round(ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower))

    return {"p25": percentile(0.25), "median": percentile(0.5), "p75": percentile(0.75)}


def image_tag(dataset_root: Path, source_ref: str, cache: dict[str, str], alt: str) -> str:
    if source_ref not in cache:
        path = resolve_image_path(dataset_root, source_ref)
        cache[source_ref] = data_url(path, max_size=(760, 520)) if path.is_file() else ""
    image = cache[source_ref]
    if not image:
        return '<div class="missing">이미지를 찾지 못했습니다</div>'
    return f'<img src="{image}" alt="{html.escape(alt)}">'


def details(rows: list[tuple[str, Any]]) -> str:
    return "".join(
        f"<dt>{html.escape(label)}</dt><dd>{html.escape(number(value))}</dd>"
        for label, value in rows
    )


def reference_card(dataset_root: Path, item: dict[str, Any], rank: int,
                   cache: dict[str, str]) -> str:
    source_ref = str(item.get("sourceImageRef") or "")
    methods = ", ".join(str(method) for method in item.get("methods") or []) or "-"
    return f'''<article class="reference-card">
  <h3>견적 근거 #{rank} · case {html.escape(str(item.get("caseId")))}</h3>
  {image_tag(dataset_root, source_ref, cache, f"견적 근거 사례 {rank}")}
  <p class="path">{html.escape(source_ref)}</p>
  <dl>{details([
      (item.get("referenceRankLabel", "YOLO rerank 순위"), item.get("referenceRank")),
      ("벡터 유사도", item.get("similarity")),
      ("FULL_REPAIR 총액", money(item.get("total"))),
      ("작업 방식", methods),
      ("corpus 부품", item.get("corpusPartCode")),
      ("부품 confidence", item.get("corpusPartConfidence")),
      ("부품 overlap", item.get("corpusPartOverlap")),
  ])}</dl>
</article>'''


def render_html(dataset_root: Path, query: dict[str, Any], references: list[dict[str, Any]],
                minimum: int, top_k: int, reference_source: str) -> str:
    cache: dict[str, str] = {}
    costs = [int(item["total"]) for item in references]
    summary = percentiles(costs)
    query_ref = str(query.get("sourceImageRef") or "")
    available = len(references) >= minimum
    cards = "".join(
        reference_card(dataset_root, item, index, cache)
        for index, item in enumerate(references, 1)
    )
    cost_text = (
        f"약 {money(summary.get('p25'))} ~ {money(summary.get('p75'))} · 대표 {money(summary.get('median'))}"
        if available else "유효 FULL_REPAIR 사례가 최소 기준에 못 미쳐 견적 범위를 제시하지 않음"
    )
    return f'''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>전면 범퍼 스크래치 견적 데모 검토</title>
<style>
*{{box-sizing:border-box}} body{{margin:0;background:#f3f5f8;color:#172033;font:15px system-ui,-apple-system,"Segoe UI",sans-serif;line-height:1.45}} main{{max-width:1440px;margin:auto;padding:28px}} h1,h2,h3,p{{margin-top:0}} .notice{{background:#fff7e6;border-left:4px solid #b66b00;padding:12px 14px;margin:18px 0}} .hero{{display:grid;grid-template-columns:minmax(300px,0.9fr) minmax(300px,1.1fr);gap:24px;background:#fff;border-radius:14px;padding:20px;box-shadow:0 2px 12px #17203318}} .hero img,.reference-card img{{display:block;width:100%;height:330px;object-fit:contain;background:#edf1f5;border-radius:8px}} .missing{{height:330px;display:grid;place-items:center;background:#edf1f5;border-radius:8px;color:#64748b}} dl{{display:grid;grid-template-columns:150px 1fr;gap:6px 12px;margin:0}} dt{{color:#64748b}} dd{{margin:0;overflow-wrap:anywhere}} .cost{{background:#e8f5ef;border-radius:10px;padding:16px;margin:18px 0}} .cost strong{{display:block;font-size:28px;margin:4px 0}} .cards{{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px}} .reference-card{{background:#fff;border-radius:12px;padding:14px;border-top:4px solid #3d8068;box-shadow:0 1px 6px #17203314}} .reference-card h3{{font-size:16px}} .reference-card img{{height:210px}} .path{{min-height:42px;font-size:12px;color:#536278;overflow-wrap:anywhere;margin:8px 0}} .reference-card dl{{grid-template-columns:120px 1fr;font-size:13px}} .method{{display:inline-block;background:#e6edf9;padding:2px 7px;border-radius:999px}} @media(max-width:760px){{main{{padding:12px}}.hero{{grid-template-columns:1fr}}.hero img{{height:260px}}}}
</style></head><body><main>
<h1>전면 범퍼 스크래치 · 유사 사례 견적 데모 검토</h1>
<p class="notice">검색은 v2 DAMAGE + YOLO flat 0.05 rerank를 사용했습니다. repair hint는 사용하지 않았고, 비용은 같은 부품의 유효 FULL_REPAIR 사례만 사용합니다.</p>
<section class="hero"><div>{image_tag(dataset_root, query_ref, cache, "입력 손상 이미지")}</div><div>
<h2>입력 이미지</h2><dl>{details([
    ("source path", query_ref), ("손상 유형", query.get("damageType")),
    ("YOLO 부품", query.get("partCode")), ("pair status", query.get("pairStatus")),
    ("부품 confidence", query.get("partConfidence")), ("손상 confidence", query.get("damageConfidence")),
    ("손상 bbox", query.get("bbox")), ("YOLO Top-10 유효 사례", query.get("validWorkCostYoloTop10Count")),
])}</dl></div></section>
<section class="cost"><h2>제시 견적 범위</h2><strong>{cost_text}</strong><span>유사 사례 {len(references)}건 사용 · 최소 기준 {minimum}건 · {reference_source}에서 유사도 상위 {top_k}건까지 확인</span></section>
<section><h2>견적 근거 유사 사례 {len(references)}건</h2><div class="cards">{cards}</div></section>
</main></body></html>'''


def main() -> None:
    args = parse_args()
    payload = json.loads(args.audit_json.read_text(encoding="utf-8"))
    audits = payload.get("audits") or []
    query = next((item for item in audits if item.get("queryId") == args.query_id), None)
    if query is None:
        raise SystemExit(f"query not found in audit: {args.query_id}")
    valid_by_case = {int(item["caseId"]): dict(item) for item in query.get("validWorkCostCases") or []}
    references: list[dict[str, Any]] = []
    if args.reference_source == "pool":
        for rank, item in enumerate((query.get("validWorkCostCases") or [])[:args.top_k], 1):
            copied = dict(item)
            copied["referenceRank"] = rank
            copied["referenceRankLabel"] = "K=200 pool 유효 사례 순위"
            references.append(copied)
        reference_source = "K=200 case-dedup 후보 pool"
    else:
        for rank, hit in enumerate((query.get("yoloTop10") or [])[:args.top_k], 1):
            item = valid_by_case.get(int(hit["caseId"]))
            if item is not None:
                item["referenceRank"] = rank
                item["referenceRankLabel"] = "YOLO rerank 순위"
                references.append(item)
        reference_source = "YOLO rerank 검색 결과"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        render_html(args.dataset_root.resolve(), query, references,
                    args.min_reference_count, args.top_k, reference_source),
        encoding="utf-8",
    )
    costs = [int(item["total"]) for item in references]
    print(json.dumps({
        "status": "SUCCEEDED", "queryId": query.get("queryId"),
        "partCode": query.get("partCode"), "damageType": query.get("damageType"),
        "referenceCount": len(references), "minimumReferenceCount": args.min_reference_count,
        "costRangeAvailable": len(references) >= args.min_reference_count,
        "referenceSource": args.reference_source,
        "costSummary": percentiles(costs), "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
