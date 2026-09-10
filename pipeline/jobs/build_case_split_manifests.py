"""검색 사례를 사고 단위로 DEV/DEMO/EVAL manifest로 나눈다.

이미지 단위로 split하면 한 사고의 다른 사진이 DEV와 EVAL에 동시에 들어갈 수
있으므로, 이 스크립트는 항상 ``case_id``를 그룹 키로 사용한다. AI-Hub 원본
이미지·라벨은 저장소 밖에 두고, 저장소에는 재현 가능한 CSV manifest만 둔다.

기본 정책:
  - TRAIN-only 사례: DEV 후보
  - VALIDATION-only 사례: DEMO/EVAL 후보
  - TRAIN·VALIDATION 양쪽 사례: MIXED, 정량 평가에서 제외
  - 검색 전체 적재용 manifest에는 세 그룹 모두 포함
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

PIPELINE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PIPELINE_ROOT))

from standardization import DAMAGES, PARTS, normalize_repair_label  # noqa: E402


CASE_RE = re.compile(r"^(?:as|sc)-\d+$", re.IGNORECASE)
LABEL_RE = re.compile(r"_(?P<case>(?:as|sc)-\d+)\.json$", re.IGNORECASE)
LABEL_DIRS = (
    Path("1.Training/2.라벨링데이터/TL_damage/damage"),
    Path("1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    Path("2.Validation/2.라벨링데이터/VL_damage/damage"),
    Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}
ALLOWED_CLASSES = {
    "CityCar": "CityCar",
    "Compact": "Compact",
    "Compact car": "Compact",
    "Mid-size": "Mid-size",
    "Mid-size car": "Mid-size",
    "Full-size": "Full-size",
    "Full-size car": "Full-size",
}
PART_BY_RAW = {values[0].casefold(): code for code, values in PARTS.items()}
DAMAGE_BY_RAW = {values[0].casefold(): code for code, values in DAMAGES.items()}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--readiness-csv", type=Path, required=True)
    parser.add_argument("--linkage-csv", type=Path, required=True)
    parser.add_argument("--estimate-root", type=Path, default=None,
                        help="견적 JSON 경로. 생략하면 subset-root의 표준 경로를 사용한다")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--dev-cases", type=int, default=1000,
                        help="DEV로 고를 TRAIN-only 사례 수")
    parser.add_argument("--demo-cases", type=int, default=20,
                        help="DEMO로 고를 VALIDATION-only 사례 수")
    parser.add_argument("--seed", type=str, default="a307-search-cases-v1")
    return parser.parse_args()


def truth(value: Any) -> bool:
    return str(value).strip().lower() in {"true", "1", "yes"}


def source_for_case(case_id: str) -> str:
    return SOURCE_BY_PREFIX[case_id[:2].lower()]


def normalize_class(value: Any) -> str | None:
    return ALLOWED_CLASSES.get(str(value or "").strip())


def selected_cases(path: Path) -> set[str]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        return {
            row["case_id"].strip()
            for row in csv.DictReader(fp)
            if CASE_RE.fullmatch(row.get("case_id", ""))
            and truth(row.get("is_final_searchable_case"))
        }


def linkage_groups(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    with path.open(encoding="utf-8-sig", newline="") as fp:
        for row in csv.DictReader(fp):
            case_id = row.get("case_id", "").strip()
            if case_id:
                result[case_id] = row.get("groups", "")
    return result


def dataset_split(groups: str) -> str:
    # DAMAGE와 DAMAGE_PART 어느 쪽에만 있어도 원천 split에 포함된 사례다.
    has_train = "train/damage" in groups
    has_validation = "validation/damage" in groups
    if has_train and has_validation:
        return "MIXED"
    if has_train:
        return "TRAIN_ONLY"
    if has_validation:
        return "VALIDATION_ONLY"
    return "UNKNOWN"


def image_path_for_label(label_path: Path) -> Path:
    parts = list(label_path.parts)
    marker = "2.라벨링데이터"
    index = parts.index(marker)
    parts[index] = "1.원천데이터"
    parts[index + 1] = parts[index + 1].replace("TL_", "TS_").replace("VL_", "VS_")
    return Path(*parts[:-1]) / (label_path.stem + ".jpg")


def label_paths_by_case(subset_root: Path, wanted: set[str]) -> dict[str, list[Path]]:
    result: dict[str, list[Path]] = defaultdict(list)
    wanted_by_lower = {case_id.lower(): case_id for case_id in wanted}

    # os.scandir는 대량 파일을 Path 객체로 한꺼번에 만들지 않는다.
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
                if match and match.group("case").lower() in wanted_by_lower:
                    case_id = wanted_by_lower[match.group("case").lower()]
                    result[case_id].append(Path(entry.path))
    return result


def add_repair_parts(value: Any, parts: set[str]) -> None:
    values = value if isinstance(value, list) else [value]
    for item in values:
        if not isinstance(item, str):
            continue
        try:
            parts.add(normalize_repair_label(item)["part_code"])
        except ValueError:
            continue


def label_metadata(paths: Iterable[Path]) -> tuple[set[str], set[str], set[str]]:
    classes: set[str] = set()
    parts: set[str] = set()
    damages: set[str] = set()
    for path in paths:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
        category = document.get("categories") or {}
        car_class = normalize_class(category.get("supercategory_name"))
        if car_class:
            classes.add(car_class)
        for annotation in document.get("annotations") or []:
            damage = DAMAGE_BY_RAW.get(str(annotation.get("damage") or "").strip().casefold())
            if damage:
                damages.add(damage)
            part = PART_BY_RAW.get(str(annotation.get("part") or "").strip().casefold())
            if part:
                parts.add(part)
            add_repair_parts(annotation.get("repair"), parts)
    return classes, parts, damages


def vehicle_metadata(path: Path) -> tuple[str, str]:
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "", ""
    vehicle = document.get("차량정보") or {}
    manufacturer = str(vehicle.get("제작사/차종") or "").strip()
    vehicle_name = str(vehicle.get("차량명칭") or "").strip()
    model = str(vehicle.get("모델") or "").strip()
    name = " ".join(value for value in (manufacturer, vehicle_name) if value)
    return name, model


def stable_rank(case_id: str, seed: str) -> str:
    return hashlib.sha256(f"{seed}:{case_id}".encode()).hexdigest()


def features(row: dict[str, str]) -> set[str]:
    values = {
        f"source={row['source']}",
        f"car_class={row['car_class']}",
    }
    values.update(f"part={value}" for value in row["part_codes"].split("|") if value)
    values.update(f"damage={value}" for value in row["damage_types"].split("|") if value)
    return values


def choose_balanced(rows: list[dict[str, str]], limit: int, seed: str) -> list[dict[str, str]]:
    if limit <= 0 or not rows:
        return []
    if limit >= len(rows):
        return sorted(rows, key=lambda row: row["case_id"])

    # 먼저 source×car_class 버킷에 quota를 배분하고, 버킷 안에서는 희소한
    # part/damage 라벨을 우선한다. 매 라운드 모든 후보를 재평가하는 greedy
    # 방식은 4만여 후보에서 불필요하게 O(n×limit)이 되므로 사용하지 않는다.
    frequency = Counter(feature for row in rows for feature in features(row))

    def score(row: dict[str, str]) -> float:
        return sum(1.0 / math.sqrt(frequency[feature]) for feature in features(row))

    buckets: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        buckets[(row["source"], row["car_class"] or "UNKNOWN")].append(row)
    bucket_keys = sorted(buckets, key=lambda key: stable_rank("|".join(key), seed))
    base, remainder = divmod(limit, len(bucket_keys))
    selected: list[dict[str, str]] = []
    selected_ids: set[str] = set()
    for index, bucket_key in enumerate(bucket_keys):
        quota = base + (1 if index < remainder else 0)
        candidates = sorted(
            buckets[bucket_key],
            key=lambda row: (-score(row), stable_rank(row["case_id"], seed)),
        )
        chosen = candidates[:quota]
        selected.extend(chosen)
        selected_ids.update(row["case_id"] for row in chosen)

    if len(selected) < limit:
        remaining = [row for row in rows if row["case_id"] not in selected_ids]
        remaining.sort(key=lambda row: (-score(row), stable_rank(row["case_id"], seed)))
        selected.extend(remaining[: limit - len(selected)])
    return sorted(selected, key=lambda row: row["case_id"])


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    fields = [
        "case_id", "source", "dataset_split", "purpose", "car_class",
        "vehicle_model", "model_name", "part_codes", "damage_types", "image_count",
    ]
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    args = parse_args()
    if args.dev_cases < 0 or args.demo_cases < 0:
        raise SystemExit("--dev-cases와 --demo-cases는 0 이상이어야 합니다")

    subset_root = args.subset_root.resolve()
    readiness = selected_cases(args.readiness_csv.resolve())
    groups = linkage_groups(args.linkage_csv.resolve())
    label_index = label_paths_by_case(subset_root, readiness)
    missing_labels = sorted(readiness - label_index.keys())
    if missing_labels:
        raise SystemExit(
                f"readiness 최종 사례에 DAMAGE/DAMAGE_PART 라벨이 없습니다: {len(missing_labels)}건"
        )
    missing_images = [
        path
        for paths in label_index.values()
        for path in paths
        if not image_path_for_label(path).is_file()
    ]
    if missing_images:
        raise SystemExit(
                f"DAMAGE/DAMAGE_PART 라벨에 대응하는 원본 이미지가 없습니다: {len(missing_images)}건"
        )
    estimate_root = (args.estimate_root or (
        subset_root / "1.Training/1.원천데이터_230126_add/TS_99. 붙임_견적서"
    )).resolve()

    rows: list[dict[str, str]] = []
    for case_id in sorted(readiness):
        labels = sorted(label_index.get(case_id, []))
        if not labels:
            continue
        classes, parts, damages = label_metadata(labels)
        estimate_path = estimate_root / f"{case_id}.json"
        vehicle_model, model_name = vehicle_metadata(estimate_path)
        split = dataset_split(groups.get(case_id, ""))
        rows.append({
            "case_id": case_id,
            "source": source_for_case(case_id),
            "dataset_split": split,
            "purpose": "FULL_ONLY" if split in {"MIXED", "UNKNOWN"} else "POOL",
            "car_class": "|".join(sorted(classes)),
            "vehicle_model": vehicle_model,
            "model_name": model_name,
            "part_codes": "|".join(sorted(parts)),
            "damage_types": "|".join(sorted(damages)),
            "image_count": str(len(labels)),
        })

    train_only = [row for row in rows if row["dataset_split"] == "TRAIN_ONLY"]
    validation_only = [row for row in rows if row["dataset_split"] == "VALIDATION_ONLY"]
    dev = choose_balanced(train_only, args.dev_cases, args.seed + ":dev")
    demo = choose_balanced(validation_only, args.demo_cases, args.seed + ":demo")
    dev_ids = {row["case_id"] for row in dev}
    demo_ids = {row["case_id"] for row in demo}
    eval_rows = [row for row in validation_only if row["case_id"] not in demo_ids]

    for row in rows:
        if row["dataset_split"] == "TRAIN_ONLY" and row["case_id"] in dev_ids:
            row["purpose"] = "DEV"
        elif row["dataset_split"] == "VALIDATION_ONLY":
            row["purpose"] = "DEMO" if row["case_id"] in demo_ids else "EVAL"

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "search_case_manifest.csv", rows)
    write_csv(output / "search_dev_cases.csv", dev)
    write_csv(output / "search_demo_cases.csv", demo)
    write_csv(output / "search_eval_cases.csv", eval_rows)
    write_csv(output / "search_mixed_cases.csv", [row for row in rows if row["dataset_split"] == "MIXED"])

    counts = Counter(row["dataset_split"] for row in rows)
    print(json.dumps({
        "total_final_cases": len(rows),
        "dataset_split_counts": dict(sorted(counts.items())),
        "dev_cases": len(dev),
        "demo_cases": len(demo),
        "eval_cases": len(eval_rows),
        "mixed_cases_excluded_from_eval": counts["MIXED"],
        "image_source_scope": "damage_and_damage_part_case_split__damage_query_default",
        "seed": args.seed,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
