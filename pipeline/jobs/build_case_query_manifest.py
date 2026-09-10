"""사례 manifest에서 사용자 입력용 이미지 query manifest를 만든다.

query manifest는 검색 DB 적재 대상이 아니다. AI-Hub 원본 이미지를 로컬에서
읽어 실제 사용자 사고 이미지 업로드와 같은 입력으로 재현하기 위한 목록이다.
"""

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path


CASE_RE = re.compile(r"^(?:as|sc)-\d+$", re.IGNORECASE)
LABEL_RE = re.compile(r"_(?P<case>(?:as|sc)-\d+)\.json$", re.IGNORECASE)
LABEL_DIRS = (
    Path("1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--case-manifest", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, default=None,
                        help="source_image_ref 기준 경로. 생략하면 subset-root의 부모")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def case_ids(path: Path) -> set[str]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        return {
            row["case_id"].strip()
            for row in csv.DictReader(fp)
            if CASE_RE.fullmatch(row.get("case_id", ""))
        }


def image_path_for_label(label_path: Path) -> Path:
    parts = list(label_path.parts)
    marker = "2.라벨링데이터"
    index = parts.index(marker)
    parts[index] = "1.원천데이터"
    parts[index + 1] = parts[index + 1].replace("TL_", "TS_").replace("VL_", "VS_")
    return Path(*parts[:-1]) / (label_path.stem + ".jpg")


def main() -> None:
    args = parse_args()
    subset_root = args.subset_root.resolve()
    dataset_root = (args.dataset_root or subset_root.parent).resolve()
    wanted = case_ids(args.case_manifest.resolve())
    wanted_by_lower = {case_id.lower(): case_id for case_id in wanted}
    rows: list[dict[str, str]] = []

    import os

    for relative_dir in LABEL_DIRS:
        label_dir = subset_root / relative_dir
        if not label_dir.is_dir():
            continue
        with os.scandir(label_dir) as entries:
            for entry in entries:
                if not entry.is_file() or not entry.name.lower().endswith(".json"):
                    continue
                match = LABEL_RE.search(entry.name)
                if not match or match.group("case").lower() not in wanted_by_lower:
                    continue
                case_id = wanted_by_lower[match.group("case").lower()]
                label_path = Path(entry.path)
                image_path = image_path_for_label(label_path)
                if not image_path.is_file():
                    raise SystemExit(f"원본 이미지가 없습니다: {image_path}")
                rows.append({
                    "case_id": case_id,
                    "source_image_ref": image_path.relative_to(dataset_root).as_posix(),
                    "label_ref": label_path.relative_to(subset_root).as_posix(),
                    "image_file_name": image_path.name,
                })

    found = {row["case_id"] for row in rows}
    missing = sorted(wanted - found)
    if missing:
        raise SystemExit(f"query manifest 대상 사례의 이미지가 없습니다: {len(missing)}건")

    rows.sort(key=lambda row: (row["case_id"], row["source_image_ref"]))
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(
            fp,
            fieldnames=["case_id", "source_image_ref", "label_ref", "image_file_name"],
        )
        writer.writeheader()
        writer.writerows(rows)
    print(f"query_cases={len(found)} query_images={len(rows)} output={output}")


if __name__ == "__main__":
    main()
