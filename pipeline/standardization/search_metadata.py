"""검색용 메타데이터 생성.

원본 YOLO 출력과 공통 추론 결과를 직접 검색 테이블로 사용하지 않고,
ROI 단위의 검색 계약으로 투영한다. 비용·작업 의미는 다루지 않는다.

1차 후보 필터는 part_code·damage_type을 강한 조건으로, car_class를 약한 조건으로
쓴다. 심각도는 검색 축에서 제외한다 — 원본 level이 한 값에 몰려 있고 파생 규칙이
확정되지 않았다(S15P21A307-197).
"""

from __future__ import annotations

from typing import Any, Mapping

from .roi import (
    PAD_RATIO,
    PREPROCESSING_VERSION,
    QUALITY_GOOD,
    QUALITY_INVALID,
    QUALITY_LOW_CONFIDENCE,
    QUALITY_PARTIAL_PART,
    feature_quality,
    part_clipped,
    roi_box,
    roi_quality,
)


SEARCH_SCHEMA_VERSION = "1.1.0"

# PARTIAL_PART는 후보로 남긴다. 부품이 잘렸어도 부품 코드·손상 유형은 정확하다.
# 신뢰하지 않는 것은 면적 비율 기반 값뿐이다.
SEARCHABLE_QUALITY = (QUALITY_GOOD, QUALITY_PARTIAL_PART)


def _bbox_tuple(value: Mapping[str, Any]) -> tuple[float, float, float, float]:
    try:
        return tuple(float(value[key]) for key in ("x", "y", "width", "height"))  # type: ignore[return-value]
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("normalized detection geometry.bbox must be an XYWH object") from exc


def _roi_bbox(box: tuple[int, int, int, int]) -> dict[str, Any]:
    x0, y0, x1, y1 = box
    return {
        "coordinate_system": "PIXEL_XY_TOP_LEFT",
        "format": "XYWH",
        "x": x0,
        "y": y0,
        "width": x1 - x0,
        "height": y1 - y0,
    }


def _exclusion_reason(quality_status: str, part_code: str | None,
                      damage_type: str | None) -> str | None:
    if quality_status not in SEARCHABLE_QUALITY:
        return quality_status
    if not part_code or not damage_type:
        return "missing_search_feature"
    return None


def _as_bbox(value: Any) -> tuple[float, float, float, float]:
    if isinstance(value, Mapping):
        return _bbox_tuple(value)
    x, y, w, h = value
    return (float(x), float(y), float(w), float(h))


def _quality_reasons(roi_status: str, part_is_clipped: bool | None,
                     roi_is_clipped: bool, part_code: str | None,
                     damage_type: str | None) -> list[str]:
    """왜 이 품질 등급인지 사유를 남긴다(설계 6절 quality_reasons).

    등급은 하나지만 사유는 여럿일 수 있으므로 목록이다.
    """
    reasons: list[str] = []
    if roi_status == QUALITY_INVALID:
        reasons.append("DEGENERATE_BBOX")
    elif roi_status == QUALITY_LOW_CONFIDENCE:
        reasons.append("TOO_SMALL")
    if part_is_clipped is None:
        reasons.append("PART_BOX_UNKNOWN")
    elif part_is_clipped:
        reasons.append("PART_TOUCHES_IMAGE_EDGE")
    if roi_is_clipped:
        reasons.append("ROI_CLIPPED")
    if not part_code or not damage_type:
        reasons.append("MISSING_SEARCH_FEATURE")
    return reasons


def build_search_metadata(
    normalized: Mapping[str, Any],
    *,
    case_id: str | int,
    source: str | None,
    car_class: str | None,
    pipeline_version_id: str,
    source_image_ref: str | None = None,
    model_name: str = "unknown",
    model_version: str = "unknown",
    raw_contract_version: str = "1.0.0",
    roi_preprocessing_version: str = PREPROCESSING_VERSION,
    part_boxes_by_detection_id: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """표준화된 추론 결과를 ROI별 검색 메타데이터 목록으로 변환한다.

    결과 하나는 손상 검출 하나에 대응한다. ROI 품질이 낮거나 검색 필드가
    비어 있어도 레코드는 남기고 ``search.is_searchable``만 false로 둔다.
    이렇게 해야 제외된 검출도 원본과 대조할 수 있다.

    ``pipeline_version_id``는 필수다. 설계 2절 전제 3번("과거 사례와 신규 입력은
    동일한 YOLO 및 동일한 특징 추출 버전을 사용한다")을 강제하는 값이며, 검색은
    같은 값을 가진 레코드끼리만 비교한다. 기본값을 주지 않는 이유는, 버전 없는
    레코드가 한 번 적재되면 무엇으로 만든 값인지 되찾을 수 없어 재적재해야 하기
    때문이다.

    ``part_boxes_by_detection_id``는 detection_id -> 부품 bbox(XYWH)다. 정규화된
    추론 출력의 detection은 부품을 코드로만 담고 부품 영역을 담지 않으므로,
    부품 잘림을 판정하려면 호출부가 따로 넘겨야 한다. 넘기지 않으면 부품 잘림을
    추측하지 않고 ``part_clipped=null`` + 사유 ``PART_BOX_UNKNOWN``으로 남긴다.
    """
    if not str(pipeline_version_id or "").strip():
        raise ValueError("pipeline_version_id is required and must be non-empty")

    image = normalized.get("image") or {}
    try:
        width, height = int(image["width"]), int(image["height"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("normalized image must contain positive width and height") from exc
    if width <= 0 or height <= 0:
        raise ValueError("normalized image must contain positive width and height")

    image_id = image.get("id")
    image_ref = source_image_ref or (str(image_id) if image_id is not None else None)
    if not image_ref:
        raise ValueError("source_image_ref or normalized image.id is required")

    detections = normalized.get("detections")
    if not isinstance(detections, list):
        raise ValueError("normalized detections must be an array")

    records = []
    for index, detection in enumerate(detections):
        if not isinstance(detection, Mapping):
            raise ValueError("each normalized detection must be an object")
        geometry = detection.get("geometry") or {}
        bbox = _bbox_tuple(geometry.get("bbox", {}))
        roi_status = roi_quality(bbox)
        padded_box, effective_padding, clipped, _ = roi_box(bbox, (width, height))

        part = detection.get("part") or {}
        damage = detection.get("damage") or {}
        part_code = part.get("code")
        damage_type = damage.get("code")
        detection_id = str(detection.get("detection_id") or f"detection-{index:04d}")
        segmentation = geometry.get("segmentation") or {}
        confidence = detection.get("confidence") or {"part": None, "damage": None}

        part_box = (part_boxes_by_detection_id or {}).get(detection_id)
        part_is_clipped = (None if part_box is None
                           else part_clipped(_as_bbox(part_box), (width, height)))
        quality_status = feature_quality(roi_status, part_is_clipped)
        searchable = quality_status in SEARCHABLE_QUALITY and bool(part_code and damage_type)
        exclusion_reason = _exclusion_reason(quality_status, part_code, damage_type)
        quality_reasons = _quality_reasons(
            roi_status, part_is_clipped, clipped, part_code, damage_type)

        records.append({
            "schema_version": SEARCH_SCHEMA_VERSION,
            "case": {
                "case_id": case_id,
                "source": source,
                "car_class": car_class,
            },
            "image": {
                "image_id": image_id if image_id is not None else image_ref,
                "source_image_ref": image_ref,
                "width": width,
                "height": height,
            },
            "roi": {
                "roi_id": f"{case_id}/{image_ref}#{detection_id}@{pipeline_version_id}",
                "detection_id": detection_id,
                "bbox": _roi_bbox(padded_box),
                "area_px": segmentation.get("area_px"),
                "area_ratio": segmentation.get("area_ratio"),
                "quality_status": quality_status,
                "quality_reasons": quality_reasons,
                "part_clipped": part_is_clipped,
                "is_searchable": searchable,
                # 설계 6절 roi_padding_ratio. 저장값으로 ROI를 재현할 수 있어야 한다.
                "padding_ratio": PAD_RATIO,
                "effective_padding": [round(e, 4) for e in effective_padding],
                "clipped": clipped,
            },
            "features": {
                "part_code": part_code,
                "damage_type": damage_type,
                "confidence": confidence,
            },
            "search": {
                "is_searchable": searchable,
                "exclusion_reason": exclusion_reason,
            },
            "provenance": {
                "pipeline_version_id": pipeline_version_id,
                "model_name": model_name,
                "model_version": model_version,
                "raw_contract_version": raw_contract_version,
                "normalization_version": str(normalized.get("schema_version", "unknown")),
                "roi_preprocessing_version": roi_preprocessing_version,
            },
        })
    return records
