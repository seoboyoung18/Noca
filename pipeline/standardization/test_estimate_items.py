import unittest

from standardization.estimate_items import normalize_estimate_item


class EstimateItemNormalizationTest(unittest.TestCase):
    def test_estimate_part_is_case_candidate_not_roi_feature(self):
        item = normalize_estimate_item(
            {"작업항목 및 부품명": "Front bumper", "작업": "도장", "부품가격": "1000", "공임": "2000"},
            "AIHUB_AS", {"Front bumper": "FRONT_BUMPER"}, 1,
        )
        self.assertEqual(item["source_item_key"], "item-00001")
        self.assertEqual(item["part_code"], "FRONT_BUMPER")
        self.assertNotIn("damage_type", item)
        self.assertNotIn("roi_embedding_id", item)

    def test_unmapped_non_ancillary_item_is_quarantined(self):
        with self.assertRaisesRegex(ValueError, "unmapped_part"):
            normalize_estimate_item(
                {"작업항목 및 부품명": "unknown", "작업": "도장"},
                "AIHUB_AS", {}, 1,
            )


if __name__ == "__main__":
    unittest.main()
