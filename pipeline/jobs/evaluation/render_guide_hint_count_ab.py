"""Visually compare raw v2 vector ranking and hint-count soft re-ranking."""

from __future__ import annotations

import argparse
import html
import json
import os
import sys
from pathlib import Path

from PIL import Image


PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for import_root in (REPO_ROOT, PIPELINE_ROOT):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))

from pipeline.jobs.evaluation.evaluate_search_ab import (  # noqa: E402
    annotate_results,
    rerank_by_hint_count,
    search_query,
)
from pipeline.jobs.evaluation.render_guide_good_comparison import GUIDE_DETECTION  # noqa: E402
from pipeline.jobs.evaluation.render_query_comparison import card, data_url  # noqa: E402
from pipeline.jobs.ingestion.embed_search_corpus import resolve_model_version  # noqa: E402
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec  # noqa: E402
from shared.vision.roi import make_roi  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--hint-count-penalty", type=float, default=0.01)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.top_k < 1 or args.candidate_k < args.top_k:
        raise SystemExit("candidate-k must be at least top-k, and both must be positive")
    if args.hint_count_penalty < 0:
        raise SystemExit("hint-count-penalty must be non-negative")
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")

    guide_image = REPO_ROOT / "frontend" / "public" / "assets" / "guide-good.jpg"
    spec = EmbeddingSpec()
    with Image.open(guide_image) as image:
        image.load()
        roi, _ = make_roi(image, {"bbox": GUIDE_DETECTION["bbox"]})
    vector = DinoV2Embedder(spec).embed([roi])[0]
    query = {
        "damage_type": GUIDE_DETECTION["damage_type"],
        "query_parts": [GUIDE_DETECTION["part_code"]],
    }

    import psycopg
    from psycopg.rows import dict_row

    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            model_version_id = resolve_model_version(cur, spec)
            candidates = search_query(cur, 2, model_version_id, vector, args.candidate_k)
    baseline = candidates[:args.top_k]
    reranked = rerank_by_hint_count(
        candidates,
        query_part_count=len(query["query_parts"]),
        penalty=args.hint_count_penalty,
        top_k=args.top_k,
    )
    annotate_results(query, baseline)
    annotate_results(query, reranked)

    dataset_root = args.dataset_root.resolve()
    query_image = data_url(guide_image)
    box = GUIDE_DETECTION["bbox"]
    sections = []
    for title, rows in (
        ("A. v2 벡터 원순위", baseline),
        ("B. v2 + hint_count soft-ranking", reranked),
    ):
        cards = "".join(card(row, rank, dataset_root, show_roi=False) for rank, row in enumerate(rows, 1))
        sections.append(f"<section><h2>{title}</h2><div class='grid'>{cards}</div></section>")

    page = f"""<!doctype html><html lang='ko'><meta charset='utf-8'>
<title>guide-good v2 hint-count A/B</title>
<style>body{{font:15px system-ui;margin:28px;background:#f6f7fb;color:#172033}}h1,h2{{margin:0 0 14px}}section{{margin-top:28px}}.query,.result{{background:white;border:1px solid #dce2ee;border-radius:12px;padding:14px;box-shadow:0 2px 6px #0000000b}}.stage{{position:relative;max-width:760px}}.stage img{{display:block;width:100%;border-radius:8px}}.box{{position:absolute;border:3px solid #16a34a;box-sizing:border-box}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:14px}}.result img{{width:100%;height:190px;object-fit:cover;border-radius:7px}}.result.hit{{border:2px solid #16a34a}}.result.miss{{border-color:#fca5a5}}h3{{margin:0 0 9px}}dl{{display:grid;grid-template-columns:82px 1fr;gap:4px;margin:10px 0 0}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere}}.missing{{height:190px;display:grid;place-content:center;color:#b42318;background:#fff1f2;padding:8px;overflow-wrap:anywhere}}</style>
<main><h1>v2 DAMAGE · hint_count soft-ranking A/B</h1>
<p>후보 {args.candidate_k}개를 벡터로 가져온 뒤, 쿼리 부품 수(1)와 repair hint 수의 차이마다 distance에 {args.hint_count_penalty:.3f}을 더합니다. 후보를 제거하지 않습니다.</p>
<article class='query'><h2>Query: guide-good.jpg</h2><div class='stage'><img src='{query_image}' alt='guide-good'><i class='box' style='left:{box[0] / 960 * 100:.4f}%;top:{box[1] / 720 * 100:.4f}%;width:{box[2] / 960 * 100:.4f}%;height:{box[3] / 720 * 100:.4f}%'></i></div><p>YOLO: FRONT_BUMPER · SCRATCHED</p></article>{''.join(sections)}</main></html>"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output), "candidate_k": args.candidate_k, "penalty": args.hint_count_penalty}, ensure_ascii=False))


if __name__ == "__main__":
    main()
