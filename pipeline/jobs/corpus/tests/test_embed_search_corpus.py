import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from pipeline.jobs.ingestion.embed_search_corpus import (
    roi_from_feature_box,
    upsert_embedding,
    validate_pipeline_version,
    vector_literal,
)


class EmbeddingBatchTest(unittest.TestCase):
    class Cursor:
        def __init__(self):
            self.calls = []
            self.row = None

        def execute(self, sql, values):
            self.calls.append((sql, values))

        def fetchone(self):
            return self.row

    def test_roi_is_recreated_from_persisted_box(self):
        image = Image.new("RGB", (640, 480), (10, 20, 30))
        roi = roi_from_feature_box(image, {
            "format": "XYWH", "x": 100, "y": 120, "width": 200, "height": 100,
        })
        self.assertEqual(roi.size, (224, 224))

    def test_vector_literal_requires_dinov2_dimension(self):
        self.assertEqual(len(vector_literal([0.0] * 768).split(",")), 768)
        with self.assertRaises(ValueError):
            vector_literal([0.0] * 767)

    def test_embedding_upsert_is_idempotent_by_feature_and_model(self):
        cursor = self.Cursor()
        upsert_embedding(
            cursor,
            {"case_image_id": 11, "damage_feature_id": 22, "confidence": 0.91},
            3,
            [0.0] * 768,
        )
        sql, values = cursor.calls[0]
        self.assertIn("ON CONFLICT (damage_feature_id, model_version_id)", sql)
        self.assertEqual(values[:3], (11, 3, 22))
        self.assertTrue(values[-1].startswith("[0"))

    def test_damage_pipeline_contract_is_required(self):
        cursor = self.Cursor()
        cursor.row = {
            "pipeline_version_id": 2,
            "pipeline_name": "a307-damage-search",
            "version": "v2",
            "params": {
                "image_source": "DAMAGE",
                "part_code_policy": "NULL",
                "pair_status": "UNPAIRED",
                "repair_hint_source": "REPAIR",
            },
            "is_active": False,
        }
        self.assertEqual(validate_pipeline_version(cursor, 2)["version"], "v2")

        cursor.row["params"]["part_code_policy"] = "STRICT"
        with self.assertRaises(RuntimeError):
            validate_pipeline_version(cursor, 2)


if __name__ == "__main__":
    unittest.main()
