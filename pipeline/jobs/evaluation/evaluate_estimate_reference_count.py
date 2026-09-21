"""견적 참조 사례 수(N)를 leave-one-case-out으로 평가한다.

정답은 query 사례의 **전체 사고 견적 총액이 아니라 같은 case + 같은 부품**의 유효
FULL_REPAIR 비용이다. 한 사고 견적에는 다른 부품 비용이 섞이므로 총액을 정답으로
쓰면 부품 단위 예측을 평가할 수 없다.

비용 행 필터·집계·분위수는 운영 `EstimateService`의 것을 그대로 import 한다.
여기서 다시 구현하면 평가와 운영이 조용히 갈라진다.

    python pipeline/jobs/evaluation/evaluate_estimate_reference_count.py \
      --dataset-root <01.데이터> --query-manifest <query_manifest.csv> \
      --output-json out.json --output-csv out.csv --candidate-k 200
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from AI.server.app.adapters.ultralytics_yolo import ModelRunError, ModelSpec, UltralyticsRunner
from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.infrastructure.vector_repository import VectorRepository
from AI.server.app.services.estimate_service import (
    EstimateService, _aggregate_case, _is_included_row, _percentiles,
)
from pipeline.jobs.evaluation.audit_yolo_full_repair_coverage import (
    read_manifest, valid_work_costs,
)
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    DAMAGE_MODEL, PART_MODEL, db_case_ids, rerank, resolve_image_path, run_query_yolo,
)
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi

REPAIR_FAMILY_METHODS = {"coating", "sheet_metal", "repair"}
GROUP_EXCHANGE = "EXCHANGE_INCLUDED"
GROUP_REPAIR = "REPAIR_FAMILY"
GROUP_OTHER = "OTHER_VALID_WORK"

# all      : 작업 방식을 나누지 않는다 (현재 코드 동작)
# majority : 가까운 사례 다수결로 그룹을 정한다 (운영에서 실제로 가능한 방식)
# oracle   : 정답 사례의 그룹을 쓴다. 누설이므로 상한 참고용으로만 본다
VARIANTS = ("all", "majority", "oracle")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-csv", type=Path, required=True)
    parser.add_argument("--candidate-k", type=int, default=200)
    parser.add_argument("--flat-boost", type=float, default=0.05)
    parser.add_argument("--min-cases", type=int, default=5)
    parser.add_argument("--reference-counts", default="5,10,15,20,30,50")
    parser.add_argument("--pipeline-version-id", type=int, default=2)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--majority-window", type=int, default=5)
    return parser.parse_args()


def work_group(methods: list[str]) -> str:
    if "exchange" in methods:
        return GROUP_EXCHANGE
    if set(methods) & REPAIR_FAMILY_METHODS:
        return GROUP_REPAIR
    return GROUP_OTHER


def truth_cost(repository: PostgresCostCaseRepository, case_id: int, part_code: str) -> dict[str, Any] | None:
    """정답 = 같은 case + 같은 부품의 유효 FULL_REPAIR 비용."""
    rows = [row for row in repository.fetch_case_items([case_id], [part_code])
            if _is_included_row(row)]
    if not rows:
        return None
    cost = _aggregate_case(case_id, part_code, rows)
    if cost is None:
        return None
    return {"total": int(cost.total), "group": work_group(sorted(cost.methods))}


def ordered_references(
    pool: list[Any], costs: dict[int, dict[str, Any]], part_code: str, flat_boost: float,
) -> list[dict[str, Any]]:
    """유사도(YOLO boost 반영) 순으로 정렬한 유효 비용 사례."""
    ordered = rerank(pool, part_code, flat_boost, len(pool))
    output: list[dict[str, Any]] = []
    for hit in ordered:
        data = costs.get(hit.case_id)
        if data is None:
            continue
        output.append({
            "caseId": hit.case_id, "total": data["total"],
            "group": work_group(data["methods"]),
            "similarity": float(hit.reranked_similarity or hit.similarity),
            "visualPartMatched": bool(getattr(hit, "corpus_part_matched", False))
                                 and getattr(hit, "corpus_part_code", None) == part_code,
        })
    return output


def select(references: list[dict[str, Any]], variant: str, truth_group: str,
           window: int) -> list[dict[str, Any]]:
    if variant == "all":
        return references
    if variant == "oracle":
        return [ref for ref in references if ref["group"] == truth_group]
    counts = Counter(ref["group"] for ref in references[:window])
    if not counts:
        return []
    chosen = counts.most_common(1)[0][0]
    return [ref for ref in references if ref["group"] == chosen]


def metrics(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        return {"queryCount": 0}
    apes = [abs(r["predicted"] - r["truth"]) / r["truth"] for r in records]
    return {
        "queryCount": len(records),
        "mape": round(sum(apes) / len(apes), 4),
        "mdape": round(statistics.median(apes), 4),
        "wape": round(sum(abs(r["predicted"] - r["truth"]) for r in records)
                      / sum(r["truth"] for r in records), 4),
        "intervalHitRate": round(
            sum(1 for r in records if r["p25"] <= r["truth"] <= r["p75"]) / len(records), 4),
        "medianPredicted": int(statistics.median(r["predicted"] for r in records)),
        "medianTruth": int(statistics.median(r["truth"] for r in records)),
    }


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    counts = [int(value) for value in args.reference_counts.split(",")]
    manifest = read_manifest(args.query_manifest.resolve())
    if args.limit is not None:
        manifest = manifest[:args.limit]
    refs = sorted({str(row.get("case_id")) for row in manifest if row.get("case_id")})
    case_ids = db_case_ids(dsn, refs)
    repository = VectorRepository(
        dsn, expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray", yolo_corpus_part_boost=0.0,
    )
    cost_repository = PostgresCostCaseRepository(dsn)
    EstimateService(cost_repository)  # 비용 정책 의존을 명시적으로 남긴다
    embedder = DinoV2Embedder(EmbeddingSpec())
    runner = UltralyticsRunner(960)
    ai_root = REPO_ROOT / "AI"
    part_spec = ModelSpec("part", PART_MODEL[0], PART_MODEL[1], "detect",
                          ai_root / "models" / "part" / "damage_part_best-35ep.pt")
    damage_spec = ModelSpec("damage", DAMAGE_MODEL[0], DAMAGE_MODEL[1], "segment",
                            ai_root / "models" / "damage" / "damage_best-60ep.pt")

    seen: set[tuple[int, str]] = set()          # (case_id, part_code) dedup
    rows: list[dict[str, Any]] = []
    skipped: Counter[str] = Counter()

    for index, entry in enumerate(manifest, start=1):
        source_ref = str(entry["source_image_ref"])
        path = resolve_image_path(args.dataset_root.resolve(), source_ref)
        if not path.is_file():
            skipped["IMAGE_MISSING"] += 1
            continue
        try:
            detections = run_query_yolo(path, runner, part_spec, damage_spec, index)
        except (ModelRunError, ValueError, RuntimeError):
            skipped["YOLO_ERROR"] += 1
            continue
        own_case_id = case_ids.get(str(entry.get("case_id")))
        if own_case_id is None:
            skipped["CASE_NOT_IN_DB"] += 1
            continue
        with Image.open(path) as image:
            for detection in detections:
                part_code = detection.get("part_code")
                if detection.get("pair_status") != "PAIRED" or not part_code:
                    skipped["QUERY_PART_UNRESOLVED"] += 1
                    continue
                key = (own_case_id, str(part_code))
                if key in seen:
                    continue
                seen.add(key)
                truth = truth_cost(cost_repository, own_case_id, str(part_code))
                if truth is None:
                    skipped["NO_GROUND_TRUTH"] += 1
                    continue
                bbox = detection.get("bbox") or {}
                annotation = {
                    "bbox": [bbox.get("x"), bbox.get("y"), bbox.get("width"), bbox.get("height")],
                    "segmentation": detection.get("segmentation") or [],
                }
                roi, _ = make_roi(image, annotation, confidence=detection.get("damage_confidence"))
                vector = embedder.embed([roi])[0].tolist()
                _, pool = repository.search(
                    vector=vector, pipeline_version_id=args.pipeline_version_id,
                    damage_type=str(detection["damage_type"]), part_code=str(part_code),
                    limit=args.candidate_k, exclude_case_id=own_case_id,
                )
                costs = valid_work_costs(cost_repository, pool, str(part_code))
                references = ordered_references(pool, costs, str(part_code), args.flat_boost)
                row = {
                    "caseId": own_case_id, "externalRef": entry.get("case_id"),
                    "sourceImageRef": source_ref, "partCode": part_code,
                    "damageType": detection.get("damage_type"),
                    "truthTotal": truth["total"], "truthGroup": truth["group"],
                    "poolCount": len(pool), "validReferenceCount": len(references),
                }
                for variant in VARIANTS:
                    selected = select(references, variant, truth["group"], args.majority_window)
                    row[f"available_{variant}"] = len(selected)
                    for n in counts:
                        used = selected[:n]
                        if len(used) < args.min_cases:
                            continue
                        p25, median, p75 = _percentiles([ref["total"] for ref in used])
                        row[f"{variant}_{n}_used"] = len(used)
                        row[f"{variant}_{n}_median"] = median
                        row[f"{variant}_{n}_p25"] = p25
                        row[f"{variant}_{n}_p75"] = p75
                rows.append(row)

    summary: dict[str, Any] = {"variants": {}}
    for variant in VARIANTS:
        per_n: dict[str, Any] = {}
        full_subset = [r for r in rows if r.get(f"available_{variant}", 0) >= max(counts)]
        for n in counts:
            records = [
                {"predicted": r[f"{variant}_{n}_median"], "truth": r["truthTotal"],
                 "p25": r[f"{variant}_{n}_p25"], "p75": r[f"{variant}_{n}_p75"]}
                for r in rows if f"{variant}_{n}_median" in r
            ]
            common = [
                {"predicted": r[f"{variant}_{n}_median"], "truth": r["truthTotal"],
                 "p25": r[f"{variant}_{n}_p25"], "p75": r[f"{variant}_{n}_p75"]}
                for r in full_subset if f"{variant}_{n}_median" in r
            ]
            per_n[str(n)] = {"all": metrics(records), "commonSubset": metrics(common)}
        summary["variants"][variant] = per_n

    summary.update({
        "status": "SUCCEEDED", "queryCount": len(rows), "candidateK": args.candidate_k,
        "flatBoost": args.flat_boost, "minCases": args.min_cases,
        "referenceCounts": counts, "skipped": dict(skipped),
        "note": "정답은 같은 case + 같은 part_code 의 유효 FULL_REPAIR 비용이다. "
                "oracle 변형은 정답 그룹을 사용하므로 누설이며 상한 참고용이다.",
    })
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if rows:
        fields = sorted({key for row in rows for key in row})
        with args.output_csv.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
