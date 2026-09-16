from __future__ import annotations

from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, status

from ..adapters.ultralytics_yolo import ModelRunError
from ..core.config import Settings
from ..core.security import require_internal_token
from ..infrastructure.image_fetcher import ImageFetchError
from ..schemas.contracts import AnalyzeRequest, EstimateRequest, InferenceRequest, SearchRequest
from ..services.inference_service import InferenceService
from ..services.embedding_service import EmbeddingService, QueryEmbeddingError
from ..services.search_service import SearchService
from ..services.analysis_service import AnalysisService
from ..infrastructure.vector_repository import VectorSearchError


def build_router(settings: Settings, inference_service: InferenceService,
                 search_service: SearchService, embedding_service: EmbeddingService,
                 analysis_service: AnalysisService) -> APIRouter:
    router = APIRouter()

    def authenticated(token: str | None = Header(default=None, alias="X-Internal-Token")) -> None:
        require_internal_token(settings, token)

    @router.get("/health")
    def health() -> dict[str, bool | str]:
        return {
            "status": "ok",
            "modelsLoaded": inference_service.models_loaded,
            "embeddingModelLoaded": embedding_service.model_loaded,
            "dbReachable": search_service.repository.is_reachable(),
            "configured": settings.has_required_runtime_config,
            "analysisProfile": settings.analysis_profile,
            "embeddingConfigured": bool(settings.database_url),
            "partWeightsPresent": settings.part_weights.is_file(),
            "damageWeightsPresent": settings.damage_weights.is_file(),
        }

    @router.post("/inference", dependencies=[Depends(authenticated)])
    async def inference(request: InferenceRequest) -> dict:
        if settings.pipeline_version_id <= 0:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail={"code": "PIPELINE_NOT_CONFIGURED"})
        try:
            return await inference_service.infer(request.images)
        except ImageFetchError as exc:
            raise HTTPException(status.HTTP_404_NOT_FOUND, detail={"code": "IMAGE_FETCH_FAILED"}) from exc
        except ModelRunError as exc:
            raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, detail={"code": "MODEL_ERROR"}) from exc
        except ValueError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, detail={"code": "INVALID_MODEL_OUTPUT"}) from exc

    @router.post("/search", dependencies=[Depends(authenticated)])
    async def search(request: SearchRequest) -> dict:
        if settings.pipeline_version_id <= 0:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail={"code": "PIPELINE_NOT_CONFIGURED"})
        try:
            return await search_service.search(request)
        except ImageFetchError as exc:
            raise HTTPException(status.HTTP_404_NOT_FOUND, detail={"code": "IMAGE_FETCH_FAILED"}) from exc
        except QueryEmbeddingError as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, detail={"code": "INVALID_SEARCH_ROI"}) from exc
        except VectorSearchError as exc:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail={"code": "VECTOR_SEARCH_UNAVAILABLE"}) from exc

    @router.post("/estimate", dependencies=[Depends(authenticated)])
    async def estimate(_: EstimateRequest) -> dict:
        raise HTTPException(status.HTTP_501_NOT_IMPLEMENTED, detail={"code": "ESTIMATE_GATEWAY_NOT_IMPLEMENTED"})

    @router.post("/analyze", dependencies=[Depends(authenticated)], status_code=status.HTTP_202_ACCEPTED)
    async def analyze(request: AnalyzeRequest, background_tasks: BackgroundTasks) -> dict[str, Any]:
        if settings.pipeline_version_id <= 0:
            raise HTTPException(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": "PIPELINE_NOT_CONFIGURED"},
            )
        if settings.analysis_profile != "mock":
            raise HTTPException(
                status.HTTP_501_NOT_IMPLEMENTED,
                detail={"code": "ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED"},
            )
        background_tasks.add_task(analysis_service.run_mock, request)
        return {"accepted": True, "jobId": request.job_id, "requestId": request.request_id}

    return router
