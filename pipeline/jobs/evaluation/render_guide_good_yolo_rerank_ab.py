"""Render read-only guide-good v2 pure-vector versus YOLO-part-boost Top-10."""
from __future__ import annotations
import argparse, html, json, os, sys
from pathlib import Path
PIPELINE_ROOT=Path(__file__).resolve().parents[2]; REPO_ROOT=PIPELINE_ROOT.parent
for p in (REPO_ROOT, PIPELINE_ROOT):
    if str(p) not in sys.path: sys.path.insert(0,str(p))
from shared.vision.dinov2 import DinoV2Embedder, EmbeddingSpec
from shared.vision.roi import make_roi
from pipeline.jobs.evaluation.render_query_comparison import data_url
from AI.server.app.infrastructure.vector_repository import VectorRepository
QUERY={"part":"FRONT_BUMPER","damage":"SCRATCHED","bbox":[167,224,663,178]}
def args():
 p=argparse.ArgumentParser(); p.add_argument('--dataset-root',type=Path,required=True); p.add_argument('--output',type=Path,required=True); return p.parse_args()
def row(hit,rank,root):
 path=root / str(hit.source_image_ref or '')
 image=data_url(path) if path.is_file() else ''
 fields={"vector similarity":hit.vector_similarity,"reranked similarity":hit.reranked_similarity,"corpusPartMatched":hit.corpus_part_matched,"corpusPartCode":hit.corpus_part_code,"corpusPartConfidence":hit.corpus_part_confidence,"corpusPartOverlap":hit.corpus_part_overlap,"rankingReason":hit.ranking_reason,"source image path":hit.source_image_ref}
 dl=''.join(f'<dt>{html.escape(k)}</dt><dd>{html.escape("-" if v is None else (f"{v:.4f}" if isinstance(v,float) else str(v)))}</dd>' for k,v in fields.items())
 body=f'<img src="{image}" alt="rank {rank}">' if image else '<div class="missing">image missing</div>'
 return f'<article><h3>#{rank}</h3>{body}<dl>{dl}</dl></article>'
def main():
 a=args(); dsn=os.environ.get('DATABASE_URL')
 if not dsn: raise SystemExit('DATABASE_URL 환경변수가 필요합니다')
 from PIL import Image
 guide=REPO_ROOT/'frontend/public/assets/guide-good.jpg'
 with Image.open(guide) as im: im.load(); roi,_=make_roi(im,{'bbox':QUERY['bbox']})
 vector=DinoV2Embedder(EmbeddingSpec()).embed([roi])[0]
 repo=VectorRepository(dsn,expected_model_name='facebook/dinov2-base',expected_model_version='f9e44c8-pooler-pad20-lb224gray')
 _,pure=repo.search(vector=vector,pipeline_version_id=2,damage_type=QUERY['damage'],part_code=None,limit=10)
 _,boost=repo.search(vector=vector,pipeline_version_id=2,damage_type=QUERY['damage'],part_code=QUERY['part'],limit=10)
 changed=sum(1 for i,h in enumerate(boost) if i>=len(pure) or h.case_id!=pure[i].case_id)
 summary={'boosted':sum(h.ranking_reason=='YOLO_PART_BOOSTED' for h in boost),'fallback':sum(h.ranking_reason=='VECTOR_ONLY_FALLBACK' for h in boost),'changed_cases':changed,'front_bumper':sum(h.corpus_part_code=='FRONT_BUMPER' for h in boost)}
 left=''.join(row(h,i+1,a.dataset_root.resolve()) for i,h in enumerate(pure)); right=''.join(row(h,i+1,a.dataset_root.resolve()) for i,h in enumerate(boost))
 page=f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>guide-good v2 YOLO rerank A/B</title><style>body{{font:14px system-ui;margin:24px;background:#f5f7fb}}.cols{{display:grid;grid-template-columns:1fr 1fr;gap:18px}}.grid{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}}article{{background:white;padding:10px;border:1px solid #d8deea;border-radius:8px}}img,.missing{{width:100%;height:150px;object-fit:contain;background:#eef2f7}}.missing{{display:grid;place-items:center}}dl{{display:grid;grid-template-columns:110px 1fr;gap:3px}}dt{{color:#687386}}dd{{margin:0;overflow-wrap:anywhere}}h3{{margin:0 0 8px}}</style><main><h1>guide-good v2 pure vector / YOLO part boost</h1><p>query: FRONT_BUMPER · SCRATCHED · boost 0.03</p><p>YOLO_PART_BOOSTED: {summary['boosted']} · VECTOR_ONLY_FALLBACK: {summary['fallback']} · rank changed: {summary['changed_cases']} · FRONT_BUMPER corpus: {summary['front_bumper']}</p><div class="cols"><section><h2>Pure vector Top-10</h2><div class="grid">{left}</div></section><section><h2>v2 + YOLO part boost Top-10</h2><div class="grid">{right}</div></section></div></main></html>'''
 a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(page,encoding='utf-8')
 print(json.dumps({'status':'SUCCEEDED','output':str(a.output),**summary},ensure_ascii=False))
if __name__=='__main__': main()
