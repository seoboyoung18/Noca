import unittest

from standardization import (
    ESTIMATE_WORKS,
    NormalizationError,
    WORKS,
    normalize_estimate_work,
    normalize_inference,
    normalize_repair_label,
)


class NormalizerTest(unittest.TestCase):
    def test_inference(self):
        actual = normalize_inference({
            "image": {"id": "sample.jpg", "width": 800, "height": 600},
            "detections": [{
                "part": "Front bumper", "damage": "Scratched",
                "bbox": [230, 314, 347, 141],
                "segmentation": [[[[230, 314], [577, 314], [577, 455], [230, 455]]]],
                "part_confidence": .91, "damage_confidence": .87,
            }],
        })
        detection = actual["detections"][0]
        self.assertEqual(detection["part"]["code"], "FRONT_BUMPER")
        self.assertEqual(detection["damage"]["code"], "SCRATCHED")
        self.assertEqual(detection["work_candidates"][0]["code"], "COATING")
        self.assertEqual(len(detection["geometry"]["segmentation"]["polygons"]), 1)
        self.assertGreater(detection["geometry"]["segmentation"]["area_px"], 0)

    def test_normalized_multi_polygon_segmentation(self):
        actual = normalize_inference({
            "coordinate_space": "NORMALIZED_XY",
            "image": {"width": 100, "height": 200},
            "detections": [{
                "part": "Rear bumper", "damage": "Breakage",
                "bbox": [.1, .1, .8, .8],
                "polygons": [
                    [[.1, .1], [.2, .1], [.2, .2], [.1, .2]],
                    [[.7, .7], [.8, .7], [.8, .8], [.7, .8]],
                ],
            }],
        })
        geometry = actual["detections"][0]["geometry"]
        self.assertEqual(len(geometry["segmentation"]["polygons"]), 2)
        self.assertEqual(geometry["bbox"]["width"], 80)
        self.assertAlmostEqual(geometry["segmentation"]["area_px"], 400)

    def test_repair_label_deduplicates(self):
        actual = normalize_repair_label("Front bumper:exchange,coating,exchange")
        self.assertEqual(actual["work_codes"], ["EXCHANGE", "COATING"])

    def test_unknown_label_fails_closed(self):
        with self.assertRaises(NormalizationError):
            normalize_repair_label("Unknown:repair")


class EstimateWorkTest(unittest.TestCase):
    """견적서 작업 어휘. 사진 후보 4종과 별개 어휘임을 함께 고정한다."""

    def test_six_common_work_types(self):
        for raw, code in (("도장", "COATING"), ("수리", "REPAIR"), ("판금", "SHEET_METAL"),
                          ("교환", "EXCHANGE"), ("탈착", "REMOVE_INSTALL"), ("오버홀", "OVERHAUL")):
            actual = normalize_estimate_work(raw)
            self.assertEqual(actual["code"], code)
            self.assertEqual(actual["category"], "WORK")
            self.assertEqual(actual["raw"], raw)

    def test_partial_overhaul_and_aliases(self):
        self.assertEqual(normalize_estimate_work("1/2OH")["code"], "OVERHAUL_HALF")
        self.assertEqual(normalize_estimate_work("1/3OH")["code"], "OVERHAUL_THIRD")
        self.assertEqual(normalize_estimate_work("1/4오버홀")["code"], "OVERHAUL_QUARTER")

    def test_ancillary_is_not_repair_work(self):
        for raw in ("견인", "견인비", "구난", "구난비"):
            self.assertEqual(normalize_estimate_work(raw)["category"], "ANCILLARY")

    def test_not_approved_is_a_status(self):
        actual = normalize_estimate_work("불인정")
        self.assertEqual(actual["code"], "NOT_APPROVED")
        self.assertEqual(actual["category"], "STATUS")

    def test_adjustment_is_repair_work(self):
        self.assertEqual(normalize_estimate_work("조정")["category"], "WORK")

    def test_unknown_and_empty_fail_closed(self):
        for value in ("작업", "", "   ", None):
            with self.assertRaises(NormalizationError):
                normalize_estimate_work(value)

    def test_estimate_vocabulary_is_separate_from_photo_candidates(self):
        self.assertEqual(set(WORKS), {"COATING", "REPAIR", "SHEET_METAL", "EXCHANGE"})
        self.assertTrue(set(WORKS).issubset(set(ESTIMATE_WORKS)))
        self.assertGreater(len(ESTIMATE_WORKS), len(WORKS))


if __name__ == "__main__":
    unittest.main()
