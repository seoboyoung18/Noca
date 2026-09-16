from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import build_router
from app.schemas.contracts import AnalyzeRequest
from app.services.analysis_service import (
    AnalysisService,
    _failure_callback,
    _mock_success_callback,
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
        self.assertEqual(actual["nonEstimableReason"], "INSUFFICIENT_CASES")
        self.assertEqual(actual["imageResults"], image_results)
        self.assertEqual(actual["items"], [])

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


if __name__ == "__main__":
    unittest.main()
