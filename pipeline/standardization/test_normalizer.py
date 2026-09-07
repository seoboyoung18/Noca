import unittest

from standardization import NormalizationError, normalize_inference, normalize_repair_label


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


if __name__ == "__main__":
    unittest.main()
