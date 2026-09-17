from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path
from typing import Any

from ..core.bootstrap import ensure_repo_root
ensure_repo_root()

from ..adapters.ultralytics_yolo import ModelSpec, UltralyticsRunner
from ..adapters.yolo_adapter import adapt_raw_yolo_outputs
from ..core.config import Settings
from ..infrastructure.image_fetcher import download_image
from ..schemas.contracts import InputImage


class InferenceService:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._runner = UltralyticsRunner(settings.model_imgsz)
        self._part = ModelSpec("part", "vehicle-part-detection", settings.part_model_version,
                               "detect", settings.part_weights)
        self._damage = ModelSpec("damage", "vehicle-damage-segmentation", settings.damage_model_version,
                                 "segment", settings.damage_weights)

    @property
    def models(self) -> dict[str, dict[str, str]]:
        return {
            "part": {"name": self._part.name, "version": self._part.version, "task": self._part.api_task},
            "damage": {"name": self._damage.name, "version": self._damage.version, "task": self._damage.api_task},
        }

    @property
    def models_loaded(self) -> bool:
        """Both YOLO artifacts required by /inference are present in the process cache."""
        return self._runner.is_loaded(self._part) and self._runner.is_loaded(self._damage)

    async def infer(self, images: list[InputImage]) -> dict[str, Any]:
        results: list[dict[str, Any]] = []
        with tempfile.TemporaryDirectory(prefix="a307-ai-") as directory:
            target_dir = Path(directory)
            for image in images:
                image_path = await download_image(image.url, target_dir, image.image_id)
                part_raw, part_map = await asyncio.to_thread(self._runner.run, self._part, image_path, image.image_id)
                damage_raw, damage_map = await asyncio.to_thread(self._runner.run, self._damage, image_path, image.image_id)
                normalized = await asyncio.to_thread(
                    adapt_raw_yolo_outputs,
                    part_raw,
                    damage_raw,
                    image_id=image.image_id,
                    part_class_map=part_map,
                    damage_class_map=damage_map,
                )
                # The part model is the vehicle-validity gate.  A valid vehicle
                # photo is expected to contain at least one detectable part;
                # the existing backend/FE contract already handles this reason.
                part_predictions = part_raw.get("predictions") or []
                results.append(_to_api_image_result(
                    normalized,
                    image.image_id,
                    excluded=not bool(part_predictions),
                ))
        return {
            "models": self.models,
            "normalization": {
                "schemaVersion": "1.2.0",
                "workRuleVersion": "damage-default-v1",
                "pipelineVersionId": self._settings.pipeline_version_id,
            },
            "imageResults": results,
        }


def _to_api_image_result(
    normalized: dict[str, Any], image_id: int, *, excluded: bool = False,
) -> dict[str, Any]:
    image = normalized["image"]
    detections = []
    for detection in normalized["detections"]:
        geometry = detection["geometry"]
        segmentation = geometry["segmentation"]
        part = detection.get("part") or {}
        damage = detection["damage"]
        detections.append({
            "detectionId": detection["detection_id"],
            "partCode": part.get("code"),
            "partRawLabel": part.get("raw_label"),
            "damageType": damage["name_en"],
            "damageRawLabel": damage["raw_label"],
            "pairStatus": detection["pair_status"],
            "searchability": detection["searchability"],
            "confidence": detection["confidence"],
            "geometry": {
                "coordinateSystem": geometry["coordinate_system"],
                "bboxFormat": geometry["bbox_format"],
                "bbox": geometry["bbox"],
                "polygons": segmentation["polygons"] if segmentation else [],
                "areaPx": segmentation["area_px"] if segmentation else None,
                "areaRatio": segmentation["area_ratio"] if segmentation else None,
            },
        })
    return {
        "imageId": image_id,
        "width": image["width"],
        "height": image["height"],
        "excluded": excluded,
        "exclusionReason": "NOT_VEHICLE" if excluded else None,
        "detections": detections,
        "normalizationStats": {"detectionCount": len(detections), "droppedCount": 0, "dropReasons": []},
    }
