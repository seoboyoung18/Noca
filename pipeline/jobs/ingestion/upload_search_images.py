"""검색 코퍼스 이미지를 S3에 멱등 업로드한다.

DB 적재기와 같은 readiness·case manifest를 사용해 대상 사례를 결정한다.
기본 대상은 DAMAGE_PART이고, ``--include-damage-reference``를 지정한 경우에만
DAMAGE도 함께 업로드한다. ``--all-aihub``은 manifest와 readiness를 사용하지 않고
AI-Hub 라벨 네 디렉터리(DAMAGE/DAMAGE_PART, TRAIN/VALIDATION)를 모두 순회한다.
DB의 storage_key와 같은 규칙을 사용하므로 두 적재 작업에 서로 다른 이미지가
들어가지 않는다.

실행 전제:
  pip install -r pipeline/requirements.txt

예시(실제 실행 전 --dry-run으로 확인):
  python pipeline/jobs/ingestion/upload_search_images.py \
    --subset-root "<견적서 보유 subset>" \
    --dataset-root "<전체 데이터셋>" \
    --readiness-csv "<output>/case_search_readiness.csv" \
    --case-manifest "<output>/search_dev_cases.csv" \
    --bucket "<검색 코퍼스 버킷>" \
    --dry-run

AI-Hub 원본 전체를 적재할 때(검색 DEV manifest를 사용하지 않음):
  python -m pipeline.jobs.ingestion.upload_search_images \
    --dataset-root "<01.데이터_견적서보유 경로>" \
    --all-aihub --bucket "<검색 코퍼스 버킷>" \
    --workers 16 --progress-every 1000 \
    --report "<output>/s3_upload_all_aihub.json"

중단 후 같은 명령을 다시 실행해도, 같은 크기의 기존 객체는 HeadObject로
건너뛰므로 재개 시 중복 업로드가 없다.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pipeline.jobs.ingestion.load_search_data import (
    IMAGE_GROUPS,
    build_label_index,
    filter_search_labels,
    image_path_for_label,
    image_metadata_for_label,
    load_case_manifest,
    load_readiness,
    source_for_case,
    source_image_for_label,
)
from pipeline.standardization.storage_keys import repair_case_image_key_from_ref


IMAGE_CASE_RE = re.compile(r"_(?P<case>(?:as|sc)-\d+)[.]jpg$", re.IGNORECASE)


@dataclass(frozen=True)
class UploadCandidate:
    case_id: str
    source: str
    image_type: str
    source_dataset_split: str
    local_path: str
    source_image_ref: str
    storage_key: str


def files_are_identical(first: str, second: str) -> bool:
    """같은 storage key를 공유하는 원본 복제본인지 바이트 단위로 확인한다."""
    first_path = Path(first)
    second_path = Path(second)
    if first_path.stat().st_size != second_path.stat().st_size:
        return False
    with first_path.open("rb") as first_file, second_path.open("rb") as second_file:
        while True:
            first_chunk = first_file.read(1024 * 1024)
            second_chunk = second_file.read(1024 * 1024)
            if first_chunk != second_chunk:
                return False
            if not first_chunk:
                return True


def build_upload_candidates(
    *,
    subset_root: Path,
    dataset_root: Path,
    readiness_csv: Path,
    case_manifest: Path,
    include_damage_reference: bool = False,
    source: str = "all",
    limit: int | None = None,
) -> list[UploadCandidate]:
    """선택된 사례의 S3 업로드 후보를 만든다. 네트워크는 사용하지 않는다."""
    selected = load_readiness(readiness_csv.resolve(), source)
    selected &= load_case_manifest(case_manifest.resolve())
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be positive")
        selected = set(sorted(selected)[:limit])

    label_index = build_label_index(subset_root.resolve(), selected)
    candidates: list[UploadCandidate] = []
    for case_id in sorted(selected):
        labels = filter_search_labels(
            label_index.get(case_id, []),
            include_damage_reference=include_damage_reference,
        )
        for label_path in sorted(labels):
            image_path, source_image_ref = source_image_for_label(
                label_path, dataset_root, subset_root
            )
            if not image_path.is_file():
                raise FileNotFoundError(f"라벨에 대응하는 이미지가 없습니다: {image_path}")
            image_type, source_dataset_split = image_metadata_for_label(label_path)
            candidates.append(UploadCandidate(
                case_id=case_id,
                source=source_for_case(case_id),
                image_type=image_type,
                source_dataset_split=source_dataset_split,
                local_path=str(image_path),
                source_image_ref=source_image_ref,
                storage_key=repair_case_image_key_from_ref(
                    source_for_case(case_id), case_id, source_image_ref
                ),
            ))
    return candidates


def build_all_aihub_upload_candidates(
    *,
    dataset_root: Path,
    source: str = "all",
    limit: int | None = None,
) -> list[UploadCandidate]:
    """AI-Hub 원본 전체의 DAMAGE/DAMAGE_PART 이미지를 업로드 대상으로 만든다.

    라벨·검색 DB의 DEV manifest·``is_final_searchable_case`` 필터를 적용하지 않고,
    실제 원본 이미지 파일만 순회한다. 따라서 이 모드는 AI-Hub 전체 원본을 S3에
    보존할 때만 사용한다.
    """
    resolved_dataset_root = dataset_root.resolve()

    candidates: list[UploadCandidate] = []
    local_path_by_key: dict[str, str] = {}
    for dataset_split, image_type, relative_dir in IMAGE_GROUPS:
        # image_path_for_label은 라벨 *파일* 경로를 받아 파일명을 떼므로,
        # 디렉터리 자체가 아니라 가짜 파일명을 붙여 변환한다.
        relative_image_dir = image_path_for_label(relative_dir / "__image__.json")
        image_dir = resolved_dataset_root / relative_image_dir
        if not image_dir.is_dir():
            raise FileNotFoundError(f"AI-Hub 원본 이미지 디렉터리가 없습니다: {image_dir}")
        with os.scandir(image_dir) as entries:
            for entry in entries:
                if not entry.is_file() or not entry.name.lower().endswith(".jpg"):
                    continue
                match = IMAGE_CASE_RE.search(entry.name)
                if not match:
                    raise ValueError(f"AI-Hub 이미지 파일명에서 case_id를 읽을 수 없습니다: {entry.path}")
                case_id = match.group("case")
                candidate_source = source_for_case(case_id)
                if source != "all" and candidate_source != source:
                    continue
                source_image_ref = (relative_image_dir / entry.name).as_posix()
                storage_key = repair_case_image_key_from_ref(
                    candidate_source, case_id, source_image_ref
                )
                previous_local_path = local_path_by_key.get(storage_key)
                if previous_local_path is not None:
                    if files_are_identical(previous_local_path, entry.path):
                        # TRAIN/VALIDATION에 복제된 동일 원본은 한 S3 객체만 보관한다.
                        continue
                    raise ValueError(
                        "서로 다른 AI-Hub 원본이 같은 S3 key를 만듭니다: "
                        f"{storage_key}; first={previous_local_path}; next={entry.path}"
                    )
                local_path_by_key[storage_key] = entry.path
                candidates.append(UploadCandidate(
                    case_id=case_id,
                    source=candidate_source,
                    image_type=image_type,
                    source_dataset_split=dataset_split,
                    local_path=entry.path,
                    source_image_ref=source_image_ref,
                    storage_key=storage_key,
                ))

    candidates.sort(key=lambda candidate: candidate.storage_key)
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be positive")
        candidates = candidates[:limit]
    return candidates


def s3_key(storage_key: str, prefix: str = "") -> str:
    """선택적 prefix를 안전하게 붙인다. 기본값은 DB storage_key와 같다."""
    normalized_prefix = prefix.strip("/")
    return f"{normalized_prefix}/{storage_key}" if normalized_prefix else storage_key


def candidate_summary(candidates: list[UploadCandidate]) -> dict[str, Any]:
    by_type = Counter(candidate.image_type for candidate in candidates)
    by_split = Counter(candidate.source_dataset_split for candidate in candidates)
    total_bytes = sum(Path(candidate.local_path).stat().st_size for candidate in candidates)
    return {
        "candidate_count": len(candidates),
        "image_count_by_type": dict(sorted(by_type.items())),
        "image_count_by_dataset_split": dict(sorted(by_split.items())),
        "total_bytes": total_bytes,
    }


def _is_not_found(exc: Exception) -> bool:
    response = getattr(exc, "response", {}) or {}
    error = response.get("Error", {}) if isinstance(response, dict) else {}
    code = str(error.get("Code", ""))
    return code in {"404", "NoSuchKey", "NotFound"}


def upload_candidates(
    candidates: list[UploadCandidate],
    *,
    bucket: str,
    prefix: str = "",
    overwrite: bool = False,
    workers: int = 1,
    progress_every: int = 1_000,
    include_keys: bool = True,
    s3_client: Any | None = None,
) -> dict[str, Any]:
    """후보를 S3에 업로드한다.

    같은 크기의 기존 객체는 건너뛰고, 크기가 다른 객체는
    ``--overwrite`` 없이는 중단한다. 권한 오류·네트워크 오류도 조용히
    무시하지 않는다. 대량 업로드는 worker별 병렬 HeadObject/PutObject와
    주기적 진행 로그를 사용한다.
    """
    if not bucket.strip():
        raise ValueError("bucket is required")
    if workers < 1:
        raise ValueError("workers must be positive")
    if progress_every < 1:
        raise ValueError("progress_every must be positive")
    if s3_client is None:
        try:
            import boto3
            from botocore.config import Config
        except ImportError as exc:
            raise SystemExit("boto3가 필요합니다: pip install boto3") from exc
        s3_client = boto3.client(
            "s3", config=Config(max_pool_connections=max(workers, 10))
        )

    uploaded = 0
    skipped_existing = 0
    keys: list[str] = []

    def upload_one(candidate: UploadCandidate) -> tuple[str, str]:
        key = s3_key(candidate.storage_key, prefix)
        local_size = Path(candidate.local_path).stat().st_size
        try:
            existing = s3_client.head_object(Bucket=bucket, Key=key)
        except Exception as exc:
            if not _is_not_found(exc):
                raise
            existing = None

        if existing is not None:
            existing_size = int(existing.get("ContentLength", -1))
            if not overwrite:
                if existing_size != local_size:
                    raise RuntimeError(
                        "기존 S3 객체 크기가 원본과 다릅니다. "
                        f"--overwrite로 덮어쓸 수 있습니다: s3://{bucket}/{key}"
                    )
                return "skipped_existing", key

        content_type = mimetypes.guess_type(candidate.local_path)[0] or "image/jpeg"
        s3_client.upload_file(
            candidate.local_path,
            bucket,
            key,
            ExtraArgs={"ContentType": content_type},
        )
        return "uploaded", key

    processed = 0
    started_at = time.monotonic()
    batch_size = max(workers * 16, 256)
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="s3-upload") as executor:
        for offset in range(0, len(candidates), batch_size):
            batch = candidates[offset:offset + batch_size]
            for outcome, key in executor.map(upload_one, batch):
                processed += 1
                if outcome == "uploaded":
                    uploaded += 1
                else:
                    skipped_existing += 1
                if include_keys:
                    keys.append(key)
            if processed % progress_every == 0 or processed == len(candidates):
                elapsed_seconds = round(time.monotonic() - started_at, 1)
                print(json.dumps({
                    "event": "UPLOAD_PROGRESS",
                    "processed": processed,
                    "total": len(candidates),
                    "uploaded": uploaded,
                    "skipped_existing": skipped_existing,
                    "elapsed_seconds": elapsed_seconds,
                }, ensure_ascii=False), flush=True)

    result = {
        "uploaded": uploaded,
        "skipped_existing": skipped_existing,
        "processed": processed,
        "bucket": bucket,
        "prefix": prefix.strip("/"),
    }
    if include_keys:
        result["keys"] = keys
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--readiness-csv", type=Path)
    parser.add_argument("--case-manifest", type=Path)
    parser.add_argument("--all-aihub", action="store_true",
                        help="manifest 없이 AI-Hub 전체 DAMAGE/DAMAGE_PART 원본을 대상으로 함")
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--prefix", default="", help="선택적 S3 key prefix")
    parser.add_argument("--source", choices=("all", "AIHUB_AS", "AIHUB_SC"), default="all")
    parser.add_argument("--include-damage-reference", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--workers", type=int, default=16,
                        help="동시에 처리할 S3 요청 수(기본 16)")
    parser.add_argument("--progress-every", type=int, default=1_000,
                        help="이 처리 건수마다 진행 JSON을 출력(기본 1000)")
    parser.add_argument("--dry-run", action="store_true", help="후보만 검증하고 S3에는 업로드하지 않음")
    parser.add_argument("--report", type=Path, help="JSON 실행 결과 저장 경로")
    args = parser.parse_args()

    if args.all_aihub:
        if args.readiness_csv or args.case_manifest:
            parser.error("--all-aihub에서는 --readiness-csv/--case-manifest를 지정할 수 없습니다")
        if args.include_damage_reference:
            parser.error("--all-aihub은 DAMAGE와 DAMAGE_PART를 모두 포함하므로 --include-damage-reference가 불필요합니다")
        candidates = build_all_aihub_upload_candidates(
            dataset_root=args.dataset_root,
            source=args.source,
            limit=args.limit,
        )
    else:
        if not args.subset_root or not args.readiness_csv or not args.case_manifest:
            parser.error("기본 검색 코퍼스 모드에는 --subset-root, --readiness-csv와 --case-manifest가 필요합니다")
        candidates = build_upload_candidates(
            subset_root=args.subset_root,
            dataset_root=args.dataset_root,
            readiness_csv=args.readiness_csv,
            case_manifest=args.case_manifest,
            include_damage_reference=args.include_damage_reference,
            source=args.source,
            limit=args.limit,
        )
    result: dict[str, Any] = {
        "policy": (
            "all_aihub_damage_and_damage_part"
            if args.all_aihub else (
                "damage_part_search__damage_reference_optional"
                if args.include_damage_reference else "damage_part_search_only"
            )
        ),
        "bucket": args.bucket,
        "prefix": args.prefix.strip("/"),
        "dry_run": args.dry_run,
        "overwrite": args.overwrite,
        **candidate_summary(candidates),
    }
    if args.dry_run:
        result["status"] = "DRY_RUN"
    else:
        result.update(upload_candidates(
            candidates,
            bucket=args.bucket,
            prefix=args.prefix,
            overwrite=args.overwrite,
            workers=args.workers,
            progress_every=args.progress_every,
            include_keys=not args.all_aihub,
        ))
        result["status"] = "SUCCEEDED"

    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
