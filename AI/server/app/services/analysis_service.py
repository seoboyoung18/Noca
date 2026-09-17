"""Analysis orchestration and callback delivery for the AI HTTP boundary."""
from __future__ import annotations

import logging
import asyncio
from typing import Any

import httpx

from ..adapters.ultralytics_yolo import ModelRunError
from ..core.config import Settings
from ..infrastructure.image_fetcher import ImageFetchError
from ..schemas.contracts import AnalyzeRequest
from .inference_service import InferenceService

log = logging.getLogger(__name__)

# Initial delivery plus the three retry intervals in the integration contract.
CALLBACK_RETRY_DELAYS = (0, 1, 5, 20)
NO_DAMAGE_DETECTED = "NO_DAMAGE_DETECTED"
PART_NOT_RESOLVED = "PART_NOT_RESOLVED"
INSUFFICIENT_CASES = "INSUFFICIENT_CASES"


class AnalysisService:
    """Runs the mock analysis path without enabling it in production by accident.

    The mock deliberately performs real YOLO inference, then marks the result as
    non-estimable. This exercises the image download, model, normalization and
    backend callback path without inventing repair prices or search hits.
    """

    def __init__(self, settings: Settings, inference_service: InferenceService) -> None:
        self._settings = settings
        self._inference_service = inference_service

    async def run_mock(self, request: AnalyzeRequest) -> None:
        try:
            inference = await self._inference_service.infer(request.images)
            callback = _mock_success_callback(request, inference)
        except ImageFetchError as exc:
            log.exception("mock analysis image fetch failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, self._settings.pipeline_version_id,
                "IMAGE_FETCH_FAILED", "이미지 다운로드 실패", retryable=True,
            )
        except ModelRunError as exc:
            log.exception("mock analysis model failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, self._settings.pipeline_version_id,
                "MODEL_ERROR", "모델 실행 실패", retryable=True,
            )
        except ValueError as exc:
            log.exception("mock analysis output was invalid. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, self._settings.pipeline_version_id,
                "INVALID_MODEL_OUTPUT", "모델 출력 형식 오류", retryable=False,
            )
        except Exception as exc:  # callback must still tell the backend that the job ended
            log.exception("mock analysis failed unexpectedly. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, self._settings.pipeline_version_id,
                "INTERNAL", "AI 서버 내부 오류", retryable=True,
            )

        await self._deliver_callback(request.callback_url, request.request_id, callback)

    async def _deliver_callback(self, callback_url: str, request_id: str,
                                payload: dict[str, Any]) -> None:
        headers = {
            "Content-Type": "application/json",
            "X-Internal-Token": self._settings.internal_token,
            "X-Request-Id": request_id,
        }
        last_error: Exception | None = None
        for attempt, delay in enumerate(CALLBACK_RETRY_DELAYS, start=1):
            if delay:
                await asyncio.sleep(delay)
            try:
                async with httpx.AsyncClient(timeout=httpx.Timeout(10.0), follow_redirects=False) as client:
                    response = await client.post(callback_url, json=payload, headers=headers)
                    response.raise_for_status()
                log.info("analysis callback delivered. requestId=%s attempt=%s", request_id, attempt)
                return
            except httpx.HTTPError as exc:
                last_error = exc
                log.warning(
                    "analysis callback failed. requestId=%s attempt=%s/%s",
                    request_id, attempt, len(CALLBACK_RETRY_DELAYS),
                )
        log.error("analysis callback abandoned after retries. requestId=%s error=%s", request_id, last_error)


def _mock_success_callback(request: AnalyzeRequest, inference: dict[str, Any]) -> dict[str, Any]:
    image_results = inference.get("imageResults") or []

    return {
        "requestId": request.request_id,
        "jobId": request.job_id,
        "modelVersion": "a307-ai-mock-v1",
        "pipelineVersionId": inference["normalization"]["pipelineVersionId"],
        "estimable": False,
        "nonEstimableReason": _mock_non_estimable_reason(image_results),
        "confidenceGrade": None,
        "totals": None,
        "refCaseTotal": 0,
        "refYearFrom": None,
        "refYearTo": None,
        "items": [],
        "imageResults": image_results,
        "error": None,
    }


def _mock_non_estimable_reason(image_results: list[dict[str, Any]]) -> str:
    """Classify the mock result without replacing the real YOLO detections.

    ``excluded`` is set by the part model.  Damage detections on an excluded
    image must not make a non-vehicle image look like a damaged vehicle.
    """
    if not image_results or all(image.get("excluded") is True for image in image_results):
        return PART_NOT_RESOLVED

    vehicle_images = [image for image in image_results if image.get("excluded") is not True]
    if any(image.get("detections") for image in vehicle_images):
        return INSUFFICIENT_CASES

    return NO_DAMAGE_DETECTED


def _failure_callback(request: AnalyzeRequest, pipeline_version_id: int,
                      code: str, message: str,
                      *, retryable: bool) -> dict[str, Any]:
    return {
        "requestId": request.request_id,
        "jobId": request.job_id,
        "modelVersion": "a307-ai-mock-v1",
        "pipelineVersionId": pipeline_version_id,
        "estimable": False,
        "nonEstimableReason": None,
        "error": {"code": code, "message": message, "retryable": retryable},
    }
