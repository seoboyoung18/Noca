"""Render one EVAL query with v1/v2 Top-K image results as standalone HTML."""

from __future__ import annotations

import argparse
import base64
import html
import json
from io import BytesIO
from pathlib import Path
from typing import Any

from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True,
                        help="evaluation_results_v2.jsonl path")
    parser.add_argument("--dataset-root", type=Path, required=True,
                        help="Directory that directly contains 1.Training and 2.Validation")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--query-id", help="Defaults to a v2 Top-1 win over v1")
    parser.add_argument("--top-k", type=int, default=10)
    return parser.parse_args()


def choose_row(path: Path, query_id: str | None) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fp:
        rows = [json.loads(line) for line in fp if line.strip()]
    if query_id:
        for row in rows:
            if row.get("query_id") == query_id:
                return row
        raise ValueError(f"query_id not found: {query_id}")
    for row in rows:
        v1, v2 = row.get("results_v1") or [], row.get("results_v2") or []
        if v2 and v2[0].get("relevant") and (not v1 or not v1[0].get("relevant")):
            return row
    raise ValueError("No query where v2 Top-1 wins over v1 was found")


def data_url(path: Path, roi_box: Any | None = None) -> str:
    with Image.open(path) as image:
        image = image.convert("RGB")
        if isinstance(roi_box, str):
            roi_box = json.loads(roi_box)
        if isinstance(roi_box, dict):
            try:
                x, y = int(roi_box["x"]), int(roi_box["y"])
                width, height = int(roi_box["width"]), int(roi_box["height"])
                image = image.crop((x, y, x + width, y + height))
            except (KeyError, TypeError, ValueError):
                pass
        image.thumbnail((440, 330))
        buffer = BytesIO()
        image.save(buffer, format="JPEG", quality=82)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


def card(
    result: dict[str, Any],
    rank: int,
    dataset_root: Path,
    *,
    show_roi: bool = True,
) -> str:
    path = dataset_root / result["source_image_ref"]
    # A ROI view is useful when validating the embedding input.  The guide
    # comparison deliberately uses the full source image so a reviewer can
    # judge the retrieved case in its original vehicle context.
    image = data_url(path, result.get("roi_box") if show_roi else None) if path.is_file() else ""
    status = "정답" if result.get("relevant") else "오답"
    css = "hit" if result.get("relevant") else "miss"
    body = f'<img src="{image}" alt="rank {rank}">' if image else (
        f'<div class="missing">이미지 없음<br>{html.escape(str(path))}</div>'
    )
    hints = ", ".join(result.get("hint_part_codes") or []) or "-"
    rerank_rows = ""
    if "hint_count" in result:
        rerank_rows += (
            f"<dt>hint count</dt><dd>{int(result['hint_count'])}</dd>"
            f"<dt>hint penalty</dt><dd>{float(result['hint_count_penalty']):.4f}</dd>"
            f"<dt>rerank dist.</dt><dd>{float(result['rerank_distance']):.4f}</dd>"
        )
    return f"""<article class="result {css}">
<h3>#{rank} · {status}</h3>{body}
<dl><dt>damage</dt><dd>{html.escape(str(result.get('damage_type')))}</dd>
<dt>part</dt><dd>{html.escape(str(result.get('part_code') or '-'))}</dd>
<dt>hint</dt><dd>{html.escape(hints)}</dd>
<dt>distance</dt><dd>{float(result.get('distance', 0)):.4f}</dd>
{rerank_rows}
<dt>ref</dt><dd>{html.escape(str(result.get('source_image_ref') or '-'))}</dd>
<dt>path</dt><dd>{html.escape(str(path))}</dd></dl></article>"""


def main() -> None:
    args = parse_args()
    if args.top_k < 1:
        raise SystemExit("top-k must be positive")
    row = choose_row(args.results.resolve(), args.query_id)
    dataset_root = args.dataset_root.resolve()
    query_path = Path(row["image_path"])
    if not query_path.is_file():
        raise FileNotFoundError(query_path)
    query_image = data_url(query_path)
    query_parts = ", ".join(row.get("query_parts") or []) or "-"
    sections = []
    for version in (1, 2):
        results = (row.get(f"results_v{version}") or [])[:args.top_k]
        cards = "".join(card(result, rank, dataset_root) for rank, result in enumerate(results, 1))
        sections.append(f'<section><h2>v{version} Top-{args.top_k}</h2><div class="grid">{cards}</div></section>')
    page = f"""<!doctype html><html lang="ko"><meta charset="utf-8"><title>v1/v2 Query Visual Comparison</title>
<style>body{{font:15px system-ui;margin:28px;background:#f6f7fb;color:#172033}}h1,h2{{margin:0 0 14px}}section{{margin-top:28px}}.query,.result{{background:white;border:1px solid #dce2ee;border-radius:12px;padding:14px;box-shadow:0 2px 6px #0000000b}}.query img{{max-width:640px;width:100%;height:auto;border-radius:8px}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:14px}}.result img{{width:100%;height:190px;object-fit:cover;border-radius:7px}}.result.hit{{border:2px solid #16a34a}}.result.miss{{border-color:#fca5a5}}h3{{margin:0 0 9px}}dl{{display:grid;grid-template-columns:70px 1fr;gap:4px;margin:10px 0 0}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere}}.missing{{height:190px;display:grid;place-content:center;color:#b42318;background:#fff1f2;padding:8px;overflow-wrap:anywhere}}</style>
<main><h1>v1/v2 검색 결과 시각 비교</h1><p>Query: {html.escape(row['query_id'])}</p>
<article class="query"><h2>Query image</h2><img src="{query_image}" alt="query"><dl><dt>damage</dt><dd>{html.escape(str(row.get('damage_type')))}</dd><dt>parts</dt><dd>{html.escape(query_parts)}</dd></dl></article>
{''.join(sections)}</main></html>"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "query_id": row["query_id"], "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
