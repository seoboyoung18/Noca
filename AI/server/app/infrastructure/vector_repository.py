"""Read-only pgvector gateway for similar repair-case ROI search."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence


class VectorSearchError(RuntimeError):
    pass


@dataclass(frozen=True)
class SearchHit:
    case_id: int
    similarity: float
    repair_year: int | None
    item_total: int | None
    corpus_part_matched: bool = False
    corpus_part_code: str | None = None
    corpus_part_confidence: float | None = None
    corpus_part_overlap: float | None = None
    vector_similarity: float | None = None
    reranked_similarity: float | None = None
    ranking_reason: str = "VECTOR_ONLY_FALLBACK"
    source_image_ref: str | None = None


def vector_literal(vector: Sequence[float]) -> str:
    if len(vector) != 768:
        raise VectorSearchError(f"query vector must have 768 values, got {len(vector)}")
    return "[" + ",".join(f"{float(value):.8g}" for value in vector) + "]"


class VectorRepository:
    def __init__(self, dsn: str, *, expected_model_name: str, expected_model_version: str,
                 yolo_corpus_part_boost: float = 0.03,
                 yolo_part_model_name: str = "vehicle-part-detection",
                 yolo_part_model_version: str = "35ep") -> None:
        self._dsn = dsn
        self._expected_model_name = expected_model_name
        self._expected_model_version = expected_model_version
        self._yolo_corpus_part_boost = yolo_corpus_part_boost
        self._yolo_part_model_name = yolo_part_model_name
        self._yolo_part_model_version = yolo_part_model_version

    def is_reachable(self) -> bool:
        """Perform a minimal read-only connectivity probe for the health endpoint."""
        if not self._dsn:
            return False
        try:
            import psycopg
            with psycopg.connect(self._dsn, connect_timeout=2) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
                    return cursor.fetchone() == (1,)
        except Exception:
            return False

    def search(self, *, vector: Sequence[float], pipeline_version_id: int,
               damage_type: str, part_code: str | None,
               limit: int, model_id: int | None = None,
               exclude_case_id: int | None = None) -> tuple[str, list[SearchHit]]:
        """Search similar ROIs, relaxing the vehicle scope only when a stage returns nothing.

        Stages are MODEL -> PRICE_TIER -> ALL.

        car_class is deliberately not in this ladder. It is a size and displacement
        class, not a cost class -- measured on the estimate corpus the Compact /
        Mid-size / Full-size cost indices are 1.01 / 0.99 / 1.05 and their ranges
        overlap completely, so "same car_class" bought almost no cost similarity.
        price_tier is a quartile of that measured index, so a tier is by construction
        a band of comparable repair cost. See Docs/Erd/A307_VEHICLE_AXIS.md.

        The requester's tier is looked up here from vehicle_model instead of being
        taken from the request. The backend contract still sends carClass and knows
        nothing about tiers, and which tier a model sits in is our measurement, not
        the caller's input.

        Both narrowing stages depend on repair_case.model_id and price_tier being
        backfilled. Until then they return nothing and every search falls through to
        ALL, which is worse than today's car_class filter -- deploy this after the
        backfill, not before. See pipeline/jobs/ingestion/backfill_vehicle_model.py
        and pipeline/sql/012,013.
        """
        if not self._dsn:
            raise VectorSearchError("DATABASE_URL is not configured")
        try:
            import psycopg
            from psycopg.rows import dict_row
            with psycopg.connect(self._dsn, row_factory=dict_row) as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """SELECT model_version_id, dimension
                             FROM embedding_model_version
                            WHERE is_active
                              AND model_name = %s
                              AND version = %s""",
                        (self._expected_model_name, self._expected_model_version),
                    )
                    model = cursor.fetchone()
                    if not model or int(model["dimension"]) != 768:
                        raise VectorSearchError("active DINOv2 embedding model is unavailable or incompatible")
                    price_tier = self._price_tier(cursor, model_id)
                    cursor.execute("SELECT params->>'image_source' AS image_source FROM feature_pipeline_version WHERE pipeline_version_id=%s", (pipeline_version_id,))
                    pipeline = cursor.fetchone()
                    v2_yolo_rerank = bool(pipeline and pipeline.get("image_source") == "DAMAGE" and part_code)
                    stages: list[tuple[str, dict[str, int | str | None]]] = []
                    if model_id is not None:
                        stages.append(("MODEL", {"model_id": model_id, "price_tier": None}))
                    if price_tier:
                        stages.append(("PRICE_TIER", {"model_id": None, "price_tier": price_tier}))
                    stages.append(("ALL", {"model_id": None, "price_tier": None}))
                    for stage, scope in stages:
                        hits = self._query(
                            cursor, vector=vector_literal(vector), model_version_id=int(model["model_version_id"]),
                            pipeline_version_id=pipeline_version_id, damage_type=damage_type,
                            part_code=(None if v2_yolo_rerank else part_code), limit=limit,
                            yolo_query_part_code=(part_code if v2_yolo_rerank else None),
                            yolo_boost=self._yolo_corpus_part_boost,
                            yolo_model_name=self._yolo_part_model_name,
                            yolo_model_version=self._yolo_part_model_version, **scope,
                            exclude_case_id=exclude_case_id,
                        )
                        if hits:
                            return stage, hits
                    return "ALL", []
        except VectorSearchError:
            raise
        except Exception as exc:
            raise VectorSearchError("pgvector search failed") from exc

    @staticmethod
    def _price_tier(cursor, model_id: int | None) -> str | None:
        """The requested vehicle's cost band, or None when we have not measured it.

        A missing tier skips the PRICE_TIER stage rather than guessing one: an
        unmeasured model is not evidence that it is mid-priced.
        """
        if model_id is None:
            return None
        cursor.execute(
            "SELECT price_tier FROM vehicle_model WHERE model_id = %s", (model_id,))
        row = cursor.fetchone()
        return row["price_tier"] if row else None

    @staticmethod
    def _query(cursor, *, vector: str, model_version_id: int, pipeline_version_id: int,
               damage_type: str, part_code: str | None, price_tier: str | None,
               limit: int, model_id: int | None = None, yolo_query_part_code: str | None = None,
               yolo_boost: float = 0.0, yolo_model_name: str = "vehicle-part-detection",
               yolo_model_version: str = "35ep", exclude_case_id: int | None = None) -> list[SearchHit]:
        predicates = [
            "f.is_searchable",
            "f.pipeline_version_id = %(pipeline_version_id)s",
            "e.model_version_id = %(model_version_id)s",
            "f.damage_type = %(damage_type)s",
        ]
        params = {
            "vector": vector, "pipeline_version_id": pipeline_version_id,
            "model_version_id": model_version_id, "damage_type": damage_type, "limit": limit,
            "pool_limit": max(limit * 10, 100), "yolo_part_code": yolo_query_part_code,
            "yolo_boost": yolo_boost, "yolo_model_name": yolo_model_name, "yolo_model_version": yolo_model_version,
            "exclude_case_id": exclude_case_id,
        }
        if part_code is not None:
            predicates.append("f.part_code = %(part_code)s")
            params["part_code"] = part_code
        if model_id is not None:
            # ix_rc_model is a partial index, so NULL model_id rows are skipped outright
            predicates.append("c.model_id = %(model_id)s")
            params["model_id"] = model_id
        if price_tier is not None:
            # ix_rc_tier is a partial index, so NULL price_tier rows are skipped outright
            predicates.append("c.price_tier = %(price_tier)s")
            params["price_tier"] = price_tier
        if exclude_case_id is not None:
            predicates.append("c.case_id <> %(exclude_case_id)s")
        where = " AND ".join(predicates)
        if yolo_query_part_code is None:
            cursor.execute(f"""
            WITH candidate AS (SELECT DISTINCT ON (c.case_id) c.case_id, c.repair_year, c.total_cost, i.source_image_ref,
                       e.embedding <=> %(vector)s::vector AS distance
                  FROM repair_case_roi_embedding e
                  JOIN repair_case_damage_feature f ON f.damage_feature_id = e.damage_feature_id
                  JOIN repair_case_image i ON i.case_image_id = f.case_image_id
                  JOIN repair_case c ON c.case_id = i.case_id
                 WHERE {where}
                 ORDER BY c.case_id, e.embedding <=> %(vector)s::vector, f.damage_feature_id
            )
            SELECT case_id, repair_year, total_cost, source_image_ref, 1 - distance AS similarity
              FROM candidate
             ORDER BY distance, case_id
             LIMIT %(limit)s
        """, params)
        else:
            # v2: broad vector pool first; only a PAIRED primary candidate of the configured
            # model can improve ranking. Missing/ambiguous evidence remains vector-only.
            cursor.execute(f"""
            WITH pool AS (
              SELECT c.case_id, c.repair_year, c.total_cost, i.source_image_ref, f.damage_feature_id,
                     e.embedding <=> %(vector)s::vector AS distance,
                     pc.part_code AS corpus_part_code, pc.part_confidence, pc.overlap_score,
                     (pc.part_code = %(yolo_part_code)s AND inf.image_part_inference_id IS NOT NULL) AS corpus_part_matched
                FROM repair_case_roi_embedding e
                JOIN repair_case_damage_feature f ON f.damage_feature_id=e.damage_feature_id
                JOIN repair_case_image i ON i.case_image_id=f.case_image_id
                JOIN repair_case c ON c.case_id=i.case_id
                LEFT JOIN repair_case_damage_feature_part_mapping pm
                  ON pm.damage_feature_id=f.damage_feature_id AND pm.mapping_status='PAIRED'
                LEFT JOIN repair_case_image_part_inference inf
                  ON inf.image_part_inference_id=pm.image_part_inference_id
                 AND inf.run_status='SUCCEEDED' AND inf.part_model_name=%(yolo_model_name)s
                 AND inf.part_model_version=%(yolo_model_version)s
                LEFT JOIN repair_case_damage_feature_part_candidate pc
                  ON pc.candidate_id=pm.primary_candidate_id AND pc.is_primary
                 AND inf.image_part_inference_id IS NOT NULL
               WHERE {where}
               ORDER BY distance, f.damage_feature_id LIMIT %(pool_limit)s
            ), ranked AS (
              SELECT *, distance - CASE WHEN corpus_part_matched THEN %(yolo_boost)s ELSE 0 END AS rerank_distance,
                     row_number() over (partition by case_id order by distance - CASE WHEN corpus_part_matched THEN %(yolo_boost)s ELSE 0 END, distance, case_id) AS rn
              FROM pool
            ) SELECT case_id, repair_year, total_cost, source_image_ref, 1-distance AS vector_similarity,
                     1-rerank_distance AS reranked_similarity, corpus_part_matched, corpus_part_code,
                     part_confidence, overlap_score
                FROM ranked WHERE rn=1 ORDER BY rerank_distance, distance, case_id LIMIT %(limit)s
            """, params)
        return [
            SearchHit(case_id=int(row["case_id"]), similarity=float(row.get("reranked_similarity", row.get("similarity"))),
                      repair_year=(int(row["repair_year"]) if row["repair_year"] is not None else None),
                      item_total=(int(row["total_cost"]) if row["total_cost"] is not None else None),
                      corpus_part_matched=bool(row.get("corpus_part_matched", False)), corpus_part_code=row.get("corpus_part_code"),
                      corpus_part_confidence=(float(row["part_confidence"]) if row.get("part_confidence") is not None else None),
                      corpus_part_overlap=(float(row["overlap_score"]) if row.get("overlap_score") is not None else None),
                      vector_similarity=float(row.get("vector_similarity", row.get("similarity"))),
                      reranked_similarity=float(row.get("reranked_similarity", row.get("similarity"))),
                      ranking_reason=("YOLO_PART_BOOSTED" if row.get("corpus_part_matched") else "VECTOR_ONLY_FALLBACK"),
                      source_image_ref=row.get("source_image_ref"))
            for row in cursor.fetchall()
        ]
