"""Convert Ultralytics predictions into the model-developer raw JSON contract."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any


class ModelRunError(RuntimeError):
    pass


@dataclass(frozen=True)
class ModelSpec:
    key: str
    name: str
    version: str
    api_task: str
    weights: Path


class UltralyticsRunner:
    def __init__(self, image_size: int) -> None:
        self._image_size = image_size
        self._models: dict[Path, Any] = {}

    def _model(self, spec: ModelSpec) -> Any:
        if not spec.weights.is_file():
            raise ModelRunError(f"model weights not found: {spec.weights}")
        if spec.weights not in self._models:
            try:
                from ultralytics import YOLO
            except ImportError as exc:
                raise ModelRunError("ultralytics is not installed") from exc
            self._models[spec.weights] = YOLO(str(spec.weights))
        return self._models[spec.weights]

    def run(self, spec: ModelSpec, image_path: Path, image_id: int) -> tuple[dict[str, Any], dict[int, str]]:
        model = self._model(spec)
        try:
            result = model.predict(source=str(image_path), imgsz=self._image_size, save=False, verbose=False)[0]
        except Exception as exc:  # model/runtime exceptions are normalized at the route boundary
            raise ModelRunError(f"{spec.key} model execution failed") from exc

        height, width = (int(value) for value in result.orig_shape)
        names = {int(key): str(value) for key, value in dict(result.names).items()}
        masks = result.masks.xy if result.masks is not None else None
        predictions: list[dict[str, Any]] = []
        boxes = result.boxes if result.boxes is not None else []
        for index, box in enumerate(boxes):
            class_id = int(box.cls[0])
            x0, y0, x1, y1 = [round(float(value)) for value in box.xyxy[0].tolist()]
            segmentation = None
            if spec.api_task == "segment":
                if masks is None:
                    raise ModelRunError("damage segmentation model returned boxes without masks")
                segmentation = {
                    "format": "polygon",
                    "polygons": [[
                        [round(float(x)), round(float(y))] for x, y in masks[index]
                    ]],
                }
            predictions.append({
                "detection_id": f"{spec.key}-{index + 1:03d}",
                "class_id": class_id,
                "class_name": names[class_id],
                "confidence": round(float(box.conf[0]), 4),
                "bbox": {"format": "xyxy", "coordinates": [x0, y0, x1, y1]},
                "segmentation": segmentation,
            })

        return {
            "model": {"name": spec.name, "version": spec.version, "task": spec.api_task},
            "image": {"image_id": str(image_id), "original_width": width, "original_height": height},
            "predictions": predictions,
        }, names
