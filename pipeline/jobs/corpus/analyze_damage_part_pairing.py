"""damage_part pairing 표본 분석.

label JSON을 한 번만 읽어 메모리에 보관한 뒤 threshold를 바꿔 재계산한다.
운영 적재·DB·S3와 무관한 분석용 도구다.

기본 출력은 다음을 포함한다.
  - threshold별 PAIRED/UNPAIRED/AMBIGUOUS 집계
  - segmentation polygon을 픽셀 mask로 rasterize했을 때 bbox 기준 AMBIGUOUS 중
    몇 건이 해소되는지에 대한 참고 집계

polygon 집계는 운영 규칙을 변경하지 않는다. rasterization 오차가 있으므로
정식 규칙으로 승격하기 전에 샘플 시각 검토가 필요하다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

try:
    import cv2
    import numpy as np
except ImportError:  # pragma: no cover - optional analysis enhancement
    cv2 = None
    np = None

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
sys.path.insert(0, str(REPO_ROOT))

from shared.vision.damage_part_pairing import (  # noqa: E402
    damage_code,
    damage_part_roi_rows,
    part_code,
    valid_geometry,
)
from shared.vision.roi import link_parts, source_bbox  # noqa: E402

LABEL_DIRS = (
    Path("1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    Path("2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--sample-count", type=int, default=1200)
    parser.add_argument("--seed", default="a307-damage-part-pairing-v1")
    parser.add_argument(
        "--thresholds",
        default="0.3,0.4,0.5,0.6,0.7",
        help="comma-separated coverage thresholds",
    )
    parser.add_argument("--output", type=Path, default=None)
    return parser.parse_args()


def polygon_leaves(value: Any) -> list[list[tuple[float, float]]]:
    """AI-Hub의 중첩 segmentation에서 polygon leaf를 추출한다."""
    if isinstance(value, (list, tuple)) and len(value) >= 3 and all(
        isinstance(point, (list, tuple))
        and len(point) == 2
        and all(isinstance(coord, (int, float)) for coord in point)
        for point in value
    ):
        return [[(float(point[0]), float(point[1])) for point in value]]
    if not isinstance(value, (list, tuple)):
        return []
    result: list[list[tuple[float, float]]] = []
    for child in value:
        result.extend(polygon_leaves(child))
    return result


def polygon_mask(annotation: dict[str, Any], width: int, height: int) -> np.ndarray:
    if cv2 is None or np is None:
        raise RuntimeError("polygon 비교에는 선택 의존성 opencv-python·numpy가 필요하다")
    mask = np.zeros((height, width), dtype=np.uint8)
    for polygon in polygon_leaves(annotation.get("segmentation")):
        points = np.asarray(polygon, dtype=np.int32).reshape((-1, 1, 2))
        cv2.fillPoly(mask, [points], 1)
    return mask


def exact_link_codes(
    damage: dict[str, Any],
    parts: list[dict[str, Any]],
    width: int,
    height: int,
    threshold: float,
) -> set[str]:
    damage_mask = polygon_mask(damage, width, height)
    damage_area = int(damage_mask.sum())
    if damage_area == 0:
        return set()
    codes: set[str] = set()
    for candidate in parts:
        code = part_code(candidate)
        if not code:
            continue
        part_mask = polygon_mask(candidate, width, height)
        coverage = int(np.logical_and(damage_mask, part_mask).sum()) / damage_area
        if coverage >= threshold:
            codes.add(code)
    return codes


def bbox_status(rows: list[dict[str, Any]]) -> Counter[str]:
    return Counter(row["match_status"] for row in rows)


def cached_documents(dataset_root: Path, sample_count: int, seed: str) -> list[tuple[Path, dict[str, Any]]]:
    paths = [
        path
        for relative_dir in LABEL_DIRS
        for path in (dataset_root / relative_dir).glob("*.json")
    ]
    paths.sort(key=lambda path: hashlib.sha256(
        f"{seed}:{path.relative_to(dataset_root).as_posix()}".encode("utf-8")
    ).hexdigest())
    selected = paths[:sample_count]
    documents = []
    for path in selected:
        with path.open(encoding="utf-8") as fp:
            documents.append((path, json.load(fp)))
    return documents


def all_annotations(document: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    annotations = [
        value for value in (document.get("annotations") or [])
        if isinstance(value, dict)
    ]
    damages = [value for value in annotations if damage_code(value) and valid_geometry(value)]
    parts = [value for value in annotations if part_code(value) and valid_geometry(value)]
    return damages, parts


def polygon_ambiguity_summary(
    documents: list[tuple[Path, dict[str, Any]]],
    threshold: float,
) -> dict[str, int]:
    if cv2 is None or np is None:
        return {
            "bbox_ambiguous_damage_count": 0,
            "polygon_ambiguous_damage_count": 0,
            "bbox_artifact_candidate_count": 0,
            "without_polygon_comparison_count": 0,
            "status": "SKIPPED_OPTIONAL_DEPENDENCY_MISSING",
        }
    bbox_ambiguous = 0
    exact_ambiguous = 0
    bbox_artifact = 0
    no_polygon_comparison = 0
    for _, document in documents:
        image = document.get("images") or {}
        width, height = int(image.get("width", 0)), int(image.get("height", 0))
        if width <= 0 or height <= 0:
            continue
        damages, parts = all_annotations(document)
        for damage in damages:
            bbox_codes = {
                part_code(annotation)
                for annotation, _ in link_parts(damage, parts, min_overlap=threshold)
                if part_code(annotation)
            }
            if len(bbox_codes) < 2:
                continue
            bbox_ambiguous += 1
            if not polygon_leaves(damage.get("segmentation")):
                no_polygon_comparison += 1
                continue
            exact_codes = exact_link_codes(damage, parts, width, height, threshold)
            if len(exact_codes) >= 2:
                exact_ambiguous += 1
            else:
                bbox_artifact += 1
    return {
        "bbox_ambiguous_damage_count": bbox_ambiguous,
        "polygon_ambiguous_damage_count": exact_ambiguous,
        "bbox_artifact_candidate_count": bbox_artifact,
        "without_polygon_comparison_count": no_polygon_comparison,
    }


def analyze(documents: list[tuple[Path, dict[str, Any]]], thresholds: list[float]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "sample_label_count": len(documents),
        "thresholds": {},
    }
    for threshold in thresholds:
        counts = Counter()
        roi_count = 0
        for _, document in documents:
            rows = damage_part_roi_rows(document, min_overlap=threshold)
            counts.update(bbox_status(rows))
            roi_count += len(rows)
        result["thresholds"][str(threshold)] = {
            "roi_count": roi_count,
            "paired": counts["PAIRED"],
            "unpaired": counts["UNPAIRED"],
            "ambiguous": counts["AMBIGUOUS"],
            "polygon_ambiguity": polygon_ambiguity_summary(documents, threshold),
        }
    return result


def main() -> None:
    args = parse_args()
    if args.sample_count <= 0:
        raise SystemExit("--sample-count must be positive")
    thresholds = [float(value.strip()) for value in args.thresholds.split(",") if value.strip()]
    documents = cached_documents(args.dataset_root, args.sample_count, args.seed)
    result = analyze(documents, thresholds)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
