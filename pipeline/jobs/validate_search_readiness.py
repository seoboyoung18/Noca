"""Search-readiness validation: DAMAGE geometry + case estimate part candidates.

Extends the fast key-scan join result (case_id_linkage.csv) with a second pass
that parses DAMAGE and DAMAGE_PART labels to check:
  - at least one DAMAGE annotation has valid geometry (bbox or segmentation)
  - DAMAGE_PART context exists independently from the DAMAGE ROI
  - when estimate root and mapping workbook are supplied, whether an estimate
    item resolves to a standard part_code for the same case.

Only cases that were already is_searchable_case_candidate=True in the prior
category_id integrity run are considered "final" candidates; this pass adds
the polygon/part_code gate on top and reports where candidates still fail.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

GROUPS = (
    ("TRAIN", "DAMAGE", "1.Training/1.원천데이터/TS_damage/damage", "1.Training/2.라벨링데이터/TL_damage/damage"),
    ("TRAIN", "DAMAGE_PART", "1.Training/1.원천데이터/TS_damage_part/damage_part", "1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    ("VALIDATION", "DAMAGE", "2.Validation/1.원천데이터/VS_damage/damage", "2.Validation/2.라벨링데이터/VL_damage/damage"),
    ("VALIDATION", "DAMAGE_PART", "2.Validation/1.원천데이터/VS_damage_part/damage_part", "2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def case_id_of(file_name: str) -> str | None:
    stem = Path(file_name).stem
    for kind in ("as-", "sc-"):
        idx = stem.find(kind)
        if idx != -1:
            return stem[idx:]
    return None


def valid_bbox(bbox, width, height) -> bool:
    try:
        if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
            return False
        x, y, w, h = (float(v) for v in bbox)
    except (TypeError, ValueError):
        return False
    if w <= 0 or h <= 0 or x < 0 or y < 0:
        return False
    if width and height and (x + w > width * 1.02 or y + h > height * 1.02):
        return False
    return True


def valid_segmentation(seg) -> bool:
    def leaves(value):
        if isinstance(value, (list, tuple)) and len(value) >= 3 and all(
            isinstance(p, (list, tuple)) and len(p) == 2 for p in value
        ):
            return [value]
        out = []
        if isinstance(value, (list, tuple)):
            for child in value:
                out.extend(leaves(child))
        return out

    for polygon in leaves(seg):
        try:
            xy = [(float(p[0]), float(p[1])) for p in polygon]
        except (TypeError, ValueError, IndexError):
            continue
        area = abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(xy, xy[1:] + xy[:1]))) / 2
        if area > 0:
            return True
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--linkage-csv", type=Path, required=True, help="prior case_id_linkage.csv")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--estimate-root", type=Path,
                        help="견적 JSON 디렉터리. 지정하면 사례 부품 후보를 실제 매핑한다")
    parser.add_argument("--mapping-workbook", type=Path,
                        help="부품명 전체 매핑 워크북. --estimate-root와 함께 지정한다")
    parser.add_argument("--catalog-path", type=Path, default=Path(__file__).resolve().parents[1],
                        help="path to the standardization/ package parent (default: pipeline/)")
    parser.add_argument("--only-group", default=None, help="e.g. TRAIN:DAMAGE_PART — run a single group and dump a partial file instead of the final report")
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--shard-count", type=int, default=1)
    parser.add_argument("--merge-only", action="store_true", help="skip scanning; merge existing group_partial_*.json files with the linkage csv into the final report")
    args = parser.parse_args()

    if bool(args.estimate_root) != bool(args.mapping_workbook):
        parser.error("--estimate-root와 --mapping-workbook은 함께 지정해야 합니다")

    sys.path.insert(0, str(args.catalog_path.resolve()))
    estimate_mapping: dict[str, str] = {}
    if args.mapping_workbook:
        import pandas as pd
        frame = pd.read_excel(args.mapping_workbook, sheet_name="부품명 전체 매핑", header=3)
        estimate_mapping = {
            str(raw).strip(): str(code).strip()
            for raw, code, status in frame[["기존 이름", "표준 코드", "매핑 상태"]].itertuples(index=False, name=None)
            if status in {"MAPPED", "MAPPED_EXTENDED"} and str(raw).strip() and str(code).strip()
        }

    root = args.subset_root.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    execution_id = f"search-readiness-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"
    started_at = now_utc()

    candidates: dict[str, dict] = {}
    with args.linkage_csv.open(encoding="utf-8-sig") as fp:
        for row in csv.DictReader(fp):
            candidates[row["case_id"]] = row
    print(f"prior candidates: {len(candidates)}", flush=True)

    groups_to_scan = GROUPS
    if args.only_group:
        want_split, want_type = args.only_group.split(":")
        groups_to_scan = tuple(g for g in GROUPS if g[0] == want_split and g[1] == want_type)
        if not groups_to_scan:
            raise SystemExit(f"unknown --only-group {args.only_group!r}")

    case_geometry_ok: set[str] = set()
    case_damage_part_context: set[str] = set()
    case_label_counts: Counter[str] = Counter()
    case_orphan_counts: Counter[str] = Counter()
    parse_errors = 0
    processed = 0
    group_counts: dict[str, Counter] = defaultdict(Counter)

    def read_text_safe(path: Path) -> str | None:
        try:
            return path.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeError):
            return None

    if not args.merge_only:
      for split, label_type, _source_rel, label_rel in groups_to_scan:
        group_name = f"{split.lower()}/{label_type.lower()}"
        label_dir = root / label_rel
        if not label_dir.exists():
            continue
        # Avoid p.is_file() here: it forces one stat() syscall per entry, which is
        # very slow over this mount for 80k-300k files. Suffix filtering on the
        # dirent name is enough since these label dirs contain only .json files.
        label_paths = sorted(p for p in label_dir.iterdir() if p.suffix.lower() == ".json")
        if args.shard_count > 1:
            label_paths = label_paths[args.shard_index::args.shard_count]
            print(f"[{group_name}] {len(label_paths)} files (shard {args.shard_index}/{args.shard_count})", flush=True)
        else:
            print(f"[{group_name}] {len(label_paths)} files", flush=True)
        with ThreadPoolExecutor(max_workers=64) as executor:
            raw_texts = list(executor.map(read_text_safe, label_paths, chunksize=64))
        for label_path, raw_text in zip(label_paths, raw_texts):
            processed += 1
            if processed % 50000 == 0:
                print(f"processed={processed}", flush=True)
            if raw_text is None:
                parse_errors += 1
                continue
            try:
                data = json.loads(raw_text)
            except json.JSONDecodeError:
                parse_errors += 1
                continue

            image = data.get("images") or {}
            file_name = image.get("file_name", "")
            width, height = image.get("width"), image.get("height")
            case_id = case_id_of(file_name) or case_id_of(label_path.name)
            if case_id is None or case_id not in candidates:
                continue

            group_counts[group_name]["label_files_scanned"] += 1
            case_label_counts[case_id] += 1
            source_path = root / _source_rel / Path(file_name).name
            if not source_path.is_file():
                case_orphan_counts[case_id] += 1
            if label_type == "DAMAGE_PART":
                case_damage_part_context.add(case_id)
            for ann in data.get("annotations") or []:
                geom_ok = valid_bbox(ann.get("bbox"), width, height) or valid_segmentation(ann.get("segmentation"))
                if label_type == "DAMAGE" and geom_ok and ann.get("damage"):
                    case_geometry_ok.add(case_id)

    if args.only_group:
        partial = {
            "group": args.only_group,
            "case_geometry_ok": sorted(case_geometry_ok),
            "case_damage_part_context": sorted(case_damage_part_context),
            "processed": processed,
            "parse_errors": parse_errors,
            "group_counts": {k: dict(v) for k, v in group_counts.items()},
        }
        safe_name = args.only_group.replace(":", "_").lower()
        (output / f"group_partial_{safe_name}__shard{args.shard_index}of{args.shard_count}.json").write_text(json.dumps(partial, ensure_ascii=False), encoding="utf-8")
        print(f"partial written for {args.only_group}: processed={processed} geometry_ok={len(case_geometry_ok)} part_ok={len(case_part_ok)}")
        return

    if args.merge_only:
        for partial_path in sorted(output.glob("group_partial_*.json")):
            partial = json.loads(partial_path.read_text(encoding="utf-8"))
            case_geometry_ok.update(partial["case_geometry_ok"])
            case_damage_part_context.update(partial.get("case_damage_part_context", []))
            processed += partial["processed"]
            parse_errors += partial["parse_errors"]
            for g, counts in partial["group_counts"].items():
                group_counts[g].update(counts)
            print(f"merged {partial_path.name}: processed={partial['processed']}")

    finished_at = now_utc()

    fields = [
        "case_id", "has_estimate", "source_image_count", "label_file_count", "orphan_label_count",
        "is_searchable_case_candidate", "has_damage_geometry", "has_estimate_part_candidate",
        "has_damage_part_context", "is_final_searchable_case",
    ]
    final_count = 0
    fail_reasons = Counter()
    with (output / "case_search_readiness.csv").open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for case_id, row in sorted(candidates.items()):
            is_candidate = row["is_searchable_case_candidate"] in ("True", "true", "1")
            geom_ok = case_id in case_geometry_ok
            estimate_candidate = False
            if args.estimate_root:
                estimate_path = args.estimate_root / f"{case_id}.json"
                try:
                    estimate = json.loads(estimate_path.read_text(encoding="utf-8-sig"))
                    estimate_candidate = any(
                        str(item.get("작업항목 및 부품명") or "").strip() in estimate_mapping
                        for item in (estimate.get("수리내역") or []) if isinstance(item, dict)
                    )
                except (OSError, UnicodeError, json.JSONDecodeError):
                    estimate_candidate = False
            is_final = is_candidate and geom_ok
            if is_final:
                final_count += 1
            elif is_candidate:
                if not geom_ok:
                    fail_reasons["no_valid_geometry"] += 1
            writer.writerow({
                "case_id": case_id, "has_estimate": row["has_estimate"],
                "source_image_count": case_label_counts.get(case_id, 0),
                "label_file_count": case_label_counts.get(case_id, 0),
                "orphan_label_count": case_orphan_counts.get(case_id, 0),
                "is_searchable_case_candidate": is_candidate,
                "has_damage_geometry": geom_ok,
                "has_estimate_part_candidate": estimate_candidate,
                "has_damage_part_context": case_id in case_damage_part_context,
                "is_final_searchable_case": is_final,
            })

    summary = {
        "batch_job_execution": {
            "execution_id": execution_id, "job_name": "search_readiness_validation",
            "started_at": started_at, "finished_at": finished_at,
        },
        "input": {
            "subset_root": str(root), "linkage_csv": str(args.linkage_csv.resolve()),
        },
        "counts": {
            "prior_searchable_case_candidates": sum(1 for r in candidates.values() if r["is_searchable_case_candidate"] in ("True", "true", "1")),
            "final_searchable_cases": final_count,
            "parse_errors": parse_errors,
            "label_files_processed": processed,
            "fail_reasons_among_prior_candidates": dict(sorted(fail_reasons.items())),
        },
        "group_summary": {k: dict(v) for k, v in sorted(group_counts.items())},
        "rules": {
            "image_source_scope": "DAMAGE is the ROI source; DAMAGE_PART is context only",
            "has_damage_geometry": "at least one DAMAGE annotation with non-null damage code and valid bbox or polygon",
            "has_estimate_part_candidate": "an estimate item name resolves through the mapping workbook; not required for ROI indexing",
            "is_final_searchable_case": "is_searchable_case_candidate AND has_damage_geometry",
        },
    }
    (output / "search_readiness_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
