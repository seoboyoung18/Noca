"""Normalize the paired internal envelope derived from the Notion YOLO raw contract."""

from __future__ import annotations

from typing import Any

from .catalog import (
    DAMAGES,
    DEFAULT_WORK_BY_DAMAGE,
    ESTIMATE_WORK_ALIASES,
    ESTIMATE_WORKS,
    PARTS,
    WORKS,
)


class NormalizationError(ValueError):
    """Raised when a model label or geometry violates the contract."""


def _index(catalog: dict[str, tuple]) -> dict[str, str]:
    return {values[0].casefold(): code for code, values in catalog.items()}


PART_BY_RAW = _index(PARTS)
DAMAGE_BY_RAW = _index(DAMAGES)
WORK_BY_RAW = _index(WORKS)

ESTIMATE_WORK_BY_RAW = {raw: code for code, (raw, _ko, _category) in ESTIMATE_WORKS.items()}
ESTIMATE_WORK_BY_RAW.update(ESTIMATE_WORK_ALIASES)


def _bbox(value: Any, width: int, height: int, normalized: bool) -> dict[str, float]:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        raise NormalizationError("bbox must be [x, y, width, height]")
    x, y, w, h = map(float, value)
    if normalized:
        if any(number < 0 or number > 1 for number in (x, y, w, h)):
            raise NormalizationError("normalized bbox values must be between 0 and 1")
        x, y, w, h = x * width, y * height, w * width, h * height
    if x < 0 or y < 0 or w <= 0 or h <= 0 or x + w > width or y + h > height:
        raise NormalizationError("bbox is outside the image or has non-positive size")
    return {"x": x, "y": y, "width": w, "height": h}


def _is_point(value: Any) -> bool:
    return (isinstance(value, (list, tuple)) and len(value) == 2
            and all(isinstance(item, (int, float)) for item in value))


def _polygon_leaves(value: Any) -> list[list[Any]]:
    """Collect polygons from YOLO/AI-Hub nesting without merging mask components."""
    if isinstance(value, (list, tuple)) and len(value) >= 3 and all(_is_point(p) for p in value):
        return [list(value)]
    if isinstance(value, (list, tuple)):
        polygons = []
        for child in value:
            polygons.extend(_polygon_leaves(child))
        return polygons
    return []


def _segmentation(value: Any, width: int, height: int, normalized: bool) -> dict[str, Any]:
    raw_polygons = _polygon_leaves(value)
    if not raw_polygons:
        raise NormalizationError("segmentation must contain at least one polygon with three points")
    polygons = []
    total_area = 0.0
    for raw_polygon in raw_polygons:
        polygon = []
        xy = []
        for point in raw_polygon:
            x, y = map(float, point)
            if normalized:
                if not (0 <= x <= 1 and 0 <= y <= 1):
                    raise NormalizationError("normalized polygon points must be between 0 and 1")
                x, y = x * width, y * height
            if not (0 <= x <= width and 0 <= y <= height):
                raise NormalizationError("polygon point is outside the image")
            polygon.append({"x": x, "y": y})
            xy.append((x, y))
        area = abs(sum(
            x1 * y2 - x2 * y1
            for (x1, y1), (x2, y2) in zip(xy, xy[1:] + xy[:1])
        )) / 2
        if area == 0:
            raise NormalizationError("polygon area must be greater than zero")
        total_area += area
        polygons.append(polygon)
    area_ratio = total_area / (width * height)
    if area_ratio > 1:
        raise NormalizationError("total segmentation area cannot exceed image area")
    return {
        "format": "POLYGONS",
        "polygons": polygons,
        "area_px": total_area,
        "area_ratio": area_ratio,
    }


def _confidence(value: Any, field: str) -> float | None:
    if value is None:
        return None
    number = float(value)
    if not 0 <= number <= 1:
        raise NormalizationError(f"{field} must be between 0 and 1")
    return number


def normalize_detection(raw: dict[str, Any], width: int, height: int,
                        default_coordinate_space: str = "PIXEL_XY") -> dict[str, Any]:
    part_value = raw.get("part")
    part_raw = str(part_value or "").strip()
    damage_raw = str(raw.get("damage", "")).strip()
    if not damage_raw:
        raise NormalizationError("damage label is required")
    try:
        damage_code = DAMAGE_BY_RAW[damage_raw.casefold()]
    except KeyError as exc:
        raise NormalizationError(f"unknown label: {exc.args[0]!r}") from exc

    part_code = None
    part_output = None
    if part_raw:
        try:
            part_code = PART_BY_RAW[part_raw.casefold()]
        except KeyError as exc:
            raise NormalizationError(f"unknown label: {exc.args[0]!r}") from exc
        part_en, part_ko, group, side = PARTS[part_code]
        part_output = {
            "code": part_code, "name_en": part_en, "name_ko": part_ko,
            "group": group, "side": side, "raw_label": part_raw,
        }

    pair_status = str(raw.get("part_match_status") or (
        "PAIRED" if part_code else "UNPAIRED"
    )).upper()
    if pair_status not in {"PAIRED", "UNPAIRED", "AMBIGUOUS"}:
        raise NormalizationError("part_match_status must be PAIRED, UNPAIRED, or AMBIGUOUS")
    if pair_status == "PAIRED" and not part_code:
        raise NormalizationError("PAIRED detection must contain part")
    if pair_status != "PAIRED" and part_code:
        raise NormalizationError("only PAIRED detection may contain part")

    damage_en, damage_ko = DAMAGES[damage_code]
    coordinate_space = str(raw.get("coordinate_space", default_coordinate_space)).upper()
    if coordinate_space not in {"PIXEL_XY", "NORMALIZED_XY"}:
        raise NormalizationError("coordinate_space must be PIXEL_XY or NORMALIZED_XY")
    normalized = coordinate_space == "NORMALIZED_XY"
    works = [
        {"code": code, "name_en": WORKS[code][0], "name_ko": WORKS[code][1]}
        for code in DEFAULT_WORK_BY_DAMAGE[damage_code]
    ]
    result = {
        "part": part_output,
        "damage": {"code": damage_code, "name_en": damage_en,
                   "name_ko": damage_ko, "raw_label": damage_raw},
        "geometry": {
            "coordinate_system": "PIXEL_XY_TOP_LEFT",
            "bbox_format": "XYWH",
            "bbox": _bbox(raw.get("bbox"), width, height, normalized),
            "segmentation": _segmentation(
                raw.get("polygons", raw.get("polygon", raw.get("segmentation"))),
                width, height, normalized,
            ),
        },
        "confidence": {
            "part": _confidence(raw.get("part_confidence"), "part_confidence"),
            "damage": _confidence(raw.get("damage_confidence", raw.get("confidence")), "damage_confidence"),
        },
        "work_candidates": works,
        "work_decision": "CANDIDATE",
        "work_rule_version": "damage-default-v1",
        "pair_status": pair_status,
        "searchability": "STRICT" if pair_status == "PAIRED" else "VECTOR_ONLY",
    }
    if raw.get("detection_id") is not None:
        result["detection_id"] = str(raw["detection_id"])
    return result


def normalize_inference(payload: dict[str, Any]) -> dict[str, Any]:
    image = payload.get("image") or {}
    width, height = int(image.get("width", 0)), int(image.get("height", 0))
    if width <= 0 or height <= 0:
        raise NormalizationError("image.width and image.height must be positive")
    detections = payload.get("detections")
    if not isinstance(detections, list):
        raise NormalizationError("detections must be an array")
    return {
        "schema_version": "1.2.0",
        "image": {"id": image.get("id"), "width": width, "height": height},
        "detections": [
            normalize_detection(item, width, height, payload.get("coordinate_space", "PIXEL_XY"))
            for item in detections
        ],
    }


def normalize_repair_label(value: str) -> dict[str, Any]:
    """Normalize a labeling repair string such as 'Front bumper:coating,exchange'."""
    if not isinstance(value, str) or ":" not in value:
        raise NormalizationError("repair label must be '<part>:<work>[,<work>...]'")
    part_raw, work_raw = value.split(":", 1)
    try:
        part_code = PART_BY_RAW[part_raw.strip().casefold()]
    except KeyError as exc:
        raise NormalizationError(f"unknown part label: {part_raw!r}") from exc
    work_codes = []
    for item in work_raw.split(","):
        key = item.strip().casefold()
        if key not in WORK_BY_RAW:
            raise NormalizationError(f"unknown work label: {item!r}")
        code = WORK_BY_RAW[key]
        if code not in work_codes:  # source occasionally repeats a method
            work_codes.append(code)
    return {"part_code": part_code, "work_codes": work_codes, "raw_label": value}


def normalize_estimate_work(value: Any) -> dict[str, Any]:
    """견적서 `작업` 원문을 표준 코드로 정규화한다.

    빈 값과 알 수 없는 값 모두 `NormalizationError`로 격리한다. 빈 값은 작업
    행이 아닌 다른 종류의 행(부품가격·참고가)이므로 호출부가 먼저 걸러야 한다.
    """
    raw = "" if value is None else str(value).strip()
    if not raw:
        raise NormalizationError("estimate work is empty")
    code = ESTIMATE_WORK_BY_RAW.get(raw)
    if code is None:
        raise NormalizationError(f"unknown estimate work: {raw!r}")
    _representative, name, category = ESTIMATE_WORKS[code]
    return {"code": code, "raw": raw, "name": name, "category": category}
