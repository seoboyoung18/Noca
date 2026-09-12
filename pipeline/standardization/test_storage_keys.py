import unittest

from standardization.storage_keys import (
    repair_case_image_key,
    repair_case_image_key_from_ref,
    source_image_id_from_ref,
)


class StorageKeysTest(unittest.TestCase):
    def test_java_contract_example_and_leading_zero(self):
        expected = "repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg"
        self.assertEqual(
            repair_case_image_key("AIHUB_AS", "as-0000160", "0406472"), expected
        )
        self.assertEqual(
            repair_case_image_key_from_ref(
                "AIHUB_AS", "as-0000160",
                "01.데이터_견적서보유/1.Training/1.원천데이터/"
                "TS_damage_part/damage_part/0406472_as-0000160.jpg",
            ),
            expected,
        )

    def test_source_image_id_preserves_zero(self):
        self.assertEqual(
            source_image_id_from_ref("folder/0406472_as-0000160.jpg"), "0406472"
        )

    def test_rejects_path_injection_and_long_key(self):
        with self.assertRaises(ValueError):
            repair_case_image_key("AIHUB_AS", "../as-0000160", "0406472")
        with self.assertRaises(ValueError):
            repair_case_image_key("AIHUB_AS", "a/b", "0406472")
        with self.assertRaises(ValueError):
            repair_case_image_key("AIHUB_AS", "as-0000160", "../0406472")
        with self.assertRaises(ValueError):
            repair_case_image_key("AIHUB_AS", "as-0000160", "a" * 480)
