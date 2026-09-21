"""Render a YOLO part-candidate review JSON with image-coordinate overlays."""

from __future__ import annotations

import argparse
import base64
import html
import json
from io import BytesIO
from pathlib import Path

from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def image_data_url(path: Path) -> tuple[str, int, int]:
    with Image.open(path) as image:
        image = image.convert("RGB")
        width, height = image.size
        image.thumbnail((960, 720))
        buffer = BytesIO()
        image.save(buffer, format="JPEG", quality=85)
    return (
        "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii"),
        width,
        height,
    )


def overlay_xywh(box: list[float], width: int, height: int, css: str, label: str) -> str:
    x, y, box_width, box_height = (float(value) for value in box)
    return (
        f'<i class="box {css}" style="left:{x / width * 100:.4f}%;'
        f'top:{y / height * 100:.4f}%;width:{box_width / width * 100:.4f}%;'
        f'height:{box_height / height * 100:.4f}%"><b>{html.escape(label)}</b></i>'
    )


def overlay_xyxy(box: dict, width: int, height: int, label: str) -> str:
    x0, y0, x1, y1 = (float(value) for value in box["coordinates"])
    return overlay_xywh([x0, y0, x1 - x0, y1 - y0], width, height, "part", label)


def feature_row(feature: dict) -> str:
    candidates = feature.get("candidates") or []
    candidate_text = ", ".join(
        f"{item['part_code']} (conf {float(item['confidence']):.2f}, overlap {float(item['overlap']):.2f})"
        for item in candidates
    ) or "-"
    return (
        "<tr>"
        f"<td>{html.escape(str(feature['damage_feature_id']))}</td>"
        f"<td>{html.escape(str(feature['mapping_status']))}</td>"
        f"<td>{html.escape(candidate_text)}</td>"
        "</tr>"
    )


def card(row: dict) -> str:
    path = Path(row["image_path"])
    if not path.is_file():
        return f"<article class='card'><h2>image {row['case_image_id']}</h2><p class='missing'>{html.escape(str(path))}</p></article>"
    image, width, height = image_data_url(path)
    overlays: list[str] = []
    for feature in row.get("features") or []:
        feature_id = str(feature["damage_feature_id"])
        status = str(feature["mapping_status"])
        overlays.append(overlay_xywh(feature["damage_bbox"], width, height, "damage", f"D{feature_id} {status}"))
        for candidate in feature.get("candidates") or []:
            label = f"{candidate['part_code']} c={float(candidate['confidence']):.2f} o={float(candidate['overlap']):.2f}"
            overlays.append(overlay_xyxy(candidate["part_bbox"], width, height, label))
    rows = "".join(feature_row(feature) for feature in row.get("features") or [])
    return f"""<article class='card'>
<h2>case_image_id {row['case_image_id']} <small>{html.escape(str(row['run_status']))}</small></h2>
<div class='stage'><img src='{image}' alt='case image'>{''.join(overlays)}</div>
<table><thead><tr><th>DAMAGE feature</th><th>mapping</th><th>YOLO part candidate</th></tr></thead><tbody>{rows}</tbody></table>
<p class='path'><b>ref</b> {html.escape(str(row['source_image_ref']))}<br><b>path</b> {html.escape(str(path))}</p>
</article>"""


def main() -> None:
    args = parse_args()
    report = json.loads(args.input.read_text(encoding="utf-8"))
    cards = "".join(card(row) for row in report.get("rows") or [])
    page = f"""<!doctype html><html lang='ko'><meta charset='utf-8'>
<title>YOLO part candidate review</title>
<style>
body{{font:15px system-ui;margin:28px;background:#f5f7fb;color:#172033}} h1,h2{{margin:0 0 10px}} p{{line-height:1.5}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(560px,1fr));gap:18px;margin-top:20px}} .card{{background:#fff;border:1px solid #dce2ee;border-radius:12px;padding:14px;box-shadow:0 2px 6px #0000000b}}
h2 small{{font-size:13px;font-weight:500;color:#526072}} .stage{{position:relative;background:#edf1f7}} .stage img{{display:block;width:100%;height:auto}} .box{{position:absolute;box-sizing:border-box;pointer-events:none}} .box.damage{{border:3px solid #ef4444}} .box.part{{border:3px solid #2563eb}} .box b{{position:absolute;left:0;top:-23px;white-space:nowrap;padding:2px 5px;border-radius:4px;font-size:11px;color:white;font-style:normal}} .box.damage b{{background:#b91c1c}} .box.part b{{background:#1d4ed8}}
table{{width:100%;border-collapse:collapse;margin-top:14px;font-size:12px}} th,td{{border-bottom:1px solid #e4e8f0;padding:7px;text-align:left;vertical-align:top}} th{{color:#526072}} .path{{font-size:11px;overflow-wrap:anywhere;color:#526072}} .legend span{{display:inline-block;margin-right:16px}} .damage-key{{color:#b91c1c}} .part-key{{color:#1d4ed8}} .missing{{color:#b42318;overflow-wrap:anywhere}}
</style>
<main><h1>v2 DAMAGE · YOLO 부품 후보 샘플 검토</h1>
<p class='legend'><span class='damage-key'>■ 빨강: AI-Hub DAMAGE bbox</span><span class='part-key'>■ 파랑: YOLO part bbox</span></p>
<p>파랑 bbox가 빨강 DAMAGE bbox를 충분히 포함하면 후보가 저장됩니다. `UNPAIRED`는 YOLO 부품이 없거나 overlap 기준을 충족한 부품이 없다는 뜻입니다.</p>
<section class='grid'>{cards}</section></main></html>"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
