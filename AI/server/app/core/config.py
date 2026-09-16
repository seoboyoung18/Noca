"""Environment-backed server configuration; secrets never live in the repository."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[3]


@dataclass(frozen=True)
class Settings:
    internal_token: str
    pipeline_version_id: int
    analysis_profile: str
    inference_concurrency: int
    model_imgsz: int
    part_weights: Path
    damage_weights: Path
    part_model_version: str
    damage_model_version: str
    database_url: str
    embedding_model_name: str
    embedding_model_revision: str
    embedding_model_version: str
    search_top_k: int

    @property
    def has_required_runtime_config(self) -> bool:
        return bool(self.internal_token) and self.pipeline_version_id > 0


def load_settings() -> Settings:
    return Settings(
        internal_token=os.getenv("AI_INTERNAL_TOKEN", ""),
        pipeline_version_id=int(os.getenv("FEATURE_PIPELINE_VERSION_ID", "0")),
        analysis_profile=os.getenv("ANALYSIS_PROFILE", "production").strip().lower(),
        inference_concurrency=int(os.getenv("MAX_INFERENCE_CONCURRENCY", "1")),
        model_imgsz=int(os.getenv("YOLO_IMAGE_SIZE", "960")),
        part_weights=Path(os.getenv(
            "PART_MODEL_WEIGHTS", str(AI_ROOT / "models/part/damage_part_best-35ep.pt"))),
        damage_weights=Path(os.getenv(
            "DAMAGE_MODEL_WEIGHTS", str(AI_ROOT / "models/damage/damage_best-60ep.pt"))),
        part_model_version=os.getenv("PART_MODEL_VERSION", "35ep"),
        damage_model_version=os.getenv("DAMAGE_MODEL_VERSION", "60ep"),
        database_url=os.getenv("DATABASE_URL", ""),
        embedding_model_name=os.getenv("EMBEDDING_MODEL_NAME", "facebook/dinov2-base"),
        embedding_model_revision=os.getenv(
            "EMBEDDING_MODEL_REVISION", "f9e44c814b77203eaa57a6bdbbd535f21ede1415"),
        embedding_model_version=os.getenv(
            "EMBEDDING_MODEL_VERSION", "f9e44c8-pooler-pad20-lb224gray"),
        search_top_k=int(os.getenv("SEARCH_TOP_K", "20")),
    )
