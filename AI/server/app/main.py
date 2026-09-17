from __future__ import annotations

from fastapi import FastAPI

from .api.routes import build_router
from .core.bootstrap import ensure_repo_root
from .core.config import load_settings
from .infrastructure.cost_repository import PostgresCostCaseRepository
from .infrastructure.vector_repository import VectorRepository
from .services.embedding_service import EmbeddingService
from .services.estimate_service import EstimateService
from .services.inference_service import InferenceService
from .services.search_service import SearchService
from .services.analysis_service import AnalysisService

ensure_repo_root()
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec  # noqa: E402

settings = load_settings()
inference_service = InferenceService(settings)
embedding_service = EmbeddingService(DinoV2Embedder(EmbeddingSpec(
    model_name=settings.embedding_model_name,
    revision=settings.embedding_model_revision,
    version=settings.embedding_model_version,
)))
vector_repository = VectorRepository(
    settings.database_url,
    expected_model_name=settings.embedding_model_name,
    expected_model_version=settings.embedding_model_version,
)
cost_repository = PostgresCostCaseRepository(settings.database_url)
estimate_service = EstimateService(cost_repository)
search_service = SearchService(
    vector_repository, embedding_service,
    pipeline_version_id=settings.pipeline_version_id,
    top_k=settings.search_top_k,
)
analysis_service = AnalysisService(
    settings, inference_service, search_service, estimate_service,
)

app = FastAPI(title="A307 AI Server", version="0.1.0")
app.include_router(build_router(
    settings,
    inference_service,
    search_service,
    embedding_service,
    analysis_service,
    estimate_service,
))
