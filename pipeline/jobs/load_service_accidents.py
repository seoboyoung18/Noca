"""실제 수리비가 기록된 서비스 사고를 ``repair_case`` 에 ``source='SERVICE'`` 로 증분 적재한다.

S15P21A307-223. 상위 Story S15P21A307-219(AI-Hub 일괄 적재)가 만든 규칙을 그대로 따르되,
원천이 파일이 아니라 **같은 DB 의 서비스 테이블**이라는 점만 다르다.

무엇을 적재하나
    ``accident.actual_repair_cost IS NOT NULL`` 인 사고만. 그 값이 "정비소에서 실제로
    나간 금액" 이다. 견적(``estimate``·``estimate_item``)은 AI 추정치라 읽지 않는다.

왜 검색에 안 쓰나
    적재한 SERVICE 행은 **사례 검색에 쓰지 않는다.** 사용자 사고의 금액은 사례로 색인할
    만큼 검증된 값이 아니고, 무엇보다 다른 사람의 데이터다. 공개 정책이 정해지기 전까지
    AI-Hub 사례만 연다. 배제는 조회 쪽에서 한다 —
    ``RepairCaseDetailRepository.findPublicCase`` 와 ``SimilarCaseRepository.findCases``
    둘 다 ``rc.source <> 'SERVICE'`` 를 건다.

    그러면 왜 적재하나. 사고 이력 보관(상위 Epic)과 통계의 원천이기 때문이다.
    "적재한다" 와 "검색에 쓴다" 는 별개다.

증분은 어떻게 되나
    ``uk_rc (source, external_ref)`` 위의 ``ON CONFLICT`` 하나로 한다. 적재 자격은
    ``accident_id`` 순서가 아니라 **수리비가 입력된 시점**에 생기므로, 마지막 id 나
    타임스탬프로 잘라 읽으면 과거 사고가 영영 누락된다. 전건을 훑고 upsert 한다.

    ``DO UPDATE`` 에 ``IS DISTINCT FROM`` 조건을 달아 **값이 그대로면 아무것도 쓰지 않는다.**
    그래서 두 번째 실행은 새 건과 정정된 건만 건드린다.

실행 예시
    python pipeline/jobs/load_service_accidents.py --dsn "$DATABASE_URL" --dry-run
    python pipeline/jobs/load_service_accidents.py --dsn "$DATABASE_URL"

    DSN 을 인자에 직접 적지 말고 환경변수로 넘긴다. 자격증명은 로그에도 남기지 않는다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[1]
if str(PIPELINE_ROOT) not in sys.path:
    sys.path.insert(0, str(PIPELINE_ROOT))

JOB_NAME = "service_accident_load"
JOB_VERSION = "001"

SOURCE = "SERVICE"
# AI-Hub 는 as-/sc- 를 쓴다. 형제 job 들의 SOURCE_BY_PREFIX 에 'sv' 가 없으므로, SERVICE 행이
# AI-Hub 경로로 잘못 흘러들면 KeyError 로 즉시 멈춘다. 조용히 틀리는 것보다 낫다.
EXTERNAL_REF_PREFIX = "svc-"

# 적재 대상. 이 한 줄이 이 job 의 범위 전체다.
#
# S15P21A307-353 — 승인된 건만 적재한다.
#   전에는 actual_repair_cost 만 봤다. 그래서 관리자가 accident_review 에서 반려한 사고도
#   그대로 재학습 데이터로 흘러갔다. S15P21A307-352 가 만든 승인·반려가 적재에 아무 영향이
#   없었다는 뜻이다.
#
#   EXISTS 로 건다. JOIN 이 아닌 이유는 accident_review 가 사고당 한 행(uk_ar_accident)이라
#   행이 불어나지는 않지만, 조건을 WHERE 안에 두는 편이 "적재 자격" 이라는 뜻이 분명하기
#   때문이다. 검수 행이 아예 없는 사고(미검수)도 EXISTS 가 거짓이라 함께 빠진다.
#
#   ⚠️ 이미 적재된 행은 건드리지 않는다. repair_case 에 125,006건이 들어 있고 소급 삭제는
#     되돌릴 수 없다. 소급 처리는 미결이며 answer79 에 적었다.
ELIGIBLE_CONDITION = """a.actual_repair_cost IS NOT NULL
       AND EXISTS (SELECT 1 FROM accident_review ar
                    WHERE ar.accident_id = a.accident_id
                      AND ar.status = 'APPROVED')"""

# accident 의 차량 정보는 **접수 당시 불변 스냅샷**이다. vehicle·vehicle_model 마스터를
# 다시 조회해 덮어쓰면 과거 조건이 바뀐다. vehicle_model 조인은 model_id FK 가 아직
# 살아 있는지 확인하는 용도뿐이다 — 사라졌으면 NULL 로 넣는다(ON DELETE SET NULL 과 같은 결과).
_ELIGIBLE_CTE = """
WITH eligible AS (
    SELECT a.accident_id,
           a.snapshot_model_id,
           a.snapshot_manufacturer,
           a.snapshot_model_name,
           a.snapshot_car_class,
           a.snapshot_model_year,
           a.actual_repair_cost,
           EXTRACT(YEAR FROM a.actual_repair_completed_date)::smallint AS repair_year
      FROM accident a
     WHERE {condition}
     ORDER BY a.accident_id{limit}
)
"""


def _eligible_cte(limit: int | None) -> str:
    return _ELIGIBLE_CTE.format(
        condition=ELIGIBLE_CONDITION,
        limit="\n     LIMIT %(limit)s" if limit is not None else "",
    )


def external_ref(accident_id: int) -> str:
    """``repair_case.external_ref`` 값. ``VARCHAR(50)`` 안에 들어간다.

    NULL 로 두면 안 된다 — ``uk_rc`` 가 ``(source, external_ref)`` 인데 PostgreSQL 에서
    NULL 은 서로 다른 값이라 유일성이 걸리지 않고, 증분 자체가 성립하지 않는다.
    """
    return f"{EXTERNAL_REF_PREFIX}{accident_id}"


def upsert_sql(limit: int | None = None) -> str:
    """적재 SQL. 한 문장이라 전건이 함께 커밋되거나 함께 롤백된다.

    ``RETURNING (xmax = 0)`` 으로 새로 넣은 행과 고쳐 쓴 행을 가른다. 값이 그대로인 행은
    ``DO UPDATE`` 의 ``WHERE`` 에 걸려 아예 반환되지 않는다.

    ``labor_rate`` · ``claim_amount`` · ``paid_amount`` 는 보험 정산 개념이라 서비스 사고에
    없다. INSERT 에서 NULL 로 두고, UPDATE 대상에서도 뺀다 — 나중에 사람이 손으로 채운
    값을 이 job 이 지우지 않게 하기 위해서다.
    """
    return _eligible_cte(limit) + """
INSERT INTO repair_case (
    source, external_ref, model_id, manufacturer, model_name, car_class,
    model_year, repair_year, labor_rate, total_cost, claim_amount, paid_amount)
SELECT %(source)s,
       %(prefix)s || e.accident_id,
       vm.model_id,
       e.snapshot_manufacturer,
       e.snapshot_model_name,
       e.snapshot_car_class,
       e.snapshot_model_year,
       e.repair_year,
       NULL,
       e.actual_repair_cost,
       NULL,
       NULL
  FROM eligible e
  LEFT JOIN vehicle_model vm ON vm.model_id = e.snapshot_model_id
ON CONFLICT (source, external_ref) DO UPDATE SET
    model_id     = EXCLUDED.model_id,
    manufacturer = EXCLUDED.manufacturer,
    model_name   = EXCLUDED.model_name,
    car_class    = EXCLUDED.car_class,
    model_year   = EXCLUDED.model_year,
    repair_year  = EXCLUDED.repair_year,
    total_cost   = EXCLUDED.total_cost
  WHERE repair_case.model_id     IS DISTINCT FROM EXCLUDED.model_id
     OR repair_case.manufacturer IS DISTINCT FROM EXCLUDED.manufacturer
     OR repair_case.model_name   IS DISTINCT FROM EXCLUDED.model_name
     OR repair_case.car_class    IS DISTINCT FROM EXCLUDED.car_class
     OR repair_case.model_year   IS DISTINCT FROM EXCLUDED.model_year
     OR repair_case.repair_year  IS DISTINCT FROM EXCLUDED.repair_year
     OR repair_case.total_cost   IS DISTINCT FROM EXCLUDED.total_cost
RETURNING (xmax = 0) AS inserted
"""


_PREVIEW_BODY = """
SELECT e.accident_id,
       %(prefix)s || e.accident_id                AS external_ref,
       e.snapshot_manufacturer                    AS manufacturer,
       e.snapshot_model_name                      AS model_name,
       e.snapshot_car_class                       AS car_class,
       e.snapshot_model_year                      AS model_year,
       e.repair_year                              AS repair_year,
       e.actual_repair_cost                       AS total_cost,
       vm.model_id                                AS model_id,
       (e.snapshot_model_id IS NOT NULL
        AND vm.model_id IS NULL)                  AS model_id_dropped,
       (rc.case_id IS NOT NULL)                   AS exists_already
  FROM eligible e
  LEFT JOIN vehicle_model vm ON vm.model_id = e.snapshot_model_id
  LEFT JOIN repair_case rc
         ON rc.source = %(source)s
        AND rc.external_ref = %(prefix)s || e.accident_id
 ORDER BY e.accident_id
"""

_PREVIEW_COLUMNS = (
    "accident_id", "external_ref", "manufacturer", "model_name", "car_class",
    "model_year", "repair_year", "total_cost", "model_id", "model_id_dropped",
    "exists_already",
)


def preview_sql(limit: int | None = None) -> str:
    """미리보기 SQL. 읽기만 한다."""
    return _eligible_cte(limit) + _PREVIEW_BODY


ELIGIBLE_PREVIEW_SQL = preview_sql()


def _params(limit: int | None) -> dict[str, Any]:
    params: dict[str, Any] = {"source": SOURCE, "prefix": EXTERNAL_REF_PREFIX}
    if limit is not None:
        params["limit"] = limit
    return params


def preview_service_cases(cur, limit: int | None = None) -> list[dict[str, Any]]:
    """무엇이 들어갈지 보여 준다. **아무것도 쓰지 않는다.**

    ``--limit`` 은 미리보기에도 같은 창을 적용해야 실제 실행과 같은 배치를 보여 준다.
    """
    cur.execute(preview_sql(limit), _params(limit))
    return [dict(zip(_PREVIEW_COLUMNS, row)) for row in cur.fetchall()]


def count_dropped_model_ids(cur, limit: int | None = None) -> int:
    """스냅샷의 ``model_id`` 가 마스터에서 사라져 NULL 로 들어가는 건수.

    적재 대상 전체를 센다 — 이번에 새로 쓴 행만이 아니다. 이 수치가 늘면 마스터 정리가
    스냅샷을 앞질렀다는 뜻이라 그 자체로 신호다.
    """
    cur.execute(_eligible_cte(limit) + """
        SELECT count(*)
          FROM eligible e
          LEFT JOIN vehicle_model vm ON vm.model_id = e.snapshot_model_id
         WHERE e.snapshot_model_id IS NOT NULL AND vm.model_id IS NULL
    """, _params(limit))
    return cur.fetchone()[0]


def count_eligible(cur, limit: int | None = None) -> int:
    cur.execute(_eligible_cte(limit) + "SELECT count(*) FROM eligible", _params(limit))
    return cur.fetchone()[0]


def upsert_service_cases(cur, limit: int | None = None) -> dict[str, int]:
    """적재하고 건수를 돌려준다. 커밋은 호출자가 한다.

    한 문장이라 **사고 하나가 실패하면 전건이 롤백된다.** 그렇게 정한 이유는 이 job 에
    행 단위 실패 경로가 사실상 없기 때문이다 — 원천이 파일이 아니라 같은 DB 의 테이블이고,
    옮기는 일곱 컬럼이 모두 ``accident`` 쪽 제약이 ``repair_case`` 쪽과 같거나 더 좁다
    (``car_class`` 는 CHECK 가 같은 4값, 나머지는 타입·길이가 같다). 남는 실패는 연결
    끊김처럼 어차피 전건에 걸리는 것뿐이고, 그때는 부분 적재보다 재실행이 낫다.
    """
    eligible = count_eligible(cur, limit)
    dropped = count_dropped_model_ids(cur, limit)
    cur.execute(upsert_sql(limit), _params(limit))
    outcomes = [row[0] for row in cur.fetchall()]
    inserted = sum(1 for flag in outcomes if flag)
    updated = len(outcomes) - inserted
    return {
        "eligible": eligible,
        "inserted": inserted,
        "updated": updated,
        "unchanged": eligible - inserted - updated,
        "model_id_null": dropped,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dsn", required=True,
                        help="PostgreSQL DSN. 환경변수로 넘긴다 — 셸 히스토리에 남기지 말 것")
    parser.add_argument("--limit", type=int,
                        help="이번 실행에서 처리할 사고 수 상한 (accident_id 오름차순)")
    parser.add_argument("--dry-run", action="store_true",
                        help="무엇이 들어갈지만 보여 주고 아무것도 쓰지 않는다")
    parser.add_argument("--preview-rows", type=int, default=20,
                        help="--dry-run 이 본문에 출력할 행 수")
    args = parser.parse_args()

    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - 실행 환경 문제
        raise SystemExit('psycopg가 필요합니다: pip install "psycopg[binary]"') from exc

    summary: dict[str, Any] = {
        "job": JOB_NAME,
        "job_version": JOB_VERSION,
        "source": SOURCE,
        "external_ref_prefix": EXTERNAL_REF_PREFIX,
        "eligible_condition": ELIGIBLE_CONDITION,
        "limit": args.limit,
        "dry_run": bool(args.dry_run),
    }

    with psycopg.connect(args.dsn) as conn:
        if args.dry_run:
            # 미리보기는 batch_job_execution 행도 남기지 않는다. "아무것도 쓰지 않는다" 다.
            with conn.cursor() as cur:
                rows = preview_service_cases(cur, args.limit)
            conn.rollback()
            summary.update({
                "status": "DRY_RUN",
                "eligible": len(rows),
                "would_insert": sum(1 for row in rows if not row["exists_already"]),
                "already_loaded": sum(1 for row in rows if row["exists_already"]),
                "model_id_null": sum(1 for row in rows if row["model_id_dropped"]),
                "preview": rows[:args.preview_rows],
            })
            print(json.dumps(summary, ensure_ascii=False, indent=2, default=str))
            return

        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO batch_job_execution(job_name, job_version, status, input_ref, summary)
                VALUES (%s, %s, 'RUNNING', %s, %s::jsonb)
                RETURNING batch_job_execution_id
                """,
                (JOB_NAME, JOB_VERSION, "accident.actual_repair_cost",
                 json.dumps(summary, ensure_ascii=False)),
            )
            execution_id = cur.fetchone()[0]
        conn.commit()

        try:
            with conn.cursor() as cur:
                counts = upsert_service_cases(cur, args.limit)
            conn.commit()
        except Exception as exc:
            # 실패한 트랜잭션 위에서는 UPDATE 도 안 되므로 먼저 롤백한다.
            # 롤백하지 않으면 batch 가 RUNNING 으로 영영 남는다 (load_search_data.py 와 같은 처리).
            conn.rollback()
            summary.update({
                "status": "FAILED",
                "failure_type": type(exc).__name__,
                "failure_reason": str(exc),
            })
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE batch_job_execution
                       SET status='FAILED', completed_at=now(), summary=%s::jsonb
                     WHERE batch_job_execution_id=%s
                    """,
                    (json.dumps(summary, ensure_ascii=False), execution_id),
                )
            conn.commit()
            raise

        summary.update(counts)
        summary["status"] = "SUCCEEDED"
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE batch_job_execution
                   SET status=%s, completed_at=now(), summary=%s::jsonb
                 WHERE batch_job_execution_id=%s
                """,
                (summary["status"], json.dumps(summary, ensure_ascii=False), execution_id),
            )
        conn.commit()

    print(json.dumps(summary, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
