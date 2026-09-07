"""견적서의 수리 항목 수·최종금액 이상치를 플래그한다.

EDA 방침에 따라 상한을 임의로 삭제하지 않고 검수 대상 목록만 만든다.
판정은 사분위수 기반이며 `Q3 + multiplier x IQR` 초과를 이상치로 본다.

기본 대상은 as- 포맷이다. as-의 최종금액은 `총계`, sc-는 `청구액`으로
금액 정의가 달라 한 분포로 섞으면 사분위수가 왜곡되므로, 포맷을 섞어
집계하지 않고 각각 따로 계산한다.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

AMOUNT_FIELD = {"as": "총계", "sc": "청구액"}


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_amount(value: Any) -> int | None:
    """'1,585,507' 형태의 금액 문자열을 정수로 바꾼다. 빈 값은 None."""
    if value is None:
        return None
    text = str(value).strip().replace(",", "")
    if not text:
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def quartile_stats(values: list[int], multiplier: float) -> dict[str, Any]:
    """statistics.quantiles(method='inclusive') 기준 사분위수와 상한."""
    ordered = sorted(values)
    q1, median, q3 = statistics.quantiles(ordered, n=4, method="inclusive")
    iqr = q3 - q1
    return {
        "n": len(ordered),
        "min": ordered[0],
        "q1": q1,
        "median": median,
        "q3": q3,
        "max": ordered[-1],
        "iqr": iqr,
        "multiplier": multiplier,
        "upper_threshold": q3 + multiplier * iqr,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--estimate-dir", type=Path, required=True,
                        help="견적서 JSON 디렉터리")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--source", choices=("as", "sc"), default="as",
                        help="집계 대상 견적 포맷 (기본 as)")
    parser.add_argument("--iqr-multiplier", type=float, default=1.5,
                        help="Q3 + multiplier x IQR (기본 1.5)")
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--shard-count", type=int, default=1,
                        help="파일 수가 많아 한 번에 못 돌 때 나눠 스캔한다. "
                             "샤드별 중간 산출물을 쓰고 종료하며, 이후 --merge-only로 합친다")
    parser.add_argument("--merge-only", action="store_true",
                        help="스캔을 건너뛰고 기존 case_metrics__shard*.json을 합쳐 통계·플래그만 만든다")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    prefix = f"{args.source}-"

    cases: dict[str, dict[str, int | None]] = {}
    parse_errors: list[dict[str, str]] = []
    amount_field = AMOUNT_FIELD[args.source]

    if args.merge_only:
        shard_files = sorted(output.glob("case_metrics__shard*.json"))
        if not shard_files:
            raise SystemExit(f"합칠 샤드 산출물이 없다: {output}")
        for sf in shard_files:
            part = json.loads(sf.read_text(encoding="utf-8"))
            cases.update(part["cases"])
            parse_errors.extend(part.get("parse_errors", []))
        print(f"merged {len(shard_files)} shards → cases={len(cases)}", flush=True)
    else:
        paths = sorted(p for p in args.estimate_dir.iterdir()
                       if p.suffix.lower() == ".json" and p.name.startswith(prefix))
        if args.shard_count > 1:
            paths = paths[args.shard_index::args.shard_count]
            print(f"[{args.source}] shard {args.shard_index}/{args.shard_count} "
                  f"→ {len(paths)} files", flush=True)
        else:
            print(f"[{args.source}] {len(paths)} files", flush=True)

        # 이 마운트는 파일당 지연이 커서 순차 읽기로는 파일 수만큼 대기한다.
        # 읽기만 스레드로 병렬화하고 파싱은 순차로 한다. 전량을 한 번에
        # 메모리에 올리지 않도록 배치 단위로 끊는다.
        def read_bytes_safe(path: Path) -> bytes | None:
            try:
                return path.read_bytes()
            except OSError:
                return None

        batch_size = 4000
        processed = 0
        with ThreadPoolExecutor(max_workers=64) as executor:
            for start in range(0, len(paths), batch_size):
                batch = paths[start:start + batch_size]
                for path, blob in zip(batch, executor.map(read_bytes_safe, batch, chunksize=32)):
                    processed += 1
                    if blob is None:
                        parse_errors.append({"file": path.name, "error": "read failed"})
                        continue
                    try:
                        payload = json.loads(blob)
                    except Exception as exc:  # noqa: BLE001 — 개별 파일 오류는 기록하고 계속
                        parse_errors.append({"file": path.name, "error": str(exc)})
                        continue
                    items = payload.get("수리내역")
                    totals = payload.get("수리비 정산정보", {}).get("합계", {})
                    cases[path.stem] = {
                        "item_count": len(items) if isinstance(items, list) else None,
                        "total_amount": parse_amount(totals.get(amount_field)),
                    }
                print(f"processed={processed}/{len(paths)}", flush=True)

        if args.shard_count > 1:
            shard_path = output / f"case_metrics__shard{args.shard_index}of{args.shard_count}.json"
            shard_path.write_text(json.dumps(
                {"source": args.source, "cases": cases, "parse_errors": parse_errors},
                ensure_ascii=False), encoding="utf-8")
            print(json.dumps({"shard": shard_path.name, "cases": len(cases),
                              "parse_errors": len(parse_errors)}, ensure_ascii=False))
            return

    metrics = ("item_count", "total_amount")
    stats = {}
    flags: list[dict[str, Any]] = []
    for metric in metrics:
        values = [v[metric] for v in cases.values() if isinstance(v[metric], int)]
        if len(values) < 4:
            stats[metric] = {"n": len(values), "note": "표본 부족으로 사분위수 미산출"}
            continue
        s = quartile_stats(values, args.iqr_multiplier)
        stats[metric] = s
        threshold = s["upper_threshold"]
        for case_id, row in cases.items():
            value = row[metric]
            if isinstance(value, int) and value > threshold:
                flags.append({
                    "case_id": case_id,
                    "source": args.source,
                    "metric": metric,
                    "value": value,
                    "upper_threshold": round(threshold, 2),
                    "q1": s["q1"],
                    "q3": s["q3"],
                    "iqr": s["iqr"],
                    "reason": f"{metric} > Q3 + {args.iqr_multiplier} x IQR",
                })
        stats[metric]["flagged_count"] = sum(1 for f in flags if f["metric"] == metric)

    # 결정적 순서: 지표별로, 값이 큰 것부터
    flags.sort(key=lambda f: (f["metric"], -f["value"], f["case_id"]))

    flag_path = output / "outlier_flags.csv"
    with flag_path.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=[
            "case_id", "source", "metric", "value", "upper_threshold",
            "q1", "q3", "iqr", "reason",
        ])
        writer.writeheader()
        writer.writerows(flags)

    missing = {
        m: sum(1 for v in cases.values() if not isinstance(v[m], int))
        for m in metrics
    }
    summary = {
        "batch_job_execution": {
            "execution_id": f"estimate-outliers-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
            "job_name": "estimate_outlier_flagging",
            "finished_at": now_utc(),
        },
        "input": {
            "estimate_dir": str(args.estimate_dir.resolve()),
            "source": args.source,
            "case_count": len(cases),
            "parse_errors": len(parse_errors),
        },
        "rules": {
            "upper_threshold": "Q3 + multiplier x IQR",
            "quantile_method": "statistics.quantiles(n=4, method='inclusive')",
            "policy": "상한 초과 건을 삭제하지 않고 검수 대상으로만 기록한다",
            "amount_field": amount_field,
        },
        "metrics": stats,
        "missing_value_counts": missing,
        "flagged_case_count": len({f["case_id"] for f in flags}),
        "flag_rows": len(flags),
    }
    (output / "outlier_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if parse_errors:
        (output / "outlier_parse_errors.json").write_text(
            json.dumps(parse_errors, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({
        "case_count": len(cases),
        "flagged_case_count": summary["flagged_case_count"],
        "flag_rows": summary["flag_rows"],
        "metrics": {m: {k: stats[m].get(k) for k in
                        ("n", "q1", "median", "q3", "upper_threshold", "max", "flagged_count")}
                    for m in metrics},
        "missing_value_counts": missing,
        "parse_errors": len(parse_errors),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
