import csv
import tempfile
import unittest
from pathlib import Path

from pipeline.jobs.ingestion.upload_search_images import (
    build_upload_candidates,
    s3_key,
)


class SearchImageUploadTest(unittest.TestCase):
    def test_s3_key_keeps_db_storage_key_without_prefix(self):
        storage_key = "repair-cases/AIHUB_AS/as-0000001/0406472/original.jpg"
        self.assertEqual(s3_key(storage_key), storage_key)
        self.assertEqual(s3_key(storage_key, "/staging/a307/"), f"staging/a307/{storage_key}")

    def test_candidates_use_dataset_root_for_subset_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subset_root = root / "subset"
            dataset_root = root / "dataset"
            label = subset_root / (
                "1.Training/2.라벨링데이터/TL_damage_part/damage_part/"
                "0406472_as-0000001.json"
            )
            label.parent.mkdir(parents=True)
            label.write_text("{}", encoding="utf-8")
            image = dataset_root / (
                "1.Training/1.원천데이터/TS_damage_part/damage_part/"
                "0406472_as-0000001.jpg"
            )
            image.parent.mkdir(parents=True)
            image.write_bytes(b"jpg")

            readiness = root / "readiness.csv"
            with readiness.open("w", encoding="utf-8", newline="") as fp:
                writer = csv.writer(fp)
                writer.writerow(["case_id", "is_final_searchable_case"])
                writer.writerow(["as-0000001", "True"])
            manifest = root / "search_dev_cases.csv"
            with manifest.open("w", encoding="utf-8", newline="") as fp:
                writer = csv.writer(fp)
                writer.writerow(["case_id", "purpose"])
                writer.writerow(["as-0000001", "DEV"])

            candidates = build_upload_candidates(
                subset_root=subset_root,
                dataset_root=dataset_root,
                readiness_csv=readiness,
                case_manifest=manifest,
            )

            self.assertEqual(len(candidates), 1)
            self.assertEqual(candidates[0].image_type, "DAMAGE_PART")
            self.assertEqual(
                candidates[0].source_image_ref,
                "1.Training/1.원천데이터/TS_damage_part/damage_part/0406472_as-0000001.jpg",
            )
            self.assertEqual(
                candidates[0].storage_key,
                "repair-cases/AIHUB_AS/as-0000001/0406472/original.jpg",
            )


if __name__ == "__main__":
    unittest.main()
