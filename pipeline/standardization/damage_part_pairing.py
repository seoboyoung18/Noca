"""AI-Hub ``damage_part`` 이미지 안의 damage-part 관계를 판정한다.

``damage``와 ``damage_part`` 폴더 사이의 case-level 관계를 사용하지 않는다.
하나의 ``damage_part`` label JSON 안에서만 damage annotation과 part annotation의
geometry를 비교한다. 겹친 후보의 표준 part_code가 하나면 strict pair, 후보가 없거나
서로 다른 part_code가 둘 이상이면 각각 unpaired/ambiguous로 남긴다.
"""
from __future__ import annotations

from typing import Any

from .catalog import DAMAGES, PARTS
from .roi import RoiError, link_parts, source_bbox

DAMAGE_BY_RAW = {values[0].casefold(): code for code, values in DAMAGES.items()}
PART_BY_RAW = {values[0].casefold(): code for code, values in PARTS.items()}


def annotation_ref(annotation: dict[str, Any], ordinal: int) -> str:
    return str(annotation.get("id") or ordinal)


def damage_code(annotation: dict[str, Any]) -> str | None:
    raw = str(annotation.get("damage") or "").strip().casefold()
    return DAMAGE_BY_RAW.get(raw)


def part_code(annotation: dict[str, Any]) -> str | None:
    raw = str(annotation.get("part") or "").strip().casefold()
    return PART_BY_RAW.get(raw)


def valid_geometry(annotation: dict[str, Any]) -> bool:
    try:
        x, y, width, height = source_bbox(annotation)
    except (RoiError, TypeError, ValueError):
        return False
    return width > 0 and height > 0


def damage_part_roi_rows(
    document: dict[str, Any],
    min_overlap: float = 0.5,
) -> list[dict[str, Any]]:
    """같은 이미지 안의 damage annotation별 part 매칭 결과를 반환한다.

    ``min_overlap``은 threshold sensitivity 분석에서만 바꾼다. 운영 적재의
    기본값은 ``roi.PART_LINK_MIN_OVERLAP``와 같은 0.5다.
    """
    annotations = [
        value for value in (document.get("annotations") or [])
        if isinstance(value, dict)
    ]
    damage_rows = [
        (ordinal, annotation)
        for ordinal, annotation in enumerate(annotations, start=1)
        if damage_code(annotation) and valid_geometry(annotation)
    ]
    part_rows = [
        (ordinal, annotation)
        for ordinal, annotation in enumerate(annotations, start=1)
        if part_code(annotation) and valid_geometry(annotation)
    ]

    results: list[dict[str, Any]] = []
    part_annotations = [annotation for _, annotation in part_rows]
    for damage_ordinal, damage_annotation in damage_rows:
        links = link_parts(
            damage_annotation,
            part_annotations,
            min_overlap=min_overlap,
        )
        linked_codes = {
            part_code(annotation)
            for annotation, _ in links
            if part_code(annotation)
        }
        if len(linked_codes) == 1:
            matched = links[0][0]
            status = "PAIRED"
            matched_code = part_code(matched)
            matched_ref = next(
                annotation_ref(annotation, ordinal)
                for ordinal, annotation in part_rows if annotation is matched
            )
            match_score = links[0][1]
        elif len(linked_codes) > 1:
            status = "AMBIGUOUS"
            matched_code = None
            matched_ref = None
            match_score = links[0][1]
        else:
            status = "UNPAIRED"
            matched_code = None
            matched_ref = None
            match_score = None

        results.append({
            "damage_ref": annotation_ref(damage_annotation, damage_ordinal),
            "damage_type": damage_code(damage_annotation),
            "match_status": status,
            "part_code": matched_code,
            "part_ref": matched_ref,
            "match_score": match_score,
            "damage_geometry": source_bbox(damage_annotation),
        })
    return results
