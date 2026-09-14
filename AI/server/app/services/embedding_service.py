"""Create query vectors using the exact corpus ROI and DINOv2 contract."""
from __future__ import annotations

from typing import Any, Mapping

import numpy as np
from PIL import Image

from ..core.bootstrap import ensure_repo_root

ensure_repo_root()
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingRunError  # noqa: E402
from shared.vision.roi import RoiError, make_roi  # noqa: E402


class QueryEmbeddingError(ValueError):
    pass


class EmbeddingService:
    def __init__(self, embedder: DinoV2Embedder) -> None:
        self._embedder = embedder

    @property
    def model_version(self) -> str:
        return self._embedder.spec.version

    def embed_detection(self, image: Image.Image, detection: Mapping[str, Any]) -> np.ndarray:
        """Make a padded ROI from API geometry, then produce one normalized vector."""
        try:
            geometry = detection["geometry"]
            bbox = geometry["bbox"]
            source_bbox = [float(bbox[key]) for key in ("x", "y", "width", "height")]
            polygons = geometry.get("polygons") or []
            segmentation = [
                [[float(point["x"]), float(point["y"])] for point in polygon]
                for polygon in polygons
            ]
            confidence = (detection.get("confidence") or {}).get("damage")
            annotation = {
                "id": detection.get("detectionId"),
                "bbox": source_bbox,
                "segmentation": segmentation,
            }
            roi, _ = make_roi(image, annotation, confidence=confidence)
        except (KeyError, TypeError, ValueError, RoiError) as exc:
            raise QueryEmbeddingError("detection geometry cannot form a search ROI") from exc
        try:
            return self._embedder.embed([roi])[0]
        except EmbeddingRunError as exc:
            raise QueryEmbeddingError(str(exc)) from exc
