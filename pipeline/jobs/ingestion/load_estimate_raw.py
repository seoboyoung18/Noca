"""원천 견적 JSON을 `aihub_estimate_raw`에 원문 그대로 전수 적재한다.

Raw 계층은 의미 분류 전 단계의 무손실 보존이다. 비용 행 생성(`repair_case_item`)은
원천을 다시 수집하지 않고 이 테이블에서 변환한다. 검색 테이블(`repair_case` 계열)
적재는 `build_search_sample_sql.py`가 맡는다.

사전 실행:
  psql "$DATABASE_URL" -f pipeline/sql/003_aihub_staging.sql
  pip install -r pipeline/requirements.txt

적재 예시:
  python pipeline/jobs/ingestion/load_estimate_raw.py \
    --estimate-root "<TS_99. 붙임_견적서 경로>" \
    --dsn "$DATABASE_URL" \
    --output-dir "<저장소 밖 결과 경로>"

중단 후 재개는 같은 명령을 다시 실행하면 된다. `(source, external_ref)`가 PK이고
`ON CONFLICT DO NOTHING`이라 이미 들어간 행은 건드리지 않으며, 시작할 때 적재된 키를
읽어 해당 파일은 읽기 자체를 건너뛴다.

`ON CONFLICT`를 `DO NOTHING`으로 둔 이유 — `loaded_at`은 "원문을 처음 확보한 시점"이다.
원천은 고정된 배포본이라 같은 `(source, external_ref)`의 payload가 달라질 일이 없고,
`DO UPDATE`를 쓰면 재실행마다 `loaded_at`이 덮여 최초 확보 시점을 잃는다. 재개와 전체
재적재도 구분할 수 없게 된다. 원천이 실제로 갱신되면 그것은 새 배포본이므로 명시적인
판단을 거쳐야 하고 조용한 덮어쓰기로 처리하지 않는다.

다건 INSERT를 쓴다. 표본 3,000건 실측에서 `executemany` 4,778건/초 · COPY→임시테이블
4,808건/초로 차이가 노이즈 수준이었고, 같은 표본의 파일 읽기가 281건/초여서 병목이
DB가 아니라 파일 I/O였다. 임시 테이블과 2단계 INSERT를 들이는 대가를 정당화할 만한
차이가 없어 `ON CONFLICT`를 직접 쓸 수 있는 쪽을 택했다.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

JOB_NAME = "estimate_raw_load"

# 파일명 stem이 external_ref다. as- 는 AIHUB_AS, sc- 는 AIHUB_SC.
# repair_case.ck_rc_src가 받는 값과 같아야 나중에 uk_rc로 조인된다.
SOURCE_BY_PREFIX = {"as": "AIHUB_AS", "sc": "AIHUB_SC"}
STEM_RE = re.compile(r"^(?P<kind>as|sc)-(?P<seq>\d+)$")

# aihub_estimate_raw.external_ref / source_file의 컬럼 길이.
# 넘치면 잘라서 넣지 않고 격리한다.
EXTERNAL_REF_MAX = 50
SOURCE_FILE_MAX = 500

INSERT_SQL = """
INSERT INTO aihub_estimate_raw(source, external_ref, payload, source_file)
VALUES (%s, %s, %s, %s)
ON CONFLICT (source, external_ref) DO NOTHING
"""


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def classify(stem: str) -> tuple[str, str]:
    """파일명 stem에서 (source, external_ref)를 정한다."""
    match = STEM_RE.match(stem)
    if not match:
        raise ValueError("as-/sc- 접두를 가진 파일명이 아닙니다: " + stem)
    return SOURCE_BY_PREFIX[match.group("kind")], stem


def estimate_paths(root: Path, shard_index: int, shard_count: int, limit: int | None) -> list[Path]:
    paths = sorted(p for p in root.iterdir() if p.is_file() and p.suffix.lower() == ".json")
    if shard_count > 1:
        paths = paths[shard_index::shard_count]
    if limit:
        paths = paths[:limit]
    return paths


def read_payload(path: Path) -> str:
    """원문 문자열을 돌려준다. JSON으로 파싱되는지 먼저 확인한다.

    payload는 jsonb라 키 순서와 공백이 보존되지 않는다. 그래도 원문 문자열을 그대로
    넘겨 Postgres가 파싱하게 두고, 여기서 하는 파싱은 깨진 파일을 걸러내는 용도다.
    """
    raw = path.read_bytes().decode("utf-8-sig")
    json.loads(raw)
    return raw


def load_existing_keys(cur) -> set[tuple[str, str]]:
    cur.execute("SELECT source, external_ref FROM aihub_estimate_raw")
    return {(row[0], row[1]) for row in cur}


def shard_suffix(shard_index: int, shard_count: int) -> str:
    if shard_count <= 1:
        return ""
    return "__shard" + str(shard_index) + "of" + str(shard_count)


def record_error(errors: list[dict[str, Any]], stats: Counter[str], error_type: str,
                 source_file: str, message: str, source: str | None = None,
                 external_ref: str | None = None) -> None:
    """오류를 조용히 넘기지 않는다. 원천 파일은 지우지 않고 목록에만 남긴다."""
    stats["errors"] += 1
    stats["error_type:" + error_type] += 1
    errors.append({
        "error_type": error_type,
        "source": source,
        "external_ref": external_ref,
        "source_file": source_file,
        "message": message,
        "detected_at": now_utc(),
    })


def iter_batches(paths: list[Path], existing: set[tuple[str, str]], batch_size: int,
                 errors: list[dict[str, Any]], stats: Counter[str],
                 by_source: Counter[str]) -> Iterator[list[tuple[str, str, str, str]]]:
    """파일을 읽어 배치 단위로 넘긴다. payload를 전부 메모리에 쌓지 않는다."""
    batch: list[tuple[str, str, str, str]] = []
    for path in paths:
        stats["files"] += 1
        source_file = str(path)
        try:
            source, external_ref = classify(path.stem)
        except ValueError as exc:
            record_error(errors, stats, "unknown_source_prefix", source_file, str(exc))
            continue
        if len(external_ref) > EXTERNAL_REF_MAX:
            record_error(errors, stats, "external_ref_too_long", source_file,
                         "external_ref " + str(len(external_ref)) + "자 > " + str(EXTERNAL_REF_MAX),
                         source, external_ref)
            continue
        if len(source_file) > SOURCE_FILE_MAX:
            record_error(errors, stats, "source_file_too_long", source_file,
                         "source_file " + str(len(source_file)) + "자 > " + str(SOURCE_FILE_MAX),
                         source, external_ref)
            continue
        if (source, external_ref) in existing:
            stats["skipped_already_loaded"] += 1
            continue
        try:
            payload = read_payload(path)
        except json.JSONDecodeError as exc:
            record_error(errors, stats, "json_parse_error", source_file, str(exc), source, external_ref)
            continue
        except UnicodeError as exc:
            record_error(errors, stats, "encoding_error", source_file, str(exc), source, external_ref)
            continue
        except OSError as exc:
            record_error(errors, stats, "read_error", source_file, str(exc), source, external_ref)
            continue

        batch.append((source, external_ref, payload, source_file))
        by_source[source] += 1
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


def write_outputs(output: Path, suffix: str, errors: list[dict[str, Any]]) -> dict[str, str]:
    """오류·격리 파일을 쓰고 경로를 돌려준다. 요약 JSON은 호출자가 마지막에 쓴다.

    요약에 산출물 경로를 담으려면 경로가 먼저 정해져야 해서 순서를 나눴다.
    """
    summary_path = output / (JOB_NAME + suffix + "_summary.json")
    errors_path = output / (JOB_NAME + suffix + "_errors.jsonl")
    quarantine_path = output / (JOB_NAME + suffix + "_quarantine.csv")

    with errors_path.open("w", encoding="utf-8", newline="") as fp:
        for error in errors:
            fp.write(json.dumps(error, ensure_ascii=False, separators=(",", ":")) + "\n")
    with quarantine_path.open("w", encoding="utf-8-sig", newline="") as fp:
        fields = ["error_type", "source", "external_ref", "source_file", "message", "quarantine_status"]
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for error in errors:
            writer.writerow({
                "error_type": error["error_type"],
                "source": error.get("source"),
                "external_ref": error.get("external_ref"),
                "source_file": error["source_file"],
                "message": error["message"],
                "quarantine_status": "QUARANTINED",
            })
    return {
        "summary": str(summary_path),
        "errors": str(errors_path),
        "quarantine_manifest": str(quarantine_path),
    }


def merge_shards(output: Path) -> dict[str, Any]:
    """샤드별 산출물을 합쳐 접미사 없는 이름으로 다시 쓴다."""
    shard_summaries = sorted(output.glob(JOB_NAME + "__shard*_summary.json"))
    if not shard_summaries:
        raise SystemExit("합칠 " + JOB_NAME + "__shard*_summary.json이 없습니다: " + str(output))

    totals: Counter[str] = Counter()
    by_source: Counter[str] = Counter()
    errors_by_type: Counter[str] = Counter()
    elapsed = 0.0
    shards = []
    for path in shard_summaries:
        data = json.loads(path.read_text(encoding="utf-8"))
        counts = data["counts"]
        for key in ("files", "inserted", "skipped_already_loaded", "errors"):
            totals[key] += counts[key]
        for source, value in counts["inserted_by_source"].items():
            by_source[source] += value
        for kind, value in counts["errors_by_type"].items():
            errors_by_type[kind] += value
        elapsed = max(elapsed, data["elapsed_seconds"])
        shards.append(path.name)

    errors: list[dict[str, Any]] = []
    for path in sorted(output.glob(JOB_NAME + "__shard*_errors.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                errors.append(json.loads(line))

    summary = {
        "job_name": JOB_NAME,
        "mode": "merge_only",
        "shards": shards,
        "elapsed_seconds": round(elapsed, 2),
        "counts": {
            "files": totals["files"],
            "inserted": totals["inserted"],
            "skipped_already_loaded": totals["skipped_already_loaded"],
            "errors": totals["errors"],
            "inserted_by_source": dict(sorted(by_source.items())),
            "errors_by_type": dict(sorted(errors_by_type.items())),
        },
    }
    summary["outputs"] = write_outputs(output, "", errors)
    Path(summary["outputs"]["summary"]).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def insert_errors(cur, execution_id: int, errors: list[dict[str, Any]]) -> None:
    for error in errors:
        cur.execute(
            """
            INSERT INTO data_validation_error(
                batch_job_execution_id, error_type, source_ref, case_external_ref, error_detail)
            VALUES (%s, %s, %s, %s, %s::jsonb)
            """,
            (execution_id, error["error_type"], error["source_file"][:SOURCE_FILE_MAX],
             error["external_ref"], json.dumps({
                 "message": error["message"],
                 "source": error["source"],
                 "detected_at": error["detected_at"],
             }, ensure_ascii=False)),
        )


def run_dry(paths: list[Path]) -> int:
    errors: list[dict[str, Any]] = []
    stats: Counter[str] = Counter()
    by_source: Counter[str] = Counter()
    started = time.perf_counter()
    for _ in iter_batches(paths, set(), 500, errors, stats, by_source):
        pass
    elapsed = time.perf_counter() - started
    print("dry-run 파일 " + str(stats["files"]) + "건, 적재 대상 " + str(sum(by_source.values()))
          + "건, 오류 " + str(stats["errors"]) + "건")
    print("출처별: " + json.dumps(dict(sorted(by_source.items())), ensure_ascii=False))
    if elapsed > 0:
        print("처리율 " + format(stats["files"] / elapsed, ".0f") + " 건/초 ("
              + format(elapsed, ".1f") + "초)")
    for error in errors[:20]:
        print("  " + error["error_type"] + ": " + error["source_file"] + " — " + error["message"])
    if len(errors) > 20:
        print("  ... 외 " + str(len(errors) - 20) + "건")
    return stats["errors"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--estimate-root", type=Path,
                        help="TS_99. 붙임_견적서 디렉터리 (원천 견적 JSON이 평면으로 들어 있다)")
    parser.add_argument("--dsn", help="PostgreSQL DSN 또는 DATABASE_URL")
    parser.add_argument("--output-dir", type=Path,
                        help="요약·오류·격리 manifest를 쓸 경로. 저장소 밖을 지정한다")
    parser.add_argument("--limit", type=int, help="최대 파일 수(표본 적재용)")
    parser.add_argument("--commit-every", type=int, default=500,
                        help="이 건수마다 INSERT 배치를 넘기고 커밋한다")
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--shard-count", type=int, default=1,
                        help="1보다 크면 정렬 목록을 나눠 이 샤드만 처리하고 샤드별 산출물을 쓴다")
    parser.add_argument("--merge-only", action="store_true",
                        help="적재를 건너뛰고 기존 샤드 산출물을 합친다")
    parser.add_argument("--dry-run", action="store_true", help="DB 없이 JSON·파일명만 검증")
    args = parser.parse_args()

    if args.merge_only:
        if not args.output_dir:
            parser.error("--merge-only에는 --output-dir이 필요합니다")
        summary = merge_shards(args.output_dir.resolve())
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return

    if not args.estimate_root:
        parser.error("--estimate-root가 필요합니다")
    root = args.estimate_root.resolve()
    if not root.is_dir():
        raise SystemExit("견적 디렉터리를 찾을 수 없습니다: " + str(root))
    if args.shard_count < 1 or not (0 <= args.shard_index < args.shard_count):
        parser.error("--shard-index는 0 이상 --shard-count 미만이어야 합니다")

    paths = estimate_paths(root, args.shard_index, args.shard_count, args.limit)
    scope = ""
    if args.shard_count > 1:
        scope = " (shard " + str(args.shard_index) + "/" + str(args.shard_count) + ")"
    print("대상 파일 " + str(len(paths)) + "건" + scope, flush=True)

    if args.dry_run:
        if run_dry(paths):
            raise SystemExit(1)
        return

    if not args.dsn:
        parser.error("실제 적재에는 --dsn이 필요합니다")
    if not args.output_dir:
        parser.error("실제 적재에는 --output-dir이 필요합니다")
    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit("psycopg가 필요합니다: pip install -r pipeline/requirements.txt") from exc

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    suffix = shard_suffix(args.shard_index, args.shard_count)

    errors: list[dict[str, Any]] = []
    stats: Counter[str] = Counter()
    by_source: Counter[str] = Counter()
    started_at = now_utc()
    started = time.perf_counter()

    with psycopg.connect(args.dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO batch_job_execution(job_name, job_version, status, input_ref)
                VALUES (%s, %s, 'RUNNING', %s) RETURNING batch_job_execution_id
                """,
                (JOB_NAME, suffix.lstrip("_") or None, str(root)[:SOURCE_FILE_MAX]),
            )
            execution_id = cur.fetchone()[0]
            existing = load_existing_keys(cur)
        conn.commit()
        print("배치 실행 id=" + str(execution_id) + ", 이미 적재된 키 "
              + str(len(existing)) + "건", flush=True)

        try:
            for batch in iter_batches(paths, existing, args.commit_every, errors, stats, by_source):
                with conn.cursor() as cur:
                    cur.executemany(INSERT_SQL, batch)
                    # rowcount는 ON CONFLICT로 건너뛴 행을 빼고 센다. 실제 적재 건수다.
                    stats["inserted"] += cur.rowcount
                conn.commit()
                print("진행 files=" + str(stats["files"]) + " inserted=" + str(stats["inserted"])
                      + " errors=" + str(stats["errors"]), flush=True)
        except Exception as exc:
            conn.rollback()
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE batch_job_execution SET status='FAILED', completed_at=now(),"
                    " error_message=%s WHERE batch_job_execution_id=%s",
                    (str(exc), execution_id))
            conn.commit()
            raise

        elapsed = time.perf_counter() - started
        summary = {
            "job_name": JOB_NAME,
            "batch_job_execution_id": execution_id,
            "started_at": started_at,
            "finished_at": now_utc(),
            "elapsed_seconds": round(elapsed, 2),
            "input": {
                "estimate_root": str(root),
                "shard_index": args.shard_index,
                "shard_count": args.shard_count,
                "limit": args.limit,
                "commit_every": args.commit_every,
            },
            "counts": {
                "files": stats["files"],
                "inserted": stats["inserted"],
                "skipped_already_loaded": stats["skipped_already_loaded"],
                "errors": stats["errors"],
                "inserted_by_source": dict(sorted(by_source.items())),
                "errors_by_type": {
                    key.split(":", 1)[1]: value
                    for key, value in sorted(stats.items()) if key.startswith("error_type:")
                },
            },
            "throughput_per_second": round(stats["files"] / elapsed, 1) if elapsed else None,
            "conflict_policy": "ON CONFLICT (source, external_ref) DO NOTHING",
        }

        with conn.cursor() as cur:
            insert_errors(cur, execution_id, errors)
            cur.execute(
                "UPDATE batch_job_execution SET status=%s, completed_at=now(), summary=%s::jsonb"
                " WHERE batch_job_execution_id=%s",
                ("PARTIAL" if errors else "SUCCEEDED",
                 json.dumps(summary["counts"], ensure_ascii=False), execution_id))
        conn.commit()

    summary["outputs"] = write_outputs(output, suffix, errors)
    Path(summary["outputs"]["summary"]).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    # 적재 자체는 끝났더라도 격리 건이 있으면 비정상 종료로 알린다.
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
