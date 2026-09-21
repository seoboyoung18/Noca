"""Render the guide-good v2 Top-K YOLO review JSON as a self-contained HTML."""

from __future__ import annotations

import argparse
import base64
import html
import json
from io import BytesIO
from pathlib import Path

from PIL import Image


REPO_ROOT = Path(__file__).resolve().parents[3]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def image_data_url(path: Path) -> str:
    with Image.open(path) as image:
        image = image.convert("RGB")
        image.thumbnail((620, 420))
        buffer = BytesIO()
        image.save(buffer, format="JPEG", quality=84)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


def detection_html(detection: dict) -> str:
    part = detection.get("part_code") or "부품 매칭 없음"
    part_confidence = detection.get("part_confidence")
    damage_confidence = detection.get("damage_confidence")
    return (
        '<li><b>{part}</b> · {damage} · {status}<br>'
        'part conf: {part_conf} · damage conf: {damage_conf}</li>'
    ).format(
        part=html.escape(str(part)),
        damage=html.escape(str(detection.get("damage_type") or "-")),
        status=html.escape(str(detection.get("pair_status") or "-")),
        part_conf="-" if part_confidence is None else f"{float(part_confidence):.4f}",
        damage_conf="-" if damage_confidence is None else f"{float(damage_confidence):.4f}",
    )


def card(row: dict) -> str:
    path = Path(row["image_path"])
    if path.is_file():
        image = f'<img src="{image_data_url(path)}" alt="rank {row["rank"]}">'
    else:
        image = '<div class="missing">원본 이미지 없음</div>'
    detections = row.get("yolo_detections") or []
    yolo = "<ul>" + "".join(detection_html(item) for item in detections) + "</ul>" if detections else (
        '<p class="none">YOLO 손상 검출 없음</p>'
    )
    return f'''<article class="card">
<h2>#{int(row["rank"])} <small>distance {float(row["distance"]):.4f}</small></h2>
{image}
<h3>YOLO 결과</h3>{yolo}
<dl><dt>feature</dt><dd>{html.escape(str(row.get("damage_feature_id")))}</dd>
<dt>source ref</dt><dd>{html.escape(str(row.get("source_image_ref")))}</dd>
<dt>local path</dt><dd>{html.escape(str(path))}</dd></dl>
</article>'''


def main() -> None:
    args = parse_args()
    report = json.loads(args.input.read_text(encoding="utf-8"))
    guide_path = REPO_ROOT / "frontend" / "public" / "assets" / "guide-good.jpg"
    guide_image = image_data_url(guide_path)
    cards = "".join(card(row) for row in report["rows"])
    page = f'''<!doctype html><html lang="ko"><meta charset="utf-8">
<title>guide-good v2 Top-10 YOLO review</title>
<style>
body{{font:15px system-ui;margin:28px;background:#f5f7fb;color:#172033}} h1,h2,h3{{margin:0 0 10px}}
.query,.card{{background:#fff;border:1px solid #dce2ee;border-radius:12px;padding:14px;box-shadow:0 2px 6px #0000000b}}
.query{{max-width:720px}} .query img{{max-width:100%;border-radius:8px}} .grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:16px;margin-top:20px}}
.card img{{width:100%;height:240px;object-fit:contain;background:#eef2f7;border-radius:8px}} .card h2 small{{font-weight:400;color:#687386}} h3{{margin-top:12px;font-size:15px}}
ul{{margin:0;padding-left:20px}} li{{margin:5px 0}} dl{{display:grid;grid-template-columns:78px 1fr;gap:4px;margin:12px 0 0}} dt{{color:#687386}} dd{{margin:0;overflow-wrap:anywhere;font-size:12px}} .none{{color:#b42318}} .missing{{height:240px;display:grid;place-items:center;background:#fff1f2;color:#b42318}}
</style>
<main><h1>guide-good → v2 DAMAGE Top-10 / YOLO 부품 검토</h1>
<p>검색 순위는 기존 DINOv2 벡터 결과이며, 각 결과 원본에 YOLO 부품·손상 모델을 별도로 실행한 샘플입니다.</p>
<section class="query"><h2>Query: guide-good.jpg</h2><img src="{guide_image}" alt="guide-good"><p>기대 부품: <b>FRONT_BUMPER</b> · 기대 손상: <b>SCRATCHED</b></p></section>
<section class="grid">{cards}</section></main></html>'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
