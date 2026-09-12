import unittest

from standardization import (
    QUALITY_LOW_CONFIDENCE,
    QUALITY_PARTIAL_PART,
    normalize_inference,
)
from standardization.search_metadata import build_search_metadata


def normalized_sample(bbox=None):
    bbox = bbox or [100, 80, 200, 120]
    return normalize_inference({
        "image": {"id": "img-001", "width": 800, "height": 600},
        "detections": [{
            "detection_id": "det-7",
            "part": "Front bumper",
            "damage": "Scratched",
            "bbox": bbox,
            "polygons": [[[100, 80], [300, 80], [300, 200], [100, 200]]],
            "part_confidence": 0.91,
            "damage_confidence": 0.87,
        }],
    })


class SearchMetadataTest(unittest.TestCase):
    def test_creates_one_roi_record_without_severity_axis(self):
        records = build_search_metadata(
            normalized_sample(),
            case_id="as-0000002",
            source="AIHUB_AS",
            car_class="SEDAN",
            pipeline_version_id="pv-test",
            model_name="yolo",
            model_version="2026-09-09",
        )

        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertEqual(record["roi"]["detection_id"], "det-7")
        self.assertEqual(record["features"]["part_code"], "FRONT_BUMPER")
        self.assertEqual(record["features"]["damage_type"], "SCRATCHED")
        self.assertEqual(record["features"]["confidence"]["damage"], 0.87)
        self.assertNotIn("severity", record["features"])
        self.assertTrue(record["search"]["is_searchable"])
        self.assertEqual(record["search"]["searchability"], "STRICT")
        self.assertTrue(record["search"]["strict_searchable"])
        self.assertFalse(record["search"]["vector_only"])
        self.assertIsNone(record["search"]["exclusion_reason"])
        self.assertEqual(record["provenance"]["model_name"], "yolo")

    def test_keeps_low_quality_detection_but_excludes_it_from_search(self):
        """설계 6절 어휘: GOOD / LOW_CONFIDENCE / INVALID"""
        records = build_search_metadata(
            normalized_sample([10, 10, 20, 20]),
            case_id="sc-0000001",
            source="AIHUB_SC",
            car_class=None,
            pipeline_version_id="pv-test",
        )

        record = records[0]
        self.assertEqual(record["roi"]["quality_status"], QUALITY_LOW_CONFIDENCE)
        self.assertFalse(record["roi"]["is_searchable"])
        self.assertFalse(record["search"]["is_searchable"])
        self.assertEqual(record["search"]["exclusion_reason"], QUALITY_LOW_CONFIDENCE)

    def test_falls_back_to_image_id_for_source_reference(self):
        record = build_search_metadata(
            normalized_sample(),
            case_id=2,
            source=None,
            car_class=None,
            pipeline_version_id="pv-test",
            source_image_ref=None,
        )[0]
        self.assertEqual(record["image"]["source_image_ref"], "img-001")

    def test_partial_part_when_part_box_touches_image_edge(self):
        """부품이 화면 밖으로 잘려도 후보에는 남는다. 부품·손상 유형은 정확하다."""
        record = build_search_metadata(
            normalized_sample(),
            case_id="as-0000002",
            source="AIHUB_AS",
            car_class="SEDAN",
            pipeline_version_id="pv-test",
            # 부품 bbox의 왼쪽이 x=0에 닿는다
            part_boxes_by_detection_id={"det-7": (0, 50, 400, 300)},
        )[0]

        self.assertTrue(record["roi"]["part_clipped"])
        self.assertEqual(record["roi"]["quality_status"], QUALITY_PARTIAL_PART)
        self.assertIn("PART_TOUCHES_IMAGE_EDGE", record["roi"]["quality_reasons"])
        self.assertTrue(record["roi"]["is_searchable"])
        self.assertIsNone(record["search"]["exclusion_reason"])

    def test_part_box_inside_image_stays_good(self):
        record = build_search_metadata(
            normalized_sample(),
            case_id="as-0000002",
            source="AIHUB_AS",
            car_class="SEDAN",
            pipeline_version_id="pv-test",
            part_boxes_by_detection_id={"det-7": (50, 50, 400, 300)},
        )[0]

        self.assertFalse(record["roi"]["part_clipped"])
        self.assertEqual(record["roi"]["quality_status"], "GOOD")
        self.assertNotIn("PART_TOUCHES_IMAGE_EDGE", record["roi"]["quality_reasons"])

    def test_missing_part_box_is_recorded_not_guessed(self):
        """부품 영역을 못 받으면 추측하지 않는다. null로 두고 사유를 남긴다."""
        record = build_search_metadata(
            normalized_sample(),
            case_id="as-0000002",
            source="AIHUB_AS",
            car_class="SEDAN",
            pipeline_version_id="pv-test",
        )[0]

        self.assertIsNone(record["roi"]["part_clipped"])
        self.assertIn("PART_BOX_UNKNOWN", record["roi"]["quality_reasons"])
        self.assertEqual(record["roi"]["quality_status"], "GOOD")

    def test_damage_only_roi_is_searchable_without_part_code(self):
        """DAMAGE annotation은 part 없이도 damage_type 기반 검색 후보가 된다."""
        normalized = normalized_sample()
        normalized["detections"][0]["part"] = {}
        record = build_search_metadata(
            normalized, case_id="sc-104422", source="AIHUB_SC", car_class="Compact",
            pipeline_version_id="pv-test",
        )[0]
        self.assertIsNone(record["features"]["part_code"])
        self.assertEqual(record["features"]["damage_type"], "SCRATCHED")
        self.assertTrue(record["search"]["is_searchable"])
        self.assertEqual(record["search"]["searchability"], "VECTOR_ONLY")
        self.assertFalse(record["search"]["strict_searchable"])
        self.assertTrue(record["search"]["vector_only"])
        self.assertEqual(record["search"]["vector_only_reason"], "missing_or_ambiguous_part_code")
        self.assertIsNone(record["search"]["exclusion_reason"])

    def test_ambiguous_part_match_is_vector_only(self):
        normalized = normalized_sample()
        normalized["detections"][0]["part"] = {
            "code": None,
            "match_status": "AMBIGUOUS",
        }
        record = build_search_metadata(
            normalized,
            case_id="sc-104422",
            source="AIHUB_SC",
            car_class="Compact",
            pipeline_version_id="pv-test",
        )[0]
        self.assertEqual(record["search"]["searchability"], "VECTOR_ONLY")
        self.assertTrue(record["search"]["vector_only"])

    def test_low_confidence_wins_over_part_clip(self):
        """손상 자체를 못 믿는데 부품 잘림을 따지지 않는다."""
        record = build_search_metadata(
            normalized_sample([10, 10, 20, 20]),
            case_id="sc-0000001",
            source="AIHUB_SC",
            car_class=None,
            pipeline_version_id="pv-test",
            part_boxes_by_detection_id={"det-7": (0, 0, 400, 300)},
        )[0]

        self.assertEqual(record["roi"]["quality_status"], QUALITY_LOW_CONFIDENCE)
        self.assertFalse(record["roi"]["is_searchable"])

    def test_pipeline_version_is_required_and_separates_roi_ids(self):
        with self.assertRaises(TypeError):
            build_search_metadata(
                normalized_sample(), case_id=1, source=None, car_class=None,
            )
        with self.assertRaises(ValueError):
            build_search_metadata(
                normalized_sample(), case_id=1, source=None, car_class=None,
                pipeline_version_id="   ",
            )

        def roi_id(version):
            return build_search_metadata(
                normalized_sample(), case_id=1, source=None, car_class=None,
                pipeline_version_id=version,
            )[0]["roi"]["roi_id"]

        self.assertNotEqual(roi_id("pv-1"), roi_id("pv-2"))

        record = build_search_metadata(
            normalized_sample(), case_id=1, source=None, car_class=None,
            pipeline_version_id="pv-1",
        )[0]
        self.assertEqual(record["provenance"]["pipeline_version_id"], "pv-1")


if __name__ == "__main__":
    unittest.main()
