"""Evaluate the v1 DAMAGE_PART and v2 DAMAGE search corpora independently."""

from __future__ import annotations

import argparse
import csv
import html
import json
import math
import os
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for import_root in (REPO_ROOT, PIPELINE_ROOT):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))

from pipeline.jobs.ingestion.embed_search_corpus import (  # noqa: E402
    resolve_model_version,
    roi_from_feature_box,
    vector_literal,
)
from pipeline.jobs.ingestion.load_damage_features import repair_hints  # noqa: E402
from pipeline.jobs.ingestion.load_search_data import _confidence, _image_size  # noqa: E402
from shared.vision.damage_part_pairing import damage_code, valid_geometry  # noqa: E402
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec  # noqa: E402
from shared.vision.roi import (  # noqa: E402
    PAD_RATIO,
    QUALITY_GOOD,
    QUALITY_PARTIAL_PART,
    feature_quality,
    roi_box,
    roi_quality,
    source_bbox,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--subset-root", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def read_query_manifest(path: Path, limit: int | None) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        rows = list(csv.DictReader(fp))
    if limit is not None:
        rows = rows[:limit]
    return rows


def query_rows(
    manifest_rows: list[dict[str, str]],
    *,
    subset_root: Path,
    dataset_root: Path,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for manifest_row in manifest_rows:
        label_path = subset_root / manifest_row["label_ref"]
        image_path = dataset_root / manifest_row["source_image_ref"]
        base = {
            "case_id": manifest_row["case_id"],
            "source_image_ref": manifest_row["source_image_ref"],
            "label_ref": manifest_row["label_ref"],
            "image_path": str(image_path),
        }
        try:
            document = json.loads(label_path.read_text(encoding="utf-8-sig"))
            image_size = _image_size(document)
            annotations = document.get("annotations") or []
        except Exception as exc:
            rows.append({
                **base,
                "query_id": f"{manifest_row['case_id']}:{manifest_row['source_image_ref']}",
                "status": "QUERY_LABEL_FAILED",
                "failure_reason": f"{type(exc).__name__}: {exc}",
            })
            continue

        for roi_index, annotation in enumerate(annotations):
            if not isinstance(annotation, dict) or not damage_code(annotation):
                continue
            query = {
                **base,
                "query_id": (
                    f"{manifest_row['case_id']}:{manifest_row['source_image_ref']}#{roi_index}"
                ),
                "roi_index": roi_index,
                "damage_type": damage_code(annotation),
                "query_parts": [],
                "repair_methods": [],
                "status": "READY",
                "failure_reason": None,
                "roi_box": None,
                "confidence": _confidence(annotation),
                "annotation_ref": str(annotation.get("id") or roi_index),
            }
            if not image_path.is_file():
                query["status"] = "QUERY_IMAGE_MISSING"
                query["failure_reason"] = str(image_path)
                rows.append(query)
                continue
            if not valid_geometry(annotation):
                query["status"] = "QUERY_INVALID_GEOMETRY"
                query["failure_reason"] = "invalid damage geometry"
                rows.append(query)
                continue
            try:
                bbox = source_bbox(annotation)
                box, effective_padding, clipped, _ = roi_box(
                    bbox, image_size, pad_ratio=PAD_RATIO
                )
                query["roi_box"] = {
                    "coordinate_system": "PIXEL_XY_TOP_LEFT",
                    "format": "XYWH",
                    "x": box[0],
                    "y": box[1],
                    "width": box[2] - box[0],
                    "height": box[3] - box[1],
                    "effective_padding": [round(value, 4) for value in effective_padding],
                    "clipped": clipped,
                }
                hints, _ = repair_hints(annotation)
                query["query_parts"] = sorted({hint["part_code"] for hint in hints})
                query["repair_methods"] = sorted({
                    method
                    for hint in hints
                    for method in hint["repair_methods"]
                })
            except Exception as exc:
                query["status"] = "QUERY_ROI_FAILED"
                query["failure_reason"] = f"{type(exc).__name__}: {exc}"
            rows.append(query)
    return rows


def prepare_query_roi(row: dict[str, Any]):
    from PIL import Image

    with Image.open(row["image_path"]) as image:
        image.load()
        return roi_from_feature_box(image, row["roi_box"])


def embed_query_rows(rows: list[dict[str, Any]], embedder, batch_size: int) -> None:
    ready = [row for row in rows if row["status"] == "READY"]
    for start in range(0, len(ready), batch_size):
        batch = ready[start:start + batch_size]
        prepared: list[tuple[dict[str, Any], Any]] = []
        for row in batch:
            try:
                prepared.append((row, prepare_query_roi(row)))
            except Exception as exc:
                row["status"] = "QUERY_ROI_FAILED"
                row["failure_reason"] = f"{type(exc).__name__}: {exc}"
        if not prepared:
            continue
        try:
            vectors = embedder.embed([item[1] for item in prepared])
            for index, (row, _) in enumerate(prepared):
                row["vector"] = vectors[index]
        except Exception as exc:
            if "failed to load DINOv2 embedding model" in str(exc):
                raise
            for row, roi in prepared:
                try:
                    row["vector"] = embedder.embed([roi])[0]
                except Exception as item_exc:
                    row["status"] = "QUERY_EMBED_FAILED"
                    row["failure_reason"] = f"{type(item_exc).__name__}: {item_exc}"


def pipeline_stats(cur, pipeline_id: int, model_version_id: int) -> dict[str, Any]:
    cur.execute("""
        SELECT COUNT(*) AS feature_total,
               COUNT(*) FILTER (WHERE f.is_searchable) AS searchable_total,
               COUNT(*) FILTER (WHERE e.roi_embedding_id IS NOT NULL) AS embedding_total,
               COUNT(*) FILTER (WHERE e.roi_embedding_id IS NOT NULL
                                      AND vector_dims(e.embedding)=768) AS dimension_768_total
          FROM repair_case_damage_feature f
          LEFT JOIN repair_case_roi_embedding e
            ON e.damage_feature_id=f.damage_feature_id
           AND e.model_version_id=%s
         WHERE f.pipeline_version_id=%s
    """, (model_version_id, pipeline_id))
    row = cur.fetchone()
    if not row:
        raise RuntimeError(f"pipeline statistics are unavailable for pipeline_version_id={pipeline_id}")
    if isinstance(row, dict):
        feature_total = row["feature_total"]
        searchable_total = row["searchable_total"]
        embedding_total = row["embedding_total"]
        dimension_768_total = row["dimension_768_total"]
    else:
        feature_total, searchable_total, embedding_total, dimension_768_total = row
    return {
        "pipeline_version_id": pipeline_id,
        "feature_total": int(feature_total),
        "searchable_total": int(searchable_total),
        "embedding_total": int(embedding_total),
        "dimension_768_total": int(dimension_768_total),
    }


def search_query(
    cur, pipeline_id: int, model_version_id: int, vector: Any, top_k: int
) -> list[dict[str, Any]]:
    cur.execute("""
        SELECT f.damage_feature_id, c.external_ref AS case_id, c.source,
               i.source_image_ref, f.damage_type, f.part_code, f.pair_status, f.roi_box,
               e.embedding <=> %s::vector AS distance,
               COALESCE((
                   SELECT array_agg(DISTINCT h.part_code ORDER BY h.part_code)
                     FROM repair_case_damage_feature_part_hint h
                    WHERE h.damage_feature_id=f.damage_feature_id
               ), ARRAY[]::varchar[]) AS hint_part_codes,
               CASE
                 WHEN f.part_code IS NOT NULL
                  AND f.pair_status <> 'AMBIGUOUS' THEN 'STRICT'
                 ELSE 'VECTOR_ONLY'
               END AS searchability,
               CASE
                 WHEN f.part_code IS NOT NULL THEN EXISTS (
                   SELECT 1 FROM repair_case_item ci
                    WHERE ci.case_id=c.case_id AND ci.part_code=f.part_code
                 )
                 ELSE EXISTS (
                   SELECT 1
                     FROM repair_case_damage_feature_part_hint h
                     JOIN repair_case_item ci ON ci.case_id=c.case_id
                                             AND ci.part_code=h.part_code
                    WHERE h.damage_feature_id=f.damage_feature_id
                 )
               END AS cost_linked
          FROM repair_case_roi_embedding e
          JOIN repair_case_damage_feature f ON f.damage_feature_id=e.damage_feature_id
          JOIN repair_case_image i ON i.case_image_id=f.case_image_id
          JOIN repair_case c ON c.case_id=i.case_id
         WHERE f.pipeline_version_id=%s
           AND f.is_searchable
           AND e.model_version_id=%s
         ORDER BY e.embedding <=> %s::vector
         LIMIT %s
    """, (vector_literal(vector), pipeline_id, model_version_id, vector_literal(vector), top_k))
    columns = [
        "damage_feature_id", "case_id", "source", "source_image_ref", "damage_type",
        "part_code", "pair_status", "roi_box", "distance", "hint_part_codes", "searchability",
        "cost_linked",
    ]
    return [
        dict(row) if isinstance(row, dict) else dict(zip(columns, row))
        for row in cur.fetchall()
    ]


def annotate_results(query: dict[str, Any], results: list[dict[str, Any]]) -> None:
    query_parts = set(query.get("query_parts") or [])
    for result in results:
        hint_parts = set(result.get("hint_part_codes") or [])
        part_match = bool(query_parts and (
            result.get("part_code") in query_parts or bool(hint_parts & query_parts)
        ))
        result["part_match"] = part_match
        result["hint_match"] = bool(query_parts and hint_parts & query_parts)
        result["relevant"] = (
            result.get("damage_type") == query.get("damage_type")
            and (part_match if query_parts else True)
        )
        result["distance"] = float(result["distance"])
        result["cost_linked"] = bool(result.get("cost_linked"))


def rerank_by_hint_count(
    results: list[dict[str, Any]],
    *,
    query_part_count: int,
    penalty: float,
    top_k: int,
) -> list[dict[str, Any]]:
    """Softly prefer a repair-hint cardinality close to the query part count.

    Repair hints are case-level candidates, not labels.  This function never
    excludes a vector hit; it only adds a small, inspectable ranking penalty.
    An empty hint list receives no cardinality preference because it carries no
    evidence that the underlying repair is a one-part repair.
    """
    if query_part_count < 1:
        raise ValueError("query_part_count must be positive")
    if penalty < 0:
        raise ValueError("penalty must be non-negative")
    reranked: list[dict[str, Any]] = []
    for source in results:
        result = dict(source)
        hint_count = len(result.get("hint_part_codes") or [])
        cardinality_gap = abs(hint_count - query_part_count) if hint_count else 0
        ranking_penalty = penalty * cardinality_gap
        result["hint_count"] = hint_count
        result["hint_count_gap"] = cardinality_gap
        result["hint_count_penalty"] = ranking_penalty
        result["rerank_distance"] = float(result["distance"]) + ranking_penalty
        reranked.append(result)
    reranked.sort(key=lambda item: (
        item["rerank_distance"], item["distance"], item["damage_feature_id"]
    ))
    return reranked[:top_k]


def ndcg_at(results: list[dict[str, Any]], k: int) -> float:
    for rank, result in enumerate(results[:k], start=1):
        if result["relevant"]:
            return 1.0 / math.log2(rank + 1)
    return 0.0


def metrics_for(pipeline_id: int, rows: list[dict[str, Any]], top_k: int) -> dict[str, Any]:
    query_count = len(rows)
    ready_count = sum(row["status"] == "READY" for row in rows)
    failure_counts = Counter(row["status"] for row in rows if row["status"] != "READY")
    all_results = [result for row in rows for result in row.get("results", [])]
    result_count = len(all_results)
    no_result_count = sum(row["status"] == "READY" and not row.get("results") for row in rows)
    metrics: dict[str, Any] = {
        "pipeline_version_id": pipeline_id,
        "query_count": query_count,
        "query_ready_count": ready_count,
        "query_failure_count": query_count - ready_count,
        "no_result_count": no_result_count,
        "result_count": result_count,
        "failure_reasons": dict(sorted(failure_counts.items())),
    }
    for k in (1, 5, 10):
        hits = [
            any(result["relevant"] for result in row.get("results", [])[:k])
            for row in rows
        ]
        metrics[f"recall_at_{k}"] = sum(hits) / query_count if query_count else 0.0
        if k in (5, 10):
            metrics[f"ndcg_at_{k}"] = (
                sum(ndcg_at(row.get("results", []), k) for row in rows) / query_count
                if query_count else 0.0
            )
    reciprocal_ranks = []
    for row in rows:
        reciprocal = 0.0
        for rank, result in enumerate(row.get("results", []), start=1):
            if result["relevant"]:
                reciprocal = 1.0 / rank
                break
        reciprocal_ranks.append(reciprocal)
    metrics["mrr"] = sum(reciprocal_ranks) / query_count if query_count else 0.0
    strict = sum(result["searchability"] == "STRICT" for result in all_results)
    vector_only = sum(result["searchability"] == "VECTOR_ONLY" for result in all_results)
    part_matches = sum(result["part_match"] for result in all_results)
    cost_linked = sum(result["cost_linked"] for result in all_results)
    hint_matches = sum(result["hint_match"] for result in all_results)
    metrics.update({
        "strict_result_ratio": strict / result_count if result_count else 0.0,
        "vector_only_result_ratio": vector_only / result_count if result_count else 0.0,
        "part_match_rate": part_matches / result_count if result_count else 0.0,
        "cost_linked_rate": cost_linked / result_count if result_count else 0.0,
        "repair_hint_actual_match_rate": (
            hint_matches / result_count if pipeline_id == 2 and result_count else None
        ),
        "search_result_coverage": (
            (query_count - no_result_count) / query_count if query_count else 0.0
        ),
    })
    return metrics


def json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: json_safe(item) for key, item in value.items() if key != "vector"}
    if isinstance(value, list):
        return [json_safe(item) for item in value]
    if hasattr(value, "item"):
        return value.item()
    return value


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as fp:
        for row in rows:
            fp.write(json.dumps(json_safe(row), ensure_ascii=False) + "\n")


def write_metrics_csv(path: Path, metrics: dict[int, dict[str, Any]]) -> None:
    names = sorted({
        key for values in metrics.values() for key, value in values.items()
        if isinstance(value, (int, float)) or value is None
    })
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["metric", "v1", "v2", "delta_v2_minus_v1"])
        for name in names:
            v1, v2 = metrics[1].get(name), metrics[2].get(name)
            delta = v2 - v1 if isinstance(v1, (int, float)) and isinstance(v2, (int, float)) else ""
            writer.writerow([name, v1 if v1 is not None else "", v2 if v2 is not None else "", delta])


def metric_label(name: str) -> str:
    return name.replace("_", " ").replace("at", "@")


def bar_chart(metrics: dict[int, dict[str, Any]], names: list[str]) -> str:
    blocks = []
    for name in names:
        blocks.append(f"<div class='chart-title'>{html.escape(metric_label(name))}</div>")
        for pipeline_id, color in ((1, "#2563eb"), (2, "#ea580c")):
            value = metrics[pipeline_id].get(name)
            numeric = float(value or 0)
            width = max(0.0, min(100.0, numeric * 100))
            blocks.append(
                f"<div class='bar-row'><span>v{pipeline_id}</span>"
                f"<div class='bar' style='width:{width:.2f}%;background:{color}'></div>"
                f"<b>{numeric:.3f}</b></div>"
            )
    return "\n".join(blocks)


def render_html(
    output: Path,
    *,
    generated_at: str,
    query_rows: list[dict[str, Any]],
    metrics: dict[int, dict[str, Any]],
    embedding_summaries: dict[int, dict[str, Any]],
) -> None:
    recommended = "v1 (DAMAGE_PART)" if metrics[1]["mrr"] >= metrics[2]["mrr"] else "v2 (DAMAGE)"
    metric_names = [
        "recall_at_1", "recall_at_5", "recall_at_10", "mrr", "ndcg_at_5", "ndcg_at_10",
        "strict_result_ratio", "vector_only_result_ratio", "part_match_rate",
        "cost_linked_rate", "repair_hint_actual_match_rate",
    ]
    table_rows = []
    for name in metric_names:
        v1, v2 = metrics[1].get(name), metrics[2].get(name)
        table_rows.append(
            f"<tr><td>{html.escape(metric_label(name))}</td>"
            f"<td>{'' if v1 is None else f'{v1:.4f}'}</td>"
            f"<td>{'' if v2 is None else f'{v2:.4f}'}</td></tr>"
        )
    query_blocks = []
    for row in query_rows:
        result_tables = []
        for pipeline_id in (1, 2):
            results = row.get(f"results_v{pipeline_id}", [])
            result_rows = "".join(
                "<tr>"
                f"<td>{index}</td><td>{html.escape(str(result.get('case_id')))}</td>"
                f"<td>{html.escape(str(result.get('damage_type')))}</td>"
                f"<td>{html.escape(str(result.get('part_code') or '-'))}</td>"
                f"<td>{html.escape(str(result.get('searchability')))}</td>"
                f"<td>{'Y' if result.get('relevant') else 'N'}</td>"
                f"<td>{'Y' if result.get('cost_linked') else 'N'}</td>"
                "</tr>"
                for index, result in enumerate(results, start=1)
            ) or "<tr><td colspan='7'>결과 없음</td></tr>"
            result_tables.append(
                f"<h4>v{pipeline_id}</h4><table><tr><th>순위</th><th>case</th>"
                f"<th>damage</th><th>part</th><th>검색유형</th><th>정답</th>"
                f"<th>비용연결</th></tr>{result_rows}</table>"
            )
        query_blocks.append(
            f"<details><summary>{html.escape(row['query_id'])} · {html.escape(row['status'])}"
            f" · damage={html.escape(str(row.get('damage_type','-')))}"
            f" · parts={html.escape(','.join(row.get('query_parts', [])) or '-')}"
            f"</summary><p>source_image_ref: <code>{html.escape(row['source_image_ref'])}</code>"
            f"<br>label_ref: <code>{html.escape(row['label_ref'])}</code></p>"
            f"{''.join(result_tables)}</details>"
        )
    failures = []
    for pipeline_id in (1, 2):
        for reason, count in metrics[pipeline_id].get("failure_reasons", {}).items():
            failures.append(f"<tr><td>v{pipeline_id}</td><td>{html.escape(reason)}</td><td>{count}</td></tr>")
        if metrics[pipeline_id]["no_result_count"]:
            failures.append(
                f"<tr><td>v{pipeline_id}</td><td>NO_RESULT</td>"
                f"<td>{metrics[pipeline_id]['no_result_count']}</td></tr>"
            )
    html_text = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>v1/v2 검색 A/B 평가</title>
<style>
body{{font-family:Arial,sans-serif;color:#172033;background:#f5f7fb;margin:0;padding:24px}}
main{{max-width:1400px;margin:auto}} h1,h2{{color:#13213b}} .card{{background:white;border:1px solid #dbe2ee;border-radius:10px;padding:16px;margin:12px 0;box-shadow:0 2px 8px #0000000b}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}} .value{{font-size:24px;font-weight:700}}
table{{border-collapse:collapse;width:100%;background:white;margin:8px 0}} th,td{{border:1px solid #dbe2ee;padding:7px;text-align:left;font-size:13px}} th{{background:#edf2f8}}
details{{background:white;border:1px solid #dbe2ee;border-radius:8px;margin:7px 0;padding:9px}} summary{{cursor:pointer;font-weight:600}}
.bar-row{{display:flex;align-items:center;gap:8px;margin:5px 0}} .bar-row span{{width:28px}} .bar{{height:16px;border-radius:4px;min-width:1px}} .bar-row b{{font-size:12px;width:55px}}
.chart-title{{font-weight:700;margin-top:13px}} code{{font-size:12px;word-break:break-all}} .muted{{color:#60708a}}
</style></head><body><main>
<h1>v1/v2 검색 corpus A/B 평가</h1>
<div class="card"><h2>요약</h2><p><b>권장 pipeline:</b> {html.escape(recommended)}</p>
<p><b>판단 상태:</b> 오프라인 EVAL 완료 · pipeline은 활성화하지 않음</p>
<p><b>평가 시각:</b> {html.escape(generated_at)}<br><b>Query:</b> {len(query_rows)}개 ROI</p></div>
<div class="grid">
<div class="card"><b>v1 전수 embedding</b><div class="value">{embedding_summaries[1]['embedding_total']:,}</div><span class="muted">searchable {embedding_summaries[1]['searchable_total']:,}</span></div>
<div class="card"><b>v2 전수 embedding</b><div class="value">{embedding_summaries[2]['embedding_total']:,}</div><span class="muted">searchable {embedding_summaries[2]['searchable_total']:,}</span></div>
</div>
<div class="card"><h2>핵심 지표</h2><table><tr><th>지표</th><th>v1 DAMAGE_PART</th><th>v2 DAMAGE</th></tr>{''.join(table_rows)}</table></div>
<div class="card"><h2>Recall / MRR / nDCG</h2>{bar_chart(metrics, ['recall_at_1','recall_at_5','recall_at_10','mrr','ndcg_at_5','ndcg_at_10'])}</div>
<div class="card"><h2>검색 유형·연결률</h2>{bar_chart(metrics, ['strict_result_ratio','vector_only_result_ratio','part_match_rate','cost_linked_rate','repair_hint_actual_match_rate'])}</div>
<div class="card"><h2>실패·제외·미연결</h2><table><tr><th>pipeline</th><th>사유</th><th>건수</th></tr>{''.join(failures) or '<tr><td colspan="3">없음</td></tr>'}</table>
<p class="muted">Recall/MRR/nDCG의 분모에는 query 실패와 결과 없음도 포함했습니다. 연결률은 반환된 top-K 결과를 기준으로 별도 표시합니다.</p></div>
<div class="card"><h2>Query별 결과</h2>{''.join(query_blocks)}</div>
<div class="card"><h2>평가 한계 및 운영 전 확인</h2><ul><li>현재 EVAL query는 DAMAGE 이미지가 실제 존재하는 267개 사례, 865개 ROI입니다.</li><li>v2는 part_code=NULL 정책이라 STRICT 결과가 생성되지 않으며, hint 기반 부품 일치율을 별도로 봅니다.</li><li>이번 결과는 로컬 DEV corpus와 오프라인 EVAL 기준이며, pipeline 활성화·S3 업로드는 수행하지 않았습니다.</li><li>운영 전에는 EVAL 범위 확대, 비용 정책 연결 검증, HNSW 검색 latency, 실제 입력 이미지 분포를 추가 확인해야 합니다.</li></ul></div>
</main></body></html>"""
    output.write_text(html_text, encoding="utf-8")


def main() -> None:
    args = parse_args()
    if args.top_k < 1 or args.batch_size < 1:
        raise SystemExit("top-k와 batch-size는 1 이상이어야 합니다")
    if args.limit is not None and args.limit < 1:
        raise SystemExit("limit은 1 이상이어야 합니다")
    dsn = os.environ.get("DATABASE_URL")
    if not dsn and not args.dry_run:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")

    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    subset_root = args.subset_root.resolve()
    dataset_root = args.dataset_root.resolve()
    manifest_rows = read_query_manifest(args.query_manifest.resolve(), args.limit)
    queries = query_rows(manifest_rows, subset_root=subset_root, dataset_root=dataset_root)
    write_jsonl(output / "evaluation_queries.jsonl", queries)

    if args.dry_run:
        print(json.dumps({
            "status": "DRY_RUN",
            "query_manifest_rows": len(manifest_rows),
            "query_roi_rows": len(queries),
            "ready": sum(row["status"] == "READY" for row in queries),
            "failures": dict(Counter(row["status"] for row in queries if row["status"] != "READY")),
        }, ensure_ascii=False, indent=2))
        return

    import psycopg
    from psycopg.rows import dict_row

    spec = EmbeddingSpec()
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            model_version_id = resolve_model_version(cur, spec)
            embedding_summaries: dict[int, dict[str, Any]] = {}
            for pipeline_id in (1, 2):
                embedding_summaries[pipeline_id] = pipeline_stats(cur, pipeline_id, model_version_id)
                cur.execute("""
                    SELECT version, is_active, params
                      FROM feature_pipeline_version
                     WHERE pipeline_version_id=%s
                """, (pipeline_id,))
                pipeline = cur.fetchone()
                if not pipeline:
                    raise RuntimeError(f"pipeline_version_id={pipeline_id} is missing")
                embedding_summaries[pipeline_id].update({
                    "version": pipeline["version"],
                    "is_active": pipeline["is_active"],
                    "params": pipeline["params"],
                    "model_version_id": model_version_id,
                })

        embedder = DinoV2Embedder(spec)
        embed_query_rows(queries, embedder, args.batch_size)
        for pipeline_id in (1, 2):
            with conn.cursor() as cur:
                for row in queries:
                    if row["status"] != "READY" or "vector" not in row:
                        row[f"results_v{pipeline_id}"] = []
                        continue
                    results = search_query(
                        cur, pipeline_id, model_version_id, row["vector"], args.top_k
                    )
                    annotate_results(row, results)
                    row[f"results_v{pipeline_id}"] = results
            for row in queries:
                row["results"] = row.get(f"results_v{pipeline_id}", [])
            metrics = metrics_for(pipeline_id, queries, args.top_k)
            embedding_summaries[pipeline_id]["evaluation_metrics"] = metrics
            write_jsonl(output / f"evaluation_results_v{pipeline_id}.jsonl", queries)

    metrics_by_pipeline = {
        pipeline_id: embedding_summaries[pipeline_id]["evaluation_metrics"]
        for pipeline_id in (1, 2)
    }
    write_metrics_csv(output / "ab_metrics.csv", metrics_by_pipeline)
    generated_at = datetime.now(timezone.utc).isoformat()
    for pipeline_id in (1, 2):
        (output / f"embedding_v{pipeline_id}_summary.json").write_text(
            json.dumps(embedding_summaries[pipeline_id], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    report = [
        "# v1/v2 검색 A/B 평가",
        "",
        f"- 평가 시각: {generated_at}",
        f"- Query ROI: {len(queries)}건",
        "- v1/v2 corpus는 별도 pipeline으로 검색했으며 활성화하지 않았습니다.",
        "",
        "## 핵심 지표",
        "",
        "| 지표 | v1 DAMAGE_PART | v2 DAMAGE |",
        "|---|---:|---:|",
    ]
    for name in (
        "recall_at_1", "recall_at_5", "recall_at_10", "mrr", "ndcg_at_5", "ndcg_at_10",
        "strict_result_ratio", "vector_only_result_ratio", "part_match_rate",
        "cost_linked_rate", "repair_hint_actual_match_rate",
    ):
        report.append(
            f"| {name} | {metrics_by_pipeline[1].get(name, '')} | "
            f"{metrics_by_pipeline[2].get(name, '')} |"
        )
    report.extend([
        "",
        "## 한계",
        "",
        "- DAMAGE 이미지가 실제 존재하는 EVAL 사례만 사용했습니다.",
        "- v2는 part_code=NULL이므로 STRICT 대신 hint 기반 부품 일치율을 사용했습니다.",
        "- 전수 embedding은 완료했지만, 운영 pipeline 활성화와 S3 업로드는 수행하지 않았습니다.",
    ])
    (output / "ab_report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    render_html(
        output / "ab_report.html",
        generated_at=generated_at,
        query_rows=queries,
        metrics=metrics_by_pipeline,
        embedding_summaries=embedding_summaries,
    )
    print(json.dumps({
        "status": "SUCCEEDED",
        "output_dir": str(output),
        "query_count": len(queries),
        "metrics": metrics_by_pipeline,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
