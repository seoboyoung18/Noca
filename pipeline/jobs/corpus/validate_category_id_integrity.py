"""Fast category_id/image/label/estimate integrity scan.

This scan is intentionally filename/key based because the subset contains
nearly half a million label JSON files. It reads each JSON file once, checks
the fields required for the join, and records malformed/missing-key files.
The case_id is the as/sc token suffix in the image filename, while
category_id is retained as its own grouping key.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


FILE_RE = re.compile(r"^(?P<prefix>\d+)_(?P<token>(?P<kind>as|sc)-\d+)\.(?P<ext>jpg|jpeg|png)$", re.IGNORECASE)
IMAGE_RE = re.compile(r'"file_name"\s*:\s*"([^"]+)"')
CATEGORY_RE = re.compile(r'"category_id"\s*:\s*"([^"]+)"')
DECLARED_CATEGORY_RE = re.compile(r'"categories"\s*:\s*\{\s*"id"\s*:\s*"?([^",}\s]+)')
GROUPS = (
    ("TRAIN", "DAMAGE", "1.Training/1.원천데이터/TS_damage/damage", "1.Training/2.라벨링데이터/TL_damage/damage"),
    ("TRAIN", "DAMAGE_PART", "1.Training/1.원천데이터/TS_damage_part/damage_part", "1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    ("VALIDATION", "DAMAGE", "2.Validation/1.원천데이터/VS_damage/damage", "2.Validation/2.라벨링데이터/VL_damage/damage"),
    ("VALIDATION", "DAMAGE_PART", "2.Validation/1.원천데이터/VS_damage_part/damage_part", "2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)
ESTIMATE_REL = "1.Training/1.원천데이터_230126_add/TS_99. 붙임_견적서"


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def rel(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def identity(file_name: str) -> tuple[str | None, str | None]:
    match = FILE_RE.match(file_name)
    if not match:
        return None, None
    return match.group("token").lower(), match.group("prefix")


def make_error(execution_id: str, detected_at: str, kind: str, source_ref: str, detail: dict[str, Any], status: str = "OPEN") -> dict[str, Any]:
    raw = json.dumps([execution_id, kind, source_ref, detail], ensure_ascii=False, sort_keys=True)
    return {
        "error_id": hashlib.sha1(raw.encode("utf-8")).hexdigest(),
        "error_type": kind,
        "source_ref": source_ref,
        "error_detail": detail,
        "batch_job_execution_id": execution_id,
        "detected_at": detected_at,
        "quarantine_status": status,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--execution-id", default=None)
    args = parser.parse_args()

    root = args.subset_root.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    execution_id = args.execution_id or f"category-integrity-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    started_at = now_utc()
    estimate_dir = root / ESTIMATE_REL
    estimate_ids = {path.stem.lower() for path in estimate_dir.iterdir() if path.is_file() and path.suffix.lower() == ".json"}

    errors: list[dict[str, Any]] = []
    quarantine: list[dict[str, Any]] = []
    category_stats: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
    case_stats: dict[str, Counter[str]] = defaultdict(Counter)
    group_summary: dict[str, Counter[str]] = defaultdict(Counter)
    processed = 0

    for split, label_type, source_rel, label_rel in GROUPS:
        group_name = f"{split.lower()}/{label_type.lower()}"
        source_dir = root / source_rel
        label_dir = root / label_rel
        source_names = {entry.name for entry in source_dir.iterdir() if entry.is_file()}
        label_paths = sorted((entry for entry in label_dir.iterdir() if entry.is_file() and entry.suffix.lower() == ".json"), key=lambda p: p.name)
        label_image_names: set[str] = set()
        group_summary[group_name]["source_image_count"] = len(source_names)
        group_summary[group_name]["label_file_count"] = len(label_paths)

        for label_path in label_paths:
            processed += 1
            if processed % 50000 == 0:
                print(f"processed_labels={processed}", flush=True)
            source_ref = rel(label_path, root)
            try:
                raw = label_path.read_text(encoding="utf-8-sig")
            except (OSError, UnicodeError) as exc:
                error = make_error(execution_id, started_at, "parse_error", source_ref, {"message": str(exc), "split": split, "label_type": label_type})
                errors.append(error)
                group_summary[group_name]["parse_error_count"] += 1
                continue

            image_match = IMAGE_RE.search(raw)
            if not image_match:
                error = make_error(execution_id, started_at, "key_mismatch", source_ref, {"missing_keys": ["images.file_name"], "split": split, "label_type": label_type})
                errors.append(error)
                group_summary[group_name]["key_mismatch_count"] += 1
                continue

            image_name = image_match.group(1)
            label_image_names.add(image_name)
            case_id, image_group_prefix = identity(image_name)
            category_values = sorted(set(CATEGORY_RE.findall(raw)) | set(DECLARED_CATEGORY_RE.findall(raw)))
            category_key = "|".join(category_values) if category_values else "__MISSING__"
            category = category_stats[(group_name, category_key)]
            category["label_file_count"] += 1
            category["category_id_observation_count"] += len(category_values)
            category["case_id_observation_count"] += 1

            case = case_stats[case_id or "__UNKNOWN__"]
            case["label_file_count"] += 1
            case["category_id_observation_count"] += len(category_values)
            if not case["groups"]:
                case["groups"] = group_name
            elif group_name not in str(case["groups"]).split("|"):
                case["groups"] = f"{case['groups']}|{group_name}"

            if case_id in estimate_ids:
                category["estimate_linked_label_count"] += 1
                case["has_estimate"] = 1
            elif case_id:
                category["missing_estimate_label_count"] += 1
                error = make_error(execution_id, started_at, "missing_estimate", source_ref, {
                    "split": split, "label_type": label_type, "image_file_name": image_name,
                    "case_id": case_id, "category_id": category_values,
                    "expected_estimate_ref": rel(estimate_dir / f"{case_id}.json", root),
                })
                errors.append(error)

            if image_name in source_names:
                category["source_image_ref_count"] += 1
                case["source_image_count"] += 1
            else:
                category["orphan_label_count"] += 1
                case["orphan_label_count"] += 1
                detail = {
                    "split": split, "label_type": label_type,
                    "label_file_name": label_path.name, "image_file_name": image_name,
                    "case_id": case_id, "image_group_prefix": image_group_prefix,
                    "category_id": category_values,
                    "expected_source_ref": rel(source_dir / image_name, root),
                    "is_quarantined": True,
                }
                error = make_error(execution_id, started_at, "orphan_label", source_ref, detail, "QUARANTINED")
                errors.append(error)
                quarantine.append({
                    "error_id": error["error_id"], "error_type": "orphan_label",
                    "split": split, "label_type": label_type, "case_id": case_id,
                    "category_id": "|".join(category_values) if category_values else None,
                    "label_ref": source_ref, "expected_image_ref": detail["expected_source_ref"],
                    "quarantine_status": "QUARANTINED",
                })

            annotation_categories = set(CATEGORY_RE.findall(raw))
            declared_categories = set(DECLARED_CATEGORY_RE.findall(raw))
            if declared_categories and annotation_categories and not declared_categories.issubset(annotation_categories):
                group_summary[group_name]["key_mismatch_count"] += 1
                errors.append(make_error(execution_id, started_at, "key_mismatch", source_ref, {
                    "split": split, "label_type": label_type, "image_file_name": image_name,
                    "categories.id": sorted(declared_categories),
                    "annotation.category_id_values": sorted(annotation_categories),
                }))

        missing_labels = source_names - label_image_names
        group_summary[group_name]["missing_label_count"] = len(missing_labels)
        for image_name in sorted(missing_labels):
            case_id, image_group_prefix = identity(image_name)
            source_ref = rel(source_dir / image_name, root)
            errors.append(make_error(execution_id, started_at, "missing_label", source_ref, {
                "split": split, "label_type": label_type, "file_name": image_name,
                "case_id": case_id, "image_group_prefix": image_group_prefix,
                "expected_label_ref": rel(label_dir / f"{Path(image_name).stem}.json", root),
            }))

    for case_id in estimate_ids:
        case_stats[case_id]["has_estimate"] = 1

    error_counts = Counter(error["error_type"] for error in errors)
    searchable_cases = sum(
        1 for case in case_stats.values()
        if case["has_estimate"] and case["source_image_count"] > 0 and case["label_file_count"] > 0 and case["orphan_label_count"] == 0
    )
    finished_at = now_utc()

    with (output / "data_validation_error.jsonl").open("w", encoding="utf-8", newline="") as fp:
        for error in errors:
            fp.write(json.dumps(error, ensure_ascii=False, separators=(",", ":")) + "\n")

    with (output / "quarantine_manifest.csv").open("w", encoding="utf-8-sig", newline="") as fp:
        fields = ["error_id", "error_type", "split", "label_type", "case_id", "category_id", "label_ref", "expected_image_ref", "quarantine_status"]
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        writer.writerows(quarantine)

    with (output / "category_id_integrity.csv").open("w", encoding="utf-8-sig", newline="") as fp:
        fields = ["group", "category_id", "label_file_count", "source_image_ref_count", "orphan_label_count", "case_id_observation_count", "category_id_observation_count", "estimate_linked_label_count", "missing_estimate_label_count"]
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for (group_name, category_key), stats in sorted(category_stats.items()):
            writer.writerow({
                "group": group_name, "category_id": None if category_key == "__MISSING__" else category_key,
                "label_file_count": stats["label_file_count"], "source_image_ref_count": stats["source_image_ref_count"],
                "orphan_label_count": stats["orphan_label_count"], "case_id_observation_count": stats["case_id_observation_count"],
                "category_id_observation_count": stats["category_id_observation_count"],
                "estimate_linked_label_count": stats["estimate_linked_label_count"],
                "missing_estimate_label_count": stats["missing_estimate_label_count"],
            })

    with (output / "case_id_linkage.csv").open("w", encoding="utf-8-sig", newline="") as fp:
        fields = ["case_id", "has_estimate", "source_image_count", "label_file_count", "orphan_label_count", "category_id_observation_count", "groups", "is_searchable_case_candidate"]
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for case_id, case in sorted(case_stats.items()):
            writer.writerow({
                "case_id": case_id, "has_estimate": bool(case["has_estimate"]),
                "source_image_count": case["source_image_count"], "label_file_count": case["label_file_count"],
                "orphan_label_count": case["orphan_label_count"],
                "category_id_observation_count": case["category_id_observation_count"],
                "groups": case.get("groups", ""),
                "is_searchable_case_candidate": bool(case["has_estimate"] and case["source_image_count"] > 0 and case["label_file_count"] > 0 and case["orphan_label_count"] == 0),
            })

    summary = {
        "batch_job_execution": {
            "execution_id": execution_id, "job_name": "category_id_integrity_validation",
            "started_at": started_at, "finished_at": finished_at,
            "status": "COMPLETED_WITH_ERRORS" if errors else "COMPLETED",
        },
        "validation_mode": "fast_key_scan",
        "input": {"subset_root": str(root), "estimate_dir": rel(estimate_dir, root), "groups": [f"{s.lower()}/{t.lower()}" for s, t, _, _ in GROUPS]},
        "counts": {
            "estimate_cases": len(estimate_ids), "case_ids_seen": len(case_stats),
            "category_groups": len(category_stats), "searchable_case_candidates": searchable_cases,
            "errors": len(errors), "quarantine_records": len(quarantine),
            "errors_by_type": dict(sorted(error_counts.items())),
        },
        "group_summary": {name: dict(stats) for name, stats in sorted(group_summary.items())},
        "outputs": {
            "data_validation_error": str(output / "data_validation_error.jsonl"),
            "quarantine_manifest": str(output / "quarantine_manifest.csv"),
            "category_id_integrity": str(output / "category_id_integrity.csv"),
            "case_id_linkage": str(output / "case_id_linkage.csv"),
        },
        "rules": {
            "case_id": "filename as/sc token suffix, e.g. 0000002_as-0036229 -> as-0036229",
            "category_id": "categories.id and annotation.category_id values, grouped without converting to case_id",
            "orphan_label": "label JSON images.file_name has no same-group source image",
            "missing_label": "source image has no same-group label JSON",
            "searchable_case_candidate": "has estimate and at least one source/label join, with no orphan label for that case",
        },
    }
    (output / "validation_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "batch_job_execution.json").write_text(json.dumps(summary["batch_job_execution"], ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
