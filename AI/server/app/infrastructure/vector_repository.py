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


def vector_literal(vector: Sequence[float]) -> str:
    if len(vector) != 768:
        raise VectorSearchError(f"query vector must have 768 values, got {len(vector)}")
    return "[" + ",".join(f"{float(value):.8g}" for value in vector) + "]"


class VectorRepository:
    def __init__(self, dsn: str, *, expected_model_name: str, expected_model_version: str) -> None:
        self._dsn = dsn
        self._expected_model_name = expected_model_name
        self._expected_model_version = expected_model_version

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
               limit: int, model_id: int | None = None) -> tuple[str, list[SearchHit]]:
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
                            part_code=part_code, limit=limit, **scope,
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
               limit: int, model_id: int | None = None) -> list[SearchHit]:
        predicates = [
            "f.is_searchable",
            "f.pipeline_version_id = %(pipeline_version_id)s",
            "e.model_version_id = %(model_version_id)s",
            "f.damage_type = %(damage_type)s",
        ]
        params = {
            "vector": vector, "pipeline_version_id": pipeline_version_id,
            "model_version_id": model_version_id, "damage_type": damage_type, "limit": limit,
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
        where = " AND ".join(predicates)
        cursor.execute(f"""
            WITH candidate AS (
                SELECT DISTINCT ON (c.case_id)
                       c.case_id, c.repair_year, c.total_cost,
                       e.embedding <=> %(vector)s::vector AS distance
                  FROM repair_case_roi_embedding e
                  JOIN repair_case_damage_feature f ON f.damage_feature_id = e.damage_feature_id
                  JOIN repair_case_image i ON i.case_image_id = f.case_image_id
                  JOIN repair_case c ON c.case_id = i.case_id
                 WHERE {where}
                 ORDER BY c.case_id, e.embedding <=> %(vector)s::vector, f.damage_feature_id
            )
            SELECT case_id, repair_year, total_cost, 1 - distance AS similarity
              FROM candidate
             ORDER BY distance, case_id
             LIMIT %(limit)s
        """, params)
        return [
            SearchHit(case_id=int(row["case_id"]), similarity=float(row["similarity"]),
                      repair_year=(int(row["repair_year"]) if row["repair_year"] is not None else None),
                      item_total=(int(row["total_cost"]) if row["total_cost"] is not None else None))
            for row in cursor.fetchall()
        ]
