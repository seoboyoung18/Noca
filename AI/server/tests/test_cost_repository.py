from __future__ import annotations

import sys
import types

from app.infrastructure.cost_repository import PostgresCostCaseRepository


class _Cursor:
    def __init__(self, rows: list[dict]) -> None:
        self.rows = rows
        self.query = None
        self.params = None

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        return None

    def execute(self, query, params) -> None:
        self.query = query
        self.params = params

    def fetchall(self):
        return self.rows


class _Connection:
    def __init__(self, cursor: _Cursor) -> None:
        self._cursor = cursor

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        return None

    def cursor(self):
        return self._cursor


def test_postgres_repository_fetches_joined_cost_rows(monkeypatch):
    cursor = _Cursor([{
        "case_id": 101,
        "source": "AIHUB_SC",
        "part_code": "FRONT_BUMPER",
        "line_type": "WORK",
        "work_code": "COATING",
        "assessment_status": "APPROVED",
        "part_cost": 1,
        "paint_material_cost": 2,
        "labor_cost": 3,
        "post_adjustment_part_cost": 4,
        "post_adjustment_labor_cost": 5,
    }])
    connection = _Connection(cursor)
    psycopg = types.ModuleType("psycopg")
    psycopg.connect = lambda *args, **kwargs: connection
    psycopg_rows = types.ModuleType("psycopg.rows")
    psycopg_rows.dict_row = object()
    monkeypatch.setitem(sys.modules, "psycopg", psycopg)
    monkeypatch.setitem(sys.modules, "psycopg.rows", psycopg_rows)

    rows = PostgresCostCaseRepository("postgresql://test").fetch_case_items(
        [101, 101], [" FRONT_BUMPER ", "FRONT_BUMPER"],
    )

    assert rows[0].case_id == 101
    assert rows[0].source == "AIHUB_SC"
    assert rows[0].post_adjustment_part_cost == 4
    assert rows[0].post_adjustment_labor_cost == 5
    assert "JOIN repair_case rc" in cursor.query
    assert "case_id = ANY" in cursor.query
    assert cursor.params == {
        "case_ids": [101],
        "part_codes": ["FRONT_BUMPER"],
    }


def test_postgres_repository_does_not_connect_for_empty_filter(monkeypatch):
    psycopg = types.ModuleType("psycopg")
    psycopg.connect = lambda *_args, **_kwargs: (_ for _ in ()).throw(
        AssertionError("empty filters must not open a database connection"))
    monkeypatch.setitem(sys.modules, "psycopg", psycopg)

    rows = PostgresCostCaseRepository("postgresql://test").fetch_case_items([], ["PART_A"])

    assert rows == []
