from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import build_router
from app.schemas.contracts import AnalyzeRequest
from app.infrastructure.cost_repository import CostRepositoryError
from app.infrastructure.image_fetcher import ImageFetchError
from app.infrastructure.vector_repository import VectorSearchError
from app.services.analysis_service import (
    AnalysisService,
    _failure_callback,
    _mock_success_callback,
    _reference_years,
)


def _request() -> AnalyzeRequest:
    return AnalyzeRequest.model_validate({
        "jobId": 12,
        "requestId": "req-12",
        "vehicle": {"modelId": 41, "carClass": "Compact"},
        "images": [{"imageId": 501, "url": "https://example.test/image.jpg"}],
        "callbackUrl": "http://backend.test/internal/analysis-jobs/12/result",
    })


class AnalysisServiceTest(unittest.IsolatedAsyncioTestCase):
    def test_analyze_mock_accepts_request_and_schedules_work(self):
        settings = SimpleNamespace(
            internal_token="shared-token",
            pipeline_version_id=3,
            analysis_profile="mock",
            part_weights=SimpleNamespace(is_file=lambda: True),
            damage_weights=SimpleNamespace(is_file=lambda: True),
            database_url="postgresql://test",
            has_required_runtime_config=True,
        )
        analysis_service = SimpleNamespace(run_mock=AsyncMock())
        app = FastAPI()
        app.include_router(build_router(
            settings,
            SimpleNamespace(),
            SimpleNamespace(),
            SimpleNamespace(model_loaded=False),
            analysis_service,
        ))

        with TestClient(app) as client:
            response = client.post(
                "/analyze",
                headers={"X-Internal-Token": "shared-token"},
                json={
                    "jobId": 12,
                    "requestId": "req-12",
                    "vehicle": {"modelId": 41, "carClass": "Compact"},
                    "images": [{"imageId": 501, "url": "https://example.test/image.jpg"}],
                    "callbackUrl": "http://backend.test/internal/analysis-jobs/12/result",
                },
            )

        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.json(), {
            "accepted": True, "jobId": 12, "requestId": "req-12",
        })
        analysis_service.run_mock.assert_awaited_once()

    def _route_app(self, profile, analysis_service):
        settings = SimpleNamespace(
            internal_token="shared-token",
            pipeline_version_id=3,
            analysis_profile=profile,
            part_weights=SimpleNamespace(is_file=lambda: True),
            damage_weights=SimpleNamespace(is_file=lambda: True),
            database_url="postgresql://test",
            has_required_runtime_config=True,
        )
        app = FastAPI()
        app.include_router(build_router(
            settings, SimpleNamespace(), SimpleNamespace(),
            SimpleNamespace(model_loaded=False), analysis_service,
        ))
        return app

    def _post(self, app):
        with TestClient(app) as client:
            return client.post(
                "/analyze",
                headers={"X-Internal-Token": "shared-token"},
                json={
                    "jobId": 12,
                    "requestId": "req-12",
                    "vehicle": {"modelId": 41, "carClass": "Compact"},
                    "images": [{"imageId": 501, "url": "https://example.test/image.jpg"}],
                    "callbackUrl": "http://backend.test/internal/analysis-jobs/12/result",
                },
            )

    def test_analyze_runs_the_real_path_on_the_production_profile(self):
        analysis_service = SimpleNamespace(
            can_orchestrate=True, run=AsyncMock(), run_mock=AsyncMock(),
        )

        response = self._post(self._route_app("production", analysis_service))

        self.assertEqual(response.status_code, 202)
        analysis_service.run.assert_awaited_once()
        analysis_service.run_mock.assert_not_awaited()

    def test_analyze_still_reports_501_when_the_stages_are_not_wired(self):
        analysis_service = SimpleNamespace(
            can_orchestrate=False, run=AsyncMock(), run_mock=AsyncMock(),
        )

        response = self._post(self._route_app("production", analysis_service))

        self.assertEqual(response.status_code, 501)
        self.assertEqual(
            response.json()["detail"], {"code": "ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED"},
        )
        analysis_service.run.assert_not_awaited()

    def test_mock_success_callback_keeps_inference_image_results(self):
        request = _request()
        image_results = [{
            "imageId": 501,
            "width": 1600,
            "height": 1200,
            "excluded": False,
            "exclusionReason": None,
            "detections": [],
        }]

        actual = _mock_success_callback(request, {
            "normalization": {"pipelineVersionId": 3},
            "imageResults": image_results,
        })

        self.assertEqual(actual["jobId"], 12)
        self.assertEqual(actual["requestId"], "req-12")
        self.assertFalse(actual["estimable"])
        self.assertEqual(actual["nonEstimableReason"], "NO_DAMAGE_DETECTED")
        self.assertEqual(actual["imageResults"], image_results)
        self.assertEqual(actual["items"], [])

    def test_mock_success_callback_reports_insufficient_cases_after_damage_detection(self):
        request = _request()
        detection = {"detectionId": "501-1", "partCode": "P001", "damageType": "SCRATCH"}

        actual = _mock_success_callback(request, {
            "normalization": {"pipelineVersionId": 3},
            "imageResults": [{
                "imageId": 501,
                "excluded": False,
                "detections": [detection],
            }],
        })

        self.assertEqual(actual["nonEstimableReason"], "INSUFFICIENT_CASES")
        self.assertEqual(actual["imageResults"][0]["detections"], [detection])

    def test_mock_success_callback_reports_part_not_resolved_when_all_images_excluded(self):
        request = _request()
        image_results = [{
            "imageId": 501,
            "excluded": True,
            "exclusionReason": "NOT_VEHICLE",
            "detections": [],
        }]

        actual = _mock_success_callback(request, {
            "normalization": {"pipelineVersionId": 3},
            "imageResults": image_results,
        })

        self.assertEqual(actual["nonEstimableReason"], "PART_NOT_RESOLVED")
        self.assertEqual(actual["imageResults"], image_results)

    def test_failure_callback_matches_backend_error_contract(self):
        actual = _failure_callback(
            _request(), 3, "MODEL_ERROR", "모델 실행 실패", retryable=True,
        )

        self.assertEqual(actual["pipelineVersionId"], 3)
        self.assertEqual(actual["error"], {
            "code": "MODEL_ERROR",
            "message": "모델 실행 실패",
            "retryable": True,
        })

    async def test_run_mock_infers_then_delivers_callback(self):
        inference = AsyncMock(return_value={
            "normalization": {"pipelineVersionId": 3},
            "imageResults": [],
        })
        settings = SimpleNamespace(internal_token="shared-token", pipeline_version_id=3)
        service = AnalysisService(settings, inference)
        service._deliver_callback = AsyncMock()

        await service.run_mock(_request())

        inference.infer.assert_awaited_once()
        service._deliver_callback.assert_awaited_once()
        callback_url, request_id, payload = service._deliver_callback.await_args.args
        self.assertEqual(callback_url, "http://backend.test/internal/analysis-jobs/12/result")
        self.assertEqual(request_id, "req-12")
        self.assertFalse(payload["estimable"])


class RealAnalysisPathTest(unittest.IsolatedAsyncioTestCase):
    """`run` = inference -> search -> estimate -> callback 1회."""

    def _service(self, *, inference, search=None, estimate=None):
        settings = SimpleNamespace(internal_token="shared-token", pipeline_version_id=3)
        service = AnalysisService(settings, inference, search, estimate)
        service._deliver_callback = AsyncMock()
        return service

    def _inference(self, image_results):
        return AsyncMock(infer=AsyncMock(return_value={
            "models": {"part": {"version": "35ep"}, "damage": {"version": "60ep"}},
            "normalization": {"pipelineVersionId": 3},
            "imageResults": image_results,
        }))

    def _damaged_image(self):
        return [{
            "imageId": 501, "width": 1600, "height": 1200,
            "excluded": False, "exclusionReason": None,
            "detections": [{
                "detectionId": "501:damage:damage-001",
                "partCode": "REAR_BUMPER", "damageType": "Scratched",
                "pairStatus": "PAIRED", "searchability": "STRICT",
                "confidence": {"part": 0.96, "damage": 0.93},
            }],
        }]

    async def test_run_chains_search_and_estimate_into_one_callback(self):
        image_results = self._damaged_image()
        search_result = {
            "parts": [{
                "partCode": "REAR_BUMPER", "damageType": "Scratched",
                "searchability": "STRICT", "fallbackStage": "PRICE_TIER",
                "confidence": 0.93, "detectionIds": ["501:damage:damage-001"],
                "referencedCaseIds": [121381, 121414],
                "cases": [
                    {"caseId": 121381, "similarity": 0.91, "repairYear": 2021, "itemTotal": 330000},
                    {"caseId": 121414, "similarity": 0.88, "repairYear": 2023, "itemTotal": 350000},
                ],
            }],
            "vectorOnly": [],
        }
        estimate_result = {
            "estimable": True, "nonEstimableReason": None, "confidenceGrade": "MEDIUM",
            "totals": {"min": 300000, "median": 335500, "max": 380000},
            "refCaseTotal": 2,
            "items": [{
                "partCode": "REAR_BUMPER", "damageType": "Scratched", "confidence": 0.93,
                "itemTotal": 335500, "refCaseCount": 2,
                "referencedCaseIds": [121381, 121414],
            }],
            "unresolvedParts": [],
        }
        search = SimpleNamespace(search=AsyncMock(return_value=search_result))
        estimate = SimpleNamespace(calculate=lambda request: estimate_result)
        service = self._service(
            inference=self._inference(image_results), search=search, estimate=estimate,
        )

        await service.run(_request())

        search.search.assert_awaited_once()
        search_request = search.search.await_args.args[0]
        self.assertEqual(search_request.image_results, image_results)
        self.assertEqual(search_request.vehicle.model_id, 41)

        service._deliver_callback.assert_awaited_once()
        callback_url, request_id, payload = service._deliver_callback.await_args.args
        self.assertEqual(callback_url, "http://backend.test/internal/analysis-jobs/12/result")
        self.assertEqual(request_id, "req-12")
        self.assertTrue(payload["estimable"])
        self.assertIsNone(payload["error"])
        self.assertEqual(payload["modelVersion"], "part-35ep/damage-60ep")
        self.assertEqual(payload["pipelineVersionId"], 3)
        self.assertEqual(payload["totals"], estimate_result["totals"])
        self.assertEqual(payload["items"], estimate_result["items"])
        self.assertEqual(payload["imageResults"], image_results)
        # 연도는 검색 결과에만 있다. 산정에 쓰인 사례로 범위를 잇는다.
        self.assertEqual((payload["refYearFrom"], payload["refYearTo"]), (2021, 2023))

    async def test_run_relaxes_car_class_when_class_result_lacks_cost_cases(self):
        image_results = self._damaged_image()
        class_search = {
            "parts": [{"partCode": "REAR_BUMPER", "damageType": "Scratched",
                       "searchability": "STRICT", "fallbackStage": "CAR_CLASS",
                       "referencedCaseIds": [1, 2]}],
            "vectorOnly": [],
        }
        all_search = {
            "parts": [{"partCode": "REAR_BUMPER", "damageType": "Scratched",
                       "searchability": "STRICT", "fallbackStage": "ALL",
                       "referencedCaseIds": [10, 11, 12], "cases": []}],
            "vectorOnly": [],
        }
        insufficient = {
            "estimable": False, "nonEstimableReason": "INSUFFICIENT_CASES",
            "confidenceGrade": None, "totals": None, "refCaseTotal": 0,
            "items": [], "unresolvedParts": [],
        }
        resolved = {
            "estimable": True, "nonEstimableReason": None, "confidenceGrade": "LOW",
            "totals": {"min": 10, "median": 20, "max": 30}, "refCaseTotal": 3,
            "items": [], "unresolvedParts": [],
        }
        search = SimpleNamespace(search=AsyncMock(side_effect=[class_search, all_search]))
        estimates = iter([insufficient, resolved])
        estimate = SimpleNamespace(calculate=lambda request: next(estimates))
        service = self._service(
            inference=self._inference(image_results), search=search, estimate=estimate,
        )

        await service.run(_request())

        self.assertEqual(search.search.await_count, 2)
        self.assertEqual(search.search.await_args_list[0].args[0].vehicle.car_class, "Compact")
        self.assertIsNone(search.search.await_args_list[1].args[0].vehicle.car_class)
        _, _, payload = service._deliver_callback.await_args.args
        self.assertTrue(payload["estimable"])
        self.assertEqual(payload["totals"], resolved["totals"])

    async def test_run_skips_search_when_every_image_is_excluded(self):
        image_results = [{"imageId": 501, "excluded": True,
                          "exclusionReason": "NOT_VEHICLE", "detections": []}]
        search = SimpleNamespace(search=AsyncMock())
        estimate = SimpleNamespace(calculate=AsyncMock())
        service = self._service(
            inference=self._inference(image_results), search=search, estimate=estimate,
        )

        await service.run(_request())

        search.search.assert_not_awaited()
        _, _, payload = service._deliver_callback.await_args.args
        self.assertFalse(payload["estimable"])
        self.assertEqual(payload["nonEstimableReason"], "PART_NOT_RESOLVED")
        self.assertEqual(payload["items"], [])
        self.assertEqual(payload["imageResults"], image_results)

    async def test_run_skips_search_when_no_damage_was_detected(self):
        image_results = [{"imageId": 501, "excluded": False, "detections": []}]
        search = SimpleNamespace(search=AsyncMock())
        service = self._service(
            inference=self._inference(image_results), search=search,
            estimate=SimpleNamespace(calculate=AsyncMock()),
        )

        await service.run(_request())

        search.search.assert_not_awaited()
        _, _, payload = service._deliver_callback.await_args.args
        self.assertEqual(payload["nonEstimableReason"], "NO_DAMAGE_DETECTED")

    async def test_run_reports_search_failure_as_retryable_internal_error(self):
        search = SimpleNamespace(search=AsyncMock(side_effect=VectorSearchError("pgvector down")))
        service = self._service(
            inference=self._inference(self._damaged_image()), search=search,
            estimate=SimpleNamespace(calculate=AsyncMock()),
        )

        await service.run(_request())

        _, _, payload = service._deliver_callback.await_args.args
        self.assertEqual(payload["error"], {
            "code": "INTERNAL", "message": "검색·비용 데이터 조회 실패", "retryable": True,
        })
        self.assertEqual(payload["pipelineVersionId"], 3)
        self.assertEqual(payload["modelVersion"], "part-35ep/damage-60ep")

    async def test_run_reports_cost_failure_as_retryable_internal_error(self):
        search = SimpleNamespace(search=AsyncMock(return_value={"parts": [], "vectorOnly": []}))

        def calculate(request):
            raise CostRepositoryError("cost tables unavailable")

        service = self._service(
            inference=self._inference(self._damaged_image()), search=search,
            estimate=SimpleNamespace(calculate=calculate),
        )

        await service.run(_request())

        _, _, payload = service._deliver_callback.await_args.args
        self.assertEqual(payload["error"]["code"], "INTERNAL")
        self.assertTrue(payload["error"]["retryable"])

    async def test_run_reports_image_fetch_failure_before_a_model_version_is_known(self):
        inference = AsyncMock(infer=AsyncMock(side_effect=ImageFetchError("404")))
        service = self._service(inference=inference, search=SimpleNamespace(),
                                estimate=SimpleNamespace())

        await service.run(_request())

        _, _, payload = service._deliver_callback.await_args.args
        self.assertEqual(payload["error"]["code"], "IMAGE_FETCH_FAILED")
        self.assertEqual(payload["modelVersion"], "unknown")

    async def test_run_without_search_or_estimate_still_ends_the_job(self):
        service = self._service(inference=self._inference(self._damaged_image()))

        await service.run(_request())

        _, _, payload = service._deliver_callback.await_args.args
        self.assertEqual(payload["error"]["code"], "INTERNAL")


class ReferenceYearsTest(unittest.TestCase):
    def test_years_come_only_from_cases_the_estimate_actually_used(self):
        search = {
            "parts": [{"cases": [
                {"caseId": 1, "repairYear": 2019},
                {"caseId": 2, "repairYear": 2021},
                {"caseId": 3, "repairYear": 2024},
            ]}],
            "vectorOnly": [],
        }
        estimate = {"items": [{"referencedCaseIds": [2, 3]}]}

        self.assertEqual(_reference_years(search, estimate), (2021, 2024))

    def test_years_are_null_when_no_referenced_case_has_one(self):
        search = {"parts": [{"cases": [{"caseId": 1, "repairYear": None}]}], "vectorOnly": []}
        estimate = {"items": [{"referencedCaseIds": [1]}]}

        self.assertEqual(_reference_years(search, estimate), (None, None))


if __name__ == "__main__":
    unittest.main()
