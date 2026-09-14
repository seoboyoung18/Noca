"""damage·part 모델을 순차 실행해 raw YOLO JSON 형식으로 추론 결과 저장.

- 좌표: 원본 이미지 기준 픽셀 좌표 (xyxy, polygon 모두)
- 모델별 결과는 ./추론결과/<타임스탬프>/<damage|damage_part>/ 아래에
  이미지 1장당 JSON 1개 + classes.json(클래스 ID-이름 매핑표) 저장
"""
import json
from datetime import datetime
from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent
IMG_DIR = ROOT / "samples" / "input"
OUT_ROOT = ROOT / "artifacts" / "raw-yolo"

MODELS = [
    {
        "key": "damage",
        "weights": ROOT / "models" / "damage" / "damage_best-60ep.pt",
        "name": "vehicle-damage-segmentation",
        "version": "60ep",
        "prefix": "damage",
    },
    {
        "key": "damage_part",
        "weights": ROOT / "models" / "part" / "damage_part_best-35ep.pt",
        "name": "vehicle-part-segmentation-legacy",
        "version": "35ep",
        "prefix": "part",
    },
]


def to_prediction(idx, prefix, box, names, mask_xy):
    x1, y1, x2, y2 = [round(float(v)) for v in box.xyxy[0].tolist()]
    cls_id = int(box.cls[0])
    conf = round(float(box.conf[0]), 4)

    segmentation = None
    if mask_xy is not None:
        segmentation = {
            "format": "polygon",
            "polygons": [[[round(float(x)), round(float(y))] for x, y in mask_xy]],
        }

    return {
        "detection_id": f"{prefix}-{idx + 1:03d}",
        "class_id": cls_id,
        "class_name": names[cls_id],
        "confidence": conf,
        "bbox": {"format": "xyxy", "coordinates": [x1, y1, x2, y2]},
        "segmentation": segmentation,
    }


def run(cfg, run_dir):
    model = YOLO(str(cfg["weights"]))
    out_dir = run_dir / cfg["key"]
    out_dir.mkdir(parents=True, exist_ok=True)

    (out_dir / "classes.json").write_text(
        json.dumps({"model_version": cfg["version"], "classes": model.names}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    results = model.predict(source=str(IMG_DIR), imgsz=960, save=False)
    for result in results:
        image_id = Path(result.path).stem
        h, w = result.orig_shape

        preds = []
        if result.boxes is not None and len(result.boxes) > 0:
            masks_xy = result.masks.xy if result.masks is not None else None
            for i in range(len(result.boxes)):
                mask_xy = masks_xy[i] if masks_xy is not None else None
                preds.append(to_prediction(i, cfg["prefix"], result.boxes[i], result.names, mask_xy))

        payload = {
            "model": {"name": cfg["name"], "version": cfg["version"], "task": model.task},
            "image": {"image_id": image_id, "original_width": w, "original_height": h},
            "predictions": preds,
        }
        (out_dir / f"{image_id}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    print(f"[{cfg['key']}] 완료: {out_dir}")


def main():
    run_dir = OUT_ROOT / datetime.now().strftime("%Y%m%d_%H%M%S")
    for cfg in MODELS:
        run(cfg, run_dir)
    print(f"전체 완료: {run_dir}")


if __name__ == "__main__":
    main()
