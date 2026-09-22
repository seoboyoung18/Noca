import unittest

from app.services.inference_service import _apply_selected_part_code, _to_api_image_result


def _normalized():
    return {
        "image": {"id": "501", "width": 800, "height": 600},
        "detections": [],
    }


class InferenceImageResultTest(unittest.TestCase):
    def test_part_detected_image_is_not_excluded(self):
        actual = _to_api_image_result(_normalized(), 501, excluded=False)
        self.assertFalse(actual["excluded"])
        self.assertIsNone(actual["exclusionReason"])

    def test_no_part_detected_image_is_not_vehicle(self):
        actual = _to_api_image_result(_normalized(), 501, excluded=True)
        self.assertTrue(actual["excluded"])
        self.assertEqual(actual["exclusionReason"], "NOT_VEHICLE")

    def test_user_selected_part_resolves_only_unpaired_damage(self):
        normalized = _normalized()
        normalized["detections"] = [{
            "detection_id": "501:damage:1",
            "part": None,
            "damage": {"name_en": "Scratched", "raw_label": "Scratched"},
            "pair_status": "UNPAIRED",
            "searchability": "VECTOR_ONLY",
            "confidence": {"part": None, "damage": 0.91},
            "geometry": {
                "coordinate_system": "PIXEL_XY_TOP_LEFT", "bbox_format": "XYWH",
                "bbox": {"x": 1, "y": 2, "width": 3, "height": 4},
                "segmentation": None,
            },
        }]
        actual = _to_api_image_result(normalized, 501, excluded=True)

        _apply_selected_part_code(actual, "FRONT_BUMPER")

        detection = actual["detections"][0]
        self.assertFalse(actual["excluded"])
        self.assertIsNone(actual["exclusionReason"])
        self.assertEqual(detection["partCode"], "FRONT_BUMPER")
        self.assertEqual(detection["pairStatus"], "PAIRED")
        self.assertEqual(detection["searchability"], "STRICT")
        self.assertEqual(detection["partSelectionSource"], "USER")

    def test_user_selected_part_does_not_enable_an_image_without_damage(self):
        actual = _to_api_image_result(_normalized(), 501, excluded=True)

        _apply_selected_part_code(actual, "FRONT_BUMPER")

        self.assertTrue(actual["excluded"])
        self.assertEqual(actual["exclusionReason"], "NOT_VEHICLE")


if __name__ == "__main__":
    unittest.main()
