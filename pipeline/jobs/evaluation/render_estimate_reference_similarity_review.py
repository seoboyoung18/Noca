"""Render the rank/similarity distribution of v2 YOLO FULL_REPAIR candidates.

This is a read-only review tool.  It reuses the canonical query detections from
the completed full-repair audit, embeds their existing damage ROI, and asks the
v2 repository for a case-level vector pool of at most 100 cases.  It does not
choose a production K or a similarity threshold.
"""
from __future__ import annotations

import argparse
import csv
import html
import json
import os
import random
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from PIL import Image

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
PROJECT_ROOT = REPO_ROOT.parent
DEFAULT_AUDIT = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-18" / "v2_yolo_full_repair_coverage_audit.json"
DEFAULT_MANIFEST = PROJECT_ROOT / "outputs" / "ab_eval_2026-09-17" / "query_manifest.csv"
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.infrastructure.cost_repository import PostgresCostCaseRepository
from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from AI.server.app.services.estimate_service import _aggregate_case, _is_included_row
from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import resolve_image_path
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi
from pipeline.jobs.evaluation.render_query_comparison import data_url


GROUPS = ("EXCHANGE_INCLUDED", "REPAIR_FAMILY", "OTHER_VALID_WORK")
K_VALUES = (10, 20, 30, 50, 100)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--audit-json", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--selection-output", type=Path, required=True)
    parser.add_argument("--output-html", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--seed", type=int, default=307)
    parser.add_argument("--review-query-count", type=int, default=30)
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error("limit must be positive")
    if args.batch_size < 1 or args.top_k < 1 or args.candidate_k < args.top_k:
        parser.error("batch-size/top-k must be positive and candidate-k >= top-k")
    if args.review_query_count < 1:
        parser.error("review-query-count must be positive")
    return args


def checkpoint_path(output: Path) -> Path:
    return output.with_suffix(output.suffix + ".checkpoint.json")


def load_checkpoint(path: Path, resume: bool) -> dict[str, Any]:
    if not resume or not path.is_file():
        return {"version": 1, "completed": {}, "manifestCount": 0}
    state = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(state.get("completed"), dict):
        raise ValueError("invalid checkpoint: completed must be an object")
    return state


def save_checkpoint(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def read_manifest_count(path: Path) -> int:
    if not path.is_file():
        return 0
    with path.open(encoding="utf-8-sig", newline="") as fp:
        return sum(1 for row in csv.DictReader(fp) if row.get("image_type", "DAMAGE") == "DAMAGE")


def verify_db_read_only(dsn: str) -> None:
    import psycopg

    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            if cursor.fetchone() != (1,):
                raise RuntimeError("read-only DB probe failed")


def db_case_ids(dsn: str, refs: list[str]) -> dict[str, int]:
    import psycopg

    if not refs:
        return {}
    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT external_ref, case_id FROM repair_case WHERE external_ref = ANY(%s)", (refs,))
            return {str(external): int(case_id) for external, case_id in cursor.fetchall()}


def recompute_costs(repository: PostgresCostCaseRepository, candidates: list[SearchHit], part_code: str) -> dict[int, dict[str, Any]]:
    ids = list(dict.fromkeys(int(hit.case_id) for hit in candidates))
    rows_by_case: dict[int, list[Any]] = defaultdict(list)
    for row in repository.fetch_case_items(ids, [part_code]):
        rows_by_case[int(row.case_id)].append(row)
    result: dict[int, dict[str, Any]] = {}
    for case_id in ids:
        included = [row for row in rows_by_case.get(case_id, []) if _is_included_row(row)]
        aggregate = _aggregate_case(case_id, part_code, included)
        if aggregate is not None:
            result[case_id] = {"total": int(aggregate.total), "methods": sorted(str(method) for method in aggregate.methods)}
    return result


def group_for_methods(methods: list[str]) -> str:
    if "exchange" in methods:
        return "EXCHANGE_INCLUDED"
    if any(method in {"coating", "sheet_metal", "repair"} for method in methods):
        return "REPAIR_FAMILY"
    return "OTHER_VALID_WORK"


def roi_vector(embedder: DinoV2Embedder, image_path: Path, bbox: dict[str, Any], confidence: Any) -> list[float]:
    with Image.open(image_path) as image:
        payload = {"bbox": [bbox.get("x"), bbox.get("y"), bbox.get("width"), bbox.get("height")], "segmentation": []}
        roi, _ = make_roi(image, payload, confidence=confidence)
    return embedder.embed([roi])[0].tolist()


def percentile_summary(values: list[float]) -> dict[str, Any]:
    if not values:
        return {"count": 0, "min": None, "p25": None, "median": None, "p75": None, "max": None}
    ordered = sorted(float(value) for value in values)
    def quantile(probability: float) -> float:
        position = (len(ordered) - 1) * probability
        lower = int(position)
        upper = min(lower + 1, len(ordered) - 1)
        fraction = position - lower
        return round(ordered[lower] + (ordered[upper] - ordered[lower]) * fraction, 6)
    p25, median, p75 = (quantile(0.25), quantile(0.5), quantile(0.75))
    return {"count": len(ordered), "min": ordered[0], "p25": p25, "median": median,
            "p75": p75, "max": ordered[-1]}


def candidate_record(hit: SearchHit, rank: int, *, top1: float, top10_floor: float,
                     cost: dict[str, Any]) -> dict[str, Any]:
    similarity = float(hit.vector_similarity if hit.vector_similarity is not None else hit.similarity)
    return {
        "rank": rank, "caseId": int(hit.case_id), "similarity": similarity,
        "top1SimilarityGap": top1 - similarity,
        "top10FloorSimilarityGap": top10_floor - similarity,
        "sourceImageRef": hit.source_image_ref,
        "group": group_for_methods(cost["methods"]), "methods": cost["methods"],
        "costTotal": cost["total"],
        "corpusPartConfidence": hit.corpus_part_confidence,
        "corpusPartOverlap": hit.corpus_part_overlap,
        "inTop10": rank <= 10, "inTop20": rank <= 20, "inTop30": rank <= 30,
        "inTop50": rank <= 50, "inTop100": rank <= 100,
    }


def audit_query(audit: dict[str, Any], image_path: Path, query_case_id: int | None,
                repository: VectorRepository, cost_repository: PostgresCostCaseRepository,
                embedder: DinoV2Embedder, candidate_k: int, top_k: int) -> dict[str, Any]:
    part_code = str(audit["partCode"])
    vector = roi_vector(embedder, image_path, audit["bbox"], audit.get("damageConfidence"))
    _, hits = repository.search(
        vector=vector, pipeline_version_id=2, damage_type=str(audit["damageType"]),
        part_code=part_code, limit=candidate_k, model_id=None, exclude_case_id=query_case_id,
    )
    unique_ids = [hit.case_id for hit in hits]
    if len(unique_ids) != len(set(unique_ids)):
        raise RuntimeError(f"case-level de-duplication failed for {audit.get('queryId')}")
    visual = [hit for hit in hits if hit.corpus_part_matched and hit.corpus_part_code == part_code]
    costs = recompute_costs(cost_repository, visual, part_code)
    top1 = float(hits[0].vector_similarity if hits and hits[0].vector_similarity is not None else hits[0].similarity) if hits else 0.0
    top10 = hits[:top_k]
    top10_floor = min(
        [float(hit.vector_similarity if hit.vector_similarity is not None else hit.similarity) for hit in top10],
        default=top1,
    )
    candidates = [
        candidate_record(hit, rank, top1=top1, top10_floor=top10_floor, cost=costs[hit.case_id])
        for rank, hit in enumerate(hits, 1) if hit.case_id in costs
    ]
    candidates.sort(key=lambda row: row["rank"])
    first_ranks = [row["rank"] for row in candidates]
    return {
        "queryId": audit.get("queryId"), "caseId": audit.get("caseId"), "sourceImageRef": audit.get("sourceImageRef"),
        "partCode": part_code, "damageType": str(audit.get("damageType") or "").upper(),
        "partConfidence": audit.get("partConfidence"), "damageConfidence": audit.get("damageConfidence"),
        "pairStatus": audit.get("pairStatus"), "queryCaseIdNumeric": query_case_id,
        "vectorPoolCaseCount": len(hits), "caseLevelDeduplicated": True,
        "top1Similarity": top1, "top10FloorSimilarity": top10_floor,
        "candidateCount": len(candidates), "firstCandidateRank": min(first_ranks) if first_ranks else None,
        "firstCandidateSimilarity": candidates[0]["similarity"] if candidates else None,
        "secondCandidateRank": candidates[1]["rank"] if len(candidates) > 1 else None,
        "thirdCandidateRank": candidates[2]["rank"] if len(candidates) > 2 else None,
        "costOnlyOutsideTop10": bool(candidates) and not any(row["inTop10"] for row in candidates),
        "candidates": candidates,
    }


def k_metrics(rows: list[dict[str, Any]], k: int) -> dict[str, Any]:
    included = [[candidate for candidate in row["candidates"] if candidate["rank"] <= k] for row in rows]
    def count_at_least(group: str | None, minimum: int) -> int:
        return sum(sum(1 for candidate in candidates if group is None or candidate["group"] == group) >= minimum for candidates in included)
    def first_metric(index: int, field: str) -> dict[str, Any]:
        values = []
        for candidates in included:
            if len(candidates) > index:
                values.append(candidates[index][field])
        return percentile_summary(values)
    firsts = {
        "rank": first_metric(0, "rank"), "similarity": first_metric(0, "similarity"),
        "top1Gap": first_metric(0, "top1SimilarityGap"), "top10FloorGap": first_metric(0, "top10FloorSimilarityGap"),
    }
    seconds = {"rank": first_metric(1, "rank"), "similarity": first_metric(1, "similarity")}
    thirds = {"rank": first_metric(2, "rank"), "similarity": first_metric(2, "similarity")}
    return {
        "k": k, "queriesWithValidAtLeast1": count_at_least(None, 1),
        "queriesWithValidAtLeast2": count_at_least(None, 2), "queriesWithValidAtLeast3": count_at_least(None, 3),
        "exchangeAtLeast1": count_at_least("EXCHANGE_INCLUDED", 1), "exchangeAtLeast2": count_at_least("EXCHANGE_INCLUDED", 2),
        "exchangeAtLeast3": count_at_least("EXCHANGE_INCLUDED", 3), "repairAtLeast1": count_at_least("REPAIR_FAMILY", 1),
        "repairAtLeast2": count_at_least("REPAIR_FAMILY", 2), "repairAtLeast3": count_at_least("REPAIR_FAMILY", 3),
        "costOnlyOutsideTop10": sum(row["costOnlyOutsideTop10"] and any(candidate["rank"] <= k for candidate in row["candidates"]) for row in rows),
        "first": firsts, "second": seconds, "third": thirds,
        "byPart": by_part_metrics(rows, k),
    }


def by_part_metrics(rows: list[dict[str, Any]], k: int) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for part in sorted({row["partCode"] for row in rows}):
        subset = [row for row in rows if row["partCode"] == part]
        base = k_metrics_without_part(subset, k)
        result[part] = base
    return result


def k_metrics_without_part(rows: list[dict[str, Any]], k: int) -> dict[str, Any]:
    included = [[candidate for candidate in row["candidates"] if candidate["rank"] <= k] for row in rows]
    count = lambda group, minimum: sum(sum(1 for c in candidates if group is None or c["group"] == group) >= minimum for candidates in included)
    return {"queries": len(rows), "valid1": count(None, 1), "valid2": count(None, 2), "valid3": count(None, 3),
            "exchange1": count("EXCHANGE_INCLUDED", 1), "exchange2": count("EXCHANGE_INCLUDED", 2),
            "exchange3": count("EXCHANGE_INCLUDED", 3), "repair1": count("REPAIR_FAMILY", 1),
            "repair2": count("REPAIR_FAMILY", 2), "repair3": count("REPAIR_FAMILY", 3)}


def choose_review(rows: list[dict[str, Any]], *, seed: int, limit: int) -> list[dict[str, Any]]:
    bins = {"1-10": [], "11-20": [], "21-30": [], "31-50": [], "51-100": []}
    for row in rows:
        rank = row.get("firstCandidateRank")
        if rank is None:
            continue
        key = "1-10" if rank <= 10 else "11-20" if rank <= 20 else "21-30" if rank <= 30 else "31-50" if rank <= 50 else "51-100"
        bins[key].append(row)
    rng = random.Random(seed)
    for values in bins.values():
        rng.shuffle(values)
    selected: list[dict[str, Any]] = []
    used_cases: set[str] = set()
    keys = list(bins)
    while len(selected) < min(limit, sum(len(values) for values in bins.values())) and any(bins.values()):
        for key in keys:
            if bins[key] and len(selected) < limit:
                row = bins[key].pop()
                case_key = str(row.get("caseId"))
                if case_key in used_cases:
                    continue
                used_cases.add(case_key)
                selected.append({"queryId": row["queryId"], "caseId": row["caseId"], "partCode": row["partCode"],
                                 "damageType": row["damageType"], "firstCandidateRank": row["firstCandidateRank"],
                                 "firstCandidateGroup": row["candidates"][0]["group"], "selectionBin": key})
    return selected


def render_card(row: dict[str, Any], root: Path) -> str:
    query_path = resolve_image_path(root, str(row.get("sourceImageRef") or ""))
    query_image = data_url(query_path) if query_path.is_file() else ""
    candidate_rows = []
    for candidate in row["candidates"]:
        path = resolve_image_path(root, str(candidate.get("sourceImageRef") or ""))
        image = data_url(path) if path.is_file() else ""
        badges = " ".join(f"K={k}" for k in K_VALUES if candidate[f"inTop{k}"])
        candidate_rows.append(
            f"<tr class='{candidate['group'].lower()}'><td>{candidate['rank']}</td><td>{candidate['similarity']:.6f}</td>"
            f"<td>{candidate['top1SimilarityGap']:.6f}</td><td>{candidate['top10FloorSimilarityGap']:.6f}</td>"
            f"<td>{html.escape(candidate['group'])}</td><td>{html.escape(', '.join(candidate['methods']))}</td>"
            f"<td>{candidate['costTotal']:,}</td><td>{candidate['caseId']}<br>{html.escape(str(candidate.get('sourceImageRef') or '-'))}</td>"
            f"<td>{badges}<br><img class='ref' src='{image}' alt='case {candidate['caseId']}'></td></tr>"
        )
    return f"""<article class='card'><h3>{html.escape(str(row['queryId']))}</h3><p><b>{html.escape(str(row['partCode']))}</b> · {html.escape(str(row['damageType']))} · part conf {row.get('partConfidence')} · damage conf {row.get('damageConfidence')} · pair {row.get('pairStatus')}</p>
<p>첫 비용 후보 rank <b>{row.get('firstCandidateRank')}</b> · similarity <b>{row.get('firstCandidateSimilarity')}</b> · pool {row.get('vectorPoolCaseCount')} cases</p><img class='query' src='{query_image}' alt='query image'>
<table><tr><th>rank</th><th>similarity</th><th>Top-1 gap</th><th>Top-10 floor gap</th><th>group</th><th>methods</th><th>cost</th><th>case/path</th><th>K badge/image</th></tr>{''.join(candidate_rows)}</table>
<label>여기까지는 손상 심각도·외관이 납득됨<textarea rows='2'></textarea></label><label>여기부터는 너무 멂<textarea rows='2'></textarea></label></article>"""


def render_html(rows: list[dict[str, Any]], metrics: dict[str, Any], selection: list[dict[str, Any]], root: Path) -> str:
    coverage_rows = "".join(
        f"<tr><td>K={metric['k']}</td><td>{metric['queriesWithValidAtLeast1']}</td><td>{metric['queriesWithValidAtLeast2']}</td><td>{metric['queriesWithValidAtLeast3']}</td><td>{metric['exchangeAtLeast1']}/{metric['exchangeAtLeast2']}/{metric['exchangeAtLeast3']}</td><td>{metric['repairAtLeast1']}/{metric['repairAtLeast2']}/{metric['repairAtLeast3']}</td><td>{metric['costOnlyOutsideTop10']}</td></tr>"
        for metric in metrics.values()
    )
    part_rows = []
    for part in sorted({row["partCode"] for row in rows}):
        m = metrics["100"]["byPart"].get(part, {})
        part_rows.append(f"<tr><td>{html.escape(part)}</td><td>{m.get('queries', 0)}</td><td>{m.get('valid1', 0)}</td><td>{m.get('valid2', 0)}</td><td>{m.get('valid3', 0)}</td><td>{m.get('exchange2', 0)}</td><td>{m.get('repair2', 0)}</td></tr>")
    selected_ids = {item["queryId"] for item in selection}
    selected_rows = [row for row in rows if row["queryId"] in selected_ids]
    cards = "".join(render_card(row, root) for row in selected_rows) or "<p>선정된 query 없음</p>"
    return f"""<!doctype html><html lang='ko'><meta charset='utf-8'><title>estimate reference similarity distribution review</title>
<style>body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin:0 0 10px}}section,.card{{background:#fff;border:1px solid #d8deea;border-radius:10px;padding:16px;margin:16px 0}}.warning{{background:#fff7ed;color:#92400e;padding:12px;border-radius:8px}}table{{width:100%;border-collapse:collapse;font-size:12px}}th,td{{border:1px solid #d8deea;padding:6px;text-align:left;vertical-align:top}}th{{background:#f1f4fa}}.exchange_included td,.exchange_included{{background:#fff0f0}}.repair_family td,.repair_family{{background:#effcf3}}.other_valid_work td,.other_valid_work{{background:#f5f5f5}}.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(580px,1fr));gap:12px}}.query{{width:100%;height:230px;object-fit:contain;background:#eef2f7}}.ref{{width:130px;height:85px;object-fit:cover;background:#eef2f7}}label{{display:block;margin-top:8px}}textarea{{width:100%;box-sizing:border-box}}</style>
<main><h1>v2 + YOLO 견적 참조 similarity / rank distribution review</h1><p class='warning'>Top-100은 탐색 범위이며, 이 결과만으로 비용 참조 범위를 확정하지 않습니다. similarity threshold와 운영 candidate K는 변경하지 않았습니다.</p>
<section><h2>K별 coverage</h2><table><tr><th>K</th><th>유효 1+</th><th>유효 2+</th><th>유효 3+</th><th>EXCHANGE 1/2/3+</th><th>REPAIR 1/2/3+</th><th>비용 후보가 Top-10 밖에만</th></tr>{coverage_rows}</table></section>
<section><h2>부품별 K=100</h2><table><tr><th>partCode</th><th>query</th><th>유효 1+</th><th>유효 2+</th><th>유효 3+</th><th>EXCHANGE 2+</th><th>REPAIR 2+</th></tr>{''.join(part_rows)}</table></section>
<section><h2>첫/두 번째/세 번째 비용 후보 분포</h2><table><tr><th>순번</th><th>rank count/median</th><th>similarity p25/median/p75</th><th>Top-1 gap p25/median/p75</th><th>Top-10 floor gap p25/median/p75</th></tr>{''.join(f"<tr><td>{label}</td><td>{metric['rank']['count']} / {metric['rank']['median']}</td><td>{metric['similarity']['p25']} / {metric['similarity']['median']} / {metric['similarity']['p75']}</td><td>{metric.get('top1Gap', {}).get('p25', '-')} / {metric.get('top1Gap', {}).get('median', '-')} / {metric.get('top1Gap', {}).get('p75', '-')}</td><td>{metric.get('top10FloorGap', {}).get('p25', '-')} / {metric.get('top10FloorGap', {}).get('median', '-')} / {metric.get('top10FloorGap', {}).get('p75', '-')}</td></tr>" for label, metric in (("1st", metrics['100']['first']), ("2nd", metrics['100']['second']), ("3rd", metrics['100']['third'])))}</table></section>
<section><h2>사람 검토용 query ({len(selected_rows)}개)</h2><p>선정 구간: 1–10, 11–20, 21–30, 31–50, 51–100. EXCHANGE_INCLUDED / REPAIR_FAMILY를 가능한 범위에서 균형화했습니다.</p><div class='cards'>{cards}</div></section>
<section><p>이 보고서는 시각 검토용이며 정답/오답, Recall/MRR, repair hint 기반 평가는 표시하지 않습니다.</p></section></main></html>"""


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    source = json.loads(args.audit_json.resolve().read_text(encoding="utf-8"))
    audits = [audit for audit in source.get("audits", []) if audit.get("pairStatus") == "PAIRED" and audit.get("partCode")]
    if not audits:
        raise SystemExit("PAIRED canonical query가 없습니다")
    verify_db_read_only(dsn)
    refs = sorted({str(audit.get("caseId")) for audit in audits if audit.get("caseId")})
    case_map = db_case_ids(dsn, refs)
    repository = VectorRepository(dsn, expected_model_name="facebook/dinov2-base",
                                  expected_model_version="f9e44c8-pooler-pad20-lb224gray",
                                  yolo_corpus_part_boost=0.0)
    cost_repository = PostgresCostCaseRepository(dsn)
    embedder = DinoV2Embedder(EmbeddingSpec())
    state_path = checkpoint_path(args.output_json.resolve())
    state = load_checkpoint(state_path, args.resume)
    state["manifestCount"] = read_manifest_count(args.query_manifest.resolve())
    selected = audits if args.limit is None or args.resume else audits[:args.limit]
    processed = 0
    for audit in selected:
        key = str(audit.get("queryId") or audit.get("sourceImageRef"))
        if args.resume and key in state["completed"]:
            continue
        image_path = resolve_image_path(args.dataset_root.resolve(), str(audit.get("sourceImageRef") or ""))
        if not image_path.is_file():
            state["completed"][key] = None
        else:
            try:
                state["completed"][key] = audit_query(
                    audit, image_path, case_map.get(str(audit.get("caseId"))), repository,
                    cost_repository, embedder, args.candidate_k, args.top_k,
                )
            except Exception as exc:
                state["completed"][key] = {"queryId": key, "error": f"{type(exc).__name__}: {exc}"}
        processed += 1
        if processed % args.batch_size == 0:
            save_checkpoint(state_path, state)
    save_checkpoint(state_path, state)
    rows = [row for row in state["completed"].values() if row and not row.get("error")]
    metrics = {str(k): k_metrics(rows, k) for k in K_VALUES}
    selection = choose_review(rows, seed=args.seed, limit=args.review_query_count)
    payload = {
        "status": "SUCCEEDED", "generatedAt": datetime.now(timezone.utc).isoformat(),
        "readOnly": True, "candidateK": args.candidate_k, "topK": args.top_k,
        "seed": args.seed, "manifestDamageImageCount": state["manifestCount"],
        "pairedQueryCount": len(rows), "metricsByK": metrics, "audits": rows,
        "selection": selection,
        "verification": {"databaseWrites": False, "repairHintUsed": False,
                          "migrationChanged": False, "apiFrontendChanged": False,
                          "embeddingRebuilt": False, "pipelineActivationChanged": False},
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    args.selection_output.parent.mkdir(parents=True, exist_ok=True)
    args.selection_output.write_text(json.dumps({"seed": args.seed, "queryCount": len(selection), "queries": selection}, ensure_ascii=False, indent=2), encoding="utf-8")
    args.output_html.parent.mkdir(parents=True, exist_ok=True)
    args.output_html.write_text(render_html(rows, metrics, selection, args.dataset_root.resolve()), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "processed": processed, "completed": len(rows),
                      "pairedQueryCount": len(rows), "selectionCount": len(selection),
                      "coverage": {str(k): {key: value for key, value in metrics[str(k)].items() if key.endswith("AtLeast2") or key.endswith("AtLeast3")} for k in K_VALUES}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
