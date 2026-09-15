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
               damage_type: str, part_code: str | None, car_class: str | None,
               limit: int) -> tuple[str, list[SearchHit]]:
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
                    filters = [("CAR_CLASS", car_class)] if car_class else []
                    filters.append(("ALL", None))
                    for stage, selected_class in filters:
                        hits = self._query(
                            cursor, vector=vector_literal(vector), model_version_id=int(model["model_version_id"]),
                            pipeline_version_id=pipeline_version_id, damage_type=damage_type,
                            part_code=part_code, car_class=selected_class, limit=limit,
                        )
                        if hits:
                            return stage, hits
                    return "ALL", []
        except VectorSearchError:
            raise
        except Exception as exc:
            raise VectorSearchError("pgvector search failed") from exc

    @staticmethod
    def _query(cursor, *, vector: str, model_version_id: int, pipeline_version_id: int,
               damage_type: str, part_code: str | None, car_class: str | None,
               limit: int) -> list[SearchHit]:
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
        if car_class is not None:
            predicates.append("c.car_class = %(car_class)s")
            params["car_class"] = car_class
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
