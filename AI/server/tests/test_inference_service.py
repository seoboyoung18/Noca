import unittest

from app.services.inference_service import _to_api_image_result


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


if __name__ == "__main__":
    unittest.main()
