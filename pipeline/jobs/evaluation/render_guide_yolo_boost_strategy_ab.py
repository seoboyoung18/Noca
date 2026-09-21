"""Compare pure, flat, and confidence/overlap-weighted v2 YOLO reranking."""
from __future__ import annotations

import argparse
import html
import os
import sys
from dataclasses import replace
from pathlib import Path

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from PIL import Image

from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from pipeline.jobs.evaluation.render_query_comparison import data_url
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi

QUERY = {"part_code": "FRONT_BUMPER", "damage_type": "SCRATCHED", "bbox": [167, 224, 663, 178]}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--flat-boosts", default="0.03,0.05")
    parser.add_argument("--weighted-base-boost", type=float, default=0.05)
    args = parser.parse_args()
    if args.top_k < 1 or args.candidate_k < args.top_k:
        parser.error("candidate-k must be >= top-k >= 1")
    try:
        args.flat_values = [float(value.strip()) for value in args.flat_boosts.split(",")]
    except ValueError as exc:
        parser.error(f"invalid --flat-boosts: {exc}")
    if not args.flat_values or any(value < 0 for value in args.flat_values):
        parser.error("flat-boosts must contain non-negative numbers")
    if args.weighted_base_boost < 0:
        parser.error("weighted-base-boost must be non-negative")
    return args


def effective_boost(hit: SearchHit, base_boost: float) -> float:
    """Apply confidence/overlap only to the already validated primary match."""
    if not hit.corpus_part_matched:
        return 0.0
    confidence = hit.corpus_part_confidence
    overlap = hit.corpus_part_overlap
    if confidence is None or overlap is None:
        return 0.0
    return base_boost * float(confidence) * float(overlap)


def rerank(pool: list[SearchHit], mode: str, boost: float, top_k: int) -> list[SearchHit]:
    def item(hit: SearchHit) -> tuple[float, float, int]:
        vector_distance = 1.0 - float(hit.vector_similarity)
        applied = effective_boost(hit, boost) if mode == "weighted" else (boost if hit.corpus_part_matched else 0.0)
        return vector_distance - applied, vector_distance, hit.case_id

    output = []
    for hit in sorted(pool, key=item)[:top_k]:
        applied = effective_boost(hit, boost) if mode == "weighted" else (boost if hit.corpus_part_matched else 0.0)
        vector_similarity = float(hit.vector_similarity)
        output.append(replace(
            hit,
            similarity=vector_similarity + applied,
            reranked_similarity=vector_similarity + applied,
            ranking_reason="YOLO_PART_BOOSTED" if applied > 0 else "VECTOR_ONLY_FALLBACK",
        ))
    return output


def card(hit: SearchHit, rank: int, root: Path) -> str:
    path = root / str(hit.source_image_ref or "")
    image = data_url(path) if path.is_file() else ""
    body = f'<img src="{image}" alt="rank {rank}">' if image else '<div class="missing">image missing</div>'
    effective = (float(hit.reranked_similarity) - float(hit.vector_similarity)) if hit.reranked_similarity is not None else 0.0
    values = (
        ("source path", hit.source_image_ref),
        ("vector similarity", hit.vector_similarity),
        ("reranked similarity", hit.reranked_similarity),
        ("effective boost", effective),
        ("corpusPartCode", hit.corpus_part_code),
        ("corpusPartConfidence", hit.corpus_part_confidence),
        ("corpusPartOverlap", hit.corpus_part_overlap),
        ("ranking reason", hit.ranking_reason),
    )
    rows = []
    for label, value in values:
        rendered = "-" if value is None else (f"{value:.4f}" if isinstance(value, float) else str(value))
        rows.append(f"<dt>{html.escape(label)}</dt><dd>{html.escape(rendered)}</dd>")
    return f'<article><h3>#{rank} · case {hit.case_id}</h3>{body}<dl>{"".join(rows)}</dl></article>'


def summary_row(label: str, results: list[SearchHit], baseline: list[SearchHit]) -> str:
    current = {hit.case_id: index for index, hit in enumerate(results, 1)}
    base = {hit.case_id: index for index, hit in enumerate(baseline, 1)}
    effective = [float(hit.reranked_similarity) - float(hit.vector_similarity) for hit in results if hit.ranking_reason == "YOLO_PART_BOOSTED"]
    return "<tr>" + "".join(f"<td>{html.escape(value)}</td>" for value in (
        label,
        str(sum(hit.ranking_reason == "YOLO_PART_BOOSTED" for hit in results)),
        str(sum(hit.ranking_reason == "VECTOR_ONLY_FALLBACK" for hit in results)),
        str(len(set(current) - set(base))),
        str(sum(1 for case_id in set(current) & set(base) if current[case_id] != base[case_id])),
        str(sum(hit.corpus_part_code == QUERY["part_code"] for hit in results)),
        f"{sum(effective)/len(effective):.4f}" if effective else "-",
        f"{min(effective):.4f}" if effective else "-",
        f"{max(effective):.4f}" if effective else "-",
    )) + "</tr>"


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    guide = REPO_ROOT / "frontend" / "public" / "assets" / "guide-good.jpg"
    with Image.open(guide) as image:
        image.load()
        roi, _ = make_roi(image, {"bbox": QUERY["bbox"]})
    vector = DinoV2Embedder(EmbeddingSpec()).embed([roi])[0]
    repository = VectorRepository(
        dsn, expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray",
        yolo_corpus_part_boost=0.0,
    )
    _, pool = repository.search(
        vector=vector, pipeline_version_id=2, damage_type=QUERY["damage_type"],
        part_code=QUERY["part_code"], limit=args.candidate_k,
    )
    baseline = rerank(pool, "flat", 0.0, args.top_k)
    strategies = [("pure vector", "flat", 0.0)]
    strategies.extend((f"flat {value:g}", "flat", value) for value in args.flat_values)
    strategies.append((f"weighted {args.weighted_base_boost:g} × confidence × overlap", "weighted", args.weighted_base_boost))
    results = [(label, rerank(pool, mode, boost, args.top_k)) for label, mode, boost in strategies]
    root = args.dataset_root.resolve()
    query_image = data_url(guide)
    rows = "".join(summary_row(label, hits, baseline) for label, hits in results)
    sections = "".join(
        f'<section><h2>{html.escape(label)} · Top-{args.top_k}</h2><div class="grid">{"".join(card(hit, i + 1, root) for i, hit in enumerate(hits))}</div></section>'
        for label, hits in results
    )
    page = f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>guide-good v2 YOLO boost strategy A/B</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}section{{margin-top:26px}}.query,article{{background:#fff;border:1px solid #d8deea;border-radius:9px;padding:10px}}.query{{max-width:720px}}.query img{{width:100%;border-radius:7px}}.grid{{display:grid;grid-template-columns:repeat(5,minmax(210px,1fr));gap:10px}}article img,.missing{{width:100%;height:145px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}h3{{margin:0 0 8px;font-size:14px}}dl{{display:grid;grid-template-columns:125px 1fr;gap:3px}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere;font-size:12px}}table{{border-collapse:collapse;background:#fff;margin-top:12px}}th,td{{border:1px solid #d8deea;padding:6px 9px;text-align:right}}th:first-child,td:first-child{{text-align:left}}</style>
<main><h1>guide-good v2 YOLO boost strategy comparison</h1><p>동일 v2 vector candidate pool: {len(pool)}개 · query FRONT_BUMPER / SCRATCHED · bbox {QUERY["bbox"]}</p><section class=query><h2>Query</h2><img src="{query_image}" alt="guide-good"></section><section><h2>요약</h2><table><tr><th>방식</th><th>boosted</th><th>vector-only</th><th>신규 진입</th><th>순위 변경</th><th>FRONT_BUMPER</th><th>평균 effective</th><th>최소</th><th>최대</th></tr>{rows}</table></section>{sections}</main></html>'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print({"status": "SUCCEEDED", "output": str(args.output), "candidate_pool": len(pool), "strategies": [label for label, _ in results]})


if __name__ == "__main__":
    main()
