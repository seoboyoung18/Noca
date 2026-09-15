import unittest

from shared.vision.damage_part_pairing import damage_part_roi_rows


class DamagePartPairingTest(unittest.TestCase):
    def test_pairs_only_one_same_image_part(self):
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [20, 20, 40, 40]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 100]},
            ]
        })
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["match_status"], "PAIRED")
        self.assertEqual(rows[0]["part_code"], "FRONT_BUMPER")

    def test_does_not_pair_without_geometry_overlap(self):
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [20, 20, 40, 40]},
                {"id": 2, "part": "Front bumper", "bbox": [200, 200, 100, 100]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "UNPAIRED")
        self.assertIsNone(rows[0]["part_code"])

    def test_exact_threshold_is_paired(self):
        """damage coverage가 정확히 0.5이면 >= 정책으로 PAIRED다."""
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [0, 0, 100, 100]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 50]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "PAIRED")
        self.assertAlmostEqual(rows[0]["match_score"], 0.5)

    def test_below_threshold_is_unpaired(self):
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [0, 0, 100, 100]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 49]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "UNPAIRED")

    def test_strong_and_weak_candidates_are_still_ambiguous(self):
        """0.90과 0.51이 함께 threshold를 넘으면 최댓값 우선 확정하지 않는다."""
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [0, 0, 100, 100]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 90]},
                {"id": 3, "part": "Bonnet", "bbox": [0, 0, 100, 51]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "AMBIGUOUS")
        self.assertIsNone(rows[0]["part_code"])

    def test_multiple_annotations_with_same_part_code_are_paired(self):
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [0, 0, 100, 100]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 90]},
                {"id": 3, "part": "Front bumper", "bbox": [0, 0, 100, 51]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "PAIRED")
        self.assertEqual(rows[0]["part_code"], "FRONT_BUMPER")

    def test_irregular_segmentation_uses_bbox_approximation(self):
        """실제 polygon coverage보다 bbox coverage가 커질 수 있는 known limitation."""
        rows = damage_part_roi_rows({
            "annotations": [
                {
                    "id": 1,
                    "damage": "Scratched",
                    "segmentation": [[[0, 0], [100, 0], [0, 100]]],
                },
                {"id": 2, "part": "Front bumper", "bbox": [50, 0, 50, 100]},
            ]
        })
        # polygon 자체의 coverage가 아니라 최소 bounding rectangle 기준으로 0.5다.
        self.assertEqual(rows[0]["match_status"], "PAIRED")
        self.assertAlmostEqual(rows[0]["match_score"], 0.5)

    def test_multiple_overlapping_parts_are_ambiguous(self):
        rows = damage_part_roi_rows({
            "annotations": [
                {"id": 1, "damage": "Scratched", "bbox": [20, 20, 40, 40]},
                {"id": 2, "part": "Front bumper", "bbox": [0, 0, 100, 100]},
                {"id": 3, "part": "Bonnet", "bbox": [0, 0, 100, 100]},
            ]
        })
        self.assertEqual(rows[0]["match_status"], "AMBIGUOUS")
        self.assertIsNone(rows[0]["part_code"])


if __name__ == "__main__":
    unittest.main()
