"""Analysis orchestration and callback delivery for the AI HTTP boundary."""
from __future__ import annotations

import logging
import asyncio
from typing import Any

import httpx

from ..adapters.ultralytics_yolo import ModelRunError
from ..core.config import Settings
from ..infrastructure.cost_repository import CostRepositoryError
from ..infrastructure.image_fetcher import ImageFetchError
from ..infrastructure.vector_repository import VectorSearchError
from ..schemas.contracts import AnalyzeRequest, EstimateRequest, SearchRequest
from .embedding_service import QueryEmbeddingError
from .estimate_service import EstimateService
from .inference_service import InferenceService
from .search_service import SearchService

log = logging.getLogger(__name__)

# Initial delivery plus the three retry intervals in the integration contract.
CALLBACK_RETRY_DELAYS = (0, 1, 5, 20)
NO_DAMAGE_DETECTED = "NO_DAMAGE_DETECTED"
PART_NOT_RESOLVED = "PART_NOT_RESOLVED"
INSUFFICIENT_CASES = "INSUFFICIENT_CASES"

MOCK_MODEL_VERSION = "a307-ai-mock-v1"
UNKNOWN_MODEL_VERSION = "unknown"
# 백엔드 CallbackItem 계약: modelVersion 은 50자 제한(@Size(max = 50)).
MODEL_VERSION_MAX_LENGTH = 50


class AnalysisService:
    """Runs one analysis job end to end and reports it back with a single callback.

    Two paths share the delivery and failure handling below.

    ``run`` is the real path — inference → search → estimate. ``run_mock``
    deliberately performs real YOLO inference, then marks the result as
    non-estimable. The mock exercises the image download, model, normalization
    and backend callback path without touching pgvector or the cost tables, so
    it stays useful for backend integration testing when the search corpus or
    the cost data is not loaded.
    """

    def __init__(self, settings: Settings, inference_service: InferenceService,
                 search_service: SearchService | None = None,
                 estimate_service: EstimateService | None = None) -> None:
        self._settings = settings
        self._inference_service = inference_service
        self._search_service = search_service
        self._estimate_service = estimate_service

    @property
    def can_orchestrate(self) -> bool:
        """Whether the real path has the search and estimate stages wired in."""
        return self._search_service is not None and self._estimate_service is not None

    async def run(self, request: AnalyzeRequest) -> None:
        """Real path: inference → 유사 사례 검색 → 견적 산출 → 콜백 1회.

        계약(`Docs/Api/AI 연동 계약`)상 중간 통보가 없으므로, 어느 단계에서 끊기든
        백엔드가 작업의 끝을 알 수 있도록 반드시 콜백 하나로 끝난다.
        """
        pipeline_version_id = self._settings.pipeline_version_id
        model_version = UNKNOWN_MODEL_VERSION
        try:
            if not self.can_orchestrate:
                raise RuntimeError("real analysis path requires search and estimate services")

            inference = await self._inference_service.infer(request.images)
            image_results = inference.get("imageResults") or []
            pipeline_version_id = inference["normalization"]["pipelineVersionId"]
            model_version = _model_version(inference)

            blocked = _pre_search_reason(image_results)
            if blocked is not None:
                # 검색·비용 조회는 DB 왕복이다. 사진이 전부 제외됐거나 손상이 하나도
                # 없으면 결과가 정해져 있으므로 가지 않는다.
                estimate = _non_estimable(blocked)
                ref_years: tuple[int | None, int | None] = (None, None)
            else:
                search = await self._search_service.search(SearchRequest(
                    vehicle=request.vehicle,
                    images=request.images,
                    image_results=image_results,
                ))
                # calculate 는 동기 DB 조회다. 이벤트 루프를 붙잡지 않게 스레드로 뺀다.
                estimate = await asyncio.to_thread(
                    self._estimate_service.calculate,
                    EstimateRequest(vehicle=request.vehicle, parts=search["parts"]),
                )
                # 차급 필터가 검색 결과를 만들었더라도, 비용 정책을 통과한 사례가
                # 최소 표본에 못 미치면 견적을 만들 수 없다. 이때만 차급을 ALL로
                # 완화해 재검색·재산정한다.
                if _should_relax_car_class(request, estimate):
                    relaxed_vehicle = request.vehicle.model_copy(update={"car_class": None})
                    search = await self._search_service.search(SearchRequest(
                        vehicle=relaxed_vehicle,
                        images=request.images,
                        image_results=image_results,
                    ))
                    estimate = await asyncio.to_thread(
                        self._estimate_service.calculate,
                        EstimateRequest(vehicle=relaxed_vehicle, parts=search["parts"]),
                    )
                ref_years = _reference_years(search, estimate)

            callback = _result_callback(
                request, model_version, pipeline_version_id, estimate, image_results, ref_years,
            )
        except ImageFetchError:
            log.exception("analysis image fetch failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "IMAGE_FETCH_FAILED", "이미지 다운로드 실패", retryable=True,
                model_version=model_version,
            )
        except ModelRunError:
            log.exception("analysis model failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "MODEL_ERROR", "모델 실행 실패", retryable=True,
                model_version=model_version,
            )
        except QueryEmbeddingError:
            # 검출 ROI 로 질의 벡터를 만들지 못했다. 같은 입력으로 다시 해도 같다.
            log.exception("analysis query embedding failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "INVALID_MODEL_OUTPUT", "검색 ROI 임베딩 실패", retryable=False,
                model_version=model_version,
            )
        except ValueError:
            log.exception("analysis output was invalid. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "INVALID_MODEL_OUTPUT", "모델 출력 형식 오류", retryable=False,
                model_version=model_version,
            )
        except (VectorSearchError, CostRepositoryError):
            # 오류·재시도 명세의 error.code 는 4종뿐이라 검색·비용 조회 실패는
            # INTERNAL 로 보낸다. 일시적 장애일 수 있으므로 retryable 이다.
            log.exception("analysis data access failed. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "INTERNAL", "검색·비용 데이터 조회 실패", retryable=True,
                model_version=model_version,
            )
        except Exception:  # callback must still tell the backend that the job ended
            log.exception("analysis failed unexpectedly. jobId=%s", request.job_id)
            callback = _failure_callback(
                request, pipeline_version_id,
                "INTERNAL", "AI 서버 내부 오류", retryable=True,
                model_version=model_version,
            )

        await self._deliver_callback(request.callback_url, request.request_id, callback)

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


def _result_callback(request: AnalyzeRequest, model_version: str, pipeline_version_id: int,
                     estimate: dict[str, Any], image_results: list[dict[str, Any]],
                     ref_years: tuple[int | None, int | None]) -> dict[str, Any]:
    """Shape EstimateService 출력 + imageResults 를 연동 계약 ⑥절 본문으로 옮긴다."""
    year_from, year_to = ref_years
    return {
        "requestId": request.request_id,
        "jobId": request.job_id,
        "modelVersion": model_version,
        "pipelineVersionId": pipeline_version_id,
        "estimable": estimate["estimable"],
        "nonEstimableReason": estimate["nonEstimableReason"],
        "confidenceGrade": estimate["confidenceGrade"],
        "totals": estimate["totals"],
        "refCaseTotal": estimate["refCaseTotal"],
        "refYearFrom": year_from,
        "refYearTo": year_to,
        "items": estimate["items"],
        "unresolvedParts": estimate.get("unresolvedParts", []),
        "imageResults": image_results,
        "error": None,
    }


def _non_estimable(reason: str) -> dict[str, Any]:
    """EstimateService._non_estimable 와 같은 모양. 검색 전에 끝난 경우에 쓴다."""
    return {
        "estimable": False,
        "nonEstimableReason": reason,
        "confidenceGrade": None,
        "totals": None,
        "refCaseTotal": 0,
        "items": [],
        "unresolvedParts": [],
    }


def _should_relax_car_class(request: AnalyzeRequest, estimate: dict[str, Any]) -> bool:
    """Relax the class filter only when its results lack cost evidence."""
    return (
        request.vehicle.car_class is not None
        and estimate.get("estimable") is False
        and estimate.get("nonEstimableReason") == INSUFFICIENT_CASES
    )


def _model_version(inference: dict[str, Any]) -> str:
    models = inference.get("models") or {}
    part = (models.get("part") or {}).get("version")
    damage = (models.get("damage") or {}).get("version")
    if not part and not damage:
        return UNKNOWN_MODEL_VERSION
    return f"part-{part or 'na'}/damage-{damage or 'na'}"[:MODEL_VERSION_MAX_LENGTH]


def _reference_years(search: dict[str, Any], estimate: dict[str, Any]) -> tuple[int | None, int | None]:
    """산정에 실제로 쓰인 사례의 수리 연도 범위.

    연도는 검색 결과에만 있고 견적 항목에는 없다. 그래서 여기서 잇는다. 범위는
    항목이 참조한 사례로만 좁힌다 — 검색됐지만 비용이 없어 빠진 사례의 연도를
    근거로 보여 주면 화면의 "몇 년도 사례"가 견적과 어긋난다.
    """
    years_by_case: dict[int, int] = {}
    groups = list(search.get("parts") or []) + list(search.get("vectorOnly") or [])
    for group in groups:
        for case in group.get("cases") or []:
            year = case.get("repairYear")
            if year is not None:
                years_by_case[int(case["caseId"])] = int(year)

    years = [
        years_by_case[case_id]
        for item in estimate.get("items") or []
        for case_id in item.get("referencedCaseIds") or []
        if case_id in years_by_case
    ]
    if not years:
        return None, None
    return min(years), max(years)


def _pre_search_reason(image_results: list[dict[str, Any]]) -> str | None:
    """검색을 돌리기 전에 이미 결론이 난 경우의 nonEstimableReason.

    ``excluded`` is set by the part model.  Damage detections on an excluded
    image must not make a non-vehicle image look like a damaged vehicle.
    """
    if not image_results or all(image.get("excluded") is True for image in image_results):
        return PART_NOT_RESOLVED

    vehicle_images = [image for image in image_results if image.get("excluded") is not True]
    if not any(image.get("detections") for image in vehicle_images):
        return NO_DAMAGE_DETECTED

    return None


def _mock_success_callback(request: AnalyzeRequest, inference: dict[str, Any]) -> dict[str, Any]:
    image_results = inference.get("imageResults") or []

    return {
        "requestId": request.request_id,
        "jobId": request.job_id,
        "modelVersion": MOCK_MODEL_VERSION,
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
    """Mock 은 검색을 돌리지 않으므로, 검색 전 판정이 나지 않으면 항상
    INSUFFICIENT_CASES 다 — 손상은 찾았지만 사례를 못 찾은 것으로 보인다."""
    return _pre_search_reason(image_results) or INSUFFICIENT_CASES


def _failure_callback(request: AnalyzeRequest, pipeline_version_id: int,
                      code: str, message: str,
                      *, retryable: bool,
                      model_version: str = MOCK_MODEL_VERSION) -> dict[str, Any]:
    return {
        "requestId": request.request_id,
        "jobId": request.job_id,
        "modelVersion": model_version,
        "pipelineVersionId": pipeline_version_id,
        "estimable": False,
        "nonEstimableReason": None,
        "error": {"code": code, "message": message, "retryable": retryable},
    }
