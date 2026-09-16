"""DINOv2 ROI embeddings shared by corpus batch jobs and online search."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np
import torch
from PIL import Image


MODEL_NAME = "facebook/dinov2-base"
MODEL_REVISION = "f9e44c814b77203eaa57a6bdbbd535f21ede1415"
MODEL_VERSION = "f9e44c8-pooler-pad20-lb224gray"
EMBEDDING_DIMENSION = 768
IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)


class EmbeddingRunError(RuntimeError):
    pass


@dataclass(frozen=True)
class EmbeddingSpec:
    model_name: str = MODEL_NAME
    revision: str = MODEL_REVISION
    version: str = MODEL_VERSION
    dimension: int = EMBEDDING_DIMENSION


class DinoV2Embedder:
    """Load DINOv2 lazily and embed pre-letterboxed 224px ROI images.

    Do not use AutoImageProcessor here: it would resize/center-crop a second
    time and break the corpus preprocessing contract.
    """

    def __init__(self, spec: EmbeddingSpec) -> None:
        self._spec = spec
        self._model: object | None = None
        self._device = "cuda" if torch.cuda.is_available() else "cpu"

    @property
    def spec(self) -> EmbeddingSpec:
        return self._spec

    @property
    def device(self) -> str:
        return self._device

    @property
    def is_loaded(self) -> bool:
        """Whether the lazy Hugging Face model has been loaded into this process."""
        return self._model is not None

    def _load_model(self) -> object:
        if self._model is None:
            try:
                from transformers import AutoModel
                self._model = AutoModel.from_pretrained(
                    self._spec.model_name, revision=self._spec.revision,
                ).to(self._device).eval()
            except Exception as exc:
                raise EmbeddingRunError("failed to load DINOv2 embedding model") from exc
        return self._model

    def embed(self, images: Sequence[Image.Image]) -> np.ndarray:
        if not images:
            return np.empty((0, self._spec.dimension), dtype=np.float32)
        if any(image.size != (224, 224) for image in images):
            raise EmbeddingRunError("embedding input must be a 224x224 letterbox ROI")
        pixels = np.stack([
            np.asarray(image.convert("RGB"), dtype=np.uint8) for image in images
        ])
        tensor = torch.from_numpy(pixels).float().div_(255.0).permute(0, 3, 1, 2)
        mean = torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1)
        std = torch.tensor(IMAGENET_STD).view(1, 3, 1, 1)
        tensor = ((tensor - mean) / std).to(self._device)
        try:
            with torch.inference_mode():
                output = self._load_model()(pixel_values=tensor).pooler_output
        except EmbeddingRunError:
            raise
        except Exception as exc:
            raise EmbeddingRunError("DINOv2 embedding inference failed") from exc
        vectors = torch.nn.functional.normalize(output.float(), dim=1).cpu().numpy().astype(np.float32)
        if vectors.ndim != 2 or vectors.shape[1] != self._spec.dimension:
            raise EmbeddingRunError(
                f"DINOv2 dimension mismatch: expected {self._spec.dimension}, got {vectors.shape}"
            )
        if not np.isfinite(vectors).all():
            raise EmbeddingRunError("DINOv2 produced non-finite values")
        return vectors
