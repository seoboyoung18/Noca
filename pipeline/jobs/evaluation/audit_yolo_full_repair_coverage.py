"""Exhaustively audit whether v2 YOLO matches have usable FULL_REPAIR costs.

The job is read-only. Query detections are produced through the same
UltralyticsRunner/adapter path as the multi-query review renderer, while corpus
YOLO evidence and costs are read through the existing repositories. A JSON
checkpoint makes an interrupted or limited run resumable without rerunning
completed images.
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import os
import sys
from pathlib import Path
from typing import Any

from PIL import Image

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.adapters.ultralytics_yolo import ModelRunError, ModelSpec, UltralyticsRunner
from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from AI.server.app.services.estimate_service import (
    EstimateService, _aggregate_case, _is_included_row,
)
from pipeline.jobs.evaluation.render_query_comparison import data_url
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    DAMAGE_MODEL, PART_MODEL, db_case_ids, resolve_image_path, rerank, run_query_yolo,
)
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi


STATUS_QUERY_PART_UNRESOLVED = "QUERY_PART_UNRESOLVED"
STATUS_NO_VISUAL_MATCH = "NO_VISUAL_PART_MATCH_IN_POOL"
STATUS_NO_WORK_COST = "VISUAL_MATCH_BUT_NO_VALID_WORK_COST"
STATUS_POOL_NOT_TOP10 = "VALID_WORK_COST_IN_POOL_BUT_NOT_TOP10"
STATUS_VECTOR_VALID = "VALID_WORK_COST_IN_VECTOR_TOP10"
STATUS_YOLO_VALID = "VALID_WORK_COST_IN_YOLO_RERANK_TOP10"
STATUS_VECTOR_ESTIMABLE = "ESTIMABLE_VECTOR_TOP10"
STATUS_YOLO_ESTIMABLE = "ESTIMABLE_YOLO_RERANK_TOP10"
STATUS_POOL_ESTIMABLE = "ESTIMABLE_IN_POOL_ONLY"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-csv", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--flat-boost", type=float, default=0.05)
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    if args.batch_size < 1 or args.candidate_k < args.top_k or args.top_k < 1:
        parser.error("batch-size/top-k must be positive and candidate-k >= top-k")
    if args.flat_boost < 0:
        parser.error("flat-boost must be non-negative")
    return args


def read_manifest(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        rows = [dict(row) for row in csv.DictReader(fp)]
    return [row for row in rows if row.get("image_type", "DAMAGE") == "DAMAGE" and row.get("source_image_ref")]


def checkpoint_path(output_json: Path) -> Path:
    return output_json.with_suffix(output_json.suffix + ".checkpoint.json")


def load_checkpoint(path: Path, resume: bool) -> dict[str, Any]:
    if not resume or not path.is_file():
        return {"version": 1, "completed": {}, "excluded": {}, "manifest_count": 0}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload.get("completed"), dict):
        raise ValueError("invalid checkpoint: completed must be an object")
    payload.setdefault("excluded", {})
    return payload


def save_checkpoint(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def query_case_id(query: dict[str, Any], case_ids: dict[str, int]) -> int | None:
    value = query.get("case_id")
    if isinstance(value, int) or (isinstance(value, str) and value.isdigit()):
        return int(value)
    resolved = case_ids.get(str(query.get("external_ref") or value))
    return int(resolved) if resolved is not None else None


def serial_hit(hit: SearchHit) -> dict[str, Any]:
    return {
        "caseId": int(hit.case_id),
        "similarity": round(float(hit.vector_similarity or hit.similarity), 6),
        "corpusPartMatched": bool(hit.corpus_part_matched),
        "corpusPartCode": hit.corpus_part_code,
        "corpusPartConfidence": hit.corpus_part_confidence,
        "corpusPartOverlap": hit.corpus_part_overlap,
        "sourceImageRef": hit.source_image_ref,
    }


def valid_work_costs(
    repository: PostgresCostCaseRepository, hits: list[SearchHit], part_code: str,
) -> dict[int, dict[str, Any]]:
    """Apply the same row filter/aggregation as EstimateService.

    A PART_PRICE-only case never yields a ``_CaseCost`` because
    ``_aggregate_case`` requires at least one mapped WORK method. This keeps the
    audit separate from the optional PART_PRICE_ONLY reference feature.
    """
    by_case: dict[int, list[Any]] = {}
    for row in repository.fetch_case_items([hit.case_id for hit in hits], [part_code]):
        by_case.setdefault(int(row.case_id), []).append(row)
    output: dict[int, dict[str, Any]] = {}
    for case_id, rows in by_case.items():
        included = [row for row in rows if _is_included_row(row)]
        work = _aggregate_case(case_id, part_code, included)
        if work is not None:
            output[case_id] = {"total": int(work.total), "methods": sorted(work.methods)}
    return output


def determine_status(
    *, paired: bool, visual_matches: int, valid_pool: int, valid_vector: int,
    valid_yolo: int,
) -> str:
    if not paired:
        return STATUS_QUERY_PART_UNRESOLVED
    if visual_matches == 0:
        return STATUS_NO_VISUAL_MATCH
    if valid_pool == 0:
        return STATUS_NO_WORK_COST
    if valid_vector >= 3:
        return STATUS_VECTOR_ESTIMABLE
    if valid_yolo >= 3:
        return STATUS_YOLO_ESTIMABLE
    if valid_pool >= 3:
        return STATUS_POOL_ESTIMABLE
    if valid_yolo:
        return STATUS_YOLO_VALID
    if valid_vector:
        return STATUS_VECTOR_VALID
    return STATUS_POOL_NOT_TOP10


def audit_query(
    query: dict[str, Any], vector: list[float], repository: VectorRepository,
    cost_repository: PostgresCostCaseRepository, *, exclude_case_id: int | None,
    candidate_k: int, top_k: int, flat_boost: float,
) -> dict[str, Any]:
    part_code = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
    base = {
        "queryId": query["query_id"], "caseId": query.get("case_id"),
        "externalRef": query.get("external_ref"),
        "sourceImageRef": query.get("source_image_ref"),
        "partCode": query.get("part_code"), "damageType": query.get("damage_type"),
        "pairStatus": query.get("pair_status"),
        "partConfidence": query.get("part_confidence"),
        "damageConfidence": query.get("damage_confidence"), "bbox": query.get("bbox"),
        "querySelectionReason": query.get("query_selection_reason"),
        "status": STATUS_QUERY_PART_UNRESOLVED, "vectorPoolCount": 0,
        "visualPartMatchCount": 0, "validWorkCostPoolCount": 0,
        "validWorkCostVectorTop10Count": 0, "validWorkCostYoloTop10Count": 0,
        "vectorTop10": [], "yoloTop10": [], "validWorkCostCases": [],
        "newValidWorkCases": [], "droppedValidWorkCases": [],
    }
    if not part_code:
        return base
    _, pool = repository.search(
        vector=vector, pipeline_version_id=2, damage_type=str(query["damage_type"]),
        part_code=str(part_code), limit=candidate_k, exclude_case_id=exclude_case_id,
    )
    baseline = rerank(pool, None, 0.0, top_k)
    reranked = rerank(pool, str(part_code), flat_boost, top_k)
    visual = [hit for hit in pool if hit.corpus_part_matched and hit.corpus_part_code == part_code]
    costs = valid_work_costs(cost_repository, visual, str(part_code))
    vector_ids = {hit.case_id for hit in baseline if hit.case_id in costs}
    yolo_ids = {hit.case_id for hit in reranked if hit.case_id in costs}
    pool_ids = set(costs)
    new_ids = sorted(yolo_ids - vector_ids)
    dropped_ids = sorted(vector_ids - yolo_ids)
    by_hit = {hit.case_id: hit for hit in pool}
    base.update({
        "status": determine_status(
            paired=True, visual_matches=len(visual), valid_pool=len(pool_ids),
            valid_vector=len(vector_ids), valid_yolo=len(yolo_ids),
        ),
        "vectorPoolCount": len(pool), "visualPartMatchCount": len(visual),
        "validWorkCostPoolCount": len(pool_ids),
        "validWorkCostVectorTop10Count": len(vector_ids),
        "validWorkCostYoloTop10Count": len(yolo_ids),
        "vectorTop10": [serial_hit(hit) for hit in baseline],
        "yoloTop10": [serial_hit(hit) for hit in reranked],
        "validWorkCostCases": [
            {"caseId": case_id, "total": data["total"], "methods": data["methods"],
             **serial_hit(by_hit[case_id])}
            for case_id, data in sorted(costs.items(), key=lambda pair: (
                -(by_hit[pair[0]].vector_similarity or by_hit[pair[0]].similarity), pair[0]))
        ],
        "newValidWorkCases": [
            {"caseId": case_id, "total": costs[case_id]["total"]} for case_id in new_ids
        ],
        "droppedValidWorkCases": [
            {"caseId": case_id, "total": costs[case_id]["total"]} for case_id in dropped_ids
        ],
    })
    return base


def normalize_detection(base: dict[str, Any], detection: dict[str, Any], query_id: str) -> dict[str, Any]:
    query = dict(base)
    query.update({
        "query_id": query_id, "part_code": detection.get("part_code"),
        "damage_type": detection.get("damage_type"),
        "pair_status": detection.get("pair_status"),
        "part_confidence": detection.get("part_confidence"),
        "damage_confidence": detection.get("damage_confidence"),
        "bbox": detection.get("bbox"),
        "segmentation": detection.get("segmentation") or [],
        "query_selection_reason": "canonical_damage_detection",
    })
    return query


def process_image(
    manifest: dict[str, str], path: Path, runner: UltralyticsRunner,
    part_spec: ModelSpec, damage_spec: ModelSpec, embedder: DinoV2Embedder,
    repository: VectorRepository, cost_repository: PostgresCostCaseRepository,
    case_ids: dict[str, int], args: argparse.Namespace, image_number: int,
) -> tuple[list[dict[str, Any]], str | None]:
    try:
        detections = run_query_yolo(path, runner, part_spec, damage_spec, image_number)
    except (ModelRunError, ValueError, RuntimeError) as exc:
        return [], f"YOLO_ERROR: {exc}"
    if not detections:
        return [], "DAMAGE_NOT_DETECTED"
    base = {
        "case_id": manifest.get("case_id"), "external_ref": manifest.get("case_id"),
        "source_image_ref": manifest.get("source_image_ref"),
    }
    output: list[dict[str, Any]] = []
    with Image.open(path) as image:
        for index, detection in enumerate(detections):
            query = normalize_detection(base, detection, f"{manifest.get('source_image_ref')}#{index}")
            query["case_id_numeric"] = query_case_id(query, case_ids)
            if query.get("pair_status") != "PAIRED" or not query.get("part_code"):
                output.append(audit_query(
                    query, [], repository, cost_repository, exclude_case_id=query["case_id_numeric"],
                    candidate_k=args.candidate_k, top_k=args.top_k, flat_boost=args.flat_boost,
                ))
                continue
            bbox = query.get("bbox") or {}
            bbox_payload = [bbox.get("x"), bbox.get("y"), bbox.get("width"), bbox.get("height")]
            annotation = {"bbox": bbox_payload, "segmentation": query.get("segmentation") or []}
            roi, _ = make_roi(image, annotation, confidence=query.get("damage_confidence"))
            vector = embedder.embed([roi])[0].tolist()
            output.append(audit_query(
                query, vector, repository, cost_repository,
                exclude_case_id=query["case_id_numeric"], candidate_k=args.candidate_k,
                top_k=args.top_k, flat_boost=args.flat_boost,
            ))
    return output, None


def flat_rows(audits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for audit in audits:
        row = {key: audit.get(key) for key in (
            "queryId", "caseId", "externalRef", "sourceImageRef", "partCode",
            "damageType", "pairStatus", "status", "vectorPoolCount",
            "visualPartMatchCount", "validWorkCostPoolCount",
            "validWorkCostVectorTop10Count", "validWorkCostYoloTop10Count",
        )}
        row["newValidWorkCostCaseCount"] = len(audit.get("newValidWorkCases", []))
        row["droppedValidWorkCostCaseCount"] = len(audit.get("droppedValidWorkCases", []))
        rows.append(row)
    return rows


def metric_summary(audits: list[dict[str, Any]]) -> dict[str, Any]:
    paired = [a for a in audits if a.get("pairStatus") == "PAIRED" and a.get("partCode")]
    statuses = {status: sum(a.get("status") == status for a in audits)
                for status in (STATUS_QUERY_PART_UNRESOLVED, STATUS_NO_VISUAL_MATCH,
                               STATUS_NO_WORK_COST, STATUS_POOL_NOT_TOP10, STATUS_VECTOR_VALID,
                               STATUS_YOLO_VALID, STATUS_VECTOR_ESTIMABLE,
                               STATUS_YOLO_ESTIMABLE, STATUS_POOL_ESTIMABLE)}
    by_part: dict[str, dict[str, int]] = {}
    by_damage: dict[str, dict[str, int]] = {}
    for group, key in ((by_part, "partCode"), (by_damage, "damageType")):
        for audit in paired:
            name = str(audit.get(key) or "-")
            data = group.setdefault(name, {"queries": 0, "vectorEstimable": 0,
                                           "yoloEstimable": 0, "poolValid": 0})
            data["queries"] += 1
            data["vectorEstimable"] += int(audit["validWorkCostVectorTop10Count"] >= 3)
            data["yoloEstimable"] += int(audit["validWorkCostYoloTop10Count"] >= 3)
            data["poolValid"] += int(audit["validWorkCostPoolCount"] > 0)
    new_count = sum(len(a.get("newValidWorkCases", [])) for a in audits)
    dropped_count = sum(len(a.get("droppedValidWorkCases", [])) for a in audits)
    return {
        "queryCount": len(audits), "queryPairedCount": len(paired),
        "vectorTop10FullRepairEstimableQueries": sum(a["validWorkCostVectorTop10Count"] >= 3 for a in paired),
        "yoloRerankTop10FullRepairEstimableQueries": sum(a["validWorkCostYoloTop10Count"] >= 3 for a in paired),
        "difference": sum(a["validWorkCostYoloTop10Count"] >= 3 for a in paired)
        - sum(a["validWorkCostVectorTop10Count"] >= 3 for a in paired),
        "poolValidWorkCostQueries": sum(a["validWorkCostPoolCount"] > 0 for a in paired),
        "poolOnlyEstimableQueries": sum(a["status"] == STATUS_POOL_ESTIMABLE for a in paired),
        "yoloNewValidWorkCostCases": new_count,
        "yoloDroppedValidWorkCostCases": dropped_count,
        "statuses": statuses, "byPart": by_part, "byDamageType": by_damage,
    }


def representative(audits: list[dict[str, Any]], status: str) -> list[dict[str, Any]]:
    return [audit for audit in audits if audit.get("status") == status][:10]


def render_rep_cards(audits: list[dict[str, Any]], root: Path) -> str:
    cards: list[str] = []
    for audit in audits:
        path = resolve_image_path(root, str(audit.get("sourceImageRef") or ""))
        image = data_url(path) if path.is_file() else ""
        valid_rows = "".join(
            f"<tr><td>{item['caseId']}</td><td>{item['similarity']}</td><td>{item.get('corpusPartCode') or '-'}</td>"
            f"<td>{item.get('corpusPartConfidence') or '-'}</td><td>{item.get('corpusPartOverlap') or '-'}</td><td>{item['total']:,}</td></tr>"
            for item in audit.get("validWorkCostCases", [])
        ) or '<tr><td colspan="6">-</td></tr>'
        cards.append(f'''<article><h3>{html.escape(str(audit.get("externalRef") or audit.get("caseId")))} · {html.escape(str(audit.get("status")))}</h3>
<img src="{image}" alt="query" class="sample-img"><p>part={html.escape(str(audit.get("partCode") or "-"))} · damage={html.escape(str(audit.get("damageType") or "-"))} · pair={html.escape(str(audit.get("pairStatus") or "-"))}</p>
<p>pool valid WORK {audit.get("validWorkCostPoolCount", 0)} · vector Top-10 {audit.get("validWorkCostVectorTop10Count", 0)} · YOLO Top-10 {audit.get("validWorkCostYoloTop10Count", 0)}</p>
<table><tr><th>case</th><th>sim.</th><th>part</th><th>conf.</th><th>overlap</th><th>cost</th></tr>{valid_rows}</table></article>''')
    return "".join(cards) or '<p>해당 대표 사례 없음</p>'


def render_html(audits: list[dict[str, Any]], summary: dict[str, Any], root: Path) -> str:
    def group_table(group: dict[str, dict[str, int]]) -> str:
        return "".join(
            f"<tr><td>{html.escape(name)}</td><td>{data['queries']}</td><td>{data['vectorEstimable']}</td><td>{data['yoloEstimable']}</td><td>{data['poolValid']}</td></tr>"
            for name, data in sorted(group.items())
        ) or '<tr><td colspan="5">-</td></tr>'
    query_rows = "".join(
        f"<tr><td>{html.escape(str(a.get('externalRef') or a.get('caseId')))}</td><td>{html.escape(str(a.get('partCode') or '-'))}</td><td>{html.escape(str(a.get('damageType') or '-'))}</td><td>{html.escape(str(a.get('pairStatus') or '-'))}</td><td>{html.escape(str(a.get('status')))}</td><td>{a.get('validWorkCostPoolCount',0)}</td><td>{a.get('validWorkCostVectorTop10Count',0)}</td><td>{a.get('validWorkCostYoloTop10Count',0)}</td></tr>"
        for a in audits
    )
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>v2 YOLO FULL_REPAIR coverage audit</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}.summary,.section{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.stats{{display:flex;gap:10px;flex-wrap:wrap}}.stat{{background:#eef2ff;border-radius:8px;padding:10px 14px}}.stat b{{font-size:22px;display:block}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left}}th{{background:#f1f4fa}}.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px}}article{{background:#fff;border:1px solid #d8deea;border-radius:9px;padding:12px}}.sample-img{{width:100%;height:210px;object-fit:contain;background:#eef2f7}}.note{{background:#fff7ed;padding:10px;border-radius:8px;color:#92400e}}@media(max-width:900px){{body{{margin:10px}}}}
</style><main><h1>v2 DAMAGE · YOLO FULL_REPAIR 견적 연결률 전수 audit</h1><p class="note">vector pool {100}개를 baseline과 YOLO flat 0.05가 공유합니다. 유효 비용은 기존 EstimateService의 WORK/PART_PRICE 필터와 case 집계를 사용하되, WORK가 없는 PART_PRICE_ONLY는 FULL_REPAIR에서 제외했습니다. repair hint와 DB write는 사용하지 않았습니다.</p>
<div class="summary"><h2>전체 요약</h2><div class="stats"><div class="stat"><b>{summary['queryCount']}</b>전체 query</div><div class="stat"><b>{summary['queryPairedCount']}</b>PAIRED</div><div class="stat"><b>{summary['vectorTop10FullRepairEstimableQueries']}</b>vector Top-10 estimable</div><div class="stat"><b>{summary['yoloRerankTop10FullRepairEstimableQueries']}</b>YOLO Top-10 estimable</div><div class="stat"><b>{summary['difference']:+d}</b>차이</div><div class="stat"><b>{summary['poolValidWorkCostQueries']}</b>pool valid WORK</div><div class="stat"><b>{summary['poolOnlyEstimableQueries']}</b>pool-only estimable</div><div class="stat"><b>{summary['yoloNewValidWorkCostCases']}</b>YOLO 신규 valid case</div><div class="stat"><b>{summary['yoloDroppedValidWorkCostCases']}</b>YOLO 밀어낸 valid case</div></div></div>
<section class="section"><h2>부품별</h2><table><tr><th>partCode</th><th>query</th><th>vector estimable</th><th>YOLO estimable</th><th>pool valid</th></tr>{group_table(summary['byPart'])}</table></section>
<section class="section"><h2>damageType별</h2><table><tr><th>damageType</th><th>query</th><th>vector estimable</th><th>YOLO estimable</th><th>pool valid</th></tr>{group_table(summary['byDamageType'])}</table></section>
<section class="section"><h2>query별 상태</h2><table><tr><th>query</th><th>part</th><th>damage</th><th>pair</th><th>status</th><th>pool valid</th><th>vector Top-10</th><th>YOLO Top-10</th></tr>{query_rows}</table></section>
<section class="section"><h2>YOLO로 estimable이 된 대표 사례</h2><div class="cards">{render_rep_cards([a for a in audits if a.get('status') in (STATUS_YOLO_ESTIMABLE, STATUS_YOLO_VALID)], root)}</div></section>
<section class="section"><h2>pool에는 비용이 있지만 Top-10에 못 들어온 대표 사례</h2><div class="cards">{render_rep_cards([a for a in audits if a.get('status') in (STATUS_POOL_ESTIMABLE, STATUS_POOL_NOT_TOP10)], root)}</div></section>
<section class="section"><h2>시각 부품 일치는 있으나 유효 WORK 비용이 0인 대표 사례</h2><div class="cards">{render_rep_cards([a for a in audits if a.get('status') == STATUS_NO_WORK_COST], root)}</div></section>
</main></html>'''


def write_outputs(args: argparse.Namespace, audits: list[dict[str, Any]], root: Path) -> dict[str, Any]:
    summary = metric_summary(audits)
    payload = {"status": "SUCCEEDED", "candidateK": args.candidate_k, "topK": args.top_k,
               "flatBoost": args.flat_boost, "audits": audits, "summary": summary}
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_csv.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    rows = flat_rows(audits)
    with args.output_csv.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=list(rows[0]) if rows else ["queryId"])
        writer.writeheader(); writer.writerows(rows)
    args.output_html.write_text(render_html(audits, summary, root), encoding="utf-8")
    return summary


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    manifest_path = args.query_manifest.resolve()
    if not manifest_path.is_file():
        raise SystemExit(f"query manifest not found: {manifest_path}")
    root = args.dataset_root.resolve()
    manifest = read_manifest(manifest_path)
    state_path = checkpoint_path(args.output_json.resolve())
    state = load_checkpoint(state_path, args.resume)
    state["manifest_count"] = len(manifest)
    selected_manifest = manifest if args.limit is None else manifest[:args.limit]
    refs = sorted({str(row.get("case_id")) for row in manifest if row.get("case_id")})
    case_ids = db_case_ids(dsn, refs)
    repository = VectorRepository(
        dsn, expected_model_name="facebook/dinov2-base",
        expected_model_version="f9e44c8-pooler-pad20-lb224gray", yolo_corpus_part_boost=0.0,
    )
    cost_repository = PostgresCostCaseRepository(dsn)
    # Instantiated to keep the cost policy dependency explicit and to fail fast
    # if the service module cannot be imported in the local runtime.
    EstimateService(cost_repository)
    embedder = DinoV2Embedder(EmbeddingSpec())
    ai_root = REPO_ROOT / "AI"
    runner = UltralyticsRunner(960)
    part_spec = ModelSpec("part", PART_MODEL[0], PART_MODEL[1], "detect", ai_root / "models" / "part" / "damage_part_best-35ep.pt")
    damage_spec = ModelSpec("damage", DAMAGE_MODEL[0], DAMAGE_MODEL[1], "segment", ai_root / "models" / "damage" / "damage_best-60ep.pt")
    processed = 0
    for index, manifest_row in enumerate(selected_manifest):
        source_ref = str(manifest_row["source_image_ref"])
        key = source_ref
        if args.resume and (key in state["completed"] or key in state.get("excluded", {})):
            continue
        path = resolve_image_path(root, source_ref)
        if not path.is_file():
            state.setdefault("excluded", {})[key] = {"sourceImageRef": source_ref, "reason": "IMAGE_MISSING"}
        else:
            audits, exclusion = process_image(
                manifest_row, path, runner, part_spec, damage_spec, embedder,
                repository, cost_repository, case_ids, args, index + 1,
            )
            if exclusion:
                state.setdefault("excluded", {})[key] = {
                    "sourceImageRef": source_ref, "caseId": manifest_row.get("case_id"),
                    "reason": exclusion.split(":", 1)[0], "detail": exclusion,
                }
            else:
                state["completed"][key] = audits
        processed += 1
        if processed % args.batch_size == 0:
            save_checkpoint(state_path, state)
    save_checkpoint(state_path, state)
    audits = [audit for key, rows in state["completed"].items() for audit in rows]
    summary = write_outputs(args, audits, root)
    print(json.dumps({"status": "SUCCEEDED", "processedImages": processed,
                      "completedImages": len(state["completed"]), "excludedImages": len(state.get("excluded", {})),
                      "checkpoint": str(state_path), **summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
