"""라벨 파일의 사례·이미지 경로 인덱스를 만든다.

산출 CSV는 이후 `aihub_vehicle_image` 적재의 입력이다. 컬럼 이름을 그 테이블에 맞췄고,
`image_path`만 이름이 다르다(테이블은 `file_path`). `image_exists`는 테이블 컬럼이
아니라 적재 전에 orphan 라벨을 걸러내기 위한 판정값이다.

새 스크립트로 둔 이유 — `validate_category_id_integrity.py`가 이미 같은 트리를 걷지만,

  * 그 스크립트는 검색 가능 사고 후보 수를 확정하는 검증 게이트다. 그 수치가 지라와
    회고에 인용돼 있어 여기에 파일 단위 경로 출력을 덧붙이면 두 관심사가 한 산출물에
    엉킨다.
  * 키를 유도하는 방식이 다르다. 그쪽은 `images.file_name`에서 사례 토큰을 뽑고,
    이 인덱스는 **라벨 파일명**에서 뽑은 `external_ref`를 라벨 내용의 값과 대조해야
    한다. 파일명만 믿지 않는다는 요구가 그쪽에는 없다.
  * `case_id_linkage.csv`는 사례 단위 집계라 파일 단위 경로가 애초에 없다.

같은 트리를 두 번 걷는 비용은 실측 기준 약 21분이다(라벨 457,670건, 표본 3,000건에서
369건/초). 일회성이고 `--shard-count`로 나눠 돌릴 수 있어 이 분리를 정당화한다.

내용 추출은 `validate_category_id_integrity.py`와 같은 정규식 fast scan이다. 라벨
JSON에는 segmentation 폴리곤이 들어 있어 `json.loads`로 전부 파싱하면 필요 없는 좌표
배열까지 객체로 만든다. 필요한 값은 `images.file_name`과 사례 참조 두 종류뿐이다.

실행 예시:
  python pipeline/jobs/build_label_path_index.py \
    --subset-root "<01.데이터_견적서보유 경로>" \
    --output-dir "<저장소 밖 결과 경로>"
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

JOB_NAME = "label_path_index"

# 라벨이 45만 건이라 그룹 단위 출력만으로는 십수 분 동안 아무 것도 안 보인다.
PROGRESS_EVERY = 50000

# 라벨 파일명은 <image_id>_<external_ref>.json 이다. 예: 0000002_as-0036229.json
LABEL_NAME_RE = re.compile(r"^(?P<image_id>\d+)_(?P<external_ref>(?:as|sc)-\d+)$")

IMAGE_FILE_RE = re.compile(r'"file_name"\s*:\s*"([^"]+)"')
CATEGORY_ID_RE = re.compile(r'"category_id"\s*:\s*"([^"]+)"')
DECLARED_CATEGORY_RE = re.compile(r'"categories"\s*:\s*\{\s*"id"\s*:\s*"?([^",}\s]+)')

GROUPS = (
    ("TRAIN", "DAMAGE",
     "1.Training/1.원천데이터/TS_damage/damage",
     "1.Training/2.라벨링데이터/TL_damage/damage"),
    ("TRAIN", "DAMAGE_PART",
     "1.Training/1.원천데이터/TS_damage_part/damage_part",
     "1.Training/2.라벨링데이터/TL_damage_part/damage_part"),
    ("VALIDATION", "DAMAGE",
     "2.Validation/1.원천데이터/VS_damage/damage",
     "2.Validation/2.라벨링데이터/VL_damage/damage"),
    ("VALIDATION", "DAMAGE_PART",
     "2.Validation/1.원천데이터/VS_damage_part/damage_part",
     "2.Validation/2.라벨링데이터/VL_damage_part/damage_part"),
)

INDEX_FIELDS = [
    "external_ref", "dataset_split", "label_type", "source_image_id",
    "file_name", "label_path", "image_path", "image_exists",
]
QUARANTINE_FIELDS = [
    "error_type", "dataset_split", "label_type", "external_ref_from_name",
    "external_ref_in_label", "label_path", "message", "quarantine_status",
]


@dataclass(frozen=True)
class LabelGroup:
    split: str
    label_type: str
    image_dir: Path
    label_dir: Path


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def label_groups(subset_root: Path) -> list[LabelGroup]:
    groups = []
    for split, label_type, image_rel, label_rel in GROUPS:
        group = LabelGroup(split, label_type, subset_root / image_rel, subset_root / label_rel)
        if not group.label_dir.is_dir():
            raise SystemExit("라벨 폴더를 찾을 수 없습니다: " + str(group.label_dir))
        if not group.image_dir.is_dir():
            raise SystemExit("원천 이미지 폴더를 찾을 수 없습니다: " + str(group.image_dir))
        groups.append(group)
    return groups


def shard_suffix(shard_index: int, shard_count: int) -> str:
    if shard_count <= 1:
        return ""
    return "__shard" + str(shard_index) + "of" + str(shard_count)


def scan_group(group: LabelGroup, image_names: set[str], shard_index: int, shard_count: int,
               limit: int | None, rows: list[dict[str, Any]], quarantine: list[dict[str, Any]],
               stats: Counter[str]) -> None:
    label_paths = sorted(p for p in group.label_dir.iterdir()
                         if p.is_file() and p.suffix.lower() == ".json")
    if shard_count > 1:
        label_paths = label_paths[shard_index::shard_count]
    if limit:
        label_paths = label_paths[:limit]

    for label_path in label_paths:
        stats["labels"] += 1
        stats["labels:" + group.split + "/" + group.label_type] += 1
        if stats["labels"] % PROGRESS_EVERY == 0:
            print("진행 labels=" + str(stats["labels"]) + " indexed=" + str(len(rows))
                  + " quarantined=" + str(stats["quarantined"]), flush=True)

        name_match = LABEL_NAME_RE.match(label_path.stem)
        if not name_match:
            quarantine_row(quarantine, stats, "label_name_unparseable", group, None, None,
                           label_path, "<image_id>_<external_ref> 형태가 아닙니다: " + label_path.name)
            continue
        ref_from_name = name_match.group("external_ref")
        source_image_id = int(name_match.group("image_id"))

        try:
            raw = label_path.read_bytes().decode("utf-8-sig")
        except UnicodeError as exc:
            quarantine_row(quarantine, stats, "encoding_error", group, ref_from_name, None,
                           label_path, str(exc))
            continue
        except OSError as exc:
            quarantine_row(quarantine, stats, "read_error", group, ref_from_name, None,
                           label_path, str(exc))
            continue

        refs = set(CATEGORY_ID_RE.findall(raw)) | set(DECLARED_CATEGORY_RE.findall(raw))
        refs.discard("")
        if not refs:
            quarantine_row(quarantine, stats, "external_ref_missing_in_label", group,
                           ref_from_name, None, label_path,
                           "categories.id·annotation.category_id가 모두 없습니다")
            continue
        if len(refs) > 1:
            quarantine_row(quarantine, stats, "external_ref_multiple_in_label", group,
                           ref_from_name, "|".join(sorted(refs)), label_path,
                           "라벨 안에 사례 참조가 " + str(len(refs)) + "종 있습니다")
            continue
        ref_in_label = refs.pop()
        # 파일명만 믿지 않는다. 파일명과 내용이 다르면 어느 쪽이 맞는지 알 수 없으므로 격리한다.
        if ref_in_label != ref_from_name:
            quarantine_row(quarantine, stats, "external_ref_mismatch", group, ref_from_name,
                           ref_in_label, label_path,
                           "파일명 " + ref_from_name + " ≠ 내용 " + ref_in_label)
            continue

        image_match = IMAGE_FILE_RE.search(raw)
        if not image_match:
            quarantine_row(quarantine, stats, "image_file_name_missing", group, ref_from_name,
                           ref_in_label, label_path, "images.file_name이 없습니다")
            continue
        file_name = image_match.group(1)
        exists = file_name in image_names
        if exists:
            stats["image_exists"] += 1
        else:
            stats["image_missing"] += 1

        rows.append({
            "external_ref": ref_from_name,
            "dataset_split": group.split,
            "label_type": group.label_type,
            "source_image_id": source_image_id,
            "file_name": file_name,
            "label_path": str(label_path),
            "image_path": str(group.image_dir / file_name),
            "image_exists": exists,
        })


def quarantine_row(quarantine: list[dict[str, Any]], stats: Counter[str], error_type: str,
                   group: LabelGroup, ref_from_name: str | None, ref_in_label: str | None,
                   label_path: Path, message: str) -> None:
    """오류를 조용히 넘기지 않는다. 원천 파일은 지우지 않고 목록에만 남긴다."""
    stats["quarantined"] += 1
    stats["error_type:" + error_type] += 1
    quarantine.append({
        "error_type": error_type,
        "dataset_split": group.split,
        "label_type": group.label_type,
        "external_ref_from_name": ref_from_name,
        "external_ref_in_label": ref_in_label,
        "label_path": str(label_path),
        "message": message,
        "quarantine_status": "QUARANTINED",
    })


def write_csv(path: Path, fields: list[str], rows: Iterator[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_outputs(output: Path, suffix: str, rows: list[dict[str, Any]],
                  quarantine: list[dict[str, Any]]) -> dict[str, str]:
    """CSV 두 개를 쓰고 경로를 돌려준다. 요약 JSON은 호출자가 마지막에 쓴다.

    요약에 산출물 경로를 담으려면 경로가 먼저 정해져야 해서 순서를 나눴다.
    """
    index_path = output / (JOB_NAME + suffix + ".csv")
    quarantine_path = output / (JOB_NAME + suffix + "_quarantine.csv")
    summary_path = output / (JOB_NAME + suffix + "_summary.json")

    write_csv(index_path, INDEX_FIELDS, iter(rows))
    write_csv(quarantine_path, QUARANTINE_FIELDS, iter(quarantine))
    return {
        "label_path_index": str(index_path),
        "quarantine_manifest": str(quarantine_path),
        "summary": str(summary_path),
    }


def merge_shards(output: Path) -> dict[str, Any]:
    shard_summaries = sorted(output.glob(JOB_NAME + "__shard*_summary.json"))
    if not shard_summaries:
        raise SystemExit("합칠 " + JOB_NAME + "__shard*_summary.json이 없습니다: " + str(output))

    totals: Counter[str] = Counter()
    by_group: Counter[str] = Counter()
    errors_by_type: Counter[str] = Counter()
    elapsed = 0.0
    shards = []
    for path in shard_summaries:
        data = json.loads(path.read_text(encoding="utf-8"))
        counts = data["counts"]
        for key in ("labels", "indexed", "quarantined", "image_exists", "image_missing"):
            totals[key] += counts[key]
        for group, value in counts["labels_by_group"].items():
            by_group[group] += value
        for kind, value in counts["errors_by_type"].items():
            errors_by_type[kind] += value
        elapsed = max(elapsed, data["elapsed_seconds"])
        shards.append(path.name)

    rows: list[dict[str, Any]] = []
    for path in sorted(output.glob(JOB_NAME + "__shard*.csv")):
        if path.name.endswith("_quarantine.csv"):
            continue
        with path.open("r", encoding="utf-8-sig", newline="") as fp:
            rows.extend(csv.DictReader(fp))
    quarantine: list[dict[str, Any]] = []
    for path in sorted(output.glob(JOB_NAME + "__shard*_quarantine.csv")):
        with path.open("r", encoding="utf-8-sig", newline="") as fp:
            quarantine.extend(csv.DictReader(fp))

    summary = {
        "job_name": JOB_NAME,
        "mode": "merge_only",
        "shards": shards,
        "elapsed_seconds": round(elapsed, 2),
        "counts": {
            "labels": totals["labels"],
            "indexed": totals["indexed"],
            "quarantined": totals["quarantined"],
            "image_exists": totals["image_exists"],
            "image_missing": totals["image_missing"],
            "labels_by_group": dict(sorted(by_group.items())),
            "errors_by_type": dict(sorted(errors_by_type.items())),
        },
    }
    summary["outputs"] = write_outputs(output, "", rows, quarantine)
    Path(summary["outputs"]["summary"]).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path,
                        help="01.데이터_견적서보유 디렉터리 (1.Training·2.Validation을 담고 있다)")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="인덱스 CSV·격리 manifest를 쓸 경로. 저장소 밖을 지정한다")
    parser.add_argument("--limit", type=int, help="그룹별 최대 라벨 수(시험용)")
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--shard-count", type=int, default=1,
                        help="1보다 크면 그룹별 정렬 목록을 나눠 이 샤드만 처리한다")
    parser.add_argument("--merge-only", action="store_true",
                        help="스캔을 건너뛰고 기존 샤드 산출물을 합친다")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

    if args.merge_only:
        summary = merge_shards(output)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return

    if not args.subset_root:
        parser.error("--subset-root가 필요합니다")
    if args.shard_count < 1 or not (0 <= args.shard_index < args.shard_count):
        parser.error("--shard-index는 0 이상 --shard-count 미만이어야 합니다")

    subset_root = args.subset_root.resolve()
    groups = label_groups(subset_root)
    suffix = shard_suffix(args.shard_index, args.shard_count)

    rows: list[dict[str, Any]] = []
    quarantine: list[dict[str, Any]] = []
    stats: Counter[str] = Counter()
    started_at = now_utc()
    started = time.perf_counter()

    for group in groups:
        image_names = {p.name for p in group.image_dir.iterdir() if p.is_file()}
        before = stats["labels"]
        scan_group(group, image_names, args.shard_index, args.shard_count, args.limit,
                   rows, quarantine, stats)
        print(group.split + "/" + group.label_type + ": 라벨 "
              + str(stats["labels"] - before) + "건, 원천 이미지 "
              + str(len(image_names)) + "건, 누적 인덱스 " + str(len(rows)) + "건", flush=True)

    elapsed = time.perf_counter() - started
    stats["indexed"] = len(rows)
    summary = {
        "job_name": JOB_NAME,
        "started_at": started_at,
        "finished_at": now_utc(),
        "elapsed_seconds": round(elapsed, 2),
        "input": {
            "subset_root": str(subset_root),
            "shard_index": args.shard_index,
            "shard_count": args.shard_count,
            "limit": args.limit,
        },
        "counts": {
            "labels": stats["labels"],
            "indexed": len(rows),
            "quarantined": stats["quarantined"],
            "image_exists": stats["image_exists"],
            "image_missing": stats["image_missing"],
            "labels_by_group": {
                key.split(":", 1)[1]: value
                for key, value in sorted(stats.items()) if key.startswith("labels:")
            },
            "errors_by_type": {
                key.split(":", 1)[1]: value
                for key, value in sorted(stats.items()) if key.startswith("error_type:")
            },
        },
        "throughput_per_second": round(stats["labels"] / elapsed, 1) if elapsed else None,
        "notes": {
            "source_image_id": "라벨 파일명 접두를 쓴다. 라벨 내용의 images.id는 표본 3,000건에서 전부 1이라 식별자로 쓸 수 없다",
            "image_path": "aihub_vehicle_image에서는 file_path에 해당한다",
            "image_exists": "테이블 컬럼이 아니라 orphan 라벨 판정값이다",
        },
    }
    summary["outputs"] = write_outputs(output, suffix, rows, quarantine)
    Path(summary["outputs"]["summary"]).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    # 인덱스는 남기되 격리 건이 있으면 비정상 종료로 알린다.
    if stats["quarantined"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
