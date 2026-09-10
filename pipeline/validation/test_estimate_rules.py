import unittest

from validation.estimate_rules import (
    evaluate_sample_rule,
    validate_estimate_item,
)


class EstimateRuleTest(unittest.TestCase):
    def _as_item(self, **overrides):
        item = {
            "작업항목 및 부품명": "앞범퍼",
            "작업": "수리",
            "부품가격": "100",
            "공임": "50",
        }
        item.update(overrides)
        return item

    def _sc_item(self, **overrides):
        item = {
            "작업항목 및 부품명": "앞범퍼",
            "작업": "수리",
            "손해사정전": {"부품가격": "100", "공임": "50"},
            "손해사정후": {"부품가격": "80", "공임": "40"},
        }
        item.update(overrides)
        return item

    def _validate(self, item, source="AIHUB_AS", **kwargs):
        return validate_estimate_item(
            item,
            source,
            case_id="as-0001",
            source_ref="as-0001.json#수리내역[0]",
            **kwargs,
        )

    def test_approved_cost_columns_are_reconciled(self):
        result = self._validate(
            self._sc_item(),
            source="AIHUB_SC",
            part_code="FRONT_BUMPER",
            mapping_provided=True,
            expected_item_total=150,
        )
        self.assertEqual(result["row"]["assessment_status"], "APPROVED")
        self.assertEqual(result["validation"]["cost_reconciliation"], "PASSED")
        self.assertEqual(result["errors"], [])

    def test_cost_mismatch_isolated_for_verifiable_row(self):
        result = self._validate(
            self._as_item(),
            part_code="FRONT_BUMPER",
            mapping_provided=True,
            expected_item_total=160,
        )
        self.assertIn("item_cost_mismatch", {e["error_type"] for e in result["errors"]})

    def test_not_approved_excludes_cost_decomposition(self):
        result = self._validate(
            self._sc_item(작업="불인정"),
            source="AIHUB_SC",
            part_code="FRONT_BUMPER",
            mapping_provided=True,
        )
        row = result["row"]
        self.assertEqual(row["assessment_status"], "NOT_APPROVED")
        self.assertIsNone(row["work_code"])
        self.assertEqual(row["pre_adjustment_part_cost"], 100)
        self.assertEqual(row["item_total"], 150)
        self.assertEqual(result["validation"]["cost_reconciliation"], "EXCLUDED")
        self.assertNotIn("item_cost_mismatch", {e["error_type"] for e in result["errors"]})

    def test_ancillary_without_part_code_is_valid(self):
        result = self._validate(
            self._as_item(작업="견인", **{"작업항목 및 부품명": "견인비"}),
            part_code=None,
            mapping_provided=True,
        )
        self.assertEqual(result["row"]["line_type"], "ANCILLARY")
        self.assertEqual(result["row"]["work_code"], "TOWING")
        self.assertEqual(result["errors"], [])

    def test_reference_price_is_excluded_from_repair_total(self):
        result = self._validate(
            self._as_item(작업="", **{"작업항목 및 부품명": "앞범퍼 신품가 1,000"}),
            part_code="FRONT_BUMPER",
            mapping_provided=True,
        )
        self.assertEqual(result["row"]["line_type"], "REFERENCE_PRICE")
        self.assertIsNone(result["row"]["item_total"])
        self.assertEqual(result["validation"]["cost_reconciliation"], "EXCLUDED")
        self.assertEqual(result["errors"], [])

    def test_unknown_work_isolated_with_trace_fields(self):
        result = self._validate(self._as_item(작업="알수없는작업"))
        error = result["errors"][0]
        self.assertEqual(error["error_type"], "unknown_work_type")
        self.assertEqual(error["case_id"], "as-0001")
        self.assertEqual(error["source_ref"], "as-0001.json#수리내역[0]")
        self.assertIsNone(error["line_type"])

    def test_work_outside_current_contract_isolated(self):
        result = self._validate(
            self._as_item(작업="1/2OH"),
            part_code="FRONT_BUMPER",
            mapping_provided=True,
        )
        self.assertIn(
            "work_type_outside_contract",
            {e["error_type"] for e in result["errors"]},
        )

    def test_unknown_part_code_isolated(self):
        result = self._validate(
            self._as_item(),
            part_code=None,
            mapping_provided=True,
        )
        self.assertIn("unknown_part_code", {e["error_type"] for e in result["errors"]})

    def test_null_and_empty_values_do_not_become_silent_success(self):
        result = self._validate(
            {"작업항목 및 부품명": "앞범퍼", "작업": "", "부품가격": None, "공임": None}
        )
        self.assertIn("missing_work_type", {e["error_type"] for e in result["errors"]})
        self.assertEqual(result["validation"]["exclusions"], ["unclassified_row"])

    def test_small_sample_cannot_promote_a_formula_to_invariant(self):
        result = evaluate_sample_rule(58, 24)
        self.assertEqual(result["status"], "INSUFFICIENT_SAMPLE")
        self.assertFalse(result["promote_to_invariant"])


if __name__ == "__main__":
    unittest.main()
