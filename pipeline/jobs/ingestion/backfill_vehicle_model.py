"""repair_case 의 차량 식별 정보를 견적서 원천에서 백필한다.

기존 적재기는 ``차량정보.모델``(트림, 예: '1.6 GDI 스마트')을 ``model_name`` 에 넣고
``model_id`` 는 비워 두었다. 검색의 차량 축과 화면의 모델명이 모두 여기서 막힌다.

이 job 은 견적서 JSON 만 읽어 ``(source, external_ref)`` 로 ``repair_case`` 를 UPDATE 한다.
임베딩은 ``repair_case_roi_embedding`` 에 있고 차량 정보와 무관하므로 **재임베딩이 필요 없다.**

    python -m pipeline.jobs.ingestion.backfill_vehicle_model \
        --estimate-root "<TS_99. 붙임_견적서 경로>" \
        --dsn "$DATABASE_URL" \
        --commit-every 1000

--dry-run 은 DB 없이 해석 결과 분포만 출력한다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Iterator

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from pipeline.standardization.vehicle_names import (  # noqa: E402
    alias_version, resolve_vehicle, split_manufacturer,
)

_HEAD_BYTES = 4096
_RE_NAME = re.compile(r'"차량명칭"\s*:\s*"([^"]*)"')
_RE_MAKER = re.compile(r'"제작사/차종"\s*:\s*"([^"]*)"')


def source_of(path: Path) -> str:
    return "AIHUB_SC" if path.name.startswith("sc") else "AIHUB_AS"


def read_vehicle(path: Path) -> tuple[str, str] | None:
    """차량명칭·제작사만 뽑는다. 두 값 모두 파일 앞부분에 있어 전량 파싱하지 않는다."""
    try:
        with path.open("rb") as handle:
            head = handle.read(_HEAD_BYTES).decode("utf-8", "ignore")
        name = _RE_NAME.search(head)
        maker = _RE_MAKER.search(head)
        if name is None:                                  # 드물게 앞부분을 벗어난다
            text = path.read_text(encoding="utf-8", errors="ignore")
            name = _RE_NAME.search(text)
            maker = _RE_MAKER.search(text)
        return (name.group(1).strip() if name else "",
                maker.group(1).strip() if maker else "")
    except OSError:
        return None


def rows(root: Path, workers: int,
         limit: int | None = None) -> Iterator[tuple[str, str, dict[str, Any]]]:
    paths = sorted(p for p in root.rglob("*.json") if p.is_file())
    # ThreadPoolExecutor.map 은 목록 전체를 즉시 제출한다. limit 을 소비 쪽에서만
    # 걸면 표본 검증인데 전수를 읽고 앉아 있게 된다 — 목록에서 먼저 자른다.
    if limit:
        paths = paths[:limit]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for path, parsed in zip(paths, pool.map(read_vehicle, paths, chunksize=64)):
            if parsed is None:
                continue
            raw_name, raw_maker = parsed
            ref = resolve_vehicle(raw_name)
            maker, body = split_manufacturer(raw_maker)
            yield source_of(path), path.stem, {
                "model_name": ref.model_name or ref.normalized or None,
                "price_tier": ref.price_tier,
                "manufacturer": maker,
                "body_type": body,
                "resolved": ref.resolved,
                "has_tier": ref.price_tier is not None,
            }


UPDATE = """
    UPDATE repair_case
       SET model_id     = vm.model_id,
           model_name   = COALESCE(%(model_name)s, repair_case.model_name),
           manufacturer = COALESCE(%(manufacturer)s, repair_case.manufacturer),
           price_tier   = COALESCE(%(price_tier)s, vm.price_tier)
      FROM (SELECT %(model_name)s::varchar AS name) AS want
      LEFT JOIN vehicle_model vm ON vm.model_name = want.name
     WHERE repair_case.source = %(source)s
       AND repair_case.external_ref = %(external_ref)s
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--estimate-root", type=Path, required=True,
                        help="TS_99. 붙임_견적서 디렉터리")
    parser.add_argument("--dsn", help="PostgreSQL DSN 또는 DATABASE_URL")
    parser.add_argument("--commit-every", type=int, default=1000)
    parser.add_argument("--workers", type=int, default=24)
    parser.add_argument("--limit", type=int, help="최대 파일 수(표본 검증용)")
    parser.add_argument("--dry-run", action="store_true",
                        help="DB 없이 해석 결과 분포만 출력")
    args = parser.parse_args()
    if not args.dry_run and not args.dsn:
        parser.error("실제 갱신에는 --dsn 이 필요합니다")
    if not args.estimate_root.is_dir():
        parser.error(f"견적서 경로가 없습니다: {args.estimate_root}")

    stats = Counter()
    stream = rows(args.estimate_root, args.workers, args.limit)

    if args.dry_run:
        for _src, _ref, fields in stream:
            stats["파일"] += 1
            stats["모델명 해석"] += fields["resolved"]
            stats["가격대 부여"] += fields["has_tier"]
            stats["제조사 분리"] += fields["manufacturer"] is not None
        report(stats)
        return

    import psycopg
    with psycopg.connect(args.dsn) as conn:
        with conn.cursor() as cur:
            for i, (source, external_ref, fields) in enumerate(stream, 1):
                if args.limit and i > args.limit:
                    break
                cur.execute(UPDATE, {"source": source, "external_ref": external_ref,
                                     **{k: fields[k] for k in
                                        ("model_name", "manufacturer", "price_tier")}})
                stats["파일"] += 1
                stats["갱신된 행"] += cur.rowcount
                stats["모델명 해석"] += fields["resolved"]
                stats["가격대 부여"] += fields["has_tier"]
                if i % args.commit_every == 0:
                    conn.commit()
        conn.commit()
    report(stats)


def report(stats: Counter) -> None:
    total = stats["파일"] or 1
    print(json.dumps({
        "alias_version": alias_version(),
        **{k: v for k, v in stats.items()},
        "모델명 해석 비율": round(stats["모델명 해석"] / total, 4),
        "가격대 부여 비율": round(stats["가격대 부여"] / total, 4),
    }, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
