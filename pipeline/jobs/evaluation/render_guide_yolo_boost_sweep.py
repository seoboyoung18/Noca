"""Render a read-only boost sweep from one shared v2 vector candidate pool."""
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
    parser.add_argument("--boosts", default="0,0.01,0.02,0.03,0.05")
    args = parser.parse_args()
    if args.top_k < 1 or args.candidate_k < args.top_k:
        parser.error("candidate-k must be >= top-k >= 1")
    try:
        args.boost_values = [float(value.strip()) for value in args.boosts.split(",")]
    except ValueError as exc:
        parser.error(f"invalid --boosts: {exc}")
    if not args.boost_values or any(value < 0 for value in args.boost_values):
        parser.error("boosts must contain at least one non-negative number")
    return args


def rerank(pool: list[SearchHit], boost: float, top_k: int) -> list[SearchHit]:
    def key(hit: SearchHit) -> tuple[float, float, int]:
        vector_distance = 1.0 - float(hit.vector_similarity)
        distance = vector_distance - (boost if hit.corpus_part_matched else 0.0)
        return distance, vector_distance, hit.case_id
    ordered = sorted(pool, key=key)[:top_k]
    updated = []
    for hit in ordered:
        vector_similarity = float(hit.vector_similarity)
        updated.append(replace(
            hit,
            reranked_similarity=vector_similarity + (boost if hit.corpus_part_matched else 0.0),
            similarity=vector_similarity + (boost if hit.corpus_part_matched else 0.0),
            ranking_reason=("YOLO_PART_BOOSTED" if boost > 0 and hit.corpus_part_matched else "VECTOR_ONLY_FALLBACK"),
        ))
    return updated


def card(hit: SearchHit, rank: int, dataset_root: Path) -> str:
    path = dataset_root / str(hit.source_image_ref or "")
    image = data_url(path) if path.is_file() else ""
    body = f'<img src="{image}" alt="rank {rank}">' if image else '<div class="missing">image missing</div>'
    values = (
        ("source path", hit.source_image_ref),
        ("vector similarity", hit.vector_similarity),
        ("reranked similarity", hit.reranked_similarity),
        ("corpusPartMatched", hit.corpus_part_matched),
        ("corpusPartCode", hit.corpus_part_code),
        ("corpusPartConfidence", hit.corpus_part_confidence),
        ("corpusPartOverlap", hit.corpus_part_overlap),
        ("ranking reason", hit.ranking_reason),
    )
    rows = []
    for label, value in values:
        if value is None:
            rendered = "-"
        elif isinstance(value, float):
            rendered = f"{value:.4f}"
        else:
            rendered = str(value)
        rows.append(f"<dt>{html.escape(label)}</dt><dd>{html.escape(rendered)}</dd>")
    return f'<article><h3>#{rank} · case {hit.case_id}</h3>{body}<dl>{"".join(rows)}</dl></article>'


def summary_row(label: str, results: list[SearchHit], baseline: list[SearchHit]) -> str:
    current = {hit.case_id: index for index, hit in enumerate(results, 1)}
    base = {hit.case_id: index for index, hit in enumerate(baseline, 1)}
    new_cases = len(set(current) - set(base))
    changed = sum(1 for case_id in set(current) & set(base) if current[case_id] != base[case_id])
    boosted = sum(hit.ranking_reason == "YOLO_PART_BOOSTED" for hit in results)
    fallback = sum(hit.ranking_reason == "VECTOR_ONLY_FALLBACK" for hit in results)
    front = sum(hit.corpus_part_code == QUERY["part_code"] for hit in results)
    return f"<tr><td>{html.escape(label)}</td><td>{boosted}</td><td>{fallback}</td><td>{new_cases}</td><td>{changed}</td><td>{front}</td></tr>"


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
        dsn,
        expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray",
        yolo_corpus_part_boost=0.0,
    )
    # One read-only query. The returned pool is shared by every boost value below.
    _, pool = repository.search(
        vector=vector, pipeline_version_id=2, damage_type=QUERY["damage_type"],
        part_code=QUERY["part_code"], limit=args.candidate_k,
    )
    baseline = rerank(pool, 0.0, args.top_k)
    results = {boost: rerank(pool, boost, args.top_k) for boost in args.boost_values}
    root = args.dataset_root.resolve()
    query_image = data_url(guide)
    sections = []
    for boost, hits in results.items():
        cards = "".join(card(hit, rank, root) for rank, hit in enumerate(hits, 1))
        sections.append(f"<section><h2>boost={boost:g} · v2 Top-{args.top_k}</h2><p>shared vector candidate pool: {len(pool)} cases</p><div class=grid>{cards}</div></section>")
    summary = "".join(summary_row(f"{boost:g}", hits, baseline) for boost, hits in results.items())
    page = f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>guide-good v2 YOLO boost sweep</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2{{margin:0 0 10px}}section{{margin-top:26px}}.query,article{{background:#fff;border:1px solid #d8deea;border-radius:9px;padding:10px}}.query{{max-width:720px}}.query img{{width:100%;border-radius:7px}}.grid{{display:grid;grid-template-columns:repeat(5,minmax(210px,1fr));gap:10px}}article img,.missing{{width:100%;height:145px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}h3{{margin:0 0 8px;font-size:14px}}dl{{display:grid;grid-template-columns:110px 1fr;gap:3px}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere;font-size:12px}}table{{border-collapse:collapse;background:#fff;margin-top:12px}}th,td{{border:1px solid #d8deea;padding:6px 10px;text-align:right}}th:first-child,td:first-child{{text-align:left}}</style>
<main><h1>guide-good v2 YOLO boost sweep</h1><p>모든 boost는 동일한 v2 vector candidate pool에서 시작하며, boost 값만 Python에서 변경했습니다.</p>
<section class=query><h2>Query</h2><img src="{query_image}" alt="guide-good"><p>partCode={QUERY["part_code"]} · damageType={QUERY["damage_type"]} · bbox={QUERY["bbox"]}</p></section>
<section><h2>요약</h2><table><tr><th>boost</th><th>YOLO_PART_BOOSTED</th><th>VECTOR_ONLY_FALLBACK</th><th>baseline 신규 진입</th><th>baseline 대비 순위 변경</th><th>FRONT_BUMPER</th></tr>{summary}</table></section>{"".join(sections)}</main></html>'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print({"status": "SUCCEEDED", "output": str(args.output), "candidate_pool": len(pool), "boosts": args.boost_values})


if __name__ == "__main__":
    main()
