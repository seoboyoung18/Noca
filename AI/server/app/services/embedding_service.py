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

    @property
    def model_loaded(self) -> bool:
        return self._embedder.is_loaded

    def embed_detection(self, image: Image.Image, detection: Mapping[str, Any]) -> np.ndarray:
        """Make one padded ROI from API geometry, then produce one normalized vector."""
        return self.embed_detections(image, [detection])[0]

    def embed_detections(
        self, image: Image.Image, detections: list[Mapping[str, Any]]
    ) -> list[np.ndarray]:
        """Embed upload-image detections in one model batch.

        The ROI construction is intentionally shared with the corpus pipeline via
        ``make_roi``. Keeping vectors in request memory is enough for online
        search; only corpus vectors are persisted in ``repair_case_roi_embedding``.
        """
        if not detections:
            return []

        rois: list[Image.Image] = []
        for detection in detections:
            rois.append(_make_detection_roi(image, detection))
        try:
            vectors = self._embedder.embed(rois)
        except EmbeddingRunError as exc:
            raise QueryEmbeddingError(str(exc)) from exc
        return [vectors[index] for index in range(len(rois))]


def _make_detection_roi(image: Image.Image, detection: Mapping[str, Any]) -> Image.Image:
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
        return roi
    except (KeyError, TypeError, ValueError, RoiError) as exc:
        raise QueryEmbeddingError("detection geometry cannot form a search ROI") from exc
