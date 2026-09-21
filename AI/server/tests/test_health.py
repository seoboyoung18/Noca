from __future__ import annotations

import unittest
from types import SimpleNamespace
from pathlib import Path

from app.api.routes import build_router
from app.core.config import Settings


class _Inference:
    def __init__(self, loaded: bool) -> None:
        self.models_loaded = loaded


class _Embedding:
    def __init__(self, loaded: bool) -> None:
        self.model_loaded = loaded


class _Repository:
    def __init__(self, reachable: bool) -> None:
        self.reachable = reachable

    def is_reachable(self) -> bool:
        return self.reachable


class _Search:
    def __init__(self, repository: _Repository) -> None:
        self.repository = repository


def _settings() -> Settings:
    return Settings(
        internal_token="test-token", pipeline_version_id=1, analysis_profile="production",
        inference_concurrency=1,
        model_imgsz=960, part_weights=Path("part.pt"), damage_weights=Path("damage.pt"),
        part_model_version="1", damage_model_version="1", database_url="postgresql://test",
        embedding_model_name="model", embedding_model_revision="revision",
        embedding_model_version="version", search_top_k=20, yolo_corpus_part_boost=0.03,
    )


class HealthRouteTest(unittest.TestCase):
    def test_health_reports_live_dependency_states(self):
        router = build_router(
            _settings(), _Inference(True), _Search(_Repository(True)), _Embedding(True),
            SimpleNamespace(can_orchestrate=True),
        )
        endpoint = next(route.endpoint for route in router.routes if route.path == "/health")

        self.assertEqual(endpoint(), {
            "status": "ok",
            "modelsLoaded": True,
            "embeddingModelLoaded": True,
            "dbReachable": True,
            "configured": True,
            "analysisProfile": "production",
            "embeddingConfigured": True,
            "partWeightsPresent": False,
            "damageWeightsPresent": False,
        })


if __name__ == "__main__":
    unittest.main()
