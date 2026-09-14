"""견적서 JSON에서 원본 부품명·작업명을 집계한다.

map_estimate_labels.py의 입력이 되는 audit JSON을 만든다. 파일명 접두사
as-/sc-로 견적 포맷을 구분해 원본명별 등장 횟수를 포맷별로 나눠 센다.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--estimate-dir", type=Path, required=True,
                        help="견적서 JSON 디렉터리 (as-*.json / sc-*.json)")
    parser.add_argument("--output", type=Path, required=True,
                        help="audit JSON 산출 경로")
    args = parser.parse_args()

    name_counts: Counter[str] = Counter()
    source_counts: defaultdict[str, Counter[str]] = defaultdict(Counter)
    work_counts: Counter[str] = Counter()
    name_work_counts: defaultdict[str, Counter[str]] = defaultdict(Counter)
    file_counts: Counter[str] = Counter()
    bad_files: list[dict[str, str]] = []

    # is_file()은 항목마다 stat() 한 번을 강제해 대량 파일에서 느리다.
    # 이 디렉터리에는 .json만 있으므로 확장자 필터로 충분하다.
    paths = sorted(p for p in args.estimate_dir.iterdir() if p.suffix.lower() == ".json")

    for index, path in enumerate(paths, 1):
        source = "as" if path.name.startswith("as-") else "sc"
        file_counts[source] += 1
        try:
            with path.open("rb") as fh:
                payload = json.load(fh)
            for item in payload.get("수리내역", []):
                raw_name = item.get("작업항목 및 부품명")
                if not isinstance(raw_name, str):
                    continue
                raw_name = raw_name.strip()
                if not raw_name:
                    continue
                work = item.get("작업")
                work = work.strip() if isinstance(work, str) else ""
                name_counts[raw_name] += 1
                source_counts[raw_name][source] += 1
                if work:
                    work_counts[work] += 1
                    name_work_counts[raw_name][work] += 1
        except Exception as exc:  # noqa: BLE001 — 개별 파일 오류는 목록으로 남기고 계속 진행
            bad_files.append({"file": path.name, "error": str(exc)})
        if index % 20000 == 0:
            print(f"processed={index}/{len(paths)}", flush=True)

    # most_common()은 동점일 때 삽입 순서를 따라가므로 파일 순회 순서에 결과가
    # 흔들린다. 재실행 결과를 diff할 수 있도록 동점은 이름 오름차순으로 고정한다.
    def by_count_then_name(pairs: list[tuple[str, int]]) -> list[tuple[str, int]]:
        return sorted(pairs, key=lambda kv: (-kv[1], kv[0]))

    rows = [
        {
            "raw_name": name,
            "count": count,
            "as_count": source_counts[name]["as"],
            "sc_count": source_counts[name]["sc"],
            "works": by_count_then_name(list(name_work_counts[name].items())),
        }
        for name, count in by_count_then_name(list(name_counts.items()))
    ]

    result = {
        "file_counts": dict(file_counts),
        "unique_name_count": len(rows),
        "item_count": sum(name_counts.values()),
        "work_counts": by_count_then_name(list(work_counts.items())),
        "bad_files": bad_files,
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("file_counts", "unique_name_count", "item_count")},
                     ensure_ascii=False))
    print(f"bad_files={len(bad_files)} output={args.output}")


if __name__ == "__main__":
    main()
