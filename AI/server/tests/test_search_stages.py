"""완화 단계(MODEL → PRICE_TIER → ALL) 순서와 fallthrough 계약.

DB 없이 돈다. psycopg 를 가짜 모듈로 갈아끼우고 ``_query`` 를 기록기로 바꿔,
어떤 범위로 몇 번 조회했는지만 본다.

**가격대는 요청이 아니라 DB 에서 온다.** 백엔드는 carClass 만 보내고 tier 를
모른다. 그래서 이 테스트의 가짜 커서는 vehicle_model 조회에 답하는 역할까지
한다 — 그 값이 실제로 조회 조건에 실리는지가 이 파일의 핵심 검증이다.
"""

from __future__ import annotations

import sys
import types
import unittest

from app.infrastructure.vector_repository import SearchHit, VectorRepository

_MODEL_ROW = {"model_version_id": 1, "dimension": 768}


class _Cursor:
    """질의 내용을 보고 답을 고르는 최소 스텁. 두 종류의 SELECT 만 온다."""

    price_tier: str | None = "P3"

    def __enter__(self): return self
    def __exit__(self, *exc): return False

    def execute(self, sql, *args, **kwargs):
        self._last = str(sql)
        return None

    def fetchone(self):
        if "vehicle_model" in getattr(self, "_last", ""):
            return {"price_tier": type(self).price_tier}
        return _MODEL_ROW


class _Connection:
    def __enter__(self): return self
    def __exit__(self, *exc): return False
    def cursor(self): return _Cursor()


def _install_fake_psycopg() -> None:
    psycopg = types.ModuleType("psycopg")
    psycopg.connect = lambda *args, **kwargs: _Connection()
    rows = types.ModuleType("psycopg.rows")
    rows.dict_row = object()
    psycopg.rows = rows
    sys.modules["psycopg"] = psycopg
    sys.modules["psycopg.rows"] = rows


def _hit(case_id: int = 1) -> SearchHit:
    return SearchHit(case_id=case_id, similarity=0.9, repair_year=2020, item_total=100000)


class SearchStageTest(unittest.TestCase):
    def setUp(self) -> None:
        self._saved_modules = {k: sys.modules.get(k) for k in ("psycopg", "psycopg.rows")}
        _install_fake_psycopg()
        _Cursor.price_tier = "P3"
        self._original_query = VectorRepository.__dict__["_query"]
        self.calls: list[dict] = []
        self.plan: list[list[SearchHit]] = []

        def recorder(cursor, **kwargs):
            self.calls.append(kwargs)
            return self.plan.pop(0) if self.plan else []

        VectorRepository._query = staticmethod(recorder)
        self.repo = VectorRepository("postgresql://stub",
                                     expected_model_name="dinov2", expected_model_version="v1")

    def tearDown(self) -> None:
        VectorRepository._query = self._original_query
        for name, module in self._saved_modules.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    def _search(self, **kwargs):
        defaults = dict(vector=[0.1] * 768, pipeline_version_id=3,
                        damage_type="SCRATCHED", part_code="REAR_BUMPER", limit=10)
        defaults.update(kwargs)
        return self.repo.search(**defaults)

    def test_MODEL_단계가_맞으면_거기서_멈춘다(self):
        self.plan = [[_hit()]]
        stage, hits = self._search(model_id=41)
        self.assertEqual(stage, "MODEL")
        self.assertEqual(len(hits), 1)
        self.assertEqual(len(self.calls), 1, "MODEL 이 결과를 주면 더 완화하지 않는다")
        self.assertEqual(self.calls[0]["model_id"], 41)
        self.assertIsNone(self.calls[0]["price_tier"], "MODEL 단계는 가격대를 함께 걸지 않는다")

    def test_MODEL_이_비면_PRICE_TIER_로_내려간다(self):
        self.plan = [[], [_hit()]]
        stage, _ = self._search(model_id=41)
        self.assertEqual(stage, "PRICE_TIER")
        self.assertEqual(len(self.calls), 2)
        self.assertIsNone(self.calls[1]["model_id"])
        self.assertEqual(self.calls[1]["price_tier"], "P3")

    def test_가격대는_요청이_아니라_vehicle_model_에서_읽는다(self):
        # 호출자는 tier 를 넘기지 않는다. 마스터 값이 바뀌면 조회 조건도 따라 바뀐다
        _Cursor.price_tier = "P1"
        self.plan = [[], [_hit()]]
        self._search(model_id=41)
        self.assertEqual(self.calls[1]["price_tier"], "P1")

    def test_끝까지_비면_ALL_로_내려간다(self):
        self.plan = [[], [], [_hit()]]
        stage, _ = self._search(model_id=41)
        self.assertEqual(stage, "ALL")
        self.assertEqual([c["model_id"] for c in self.calls], [41, None, None])
        self.assertEqual([c["price_tier"] for c in self.calls], [None, "P3", None])

    def test_model_id_가_없으면_완화할_축이_없어_ALL_하나만_돈다(self):
        # 가격대는 model_id 로 찾는다. 차량을 특정하지 못하면 좁힐 근거가 없다 —
        # car_class 로 좁히던 예전 동작과 다른 지점이다
        self.plan = [[_hit()]]
        stage, _ = self._search(model_id=None)
        self.assertEqual(stage, "ALL")
        self.assertEqual(len(self.calls), 1)

    def test_가격대_미측정_차량은_PRICE_TIER_단계를_건너뛴다(self):
        # 측정하지 못한 것이 '중간 가격'이라는 근거는 아니다. 추측하지 않고 넘긴다
        _Cursor.price_tier = None
        self.plan = [[], [_hit()]]
        stage, _ = self._search(model_id=41)
        self.assertEqual(stage, "ALL")
        self.assertEqual(len(self.calls), 2)
        self.assertEqual([c["price_tier"] for c in self.calls], [None, None])

    def test_모든_단계가_비면_ALL_과_빈_목록(self):
        self.plan = []
        stage, hits = self._search(model_id=41)
        self.assertEqual(stage, "ALL")
        self.assertEqual(hits, [])
        self.assertEqual(len(self.calls), 3)

    def test_부품_손상_조건은_모든_단계에_유지된다(self):
        self.plan = [[], [], [_hit()]]
        self._search(model_id=41)
        for call in self.calls:
            self.assertEqual(call["part_code"], "REAR_BUMPER")
            self.assertEqual(call["damage_type"], "SCRATCHED")
            self.assertEqual(call["limit"], 10)


if __name__ == "__main__":
    unittest.main()
