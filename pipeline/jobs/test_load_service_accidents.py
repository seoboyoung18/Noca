"""서비스 사고 증분 적재(S15P21A307-223) 테스트.

두 묶음이다.

``ContractTest``
    DB 없이 항상 돈다. SQL 본문이 계약을 지키는지 본다 — 적재 대상 조건, 스냅샷
    컬럼 사용, 쓰지 않기로 한 테이블을 건드리지 않는지.

``ServiceAccidentLoadDbTest``
    실제 PostgreSQL 이 있을 때만 돈다(``A307_TEST_DSN``). 멱등·증분처럼 **행동**으로만
    확인되는 것을 본다. SQL 문자열 단언으로는 "두 번 돌려도 하나" 를 증명할 수 없다.
    백엔드가 ``@EnabledIf("localPostgresRunning")`` 으로 같은 일을 하는 것과 같은 방식이다.

    실행:
        A307_TEST_DSN="postgresql://.../a307_test" python -m pytest jobs/test_load_service_accidents.py -q

    각 테스트는 자기 트랜잭션 안에서만 쓰고 끝에 rollback 한다. DB 에 남는 것이 없다.
"""

import os
import unittest

from load_service_accidents import (
    ELIGIBLE_PREVIEW_SQL,
    EXTERNAL_REF_PREFIX,
    SOURCE,
    external_ref,
    preview_service_cases,
    upsert_service_cases,
    upsert_sql,
)

DSN = os.environ.get("A307_TEST_DSN")


class ContractTest(unittest.TestCase):
    """DB 없이 도는 계약 검사."""

    def test_external_ref_uses_svc_prefix(self):
        self.assertEqual(external_ref(41), "svc-41")
        self.assertEqual(EXTERNAL_REF_PREFIX, "svc-")

    def test_external_ref_fits_varchar_50(self):
        # repair_case.external_ref 는 VARCHAR(50) 이다. bigint 최대값이어도 남는다.
        self.assertLessEqual(len(external_ref(9223372036854775807)), 50)

    def test_svc_prefix_breaks_the_aihub_source_mapping_on_purpose(self):
        # svc- 행이 AI-Hub 경로로 잘못 흘러들면 조용히 틀리는 대신 멈춰야 한다.
        from load_search_data import source_for_case

        with self.assertRaises(KeyError):
            source_for_case(external_ref(1))

    def test_sample_sql_builder_rejects_unknown_prefix(self):
        # 같은 안전장치가 build_search_sample_sql 에도 있어야 한다.
        from build_search_sample_sql import source_for_case

        self.assertEqual(source_for_case("as-1"), "AIHUB_AS")
        self.assertEqual(source_for_case("sc-1"), "AIHUB_SC")
        with self.assertRaises(KeyError):
            source_for_case("svc-1")

    def test_only_accidents_with_actual_repair_cost_are_loaded(self):
        for sql in (upsert_sql(limit=None), ELIGIBLE_PREVIEW_SQL):
            self.assertIn("actual_repair_cost IS NOT NULL", sql)

    def test_only_approved_accidents_are_loaded(self):
        """S15P21A307-353 — 검수에서 승인된 건만 재학습 데이터로 간다.

        전에는 actual_repair_cost 만 봤다. 그래서 관리자가 반려한 사고도 그대로 적재됐고,
        S15P21A307-352 가 만든 승인·반려가 적재에 아무 영향이 없었다.

        EXISTS 가 거짓이면 빠지므로 **반려뿐 아니라 미검수(행 없음)도 함께 빠진다.**
        """
        for sql in (upsert_sql(limit=None), ELIGIBLE_PREVIEW_SQL):
            self.assertIn("accident_review", sql)
            self.assertIn("'APPROVED'", sql)
            self.assertIn("ar.accident_id = a.accident_id", sql)

    def test_source_is_fixed_to_service(self):
        # 값은 SQL 에 박지 않고 바인딩한다. 그래서 SQL 본문이 아니라 파라미터를 본다.
        from load_service_accidents import _params

        self.assertEqual(SOURCE, "SERVICE")
        self.assertIn("%(source)s", upsert_sql(limit=None))
        self.assertEqual(_params(None)["source"], "SERVICE")
        self.assertEqual(_params(None)["prefix"], "svc-")

    def test_snapshot_columns_are_the_only_vehicle_source(self):
        sql = upsert_sql(limit=None)
        for column in ("snapshot_manufacturer", "snapshot_model_name",
                       "snapshot_car_class", "snapshot_model_year", "snapshot_model_id"):
            self.assertIn(column, sql)
        # 마스터를 다시 조회해 덮어쓰면 접수 당시 조건이 바뀐다. vehicle_model 은
        # model_id FK 가 살아 있는지 확인하는 용도로만 조인한다.
        self.assertNotIn("vm.manufacturer", sql)
        self.assertNotIn("vm.model_name", sql)
        self.assertNotIn("vm.car_class", sql)
        self.assertNotIn("JOIN vehicle v", sql)

    def test_estimate_tables_are_never_read(self):
        # 견적은 AI 추정치라 적재 원천이 아니다 (§3-1 · §4-1).
        sql = upsert_sql(limit=None)
        self.assertNotIn("estimate_item", sql)
        self.assertNotIn("FROM estimate", sql)

    def test_repair_case_item_is_never_written(self):
        # 실제 수리비는 총액 하나뿐이라 부품별 내역을 채울 근거가 없다 (§4-4).
        sql = upsert_sql(limit=None)
        self.assertNotIn("repair_case_item", sql)
        self.assertNotIn("repair_case_image", sql)

    def test_insurance_amounts_stay_null(self):
        # claim/paid/labor_rate 는 보험 정산 개념이라 서비스 사고에 없다.
        sql = upsert_sql(limit=None)
        self.assertNotIn("EXCLUDED.claim_amount", sql)
        self.assertNotIn("EXCLUDED.paid_amount", sql)
        self.assertNotIn("EXCLUDED.labor_rate", sql)

    def test_conflict_target_is_the_unique_key(self):
        self.assertIn("ON CONFLICT (source, external_ref)", upsert_sql(limit=None))

    def test_unchanged_rows_are_not_rewritten(self):
        # DO UPDATE 에 WHERE 가 없으면 두 번째 실행이 전건을 다시 쓴다.
        self.assertIn("IS DISTINCT FROM", upsert_sql(limit=None))

    def test_limit_is_parameterised_not_interpolated(self):
        self.assertIn("LIMIT %(limit)s", upsert_sql(limit=10))
        self.assertNotIn("LIMIT", upsert_sql(limit=None))

    def test_aihub_rows_are_out_of_reach(self):
        # 이 job 이 건드릴 수 있는 것은 source='SERVICE' 행뿐이다.
        sql = upsert_sql(limit=None)
        self.assertNotIn("AIHUB", sql)
        self.assertNotIn("DELETE", sql.upper())


@unittest.skipUnless(DSN, "A307_TEST_DSN 이 없으면 건너뛴다 (실제 PostgreSQL 필요)")
class ServiceAccidentLoadDbTest(unittest.TestCase):
    """실제 PostgreSQL 에서 행동을 확인한다. 모든 쓰기는 rollback 된다."""

    @classmethod
    def setUpClass(cls):
        import psycopg

        cls.psycopg = psycopg

    def setUp(self):
        self.conn = self.psycopg.connect(DSN)
        self.conn.autocommit = False
        self.cur = self.conn.cursor()
        self.member_id = self._insert_member()
        self.model_id = self._insert_model()

    def tearDown(self):
        self.conn.rollback()
        self.conn.close()

    # ---------------------------------------------------------------- 고정물

    def _insert_member(self):
        self.cur.execute(
            """
            INSERT INTO member (provider, provider_user_id, nickname)
            VALUES ('KAKAO', 'p55-' || gen_random_uuid()::text, 'p55')
            RETURNING member_id
            """)
        return self.cur.fetchone()[0]

    def _insert_model(self, manufacturer="ZZ모터스", model_name="ZZ세단"):
        self.cur.execute(
            """
            INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type, car_class)
            VALUES (%s, %s, 'SEDAN', 'Compact')
            RETURNING model_id
            """, (manufacturer, model_name))
        return self.cur.fetchone()[0]

    def _insert_vehicle(self):
        self.cur.execute(
            """
            INSERT INTO vehicle (member_id, model_id, model_year)
            VALUES (%s, %s, 2020) RETURNING vehicle_id
            """, (self.member_id, self.model_id))
        return self.cur.fetchone()[0]

    def _insert_accident(self, *, cost=None, completed=None, model_id=None,
                         manufacturer="ZZ모터스", model_name="ZZ세단",
                         car_class="Compact", model_year=2020):
        vehicle_id = self._insert_vehicle()
        self.cur.execute(
            """
            INSERT INTO accident (
                vehicle_id, vehicle_input_type, snapshot_model_id, snapshot_manufacturer,
                snapshot_model_name, snapshot_vehicle_type, snapshot_car_class,
                snapshot_model_year, actual_repair_cost, actual_repair_completed_date)
            VALUES (%s,'REGISTERED',%s,%s,%s,'SEDAN',%s,%s,%s,%s)
            RETURNING accident_id
            """,
            (vehicle_id, self.model_id if model_id is None else model_id,
             manufacturer, model_name, car_class, model_year, cost, completed))
        return self.cur.fetchone()[0]

    def _case_row(self, accident_id):
        self.cur.execute(
            """
            SELECT case_id, source, external_ref, model_id, manufacturer, model_name,
                   car_class, model_year, repair_year, labor_rate, total_cost,
                   claim_amount, paid_amount
              FROM repair_case
             WHERE source = 'SERVICE' AND external_ref = %s
            """, (external_ref(accident_id),))
        rows = self.cur.fetchall()
        return rows[0] if rows else None

    def _count_cases(self, accident_id):
        self.cur.execute(
            "SELECT count(*) FROM repair_case WHERE source='SERVICE' AND external_ref=%s",
            (external_ref(accident_id),))
        return self.cur.fetchone()[0]

    # ---------------------------------------------------------------- 행동

    def test_accident_with_actual_cost_is_loaded(self):
        accident_id = self._insert_accident(cost=1_250_000, completed="2026-03-14")

        counts = upsert_service_cases(self.cur)

        self.assertGreaterEqual(counts["inserted"], 1)
        row = self._case_row(accident_id)
        self.assertIsNotNone(row)
        self.assertEqual(row[1], "SERVICE")
        self.assertEqual(row[2], f"svc-{accident_id}")
        self.assertEqual(row[10], 1_250_000)   # total_cost
        self.assertEqual(row[8], 2026)         # repair_year

    def test_external_ref_starts_with_svc(self):
        accident_id = self._insert_accident(cost=100_000)
        upsert_service_cases(self.cur)
        self.assertTrue(self._case_row(accident_id)[2].startswith("svc-"))

    def test_accident_without_actual_cost_is_not_loaded(self):
        accident_id = self._insert_accident(cost=None, completed="2026-03-14")
        upsert_service_cases(self.cur)
        self.assertIsNone(self._case_row(accident_id))

    def test_running_twice_keeps_one_row(self):
        accident_id = self._insert_accident(cost=900_000)

        upsert_service_cases(self.cur)
        upsert_service_cases(self.cur)

        self.assertEqual(self._count_cases(accident_id), 1)

    def test_second_run_touches_nothing_when_data_is_unchanged(self):
        self._insert_accident(cost=900_000)
        first = upsert_service_cases(self.cur)

        second = upsert_service_cases(self.cur)

        self.assertGreaterEqual(first["inserted"], 1)
        self.assertEqual(second["inserted"], 0)
        self.assertEqual(second["updated"], 0)

    def test_second_run_picks_up_only_the_new_accident(self):
        self._insert_accident(cost=500_000)
        upsert_service_cases(self.cur)

        fresh = self._insert_accident(cost=700_000)
        counts = upsert_service_cases(self.cur)

        self.assertEqual(counts["inserted"], 1)
        self.assertEqual(counts["updated"], 0)
        self.assertIsNotNone(self._case_row(fresh))

    def test_cost_recorded_later_is_picked_up_on_the_next_run(self):
        """사고는 먼저 생기고 수리비는 나중에 입력된다 — accident_id 순서로 자르면 놓친다."""
        late = self._insert_accident(cost=None)
        self._insert_accident(cost=300_000)
        upsert_service_cases(self.cur)
        self.assertIsNone(self._case_row(late))

        self.cur.execute(
            "UPDATE accident SET actual_repair_cost=440_000, actual_cost_recorded_at=now()"
            " WHERE accident_id=%s", (late,))
        counts = upsert_service_cases(self.cur)

        self.assertEqual(counts["inserted"], 1)
        self.assertEqual(self._case_row(late)[10], 440_000)

    def test_corrected_cost_updates_the_existing_row(self):
        accident_id = self._insert_accident(cost=100_000)
        upsert_service_cases(self.cur)

        self.cur.execute("UPDATE accident SET actual_repair_cost=180_000 WHERE accident_id=%s",
                         (accident_id,))
        counts = upsert_service_cases(self.cur)

        self.assertEqual(counts["inserted"], 0)
        self.assertEqual(counts["updated"], 1)
        self.assertEqual(self._count_cases(accident_id), 1)
        self.assertEqual(self._case_row(accident_id)[10], 180_000)

    def test_snapshot_is_used_even_when_the_master_changed(self):
        accident_id = self._insert_accident(cost=100_000, manufacturer="접수당시제작사",
                                            model_name="접수당시모델")
        self.cur.execute(
            "UPDATE vehicle_model SET manufacturer='바뀐제작사', model_name='바뀐모델'"
            " WHERE model_id=%s", (self.model_id,))

        upsert_service_cases(self.cur)

        row = self._case_row(accident_id)
        self.assertEqual(row[4], "접수당시제작사")
        self.assertEqual(row[5], "접수당시모델")

    def test_missing_master_model_becomes_null_model_id(self):
        # snapshot_model_id 는 스냅샷이라 마스터에서 사라졌을 수 있다. FK 위반으로
        # 전체 적재가 죽는 것보다 NULL 이 낫다.
        accident_id = self._insert_accident(cost=100_000, model_id=2_000_000_001)

        counts = upsert_service_cases(self.cur)

        self.assertIsNone(self._case_row(accident_id)[3])
        self.assertGreaterEqual(counts["model_id_null"], 1)

    def test_completed_date_missing_leaves_repair_year_null(self):
        accident_id = self._insert_accident(cost=100_000, completed=None)
        upsert_service_cases(self.cur)
        self.assertIsNone(self._case_row(accident_id)[8])

    def test_insurance_columns_stay_null(self):
        accident_id = self._insert_accident(cost=100_000)
        upsert_service_cases(self.cur)
        row = self._case_row(accident_id)
        self.assertIsNone(row[9])    # labor_rate
        self.assertIsNone(row[11])   # claim_amount
        self.assertIsNone(row[12])   # paid_amount

    def test_aihub_rows_are_untouched(self):
        self.cur.execute(
            """
            INSERT INTO repair_case (source, external_ref, manufacturer, model_name,
                                     car_class, total_cost)
            VALUES ('AIHUB_AS','as-p55-1','AI허브','AI허브모델','Compact', 77)
            RETURNING case_id
            """)
        aihub_id = self.cur.fetchone()[0]
        self._insert_accident(cost=100_000)

        upsert_service_cases(self.cur)

        self.cur.execute(
            "SELECT source, manufacturer, total_cost FROM repair_case WHERE case_id=%s",
            (aihub_id,))
        self.assertEqual(self.cur.fetchone(), ("AIHUB_AS", "AI허브", 77))

    def test_no_repair_case_item_rows_are_written(self):
        accident_id = self._insert_accident(cost=100_000)
        upsert_service_cases(self.cur)

        case_id = self._case_row(accident_id)[0]
        self.cur.execute("SELECT count(*) FROM repair_case_item WHERE case_id=%s", (case_id,))
        self.assertEqual(self.cur.fetchone()[0], 0)
        self.cur.execute("SELECT count(*) FROM repair_case_image WHERE case_id=%s", (case_id,))
        self.assertEqual(self.cur.fetchone()[0], 0)

    def test_preview_writes_nothing(self):
        accident_id = self._insert_accident(cost=100_000)

        preview = preview_service_cases(self.cur)

        self.assertIsNone(self._case_row(accident_id))
        self.assertTrue(any(row["external_ref"] == external_ref(accident_id) for row in preview))
        self.assertTrue(all(row["exists_already"] is False for row in preview
                            if row["external_ref"] == external_ref(accident_id)))

    def test_preview_marks_already_loaded_accidents(self):
        accident_id = self._insert_accident(cost=100_000)
        upsert_service_cases(self.cur)

        preview = preview_service_cases(self.cur)

        matching = [row for row in preview if row["external_ref"] == external_ref(accident_id)]
        self.assertEqual(len(matching), 1)
        self.assertTrue(matching[0]["exists_already"])

    def test_limit_caps_the_batch(self):
        for _ in range(3):
            self._insert_accident(cost=100_000)

        counts = upsert_service_cases(self.cur, limit=2)

        self.assertLessEqual(counts["inserted"], 2)

    def test_smallest_allowed_cost_is_loaded(self):
        # ck_ac_cost 가 actual_repair_cost > 0 을 요구한다. 1원도 적재 대상이다.
        accident_id = self._insert_accident(cost=1)
        upsert_service_cases(self.cur)
        self.assertEqual(self._case_row(accident_id)[10], 1)

    def test_zero_and_negative_costs_cannot_exist(self):
        # 경계는 DB 가 막는다 — job 이 다시 검사할 필요가 없다는 근거다.
        for bad in (0, -1):
            with self.subTest(cost=bad):
                self.cur.execute("SAVEPOINT bad_cost")
                with self.assertRaises(Exception):
                    self._insert_accident(cost=bad)
                self.cur.execute("ROLLBACK TO SAVEPOINT bad_cost")


if __name__ == "__main__":
    unittest.main()
