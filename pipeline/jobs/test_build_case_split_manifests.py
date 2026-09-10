import unittest

from build_case_split_manifests import choose_balanced, dataset_split


class CaseSplitManifestTest(unittest.TestCase):
    def test_dataset_split_marks_cross_split_case_as_mixed(self):
        self.assertEqual(dataset_split("train/damage_part"), "TRAIN_ONLY")
        self.assertEqual(dataset_split("validation/damage_part"), "VALIDATION_ONLY")
        self.assertEqual(
            dataset_split("train/damage_part|validation/damage_part"),
            "MIXED",
        )

    def test_choose_balanced_is_deterministic(self):
        rows = [
            {
                "case_id": f"{source.lower()[:2]}-{index:07d}",
                "source": source,
                "car_class": car_class,
                "part_codes": "FRONT_BUMPER" if index % 2 else "REAR_BUMPER",
                "damage_types": "SCRATCHED" if index % 3 else "BREAKAGE",
            }
            for source in ("AIHUB_AS", "AIHUB_SC")
            for car_class in ("CityCar", "Compact", "Mid-size", "Full-size")
            for index in range(4)
        ]

        first = choose_balanced(rows, 8, "test-seed")
        second = choose_balanced(rows, 8, "test-seed")

        self.assertEqual([row["case_id"] for row in first], [row["case_id"] for row in second])
        self.assertEqual(len({row["case_id"] for row in first}), 8)


if __name__ == "__main__":
    unittest.main()
