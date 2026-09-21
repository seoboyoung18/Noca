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
from .estimate_service import EstimateService

if TYPE_CHECKING:
    from ..schemas.contracts import SearchRequest


class SearchService:
    def __init__(self, repository: VectorRepository, embedding_service: EmbeddingService,
                 *, pipeline_version_id: int, top_k: int,
                 enable_part_price_reference: bool = False,
                 estimate_service: EstimateService | None = None,
                 enable_yolo_estimate_references: bool = False,
                 yolo_estimate_candidate_k: int = 200,
                 yolo_estimate_min_cases: int = 5,
                 yolo_estimate_max_cases: int = 30) -> None:
        if yolo_estimate_min_cases < 1:
            raise ValueError("yolo_estimate_min_cases must be positive")
        if yolo_estimate_max_cases < yolo_estimate_min_cases:
            raise ValueError("yolo_estimate_max_cases must be at least yolo_estimate_min_cases")
        self._repository = repository
        self._embedding_service = embedding_service
        self._pipeline_version_id = pipeline_version_id
        self._top_k = top_k
        self._enable_part_price_reference = enable_part_price_reference
        self._estimate_service = estimate_service
        self._enable_yolo_estimate_references = enable_yolo_estimate_references
        self._yolo_estimate_candidate_k = yolo_estimate_candidate_k
        self._yolo_estimate_min_cases = yolo_estimate_min_cases
        self._yolo_estimate_max_cases = yolo_estimate_max_cases

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
                        is_strict_v2 = (
                            self._pipeline_version_id == 2
                            and searchability == "STRICT" and bool(part_code)
                        )
                        paired_v2 = is_strict_v2 and detection.get("pairStatus") == "PAIRED"
                        pool_enabled = paired_v2 and (
                            self._enable_part_price_reference or self._enable_yolo_estimate_references
                        )
                        search_limit = (
                            self._yolo_estimate_candidate_k if pool_enabled
                            else self._top_k
                        )
                        exclude_case_id = _query_case_id(detection, image_result)
                        search_kwargs = {
                            "vector": vector, "pipeline_version_id": self._pipeline_version_id,
                            "damage_type": damage_type,
                            "part_code": (str(part_code) if searchability == "STRICT" and part_code else None),
                            "model_id": request.vehicle.model_id, "limit": search_limit,
                        }
                        if exclude_case_id is not None:
                            search_kwargs["exclude_case_id"] = exclude_case_id
                        stage, hits = await asyncio.to_thread(
                            self._repository.search, **search_kwargs,
                        )
                        result = _result(
                            detection, stage, hits,
                            candidate_pool=(hits if pool_enabled else None),
                            include_part_price_candidates=self._enable_part_price_reference,
                        )
                        result.update(self._estimate_reference_fields(
                            detection, image_result, hits, paired_v2,
                        ))
                        if searchability == "STRICT" and part_code:
                            _merge_strict(strict, result)
                        else:
                            vector_only.append(result)
        return {"parts": list(strict.values()), "vectorOnly": vector_only}

    def _estimate_reference_fields(
        self, detection: Mapping[str, Any], image_result: Mapping[str, Any],
        hits: list[SearchHit], paired_v2: bool,
    ) -> dict[str, Any]:
        if not self._enable_yolo_estimate_references:
            return _empty_estimate_reference("FEATURE_DISABLED")
        if self._pipeline_version_id != 2:
            return _empty_estimate_reference("PIPELINE_NOT_V2")
        part_code = detection.get("partCode")
        if not paired_v2 or not part_code:
            return _empty_estimate_reference("QUERY_PART_UNRESOLVED")
        if self._estimate_service is None:
            return _empty_estimate_reference("ESTIMATE_SERVICE_UNAVAILABLE")
        candidates = [
            {"caseId": hit.case_id,
             "similarity": hit.vector_similarity if hit.vector_similarity is not None else hit.similarity,
             "corpusPartMatched": hit.corpus_part_matched,
             "corpusPartCode": hit.corpus_part_code,
             "corpusPartConfidence": hit.corpus_part_confidence,
             "corpusPartOverlap": hit.corpus_part_overlap}
            for hit in hits
            if hit.corpus_part_matched and hit.corpus_part_code == part_code
        ]
        valid = self._estimate_service.select_full_repair_reference_candidates(
            candidates, str(part_code), max_cases=self._yolo_estimate_max_cases,
        )
        if len(valid) < self._yolo_estimate_min_cases:
            result = _empty_estimate_reference("INSUFFICIENT_FULL_REPAIR_CASES")
            result["estimateReferenceCandidateCount"] = len(valid)
            result["estimateReferenceMinimumCaseCount"] = self._yolo_estimate_min_cases
            result["estimateReferenceMaximumCaseCount"] = self._yolo_estimate_max_cases
            return result
        ids = [int(candidate["caseId"]) for candidate in valid]
        visible_ids = {int(hit.case_id) for hit in hits[:self._top_k]}
        return {
            "estimateReferencedCaseIds": ids,
            "estimateReferenceReason": "YOLO_PART_MATCHED_FULL_REPAIR_POOL",
            "estimateReferenceCandidateCount": len(valid),
            "estimateReferenceMinimumCaseCount": self._yolo_estimate_min_cases,
            "estimateReferenceMaximumCaseCount": self._yolo_estimate_max_cases,
            "estimateReferenceVisibleCaseCount": len(set(ids) & visible_ids),
        }


def _damage_code(value: object) -> str:
    mapping = {
        "Scratched": "SCRATCHED", "Separated": "SEPARATED",
        "Crushed": "CRUSHED", "Breakage": "BREAKAGE",
    }
    if value not in mapping:
        raise ValueError(f"unsupported damageType: {value!r}")
    return mapping[str(value)]


def _result(detection: Mapping[str, Any], stage: str, hits: list[SearchHit],
            candidate_pool: list[SearchHit] | None = None,
            include_part_price_candidates: bool = False) -> dict[str, Any]:
    confidence = (detection.get("confidence") or {}).get("damage")
    result = {
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
             "repairYear": hit.repair_year, "itemTotal": hit.item_total,
             "corpusPartMatched": hit.corpus_part_matched, "corpusPartCode": hit.corpus_part_code,
             "corpusPartConfidence": hit.corpus_part_confidence, "corpusPartOverlap": hit.corpus_part_overlap,
             "vectorSimilarity": hit.vector_similarity, "rerankedSimilarity": hit.reranked_similarity,
             "rankingReason": hit.ranking_reason}
            for hit in hits[:10]
        ],
        **_empty_estimate_reference("FEATURE_DISABLED"),
    }
    if candidate_pool is not None and include_part_price_candidates:
        result["partPriceCandidateCases"] = [
            {"caseId": hit.case_id, "similarity": round(
                hit.vector_similarity if hit.vector_similarity is not None else hit.similarity, 6,
            ), "corpusPartMatched": hit.corpus_part_matched,
             "corpusPartCode": hit.corpus_part_code}
            for hit in candidate_pool
        ]
        result["partPriceCandidatePoolSize"] = len(candidate_pool)
    return result


def _empty_estimate_reference(reason: str) -> dict[str, Any]:
    return {
        "estimateReferencedCaseIds": [],
        "estimateReferenceReason": reason,
        "estimateReferenceCandidateCount": 0,
        "estimateReferenceMinimumCaseCount": 0,
        "estimateReferenceMaximumCaseCount": 0,
        "estimateReferenceVisibleCaseCount": 0,
    }


def _query_case_id(detection: Mapping[str, Any], image_result: Mapping[str, Any]) -> int | None:
    for value in (detection.get("caseId"), detection.get("case_id"),
                  image_result.get("caseId"), image_result.get("case_id")):
        try:
            if value is not None and str(value).isdigit():
                return int(value)
        except (TypeError, ValueError):
            continue
    return None


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
    if "partPriceCandidateCases" in result or "partPriceCandidateCases" in current:
        candidate_by_case = {
            int(candidate["caseId"]): candidate
            for candidate in current.get("partPriceCandidateCases", [])
        }
        for candidate in result.get("partPriceCandidateCases", []):
            case_id = int(candidate["caseId"])
            previous = candidate_by_case.get(case_id)
            if previous is None or candidate.get("similarity", 0) > previous.get("similarity", 0):
                candidate_by_case[case_id] = candidate
        current["partPriceCandidateCases"] = sorted(
            candidate_by_case.values(),
            key=lambda candidate: (-candidate.get("similarity", 0), candidate["caseId"]),
        )[:100]
        current["partPriceCandidatePoolSize"] = len(current["partPriceCandidateCases"])
    if "estimateReferencedCaseIds" in result:
        maximum = max(
            int(current.get("estimateReferenceMaximumCaseCount") or 0),
            int(result.get("estimateReferenceMaximumCaseCount") or 0),
        )
        # Legacy or test payloads without explicit metadata keep the old
        # visible-result cap. Runtime v2 results always provide their max.
        maximum = maximum or 10
        estimate_ids = list(dict.fromkeys(
            [int(case_id) for case_id in current.get("estimateReferencedCaseIds", [])]
            + [int(case_id) for case_id in result.get("estimateReferencedCaseIds", [])]
        ))[:maximum]
        current["estimateReferencedCaseIds"] = estimate_ids
        current["estimateReferenceCandidateCount"] = len(estimate_ids)
        current["estimateReferenceMinimumCaseCount"] = max(
            int(current.get("estimateReferenceMinimumCaseCount") or 0),
            int(result.get("estimateReferenceMinimumCaseCount") or 0),
        )
        current["estimateReferenceMaximumCaseCount"] = maximum
        visible_ids = {int(case["caseId"]) for case in current.get("cases", [])}
        current["estimateReferenceVisibleCaseCount"] = len(set(estimate_ids) & visible_ids)
        if estimate_ids:
            current["estimateReferenceReason"] = "YOLO_PART_MATCHED_FULL_REPAIR_POOL"
        elif current.get("estimateReferenceReason") == "FEATURE_DISABLED":
            current["estimateReferenceReason"] = result.get(
                "estimateReferenceReason", "INSUFFICIENT_FULL_REPAIR_CASES",
            )
