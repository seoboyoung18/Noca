"""Search similar cases from normalized detections without re-running YOLO."""
from __future__ import annotations

import asyncio
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING, Any, Mapping

from PIL import Image

from ..infrastructure.image_fetcher import download_image
from ..infrastructure.vector_repository import SearchHit, VectorRepository
from .embedding_service import EmbeddingService

if TYPE_CHECKING:
    from ..schemas.contracts import SearchRequest


class SearchService:
    def __init__(self, repository: VectorRepository, embedding_service: EmbeddingService,
                 *, pipeline_version_id: int, top_k: int) -> None:
        self._repository = repository
        self._embedding_service = embedding_service
        self._pipeline_version_id = pipeline_version_id
        self._top_k = top_k

    @property
    def repository(self) -> VectorRepository:
        return self._repository

    async def search(self, request: SearchRequest) -> dict[str, Any]:
        image_by_id = {image.image_id: image for image in request.images}
        strict: dict[tuple[str, str], dict[str, Any]] = {}
        vector_only: list[dict[str, Any]] = []
        with tempfile.TemporaryDirectory(prefix="a307-search-") as directory:
            target_dir = Path(directory)
            for image_result in request.image_results:
                image_id = int(image_result["imageId"])
                source = image_by_id.get(image_id)
                if source is None:
                    raise ValueError(f"imageResults references unknown imageId: {image_id}")
                image_path = await download_image(source.url, target_dir, image_id)
                with Image.open(image_path) as image:
                    image.load()
                    searchable_detections = [
                        detection for detection in image_result.get("detections", [])
                        if detection.get("searchability") != "EXCLUDED"
                    ]
                    vectors = await asyncio.to_thread(
                        self._embedding_service.embed_detections, image, searchable_detections,
                    )
                    for detection, vector in zip(searchable_detections, vectors):
                        searchability = detection.get("searchability")
                        damage_type = _damage_code(detection.get("damageType"))
                        part_code = detection.get("partCode")
                        stage, hits = await asyncio.to_thread(
                            self._repository.search,
                            vector=vector, pipeline_version_id=self._pipeline_version_id,
                            damage_type=damage_type,
                            part_code=(str(part_code) if searchability == "STRICT" and part_code else None),
                            model_id=request.vehicle.model_id, limit=self._top_k,
                        )
                        result = _result(detection, stage, hits)
                        if searchability == "STRICT" and part_code:
                            _merge_strict(strict, result)
                        else:
                            vector_only.append(result)
        return {"parts": list(strict.values()), "vectorOnly": vector_only}


def _damage_code(value: object) -> str:
    mapping = {
        "Scratched": "SCRATCHED", "Separated": "SEPARATED",
        "Crushed": "CRUSHED", "Breakage": "BREAKAGE",
    }
    if value not in mapping:
        raise ValueError(f"unsupported damageType: {value!r}")
    return mapping[str(value)]


def _result(detection: Mapping[str, Any], stage: str, hits: list[SearchHit]) -> dict[str, Any]:
    confidence = (detection.get("confidence") or {}).get("damage")
    return {
        "detectionId": detection["detectionId"],
        "partCode": detection.get("partCode"),
        "damageType": detection["damageType"],
        "confidence": confidence,
        "pairStatus": detection["pairStatus"],
        "searchability": detection["searchability"],
        "fallbackStage": stage,
        "searchHitCount": len(hits),
        "referencedCaseIds": [hit.case_id for hit in hits[:10]],
        "cases": [
            {"caseId": hit.case_id, "similarity": round(hit.similarity, 4),
             "repairYear": hit.repair_year, "itemTotal": hit.item_total}
            for hit in hits[:10]
        ],
    }


# Fallback stages ordered by how much they widen the candidate pool.
_STAGE_WIDTH = {"MODEL": 0, "PRICE_TIER": 1, "ALL": 2}


def _merge_strict(groups: dict[tuple[str, str], dict[str, Any]], result: dict[str, Any]) -> None:
    key = (str(result["partCode"]), str(result["damageType"]))
    current = groups.get(key)
    if current is None:
        result["detectionIds"] = [result.pop("detectionId")]
        groups[key] = result
        return
    current["detectionIds"].append(result["detectionId"])
    confidences = [value for value in (current.get("confidence"), result.get("confidence"))
                   if value is not None]
    current["confidence"] = max(confidences) if confidences else None
    # More relaxed fallback wins: a merged group must not claim a narrower stage
    # than the widest one that actually contributed cases. Stages widen in the
    # order MODEL -> PRICE_TIER -> ALL, so mixing MODEL and PRICE_TIER hits has
    # to report PRICE_TIER — reporting MODEL would show "동일 차종 사례" for
    # evidence that came from the whole price band.
    if _STAGE_WIDTH[result["fallbackStage"]] > _STAGE_WIDTH[current["fallbackStage"]]:
        current["fallbackStage"] = result["fallbackStage"]
    by_case = {case["caseId"]: case for case in current["cases"]}
    for case in result["cases"]:
        if case["caseId"] not in by_case or case["similarity"] > by_case[case["caseId"]]["similarity"]:
            by_case[case["caseId"]] = case
    merged = sorted(by_case.values(), key=lambda case: (-case["similarity"], case["caseId"]))[:10]
    current["cases"] = merged
    current["referencedCaseIds"] = [case["caseId"] for case in merged]
    current["searchHitCount"] = len(by_case)
