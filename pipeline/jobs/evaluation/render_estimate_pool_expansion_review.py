"""Render a visual review of new FULL_REPAIR references added by a larger pool.

The job reads two completed audit JSON files.  It makes no database connection,
does not rerun inference, and does not modify corpus rows.
"""
from __future__ import annotations

import argparse
import html
import json
import sys
from pathlib import Path
from typing import Any

PIPELINE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = PIPELINE_ROOT.parent
for root in (REPO_ROOT, PIPELINE_ROOT):
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))

from pipeline.jobs.evaluation.render_yolo_rerank_multi_query_review import (
    data_url,
    resolve_image_path,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-audit-json", type=Path, required=True,
                        help="smaller candidate pool audit, e.g. K=100")
    parser.add_argument("--expanded-audit-json", type=Path, required=True,
                        help="larger candidate pool audit, e.g. K=200")
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--selection-output", type=Path, required=True)
    parser.add_argument("--min-reference-count", type=int, default=5)
    parser.add_argument("--query-count", type=int, default=8)
    parser.add_argument("--max-reference-cases", type=int, default=10)
    args = parser.parse_args()
    if args.min_reference_count < 1 or args.query_count < 1 or args.max_reference_cases < 1:
        parser.error("minimum, query-count, and max-reference-cases must be positive")
    return args


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def money(value: Any) -> str:
    return "-" if value is None else f"{int(value):,}원"


def load_audits(path: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    audits = payload.get("audits")
    if not isinstance(audits, list):
        raise ValueError(f"audits array missing: {path}")
    return payload, {str(row["queryId"]): row for row in audits if row.get("queryId")}


def select_queries(base: dict[str, dict[str, Any]], expanded: dict[str, dict[str, Any]],
                   minimum: int, count: int) -> list[dict[str, Any]]:
    """Favor cases where expansion newly satisfies the displayable minimum."""
    candidates: list[dict[str, Any]] = []
    for query_id, after in expanded.items():
        before = base.get(query_id)
        if before is None or after.get("pairStatus") != "PAIRED" or not after.get("partCode"):
            continue
        before_rows = list(before.get("validWorkCostCases") or [])
        after_rows = list(after.get("validWorkCostCases") or [])
        before_ids = {int(item["caseId"]) for item in before_rows}
        added = [item for item in after_rows if int(item["caseId"]) not in before_ids]
        if len(before_rows) >= minimum or len(after_rows) < minimum or not added:
            continue
        candidates.append({
            "queryId": query_id, "before": before, "after": after,
            "beforeCount": len(before_rows), "afterCount": len(after_rows),
            "addedCount": len(added), "stratum": f"{after.get('partCode')} / {after.get('damageType')}",
        })

    # Round-robin strata avoids an all-front-bumper review while keeping the
    # largest evidence gain within each stratum first.
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in candidates:
        groups.setdefault(item["stratum"], []).append(item)
    for values in groups.values():
        values.sort(key=lambda item: (-item["addedCount"], -item["afterCount"], item["queryId"]))
    selected: list[dict[str, Any]] = []
    keys = sorted(groups, key=lambda key: (-len(groups[key]), key))
    while len(selected) < count and keys:
        progressed = False
        for key in keys:
            if not groups[key]:
                continue
            selected.append(groups[key].pop(0))
            progressed = True
            if len(selected) == count:
                break
        if not progressed:
            break
    return selected


def image_tag(root: Path, ref: str, cache: dict[str, str], alt: str) -> str:
    if ref not in cache:
        path = resolve_image_path(root, ref)
        cache[ref] = data_url(path, max_size=(560, 380)) if path.is_file() else ""
    src = cache[ref]
    return (f'<img src="{src}" alt="{html.escape(alt)}">' if src
            else '<div class="missing">이미지 없음</div>')


def card(root: Path, item: dict[str, Any], index: int, *, added: bool,
         cache: dict[str, str]) -> str:
    ref = str(item.get("sourceImageRef") or "")
    methods = ", ".join(str(value) for value in item.get("methods") or []) or "-"
    marker = "K=200 신규" if added else "K=100에도 존재"
    return f'''<article class="card {'added' if added else 'existing'}">
<h4>{marker} · 근거 #{index} · case {item.get('caseId')}</h4>
{image_tag(root, ref, cache, marker)}
<p class="path">{html.escape(ref)}</p>
<dl><dt>유사도</dt><dd>{fmt(item.get('similarity'))}</dd>
<dt>총 수리비</dt><dd>{money(item.get('total'))}</dd>
<dt>작업 방식</dt><dd>{html.escape(methods)}</dd>
<dt>부품 confidence</dt><dd>{fmt(item.get('corpusPartConfidence'))}</dd>
<dt>부품 overlap</dt><dd>{fmt(item.get('corpusPartOverlap'))}</dd></dl>
</article>'''


def render_query(root: Path, selected: dict[str, Any], maximum: int,
                 cache: dict[str, str]) -> str:
    before, after = selected["before"], selected["after"]
    before_rows = list(before.get("validWorkCostCases") or [])[:maximum]
    before_ids = {int(item["caseId"]) for item in before_rows}
    after_rows = list(after.get("validWorkCostCases") or [])[:maximum]
    source_ref = str(after.get("sourceImageRef") or "")
    old_cards = "".join(card(root, item, index, added=False, cache=cache)
                        for index, item in enumerate(before_rows, 1)) or "<p>유효 사례 없음</p>"
    new_cards = "".join(card(root, item, index, added=int(item["caseId"]) not in before_ids, cache=cache)
                        for index, item in enumerate(after_rows, 1))
    return f'''<section class="query"><h2>{html.escape(selected['stratum'])}</h2>
<div class="query-head">{image_tag(root, source_ref, cache, 'query input')}<dl>
<dt>query</dt><dd>{html.escape(selected['queryId'])}</dd>
<dt>K=100 → K=200</dt><dd>{selected['beforeCount']}건 → {selected['afterCount']}건 (+{selected['addedCount']})</dd>
<dt>YOLO</dt><dd>{html.escape(str(after.get('partCode')))} · {html.escape(str(after.get('damageType')))} · {html.escape(str(after.get('pairStatus')))}</dd>
<dt>검색 Top-10 유효 사례</dt><dd>{after.get('validWorkCostYoloTop10Count')}</dd>
</dl></div>
<div class="columns"><div><h3>K=100 유효 근거 (최대 {maximum}건)</h3><div class="cards">{old_cards}</div></div>
<div><h3>K=200 유효 근거 (최대 {maximum}건)</h3><div class="cards">{new_cards}</div></div></div>
<label class="memo">검토 메모<textarea rows="2" placeholder="추가된 사례가 같은 부품·손상 맥락으로 납득되는지 기록"></textarea></label>
</section>'''


def render_html(root: Path, selected: list[dict[str, Any]], args: argparse.Namespace) -> str:
    cache: dict[str, str] = {}
    sections = "".join(render_query(root, item, args.max_reference_cases, cache) for item in selected)
    return f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>K100 vs K200 견적 근거 이미지 검토</title>
<style>
*{{box-sizing:border-box}}body{{margin:0;background:#f4f6f9;color:#172033;font:14px system-ui,sans-serif}}main{{max-width:1600px;margin:auto;padding:24px}}h1,h2,h3,h4,p{{margin-top:0}}.note{{background:#fff7e5;border-left:4px solid #b96f08;padding:12px;margin:16px 0}}.query{{background:#fff;margin:22px 0;padding:18px;border-radius:12px;box-shadow:0 1px 8px #17203318}}.query-head{{display:grid;grid-template-columns:minmax(280px,420px) 1fr;gap:18px;margin-bottom:18px}}img,.missing{{width:100%;height:245px;object-fit:contain;background:#eef1f5;border-radius:8px}}.missing{{display:grid;place-items:center;color:#64748b}}dl{{display:grid;grid-template-columns:155px 1fr;gap:5px 10px;margin:0}}dt{{color:#64748b}}dd{{margin:0;overflow-wrap:anywhere}}.columns{{display:grid;grid-template-columns:1fr 1fr;gap:18px}}.columns>div{{background:#f8fafc;padding:12px;border-radius:10px}}.cards{{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:10px}}.card{{background:#fff;border-radius:8px;padding:10px;border-top:4px solid #7c8798;box-shadow:0 1px 4px #17203315}}.card.added{{border-top-color:#19775b}}.card img,.card .missing{{height:150px}}.card h4{{font-size:14px}}.card dl{{grid-template-columns:110px 1fr;font-size:12px}}.path{{font-size:11px;color:#526074;min-height:34px;overflow-wrap:anywhere;margin:7px 0}}.memo{{display:block;margin-top:14px;font-weight:600}}textarea{{display:block;width:100%;margin-top:5px;font:inherit;border:1px solid #b9c3d1;border-radius:6px;padding:7px}}@media(max-width:900px){{main{{padding:10px}}.columns,.query-head{{grid-template-columns:1fr}}}}
</style></head><body><main><h1>K=100 → K=200 견적 근거 이미지 검토</h1>
<p class="note">K=100에서는 최소 {args.min_reference_count}건을 못 채웠지만 K=200에서 채운 query만 골랐습니다. 초록 상단선은 K=200에서 새로 확보된 사례입니다. 이 화면은 공급량 검토용이며, 작업 방식 그룹 분리 정책은 별도 적용·검증해야 합니다.</p>
<p>선택 query {len(selected)}개 · 각 query의 유효 FULL_REPAIR 사례는 유사도 순으로 최대 {args.max_reference_cases}건 표시합니다. 검색 화면 Top-10과 견적 참조 후보는 다를 수 있습니다.</p>{sections}</main></body></html>'''


def main() -> None:
    args = parse_args()
    base_payload, base = load_audits(args.base_audit_json.resolve())
    expanded_payload, expanded = load_audits(args.expanded_audit_json.resolve())
    selected = select_queries(base, expanded, args.min_reference_count, args.query_count)
    if not selected:
        raise SystemExit("No query newly satisfies the minimum reference count")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.selection_output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_html(args.dataset_root.resolve(), selected, args), encoding="utf-8")
    args.selection_output.write_text(json.dumps({
        "status": "SUCCEEDED", "baseCandidateK": base_payload.get("candidateK"),
        "expandedCandidateK": expanded_payload.get("candidateK"),
        "minimumReferenceCount": args.min_reference_count, "selected": selected,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "SUCCEEDED", "selectedQueries": len(selected),
                      "output": str(args.output), "selectionOutput": str(args.selection_output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
