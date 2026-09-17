"""Read-only repair-case cost gateways for MVP real-time estimate calculation."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence


@dataclass(frozen=True)
class CostCaseRow:
    """One `repair_case_item` row joined with its case's `source`.

    Column names follow `Docs/Erd/A307_ddl_final.sql` exactly.
    """

    case_id: int
    source: str  # AIHUB_AS / AIHUB_SC / SERVICE
    part_code: str
    line_type: str  # WORK / PART_PRICE / REFERENCE_PRICE / ANCILLARY
    work_code: str | None
    assessment_status: str | None  # None / APPROVED / NOT_APPROVED
    part_cost: int | None
    paint_material_cost: int | None
    labor_cost: int | None
    post_adjustment_part_cost: int | None
    post_adjustment_labor_cost: int | None


class CostCaseRepository(Protocol):
    def fetch_case_items(
        self, case_ids: Sequence[int], part_codes: Sequence[str],
    ) -> list[CostCaseRow]:
        """Return every `repair_case_item` row whose case_id and part_code are both
        in the given sets (cross-product pre-filter, same as update.md §4.1's SQL —
        callers group the flat result by (case_id, part_code) themselves)."""
        ...


class CostRepositoryError(RuntimeError):
    """Raised when the read-only cost database cannot be queried."""


class PostgresCostCaseRepository:
    """Read-only Postgres implementation of :class:`CostCaseRepository`.

    The estimate service owns row filtering and cost-policy interpretation. This
    gateway only fetches the raw cost rows for the referenced cases and parts,
    joined with ``repair_case.source`` for source-specific column selection.
    """

    def __init__(self, dsn: str, *, connect_timeout: int = 2) -> None:
        self._dsn = dsn
        self._connect_timeout = connect_timeout

    def fetch_case_items(
        self, case_ids: Sequence[int], part_codes: Sequence[str],
    ) -> list[CostCaseRow]:
        # PostgreSQL's ANY(array) handles the cross-product filter in one query.
        # Avoid opening a connection when there is nothing to look up.
        normalized_case_ids = list(dict.fromkeys(int(case_id) for case_id in case_ids))
        normalized_part_codes = list(dict.fromkeys(
            str(part_code).strip() for part_code in part_codes
        ))
        if not normalized_case_ids or not normalized_part_codes:
            return []
        if not self._dsn:
            raise CostRepositoryError("DATABASE_URL is not configured")

        try:
            import psycopg
            from psycopg.rows import dict_row

            with psycopg.connect(
                self._dsn,
                connect_timeout=self._connect_timeout,
                row_factory=dict_row,
            ) as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT rci.case_id,
                               rc.source,
                               rci.part_code,
                               rci.line_type,
                               rci.work_code,
                               rci.assessment_status,
                               rci.part_cost,
                               rci.paint_material_cost,
                               rci.labor_cost,
                               rci.post_adjustment_part_cost,
                               rci.post_adjustment_labor_cost
                          FROM repair_case_item rci
                          JOIN repair_case rc ON rc.case_id = rci.case_id
                         WHERE rci.case_id = ANY(%(case_ids)s::bigint[])
                           AND rci.part_code = ANY(%(part_codes)s::varchar[])
                         ORDER BY rci.case_id, rci.case_item_id
                        """,
                        {
                            "case_ids": normalized_case_ids,
                            "part_codes": normalized_part_codes,
                        },
                    )
                    return [self._to_row(row) for row in cursor.fetchall()]
        except CostRepositoryError:
            raise
        except Exception as exc:
            raise CostRepositoryError("repair-case cost query failed") from exc

    @staticmethod
    def _to_row(row: dict) -> CostCaseRow:
        return CostCaseRow(
            case_id=int(row["case_id"]),
            source=str(row["source"]),
            part_code=str(row["part_code"]),
            line_type=str(row["line_type"]),
            work_code=row["work_code"],
            assessment_status=row["assessment_status"],
            part_cost=_int_or_none(row["part_cost"]),
            paint_material_cost=_int_or_none(row["paint_material_cost"]),
            labor_cost=_int_or_none(row["labor_cost"]),
            post_adjustment_part_cost=_int_or_none(row["post_adjustment_part_cost"]),
            post_adjustment_labor_cost=_int_or_none(row["post_adjustment_labor_cost"]),
        )


def _int_or_none(value: object) -> int | None:
    return None if value is None else int(value)


class InMemoryCostCaseRepository:
    """Test/dev double: serves rows from an in-memory list instead of Postgres."""

    def __init__(self, rows: Sequence[CostCaseRow]) -> None:
        self._rows = list(rows)

    def fetch_case_items(
        self, case_ids: Sequence[int], part_codes: Sequence[str],
    ) -> list[CostCaseRow]:
        case_id_set = set(case_ids)
        part_code_set = set(part_codes)
        return [
            row for row in self._rows
            if row.case_id in case_id_set and row.part_code in part_code_set
        ]
