import unittest

from pipeline.jobs.ingestion.backfill_yolo_part_candidates import candidates, damage_bbox
from shared.vision.roi import overlap


class YoloPartCandidateContractTest(unittest.TestCase):
    def test_part_prediction_uses_canonical_part_code_and_xywh_geometry(self):
        raw = {"predictions": [{
            "class_id": 0, "class_name": "Front bumper", "confidence": 0.9,
            "bbox": {"format": "xyxy", "coordinates": [10, 20, 30, 50]},
        }]}
        result = candidates(raw, {0: "Front bumper"})
        self.assertEqual("FRONT_BUMPER", result[0]["part_code"])
        self.assertEqual((10.0, 20.0, 20.0, 30.0), result[0]["xywh"])

    def test_damage_polygon_and_part_box_share_damage_coverage_rule(self):
        damage = damage_bbox([10, 20, 20, 30])
        self.assertEqual(1.0, overlap(damage, (10, 20, 20, 30)))
        self.assertEqual(0.5, overlap(damage, (10, 20, 10, 30)))


if __name__ == "__main__":
    unittest.main()
