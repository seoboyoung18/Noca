"""Search v1/v2 with the frontend guide-good image and render Top-K side by side."""

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

from pipeline.jobs.evaluation.evaluate_search_ab import annotate_results, search_query
from pipeline.jobs.evaluation.render_query_comparison import card, data_url
from pipeline.jobs.ingestion.embed_search_corpus import resolve_model_version
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi


# This is the actual /inference response recorded in AI/artifacts/guide-good-yolo-result.html.
GUIDE_DETECTION = {
    "bbox": [167, 224, 663, 178],
    "damage_type": "SCRATCHED",
    "part_code": "FRONT_BUMPER",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.top_k < 1:
        raise SystemExit("top-k must be positive")
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    guide_image = Path(__file__).resolve().parents[3] / "frontend" / "public" / "assets" / "guide-good.jpg"
    if not guide_image.is_file():
        raise FileNotFoundError(guide_image)

    import psycopg
    from psycopg.rows import dict_row

    spec = EmbeddingSpec()
    with Image.open(guide_image) as image:
        image.load()
        roi, _ = make_roi(image, {"bbox": GUIDE_DETECTION["bbox"]})
    vector = DinoV2Embedder(spec).embed([roi])[0]
    query = {
        "damage_type": GUIDE_DETECTION["damage_type"],
        "query_parts": [GUIDE_DETECTION["part_code"]],
    }
    results: dict[int, list[dict]] = {}
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            model_version_id = resolve_model_version(cur, spec)
            for pipeline_id in (1, 2):
                hits = search_query(cur, pipeline_id, model_version_id, vector, args.top_k)
                annotate_results(query, hits)
                results[pipeline_id] = hits

    dataset_root = args.dataset_root.resolve()
    query_image = data_url(guide_image)
    query_box = GUIDE_DETECTION["bbox"]
    sections = []
    for pipeline_id in (1, 2):
        cards = "".join(
            card(hit, rank, dataset_root, show_roi=False)
            for rank, hit in enumerate(results[pipeline_id], 1)
        )
        sections.append(f'<section><h2>v{pipeline_id} Top-{args.top_k}</h2><div class="grid">{cards}</div></section>')
    page = f"""<!doctype html><html lang="ko"><meta charset="utf-8"><title>guide-good v1/v2 comparison</title>
<style>body{{font:15px system-ui;margin:28px;background:#f6f7fb;color:#172033}}h1,h2{{margin:0 0 14px}}section{{margin-top:28px}}.query,.result{{background:white;border:1px solid #dce2ee;border-radius:12px;padding:14px;box-shadow:0 2px 6px #0000000b}}.stage{{position:relative;max-width:760px}}.stage img{{display:block;width:100%;border-radius:8px}}.box{{position:absolute;border:3px solid #16a34a;box-sizing:border-box}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:14px}}.result img{{width:100%;height:190px;object-fit:cover;border-radius:7px}}.result.hit{{border:2px solid #16a34a}}.result.miss{{border-color:#fca5a5}}h3{{margin:0 0 9px}}dl{{display:grid;grid-template-columns:70px 1fr;gap:4px;margin:10px 0 0}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere}}.missing{{height:190px;display:grid;place-content:center;color:#b42318;background:#fff1f2;padding:8px;overflow-wrap:anywhere}}</style>
<main><h1>프론트 guide-good 이미지 검색 비교</h1><p>실제 YOLO 검출: FRONT_BUMPER · SCRATCHED</p><article class="query"><h2>Query: guide-good.jpg</h2><div class="stage"><img src="{query_image}" alt="guide-good"><i class="box" style="left:{query_box[0] / 960 * 100:.4f}%;top:{query_box[1] / 720 * 100:.4f}%;width:{query_box[2] / 960 * 100:.4f}%;height:{query_box[3] / 720 * 100:.4f}%"></i></div></article>{''.join(sections)}</main></html>"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output), "top_k": args.top_k}, ensure_ascii=False))


if __name__ == "__main__":
    main()
