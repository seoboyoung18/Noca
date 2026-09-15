"""Validate raw estimate rows with conservative, traceable rules.

This job scans AS/SC estimate JSON files and writes all canonical rows plus
isolated errors.  It does not mutate source files or require a database.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
import sys
from typing import Any


def load_mapping(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(data, dict) and all(isinstance(k, str) for k in data):
        return {str(k).strip(): str(v).strip() for k, v in data.items() if v}
    rows = data.get("rows", []) if isinstance(data, dict) else []
    mapping: dict[str, str] = {}
    for row in rows:
        if row.get("status") not in {"MAPPED", "MAPPED_EXTENDED"}:
            continue
        raw_name = str(row.get("raw_name") or "").strip()
        code = str(row.get("standard_code") or "").strip()
        if raw_name and code:
            mapping[raw_name] = code.split(",", 1)[0].strip()
    return mapping


def iter_paths(estimate_dir: Path, source: str):
    prefixes = (f"{source}-",) if source in {"as", "sc"} else ("as-", "sc-")
    return sorted(
        path for path in estimate_dir.iterdir()
        if path.suffix.lower() == ".json" and path.name.startswith(prefixes)
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--estimate-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--source", choices=("as", "sc", "all"), default="all")
    parser.add_argument("--mapping-json", type=Path, default=None,
                        help="map_estimate_labels.py output or raw_name -> part_code JSON")
    parser.add_argument("--catalog-path", type=Path, default=Path(__file__).resolve().parents[2],
                        help="parent directory containing standardization/ and validation/")
    args = parser.parse_args()

    catalog_path = args.catalog_path.resolve()
    sys.path.insert(0, str(catalog_path.parent))
    sys.path.insert(0, str(catalog_path))
    from validation.estimate_rules import RULES, money, validate_estimate_item  # noqa: E402

    paths = iter_paths(args.estimate_dir.resolve(), args.source)
    mapping = load_mapping(args.mapping_json.resolve() if args.mapping_json else None)
    mapping_provided = args.mapping_json is not None
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

    summary = {
        "source": args.source,
        "estimate_dir": str(args.estimate_dir.resolve()),
        "file_count": len(paths),
        "files_with_parse_errors": 0,
        "item_count": 0,
        "row_counts": Counter(),
        "assessment_status_counts": Counter(),
        "error_counts": Counter(),
        "excluded_counts": Counter(),
        "mapping_provided": mapping_provided,
    }
    errors: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []

    for path in paths:
        case_id = path.stem
        source_kind = "AIHUB_AS" if case_id.startswith("as-") else "AIHUB_SC"
        try:
            payload = json.loads(path.read_bytes())
            items = payload.get("수리내역")
            if not isinstance(items, list):
                raise ValueError("수리내역 must be an array")
        except Exception as exc:  # noqa: BLE001 — preserve per-file failure and continue
            summary["files_with_parse_errors"] += 1
            error = {
                "error_type": "estimate_parse_error",
                "case_id": case_id,
                "source_ref": path.name,
                "line_type": None,
                "work_code": None,
                "assessment_status": None,
                "message": str(exc),
                "details": {},
            }
            errors.append(error)
            summary["error_counts"][error["error_type"]] += 1
            continue

        for index, item in enumerate(items):
            if not isinstance(item, dict):
                item = {}
            summary["item_count"] += 1
            raw_name = str(item.get("작업항목 및 부품명") or "").strip()
            part_code = mapping.get(raw_name) if mapping_provided else None
            result = validate_estimate_item(
                item,
                source_kind,
                case_id=case_id,
                source_ref=f"{path.name}#수리내역[{index}]",
                part_code=part_code,
                mapping_provided=mapping_provided,
                expected_item_total=money(item.get("item_total")),
            )
            row = result["row"]
            row["validation"] = result["validation"]
            rows.append(row)
            summary["row_counts"][row["line_type"] or "UNCLASSIFIED"] += 1
            summary["assessment_status_counts"][row["assessment_status"] or "NULL"] += 1
            for exclusion in result["validation"]["exclusions"]:
                summary["excluded_counts"][exclusion] += 1
            for error in result["errors"]:
                errors.append(error)
                summary["error_counts"][error["error_type"]] += 1

    summary["row_counts"] = dict(sorted(summary["row_counts"].items()))
    summary["assessment_status_counts"] = dict(sorted(summary["assessment_status_counts"].items()))
    summary["error_counts"] = dict(sorted(summary["error_counts"].items()))
    summary["excluded_counts"] = dict(sorted(summary["excluded_counts"].items()))
    summary["error_count"] = len(errors)
    summary["rules"] = RULES
    summary["explicitly_not_a_rule"] = (
        "청구공임 - 공임소계 = Σ(불인정 행 손해사정전 부품가격 + 공임)"
    )
    summary["evidence_note"] = (
        "The disputed NOT_APPROVED settlement equation matched 24/58 sampled cases; "
        "it is not promoted to an invariant."
    )

    rows_path = output / "estimate_validation_rows.jsonl"
    with rows_path.open("w", encoding="utf-8") as fp:
        for row in rows:
            fp.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    errors_path = output / "estimate_validation_errors.jsonl"
    with errors_path.open("w", encoding="utf-8") as fp:
        for error in errors:
            fp.write(json.dumps(error, ensure_ascii=False, sort_keys=True) + "\n")
    summary_path = output / "estimate_validation_summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "file_count": summary["file_count"],
        "item_count": summary["item_count"],
        "error_count": summary["error_count"],
        "error_counts": summary["error_counts"],
        "output": str(summary_path),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
