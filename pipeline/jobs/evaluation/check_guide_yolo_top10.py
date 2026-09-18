"""Run the deployed YOLO pair against guide-good's v2 Top-K source images.

This is a read-only review tool: it does not write any DB rows.  It lets us
inspect whether YOLO can provide a usable part candidate for the DAMAGE corpus
before designing a corpus-wide candidate batch job.
"""

from __future__ import annotations

import argparse
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

from AI.server.app.adapters.ultralytics_yolo import ModelSpec, UltralyticsRunner  # noqa: E402
from AI.server.app.adapters.yolo_adapter import adapt_raw_yolo_outputs  # noqa: E402
from pipeline.jobs.evaluation.evaluate_search_ab import search_query  # noqa: E402
from pipeline.jobs.evaluation.render_guide_good_comparison import GUIDE_DETECTION  # noqa: E402
from pipeline.jobs.ingestion.embed_search_corpus import resolve_model_version  # noqa: E402
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec  # noqa: E402
from shared.vision.roi import make_roi  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="guide-good v2 Top-K 원본 이미지에 part/damage YOLO를 실행합니다."
    )
    parser.add_argument("--dataset-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--imgsz", type=int, default=960)
    return parser.parse_args()


def guide_vector() -> object:
    guide_image = REPO_ROOT / "frontend" / "public" / "assets" / "guide-good.jpg"
    with Image.open(guide_image) as image:
        image.load()
        roi, _ = make_roi(image, {"bbox": GUIDE_DETECTION["bbox"]})
    return DinoV2Embedder(EmbeddingSpec()).embed([roi])[0]


def compact_detection(detection: dict) -> dict:
    """Keep the review JSON readable; polygons are not needed for this check."""
    part = detection.get("part") or {}
    damage = detection["damage"]
    return {
        "part_code": part.get("code"),
        "damage_type": damage["code"],
        "part_confidence": detection["confidence"].get("part"),
        "damage_confidence": detection["confidence"]["damage"],
        "pair_status": detection["pair_status"],
        "searchability": detection["searchability"],
        "damage_bbox": detection["geometry"]["bbox"],
    }

def main() -> None:
    args = parse_args()
    if args.top_k < 1:
        raise SystemExit("top-k must be positive")
    if not os.environ.get("DATABASE_URL"):
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")

    import psycopg
    from psycopg.rows import dict_row

    with psycopg.connect(os.environ["DATABASE_URL"], row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            model_version_id = resolve_model_version(cur, EmbeddingSpec())
            hits = search_query(cur, 2, model_version_id, guide_vector(), args.top_k)

    ai_root = REPO_ROOT / "AI"
    runner = UltralyticsRunner(args.imgsz)
    part = ModelSpec(
        "part", "vehicle-part-detection", "35ep", "detect",
        ai_root / "models" / "part" / "damage_part_best-35ep.pt",
    )
    damage = ModelSpec(
        "damage", "vehicle-damage-segmentation", "60ep", "segment",
        ai_root / "models" / "damage" / "damage_best-60ep.pt",
    )
    rows = []
    for rank, hit in enumerate(hits, 1):
        image_path = args.dataset_root.resolve() / hit["source_image_ref"]
        if not image_path.is_file():
            rows.append({"rank": rank, "source_image_ref": hit["source_image_ref"],
                         "image_path": str(image_path), "error": "IMAGE_MISSING"})
            continue
        part_raw, part_map = runner.run(part, image_path, rank)
        damage_raw, damage_map = runner.run(damage, image_path, rank)
        normalized = adapt_raw_yolo_outputs(
            part_raw, damage_raw, image_id=rank,
            part_class_map=part_map, damage_class_map=damage_map,
        )
        rows.append({
            "rank": rank,
            "damage_feature_id": hit["damage_feature_id"],
            "distance": float(hit["distance"]),
            "source_image_ref": hit["source_image_ref"],
            "image_path": str(image_path),
            "yolo_detections": [
                compact_detection(detection)
                for detection in normalized["detections"]
            ],
        })

    payload = {
        "status": "SUCCEEDED",
        "query": {"image": "frontend/public/assets/guide-good.jpg", **GUIDE_DETECTION},
        "pipeline_version_id": 2,
        "top_k": args.top_k,
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output), "count": len(rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
