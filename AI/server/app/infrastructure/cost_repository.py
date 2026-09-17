"""Read-only repair-case cost gateway for MVP real-time estimate calculation.

`CostCaseRepository` is a Protocol so `EstimateService` can be tested against
`InMemoryCostCaseRepository` today and swapped for a real Postgres-backed
implementation (mirroring `VectorRepository`'s psycopg style) later without
touching the service.
"""
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
