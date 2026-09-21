"""Run canonical query YOLO on a reproducible manifest sample and render A/B review HTML.

The job is intentionally read-only with respect to PostgreSQL.  Query YOLO output is
kept in the selection JSON only; corpus YOLO evidence is read from the v2 candidate
tables through :class:`VectorRepository`.
"""
from __future__ import annotations

import argparse
import base64
import csv
import html
import json
import os
import random
import sys
from dataclasses import replace
from io import BytesIO
from pathlib import Path
from typing import Any

from PIL import Image

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from AI.server.app.adapters.ultralytics_yolo import ModelRunError, ModelSpec, UltralyticsRunner
from AI.server.app.adapters.yolo_adapter import adapt_raw_yolo_outputs
from AI.server.app.infrastructure.vector_repository import SearchHit, VectorRepository
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi


DEFAULT_MANIFEST = REPO_ROOT.parent / "outputs" / "ab_eval_2026-09-17" / "query_manifest.csv"
PART_MODEL = ("vehicle-part-detection", "35ep")
DAMAGE_MODEL = ("vehicle-damage-segmentation", "60ep")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--query-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--selection-output", type=Path, required=True)
    parser.add_argument("--query-count", type=int, default=10)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--candidate-k", type=int, default=100)
    parser.add_argument("--flat-boost", type=float, default=0.05)
    parser.add_argument("--seed", type=int, default=307)
    parser.add_argument("--query-candidate-limit", type=int, default=200)
    args = parser.parse_args()
    if args.query_count < 1 or args.top_k < 1 or args.candidate_k < args.top_k:
        parser.error("query-count/top-k must be positive and candidate-k >= top-k")
    if args.flat_boost < 0 or args.query_candidate_limit < args.query_count:
        parser.error("flat-boost must be non-negative and query-candidate-limit >= query-count")
    return args


def data_url(path: Path, *, max_size: tuple[int, int] = (640, 440)) -> str:
    with Image.open(path) as image:
        image = image.convert("RGB")
        image.thumbnail(max_size)
        out = BytesIO()
        image.save(out, format="JPEG", quality=82)
    return "data:image/jpeg;base64," + base64.b64encode(out.getvalue()).decode("ascii")


def read_manifest(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as fp:
        rows = [dict(row) for row in csv.DictReader(fp)]
    return [row for row in rows if row.get("image_type", "DAMAGE") == "DAMAGE" and row.get("source_image_ref")]


def db_candidates(dsn: str, refs: list[str]) -> list[dict[str, Any]]:
    """Read v2 features for manifest refs; no repair-hint table is touched."""
    import psycopg
    from psycopg.rows import dict_row

    if not refs:
        return []
    sql = """
    SELECT c.case_id, c.external_ref, i.case_image_id, i.source_image_ref,
           f.damage_feature_id, f.damage_type, f.damage_polygon, f.roi_box,
           f.confidence AS damage_confidence,
           m.mapping_status, inf.run_status, inf.part_model_name, inf.part_model_version,
           pc.part_code AS primary_part_code, pc.part_confidence, pc.overlap_score
      FROM repair_case_image i
      JOIN repair_case c ON c.case_id=i.case_id
      JOIN repair_case_damage_feature f ON f.case_image_id=i.case_image_id
       AND f.pipeline_version_id=2
      LEFT JOIN repair_case_damage_feature_part_mapping m ON m.damage_feature_id=f.damage_feature_id
      LEFT JOIN repair_case_image_part_inference inf ON inf.image_part_inference_id=m.image_part_inference_id
      LEFT JOIN repair_case_damage_feature_part_candidate pc
        ON pc.candidate_id=m.primary_candidate_id AND pc.is_primary
     WHERE i.source_image_ref = ANY(%s)
        OR i.source_image_ref LIKE ANY(%s)
     ORDER BY c.case_id, i.case_image_id, f.damage_feature_id
    """
    with psycopg.connect(dsn, row_factory=dict_row) as conn:
        with conn.cursor() as cur:
            patterns = [f"%/{Path(ref).name}" for ref in refs]
            cur.execute(sql, (refs, patterns))
            return [dict(row) for row in cur.fetchall()]


def db_case_ids(dsn: str, external_refs: list[str]) -> dict[str, int]:
    import psycopg
    if not external_refs:
        return {}
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT external_ref, case_id FROM repair_case WHERE external_ref = ANY(%s)", (external_refs,))
            return {str(external): int(case_id) for external, case_id in cur.fetchall()}


def resolve_image_path(dataset_root: Path, source_ref: str) -> Path:
    """Accept both the manifest's historical prefix and current dataset root."""
    direct = dataset_root / source_ref
    if direct.is_file():
        return direct
    normalized = source_ref.replace("\\", "/")
    for prefix in ("01.데이터_견적서보유/", "01.데이터/"):
        if normalized.startswith(prefix):
            candidate = dataset_root / normalized[len(prefix):]
            if candidate.is_file():
                return candidate
    # The DB/manifest can disagree on the leading dataset directory; basename is
    # unique in the evaluation manifest and keeps the visual renderer usable.
    matches = list(dataset_root.rglob(Path(normalized).name))
    return matches[0] if matches else direct


def compact_bbox(payload: Any) -> list[float] | None:
    if isinstance(payload, str):
        try:
            payload = json.loads(payload)
        except json.JSONDecodeError:
            return None
    if isinstance(payload, dict):
        try:
            return [float(payload["x"]), float(payload["y"]), float(payload["width"]), float(payload["height"])]
        except (KeyError, TypeError, ValueError):
            return None
    return None


def query_row(row: dict[str, Any], normalized: dict[str, Any]) -> dict[str, Any]:
    detection = normalized
    part = detection.get("part") or {}
    conf = detection.get("confidence") or {}
    polygons = (detection.get("geometry", {}).get("segmentation") or {}).get("polygons", [])
    polygons = [[[float(point["x"]), float(point["y"])] for point in polygon] for polygon in polygons]
    return {
        "case_id": int(row["case_id"]), "external_ref": row.get("external_ref"),
        "case_image_id": int(row["case_image_id"]), "source_image_ref": row["source_image_ref"],
        "damage_feature_id": int(row["damage_feature_id"]),
        "part_code": part.get("code"), "damage_type": detection["damage"]["code"],
        "pair_status": detection["pair_status"], "part_confidence": conf.get("part"),
        "damage_confidence": conf.get("damage"), "bbox": detection["geometry"]["bbox"],
        "segmentation": polygons,
        "query_selection_reason": "canonical_damage_detection",
    }


def run_query_yolo(path: Path, runner: UltralyticsRunner, part_spec: ModelSpec,
                   damage_spec: ModelSpec, image_id: int) -> list[dict[str, Any]]:
    part_raw, part_map = runner.run(part_spec, path, image_id)
    damage_raw, damage_map = runner.run(damage_spec, path, image_id)
    normalized = adapt_raw_yolo_outputs(
        part_raw, damage_raw, image_id=image_id,
        part_class_map=part_map, damage_class_map=damage_map,
    )
    return [query_row({"case_id": 0, "case_image_id": 0, "source_image_ref": str(path), "damage_feature_id": 0}, item)
            for item in normalized["detections"]]


def attach_query_detection(base: dict[str, Any], detection: dict[str, Any]) -> dict[str, Any]:
    row = dict(base)
    row.update({key: detection.get(key) for key in (
        "part_code", "damage_type", "pair_status", "part_confidence", "damage_confidence", "bbox")})
    row["query_selection_reason"] = "canonical_damage_detection"
    return row


def select_candidates(candidates: list[dict[str, Any]], count: int, seed: int) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    rng = random.Random(seed)
    for row in candidates:
        row["_tie"] = rng.random()
    # one detection per case, preferring paired detections and then stable feature id
    by_case: dict[str, list[dict[str, Any]]] = {}
    for row in candidates:
        by_case.setdefault(str(row["case_id"]), []).append(row)
    one_per_case = []
    for case_rows in by_case.values():
        one_per_case.append(sorted(case_rows, key=lambda r: (r.get("pair_status") != "PAIRED", r.get("damage_feature_id", 0)))[0])
    paired = [r for r in one_per_case if r.get("pair_status") == "PAIRED" and r.get("part_code")]
    fallback = [r for r in one_per_case if r not in paired]
    rng.shuffle(paired); rng.shuffle(fallback)
    selected: list[dict[str, Any]] = []
    counts: dict[str, int] = {}
    def take(pool: list[dict[str, Any]], wanted: int) -> None:
        for _ in range(wanted):
            if not pool:
                return
            pool.sort(key=lambda r: (counts.get(str(r.get("damage_type")), 0), r.get("_tie", 0.0)))
            row = pool.pop(0); selected.append(row)
            key = str(row.get("damage_type")); counts[key] = counts.get(key, 0) + 1
    take(paired, min(8, count))
    take(fallback, min(2, count - len(selected)))
    take(paired, count - len(selected)); take(fallback, count - len(selected))
    # if too few candidates were paired, fill from the remainder without a case duplicate
    selected_cases = {str(r["case_id"]) for r in selected}
    remaining = [r for r in one_per_case if str(r["case_id"]) not in selected_cases]
    take(remaining, count - len(selected))
    for row in selected:
        row.pop("_tie", None)
    excluded = [r for r in candidates if r not in selected]
    return selected[:count], excluded


def rerank(pool: list[SearchHit], query_part: str | None, flat_boost: float, top_k: int) -> list[SearchHit]:
    def applied(hit: SearchHit) -> float:
        return flat_boost if query_part and hit.corpus_part_matched else 0.0
    def key(hit: SearchHit) -> tuple[float, float, int]:
        distance = 1.0 - float(hit.vector_similarity)
        return distance - applied(hit), distance, hit.case_id
    output = []
    for hit in sorted(pool, key=key)[:top_k]:
        boost = applied(hit)
        vector_similarity = float(hit.vector_similarity)
        output.append(replace(hit, similarity=vector_similarity + boost,
                              reranked_similarity=vector_similarity + boost,
                              ranking_reason="YOLO_PART_BOOSTED" if boost else "VECTOR_ONLY_FALLBACK"))
    return output


def render_card(hit: SearchHit, rank: int, root: Path) -> str:
    path = resolve_image_path(root, str(hit.source_image_ref or ""))
    image = data_url(path) if path.is_file() else ""
    body = f'<img src="{image}" alt="rank {rank}">' if image else '<div class="missing">image missing</div>'
    values = [
        ("source image path", hit.source_image_ref),
        ("vector similarity", hit.vector_similarity),
        ("reranked similarity", hit.reranked_similarity),
        ("corpusPartCode", hit.corpus_part_code),
        ("corpusPartConfidence", hit.corpus_part_confidence),
        ("corpusPartOverlap", hit.corpus_part_overlap),
        ("ranking reason", hit.ranking_reason),
    ]
    rows = "".join(f"<dt>{html.escape(label)}</dt><dd>{html.escape('-' if value is None else (f'{value:.4f}' if isinstance(value, float) else str(value)))}</dd>" for label, value in values)
    return f'<article><h3>#{rank} · case {hit.case_id}</h3>{body}<dl>{rows}</dl></article>'


def summary_metrics(baseline: list[SearchHit], reranked: list[SearchHit]) -> dict[str, int]:
    b = {h.case_id: i for i, h in enumerate(baseline, 1)}; r = {h.case_id: i for i, h in enumerate(reranked, 1)}
    return {"boosted": sum(h.ranking_reason == "YOLO_PART_BOOSTED" for h in reranked),
            "fallback": sum(h.ranking_reason == "VECTOR_ONLY_FALLBACK" for h in reranked),
            "rank_changed": sum(1 for case in set(b) & set(r) if b[case] != r[case]),
            "new_entries": len(set(r) - set(b))}


def render_html(args: argparse.Namespace, query_results: list[dict[str, Any]], excluded_count: int, paired_count: int) -> str:
    all_boosted = sum(bool(item["summary"]["boosted"]) for item in query_results)
    overall_rows = "".join(
        f"<tr><td>{html.escape(str(item['query']['external_ref'] or item['query']['case_id']))}</td><td>{html.escape(str(item['query'].get('part_code') or '-'))}</td><td>{html.escape(str(item['query'].get('damage_type') or '-'))}</td><td>{html.escape(str(item['query'].get('pair_status') or '-'))}</td><td>{item['summary']['boosted']}</td><td>{item['summary']['fallback']}</td><td>{item['summary']['rank_changed']}</td><td>{item['summary']['new_entries']}</td></tr>"
        for item in query_results)
    sections = []
    for item in query_results:
        q = item["query"]; qpath = resolve_image_path(args.dataset_root.resolve(), q["source_image_ref"])
        qimg = data_url(qpath) if qpath.is_file() else ""
        qmeta = "".join(f"<dt>{html.escape(k)}</dt><dd>{html.escape('-' if v is None else str(v))}</dd>" for k, v in (
            ("partCode", q.get("part_code")), ("damageType", q.get("damage_type")),
            ("part confidence", q.get("part_confidence")), ("damage confidence", q.get("damage_confidence")),
            ("pairStatus", q.get("pair_status")), ("bbox", q.get("bbox")),
            ("selection", q.get("query_selection_reason")),))
        left = "".join(render_card(h, i + 1, args.dataset_root.resolve()) for i, h in enumerate(item["baseline"]))
        right = "".join(render_card(h, i + 1, args.dataset_root.resolve()) for i, h in enumerate(item["reranked"]))
        s = item["summary"]
        sections.append(f'<section class="query-section"><h2>{html.escape(str(q.get("external_ref") or q["case_id"]))} · case {q["case_id"]}</h2><div class="query"><img src="{qimg}" alt="query"><dl>{qmeta}</dl></div><p>Top-10 boosted {s["boosted"]} · fallback {s["fallback"]} · rank changes {s["rank_changed"]} · new entries {s["new_entries"]}</p><div class="columns"><div><h3>Pure vector Top-{args.top_k}</h3><div class="grid">{left}</div></div><div><h3>YOLO flat {args.flat_boost:g} rerank Top-{args.top_k}</h3><div class="grid">{right}</div></div></div><label>사람 검토 메모<textarea rows="3"></textarea></label></section>')
    return f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>v2 YOLO flat rerank multi-query review</title><style>
body{{font:14px system-ui;margin:24px;background:#f5f7fb;color:#172033}}h1,h2,h3{{margin-top:0}}section{{margin:26px 0}}.query-section{{border-top:3px solid #5468ff;padding-top:18px}}.query,.columns>div,article{{background:#fff;border:1px solid #d8deea;border-radius:9px;padding:10px}}.query{{display:flex;gap:16px;max-width:900px}}.query img{{width:340px;max-height:250px;object-fit:contain;background:#eef2f7}}.columns{{display:grid;grid-template-columns:1fr 1fr;gap:16px}}.grid{{display:grid;grid-template-columns:repeat(2,minmax(210px,1fr));gap:10px}}article img,.missing{{width:100%;height:150px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}dl{{display:grid;grid-template-columns:145px 1fr;gap:3px;margin:8px 0}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere;font-size:12px}}table{{border-collapse:collapse;background:#fff;margin-top:12px}}th,td{{border:1px solid #d8deea;padding:6px 9px;text-align:left}}textarea{{display:block;width:100%;margin-top:6px;box-sizing:border-box}}@media(max-width:1100px){{.columns{{grid-template-columns:1fr}}.query{{display:block}}.query img{{width:100%}}}}</style>
<main><h1>v2 DAMAGE · YOLO flat {args.flat_boost:g} multi-query visual review</h1><p>모든 query가 동일한 v2 vector candidate pool {args.candidate_k}개에서 출발합니다. 검색·선정에 repair hint와 자동 판정은 사용하지 않았습니다.</p><p>선택 query {len(query_results)}개 · PAIRED {paired_count}개 · UNPAIRED/part 미검출 {len(query_results)-paired_count}개 · damage YOLO 미검출/실행 제외 {excluded_count}개 · boost 적용 query {all_boosted}개</p><table><tr><th>query</th><th>part</th><th>damage</th><th>pairStatus</th><th>boosted</th><th>fallback</th><th>rank change</th><th>new entries</th></tr>{overall_rows}</table>{''.join(sections)}</main></html>'''


def main() -> None:
    args = parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL 환경변수가 필요합니다")
    dataset_root = args.dataset_root.resolve()
    manifest_rows = read_manifest(args.query_manifest.resolve())
    # Bound expensive model execution while retaining deterministic, seeded coverage.
    manifest_rng = random.Random(args.seed)
    manifest_rng.shuffle(manifest_rows)
    manifest_rows = manifest_rows[:args.query_candidate_limit]
    case_ids = db_case_ids(dsn, [str(row.get("case_id")) for row in manifest_rows if row.get("case_id")])
    db_rows = db_candidates(dsn, sorted({r["source_image_ref"] for r in manifest_rows}))
    by_ref: dict[str, list[dict[str, Any]]] = {}
    for row in db_rows:
        by_ref.setdefault(str(row["source_image_ref"]), []).append(row)
    ai_root = REPO_ROOT / "AI"
    runner = UltralyticsRunner(960)
    part_spec = ModelSpec("part", PART_MODEL[0], PART_MODEL[1], "detect", ai_root / "models" / "part" / "damage_part_best-35ep.pt")
    damage_spec = ModelSpec("damage", DAMAGE_MODEL[0], DAMAGE_MODEL[1], "segment", ai_root / "models" / "damage" / "damage_best-60ep.pt")
    candidates: list[dict[str, Any]] = []; excluded: list[dict[str, Any]] = []
    for manifest in manifest_rows:
        ref = manifest["source_image_ref"]; path = resolve_image_path(dataset_root, ref)
        if not path.is_file():
            excluded.append({**manifest, "exclude_reason": "IMAGE_MISSING"}); continue
        try:
            raw_detections = run_query_yolo(path, runner, part_spec, damage_spec, len(candidates) + 1)
        except (ModelRunError, ValueError, RuntimeError) as exc:
            excluded.append({**manifest, "exclude_reason": "YOLO_ERROR", "error": str(exc)}); continue
        if not raw_detections:
            excluded.append({**manifest, "exclude_reason": "DAMAGE_NOT_DETECTED"}); continue
        for det in raw_detections:
            bases = by_ref.get(ref) or []
            # A manifest image may have several v2 features; only use DB case identity for self-match.
            base = dict(bases[0]) if bases else {"case_id": case_ids.get(str(manifest.get("case_id")), manifest.get("case_id", "")), "external_ref": manifest.get("case_id"), "case_image_id": 0, "source_image_ref": ref, "damage_feature_id": 0}
            candidates.append(attach_query_detection(base, det))
    selected, not_selected = select_candidates(candidates, args.query_count, args.seed)
    repository = VectorRepository(dsn, expected_model_name="facebook/dinov2-base", expected_model_version="f9e44c8-pooler-pad20-lb224gray", yolo_corpus_part_boost=0.0)
    embedder = DinoV2Embedder(EmbeddingSpec())
    query_results = []
    for idx, query in enumerate(selected, 1):
        path = resolve_image_path(dataset_root, query["source_image_ref"])
        with Image.open(path) as image:
            bbox_payload = query["bbox"]
            if isinstance(bbox_payload, dict):
                bbox_payload = [bbox_payload["x"], bbox_payload["y"], bbox_payload["width"], bbox_payload["height"]]
            annotation = {"bbox": bbox_payload, "segmentation": query.get("segmentation") or []}
            roi, roi_meta = make_roi(image, annotation, confidence=query.get("damage_confidence"))
            query["roi_box"] = {"format": "XYWH", "x": roi_meta.roi_bbox[0], "y": roi_meta.roi_bbox[1], "width": roi_meta.roi_bbox[2], "height": roi_meta.roi_bbox[3]}
            vector = embedder.embed([roi])[0]
        query_part = query.get("part_code") if query.get("pair_status") == "PAIRED" else None
        _, pool = repository.search(vector=vector, pipeline_version_id=2, damage_type=str(query["damage_type"]), part_code=query_part, limit=args.candidate_k, exclude_case_id=(int(query["case_id"]) if str(query["case_id"]).isdigit() else None))
        baseline = rerank(pool, None, 0.0, args.top_k)
        reranked = rerank(pool, query_part, args.flat_boost, args.top_k)
        query_results.append({"query": query, "baseline": baseline, "reranked": reranked, "summary": summary_metrics(baseline, reranked)})
    selection = {"status": "SUCCEEDED", "seed": args.seed, "query_count": len(selected), "models": {"part": {"name": PART_MODEL[0], "version": PART_MODEL[1]}, "damage": {"name": DAMAGE_MODEL[0], "version": DAMAGE_MODEL[1]}}, "query_manifest": str(args.query_manifest.resolve()), "selected": selected, "excluded": excluded + [{**r, "exclude_reason": "NOT_SELECTED"} for r in not_selected], "candidate_count": len(candidates), "excluded_count": len(excluded), "selected_detection_count": len(selected)}
    args.selection_output.parent.mkdir(parents=True, exist_ok=True); args.selection_output.write_text(json.dumps(selection, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(render_html(args, query_results, len(excluded), sum(q["query"].get("pair_status") == "PAIRED" for q in query_results)), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "output": str(args.output), "selection_output": str(args.selection_output), "selected": len(selected), "excluded": len(excluded), "models_ran": {"part": PART_MODEL, "damage": DAMAGE_MODEL}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
