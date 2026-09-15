"""Adapt model-developer YOLO JSON into the AI server's canonical output.

The model contract deliberately contains no domain codes.  This module owns the
version-specific class map, raw-label validation, same-image geometry pairing,
and the conversion to the input envelope expected by ``normalize_inference``.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..core.bootstrap import ensure_repo_root

ensure_repo_root()

# Batch corpus loading and online inference use the same code map, geometry
# pairing rule, and canonical schema from the shared vision domain package.
from shared.vision.catalog import DAMAGES, PARTS  # noqa: E402
from shared.vision.damage_part_pairing import damage_part_roi_rows  # noqa: E402
from shared.vision.normalizer import (  # noqa: E402
    NormalizationError,
    normalize_inference,
)
from shared.vision.roi import source_bbox  # noqa: E402


PART_RAW_TO_CODE = {values[0].casefold(): code for code, values in PARTS.items()}
DAMAGE_RAW_TO_CODE = {values[0].casefold(): code for code, values in DAMAGES.items()}


def _fail(message: str) -> None:
    raise NormalizationError(message)


def _mapping_name(class_map: Mapping[Any, str], class_id: int,
                  class_name: str, model_kind: str) -> None:
    """Require the model artifact's class_id -> class_name map to agree."""
    expected = class_map.get(class_id)
    if expected is None:
        expected = class_map.get(str(class_id))
    if expected is None:
        _fail(f"{model_kind} class_id is absent from the model class map: {class_id}")
    if str(expected) != class_name:
        _fail(
            f"{model_kind} class map mismatch for class_id {class_id}: "
            f"expected {expected!r}, received {class_name!r}"
        )


def _validate_prediction(prediction: Mapping[str, Any], *, task: str,
                         width: int, height: int, class_map: Mapping[Any, str],
                         model_kind: str) -> None:
    required = ("detection_id", "class_id", "class_name", "confidence", "bbox",
                "segmentation")
    for field in required:
        if field not in prediction:
            _fail(f"{model_kind} prediction is missing {field}")

    detection_id = prediction["detection_id"]
    class_name = prediction["class_name"]
    if not isinstance(detection_id, str) or not detection_id.strip():
        _fail(f"{model_kind} detection_id must be a non-empty string")
    if not isinstance(prediction["class_id"], int) or prediction["class_id"] < 0:
        _fail(f"{model_kind} class_id must be a non-negative integer")
    if not isinstance(class_name, str) or not class_name.strip():
        _fail(f"{model_kind} class_name must be a non-empty string")
    _mapping_name(class_map, prediction["class_id"], class_name, model_kind)

    confidence = prediction["confidence"]
    if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
        _fail(f"{model_kind} confidence must be between 0 and 1")

    bbox = prediction["bbox"]
    if not isinstance(bbox, Mapping) or bbox.get("format") != "xyxy":
        _fail(f"{model_kind} bbox format must be xyxy")
    coordinates = bbox.get("coordinates")
    if not isinstance(coordinates, (list, tuple)) or len(coordinates) != 4:
        _fail(f"{model_kind} bbox coordinates must contain four values")
    x0, y0, x1, y1 = (float(value) for value in coordinates)
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
        _fail(f"{model_kind} bbox must be inside the original image")

    segmentation = prediction["segmentation"]
    if task == "detect":
        if segmentation is not None:
            _fail("part detection must return segmentation: null")
    elif (not isinstance(segmentation, Mapping)
          or segmentation.get("format") != "polygon"
          or not segmentation.get("polygons")):
        _fail("damage segmentation must contain polygon arrays")


def _validate_raw(raw: Mapping[str, Any], *, expected_task: str,
                  expected_image_id: str, class_map: Mapping[Any, str],
                  model_kind: str) -> tuple[int, int, list[Mapping[str, Any]]]:
    if not isinstance(class_map, Mapping) or not class_map:
        _fail(f"{model_kind} model class map is required")
    if not isinstance(raw, Mapping):
        _fail(f"{model_kind} raw output must be an object")
    for field in ("model", "image", "predictions"):
        if field not in raw:
            _fail(f"{model_kind} raw output is missing {field}")

    model = raw["model"]
    image = raw["image"]
    predictions = raw["predictions"]
    if not isinstance(model, Mapping) or model.get("task") != expected_task:
        _fail(f"{model_kind} model task must be {expected_task!r}")
    if not isinstance(model.get("name"), str) or not model["name"].strip():
        _fail(f"{model_kind} model name is required")
    if not isinstance(model.get("version"), str) or not model["version"].strip():
        _fail(f"{model_kind} model version is required")
    if not isinstance(image, Mapping):
        _fail(f"{model_kind} image must be an object")
    raw_image_id = image.get("image_id")
    if raw_image_id is None or str(raw_image_id) != expected_image_id:
        _fail(
            f"{model_kind} image_id must match the backend imageId "
            f"({expected_image_id!r})"
        )
    try:
        width = int(image["original_width"])
        height = int(image["original_height"])
    except (KeyError, TypeError, ValueError) as exc:
        raise NormalizationError(
            f"{model_kind} original image dimensions are invalid"
        ) from exc
    if width <= 0 or height <= 0:
        _fail(f"{model_kind} original image dimensions must be positive")
    if not isinstance(predictions, list):
        _fail(f"{model_kind} predictions must be an array")

    result: list[Mapping[str, Any]] = []
    seen_ids: set[str] = set()
    for prediction in predictions:
        if not isinstance(prediction, Mapping):
            _fail(f"{model_kind} prediction must be an object")
        _validate_prediction(prediction, task=expected_task, width=width,
                             height=height, class_map=class_map,
                             model_kind=model_kind)
        detection_id = str(prediction["detection_id"])
        if detection_id in seen_ids:
            _fail(f"{model_kind} detection_id is duplicated: {detection_id}")
        seen_ids.add(detection_id)
        raw_label = str(prediction["class_name"])
        known = (raw_label.casefold() in
                 (PART_RAW_TO_CODE if expected_task == "detect" else DAMAGE_RAW_TO_CODE))
        if not known:
            _fail(f"unknown {model_kind} class_name: {raw_label!r}")
        result.append(prediction)
    return width, height, result


def _xyxy_to_xywh(prediction: Mapping[str, Any]) -> list[float]:
    coordinates = prediction["bbox"]["coordinates"]
    x0, y0, x1, y1 = (float(value) for value in coordinates)
    return [x0, y0, x1 - x0, y1 - y0]


def _segmentation_polygons(prediction: Mapping[str, Any]) -> list[Any]:
    return list(prediction["segmentation"]["polygons"])


def adapt_raw_yolo_outputs(
    part_raw: Mapping[str, Any],
    damage_raw: Mapping[str, Any],
    *,
    image_id: str | int,
    part_class_map: Mapping[Any, str],
    damage_class_map: Mapping[Any, str],
) -> dict[str, Any]:
    """Adapt one image's part-detection and damage-segmentation outputs.

    The returned value is the canonical ``normalize_inference`` envelope.  A
    damage prediction without a unique same-image part match is intentionally
    retained with ``part=None`` and ``searchability=VECTOR_ONLY``.
    """
    expected_image_id = str(image_id)
    part_width, part_height, part_predictions = _validate_raw(
        part_raw, expected_task="detect", expected_image_id=expected_image_id,
        class_map=part_class_map, model_kind="part")
    damage_width, damage_height, damage_predictions = _validate_raw(
        damage_raw, expected_task="segment", expected_image_id=expected_image_id,
        class_map=damage_class_map, model_kind="damage")
    if (part_width, part_height) != (damage_width, damage_height):
        _fail("part and damage outputs must have the same original image dimensions")

    part_annotations = [
        {
            "id": prediction["detection_id"],
            "part": prediction["class_name"],
            "bbox": _xyxy_to_xywh(prediction),
        }
        for prediction in part_predictions
    ]
    damage_annotations = [
        {
            "id": prediction["detection_id"],
            "damage": prediction["class_name"],
            "bbox": _xyxy_to_xywh(prediction),
            "segmentation": _segmentation_polygons(prediction),
        }
        for prediction in damage_predictions
    ]
    rows = []
    for damage_annotation in damage_annotations:
        document = {"annotations": [damage_annotation, *part_annotations]}
        rows.append(damage_part_roi_rows(document)[0])

    damage_by_id = {str(item["detection_id"]): item for item in damage_predictions}
    part_by_id = {str(item["detection_id"]): item for item in part_predictions}
    detections = []
    for row in rows:
        damage_prediction = damage_by_id[str(row["damage_ref"])]
        part_prediction = (part_by_id[str(row["part_ref"])]
                           if row["part_ref"] is not None else None)
        damage_bbox = source_bbox(row["damage_annotation"])
        detections.append({
            "detection_id": f"{expected_image_id}:damage:{damage_prediction['detection_id']}",
            "part": (part_prediction["class_name"] if part_prediction else None),
            "damage": damage_prediction["class_name"],
            "bbox": list(damage_bbox),
            "polygons": _segmentation_polygons(damage_prediction),
            "part_confidence": (part_prediction["confidence"]
                                 if part_prediction else None),
            "damage_confidence": damage_prediction["confidence"],
            "part_match_status": row["match_status"],
        })

    return normalize_inference({
        "image": {
            "id": expected_image_id,
            "width": damage_width,
            "height": damage_height,
        },
        "coordinate_space": "PIXEL_XY",
        "detections": detections,
    })

