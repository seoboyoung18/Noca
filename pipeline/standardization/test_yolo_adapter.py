import unittest

from standardization import NormalizationError, adapt_raw_yolo_outputs


def _raw(model_name, task, image_id, predictions):
    return {
        "model": {"name": model_name, "version": "1.0.0", "task": task},
        "image": {
            "image_id": image_id,
            "original_width": 800,
            "original_height": 600,
        },
        "predictions": predictions,
    }


def _part_prediction(class_name="Front bumper", detection_id="part-001",
                     segmentation=None):
    return {
        "detection_id": detection_id,
        "class_id": 0,
        "class_name": class_name,
        "confidence": 0.96,
        "bbox": {"format": "xyxy", "coordinates": [0, 0, 200, 200]},
        "segmentation": segmentation,
    }


def _damage_prediction(class_name="Scratched", detection_id="damage-001"):
    return {
        "detection_id": detection_id,
        "class_id": 0,
        "class_name": class_name,
        "confidence": 0.93,
        "bbox": {"format": "xyxy", "coordinates": [20, 20, 100, 100]},
        "segmentation": {
            "format": "polygon",
            "polygons": [[[20, 20], [100, 20], [100, 100], [20, 100]]],
        },
    }


class YoloAdapterTest(unittest.TestCase):
    def _adapt(self, part_predictions=None, damage_predictions=None,
               image_id=501, raw_image_id=None, part_class_map=None,
               damage_class_map=None):
        raw_image_id = image_id if raw_image_id is None else raw_image_id
        part_class_map = ({0: "Front bumper"}
                          if part_class_map is None else part_class_map)
        damage_class_map = ({0: "Scratched"}
                            if damage_class_map is None else damage_class_map)
        return adapt_raw_yolo_outputs(
            _raw("vehicle-part-detection", "detect", str(raw_image_id),
                 part_predictions if part_predictions is not None else
                 [_part_prediction()]),
            _raw("vehicle-damage-segmentation", "segment", str(raw_image_id),
                 damage_predictions if damage_predictions is not None else
                 [_damage_prediction()]),
            image_id=image_id,
            part_class_map=part_class_map,
            damage_class_map=damage_class_map,
        )

    def test_adapts_paired_raw_outputs(self):
        actual = self._adapt()
        detection = actual["detections"][0]
        self.assertEqual(detection["detection_id"], "501:damage:damage-001")
        self.assertEqual(detection["part"]["code"], "FRONT_BUMPER")
        self.assertEqual(detection["damage"]["code"], "SCRATCHED")
        self.assertEqual(detection["pair_status"], "PAIRED")
        self.assertEqual(detection["searchability"], "STRICT")

    def test_keeps_unpaired_damage_as_vector_only(self):
        actual = self._adapt(part_predictions=[])
        detection = actual["detections"][0]
        self.assertIsNone(detection["part"])
        self.assertEqual(detection["pair_status"], "UNPAIRED")
        self.assertEqual(detection["searchability"], "VECTOR_ONLY")

    def test_rejects_snake_case_when_class_map_says_annotation_label(self):
        with self.assertRaises(NormalizationError):
            self._adapt(
                part_predictions=[_part_prediction(class_name="front_bumper")]
            )

    def test_rejects_segmentation_on_part_detection(self):
        with self.assertRaises(NormalizationError):
            self._adapt(
                part_predictions=[_part_prediction(segmentation={
                    "format": "polygon",
                    "polygons": [[[0, 0], [100, 0], [100, 100]]],
                })]
            )

    def test_rejects_mismatched_image_id(self):
        with self.assertRaises(NormalizationError):
            self._adapt(image_id=502, raw_image_id=501)

    def test_requires_class_map(self):
        with self.assertRaises(NormalizationError):
            self._adapt(part_class_map={})


if __name__ == "__main__":
    unittest.main()
