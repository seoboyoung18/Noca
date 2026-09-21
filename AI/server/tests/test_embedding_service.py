from __future__ import annotations

import unittest
from types import SimpleNamespace

import numpy as np
from PIL import Image

from app.infrastructure.vector_repository import VectorSearchError, vector_literal
from app.services.embedding_service import EmbeddingService
from app.services.search_service import _merge_strict


class _FakeEmbedder:
    spec = SimpleNamespace(version="f9e44c8-pooler-pad20-lb224gray")

    def __init__(self) -> None:
        self.images = []
        self.calls = []

    def embed(self, images):
        self.calls.append(images)
        self.images.extend(images)
        return np.ones((len(images), 768), dtype=np.float32)


def _detection() -> dict:
    return {
        "detectionId": "501:damage:damage-001",
        "partCode": "REAR_BUMPER",
        "damageType": "Scratched",
        "pairStatus": "PAIRED",
        "searchability": "STRICT",
        "confidence": {"part": 0.9, "damage": 0.8},
        "geometry": {
            "bbox": {"x": 50, "y": 30, "width": 100, "height": 40},
            "polygons": [[
                {"x": 50, "y": 30}, {"x": 150, "y": 30},
                {"x": 150, "y": 70}, {"x": 50, "y": 70},
            ]],
        },
    }


class EmbeddingServiceTest(unittest.TestCase):
    def test_uses_shared_roi_rule_before_embedding(self):
        fake = _FakeEmbedder()
        vector = EmbeddingService(fake).embed_detection(Image.new("RGB", (300, 200)), _detection())
        self.assertEqual(vector.shape, (768,))
        self.assertEqual(fake.images[0].size, (224, 224))

    def test_embeds_upload_detections_as_one_batch(self):
        fake = _FakeEmbedder()
        detections = [_detection(), {**_detection(), "detectionId": "501:damage:damage-002"}]
        vectors = EmbeddingService(fake).embed_detections(Image.new("RGB", (300, 200)), detections)
        self.assertEqual(len(vectors), 2)
        self.assertEqual(vectors[0].shape, (768,))
        self.assertEqual(len(fake.calls), 1)
        self.assertEqual(len(fake.calls[0]), 2)

    def test_vector_literal_requires_the_schema_dimension(self):
        value = vector_literal([0.25] * 768)
        self.assertTrue(value.startswith("[0.25,"))
        with self.assertRaises(VectorSearchError):
            vector_literal([0.25] * 767)

    def test_strict_detections_merge_by_part_and_damage(self):
        groups = {}
        first = {
            "detectionId": "501:damage:damage-001", "partCode": "REAR_BUMPER",
            "damageType": "Scratched", "confidence": 0.8, "pairStatus": "PAIRED",
            "searchability": "STRICT", "fallbackStage": "MODEL", "searchHitCount": 1,
            "referencedCaseIds": [10], "cases": [{"caseId": 10, "similarity": 0.8}],
        }
        second = {**first, "detectionId": "502:damage:damage-002", "confidence": 0.9,
                  "fallbackStage": "ALL", "referencedCaseIds": [11],
                  "cases": [{"caseId": 11, "similarity": 0.9}]}
        _merge_strict(groups, first)
        _merge_strict(groups, second)
        actual = groups[("REAR_BUMPER", "Scratched")]
        self.assertEqual(actual["detectionIds"], ["501:damage:damage-001", "502:damage:damage-002"])
        self.assertEqual(actual["fallbackStage"], "ALL")
        self.assertEqual(actual["referencedCaseIds"], [11, 10])

    def test_merged_group_reports_the_widest_stage_that_contributed(self):
        """MODEL 과 PRICE_TIER 가 섞이면 PRICE_TIER 다. 좁은 쪽으로 남으면 가격대
        전체에서 온 근거를 "동일 차종 사례"로 보여 주게 된다."""
        groups = {}
        first = {
            "detectionId": "501:damage:damage-001", "partCode": "REAR_BUMPER",
            "damageType": "Scratched", "confidence": 0.8, "pairStatus": "PAIRED",
            "searchability": "STRICT", "fallbackStage": "MODEL", "searchHitCount": 1,
            "referencedCaseIds": [10], "cases": [{"caseId": 10, "similarity": 0.8}],
        }
        second = {**first, "detectionId": "502:damage:damage-002",
                  "fallbackStage": "PRICE_TIER", "referencedCaseIds": [11],
                  "cases": [{"caseId": 11, "similarity": 0.9}]}

        _merge_strict(groups, first)
        _merge_strict(groups, second)

        self.assertEqual(groups[("REAR_BUMPER", "Scratched")]["fallbackStage"], "PRICE_TIER")

    def test_merged_group_keeps_the_wider_stage_regardless_of_order(self):
        groups = {}
        wide = {
            "detectionId": "501:damage:damage-001", "partCode": "REAR_BUMPER",
            "damageType": "Scratched", "confidence": 0.8, "pairStatus": "PAIRED",
            "searchability": "STRICT", "fallbackStage": "ALL", "searchHitCount": 1,
            "referencedCaseIds": [10], "cases": [{"caseId": 10, "similarity": 0.8}],
        }
        narrow = {**wide, "detectionId": "502:damage:damage-002",
                  "fallbackStage": "MODEL", "referencedCaseIds": [11],
                  "cases": [{"caseId": 11, "similarity": 0.9}]}

        _merge_strict(groups, wide)
        _merge_strict(groups, narrow)

        self.assertEqual(groups[("REAR_BUMPER", "Scratched")]["fallbackStage"], "ALL")

    def test_merged_group_preserves_configured_thirty_estimate_references(self):
        groups = {}
        first = {
            "detectionId": "501:damage:damage-001", "partCode": "REAR_BUMPER",
            "damageType": "Scratched", "confidence": 0.8, "pairStatus": "PAIRED",
            "searchability": "STRICT", "fallbackStage": "ALL", "searchHitCount": 1,
            "referencedCaseIds": [10], "cases": [{"caseId": 10, "similarity": 0.8}],
            "estimateReferencedCaseIds": list(range(1, 21)),
            "estimateReferenceMinimumCaseCount": 5,
            "estimateReferenceMaximumCaseCount": 30,
        }
        second = {**first, "detectionId": "502:damage:damage-002",
                  "estimateReferencedCaseIds": list(range(21, 41))}

        _merge_strict(groups, first)
        _merge_strict(groups, second)

        actual = groups[("REAR_BUMPER", "Scratched")]
        self.assertEqual(actual["estimateReferencedCaseIds"], list(range(1, 31)))
        self.assertEqual(actual["estimateReferenceMinimumCaseCount"], 5)


if __name__ == "__main__":
    unittest.main()
