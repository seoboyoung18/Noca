// 분석 결과·예상 견적 표시 규칙 — 서버는 코드·원 단위 금액·원본 픽셀 좌표만 주고 라벨·포맷·환산은 FE 몫이다.
// (backend AnalysisResultResponse · EstimateResponse · ConfidenceGrade, Docs/Api/AI 연동 계약 ⑥ geometry)

/** 견적 전체 신뢰도. 산정 불가면 null — 라벨 없음 */
export const CONFIDENCE_LABEL = { HIGH: '높음', MEDIUM: '보통', LOW: '낮음' }
export const confidenceLabel = (grade) => CONFIDENCE_LABEL[grade] || ''

/** AI 손상 유형 코드(AI Hub 4종). 알 수 없는 코드는 그대로 */
export const DAMAGE_TYPE_LABEL = { Scratched: '긁힘', Crushed: '찌그러짐', Breakage: '파손', Separated: '이격' }
export const damageTypeLabel = (code) => DAMAGE_TYPE_LABEL[code] || code || ''

/** 분석에서 제외된 사진의 사유 코드 → 문구 (진행 상태 API 와 같은 코드) */
export const EXCLUSION_TEXT = { NOT_VEHICLE: '차량이 아닌 사진', RATIO_BELOW_THRESHOLD: '차량이 너무 작게 찍힘' }
export const exclusionText = (code) => EXCLUSION_TEXT[code] || '분석에서 제외됨'

/** 부품비가 원천에 없어 공임 기준으로 산정됐다는 고지 — 서버 notices 가 비어 있을 때 FE 가 대신 붙인다(S15P21A307-288 전까지) */
export const LABOR_ONLY_NOTICE = { code: 'LABOR_ONLY', message: '부품 가격이 포함되지 않은 공임 기준 금액이에요. 실제 수리비는 부품비가 더해질 수 있어요.' }

/* ----- 금액 ----- */
const man = (won) => Math.round(won / 10000)
/** 98만 ~ 162만원 · 값이 하나면 124만원 · 없으면 '' */
export function wonRange(min, max) {
  if (min == null && max == null) return ''
  if (min != null && max != null && min !== max) return `${man(min)}만 ~ ${man(max)}만원`
  return `${man(min ?? max)}만원`
}
export const wonOne = (won) => (won == null ? '' : `${man(won)}만원`)
/** 42~58만 (항목 부가 표기) */
export const wonShort = (min, max) => (min == null || max == null ? '' : `${man(min)}~${man(max)}만`)

/* ----- 좌표 환산 ----- */
/**
 * AI 검출 한 건의 bbox 를 사진 대비 퍼센트 사각형으로. 좌표는 원본 픽셀·좌상단 원점, bbox 는 XYWH(계약 ⑥).
 * 원본 치수(width/height)가 없으면 환산할 수 없어 null — 그때는 박스를 그리지 않는다(서버 Javadoc 권고).
 */
export function detectionBox(det, width, height) {
  const g = det?.geometry
  const b = g?.bbox
  if (!b || !width || !height) return null
  let x = b.x, y = b.y, w = b.width, h = b.height
  if ((g.bboxFormat || 'XYWH').toUpperCase() === 'XYXY') { w = (b.x2 ?? b.width) - x; h = (b.y2 ?? b.height) - y }
  if ([x, y, w, h].some((v) => typeof v !== 'number' || Number.isNaN(v))) return null
  const pct = (v, base) => Math.max(0, Math.min(100, (v / base) * 100))
  return { l: pct(x, width), t: pct(y, height), w: pct(w, width), h: pct(h, height) }
}

/* ----- 부위 번호 매기기 ----- */
/**
 * 화면의 ①②③ 번호는 견적 항목 순서(display_order)를 따르고, 견적에 없는데 분석에서만 검출된 부위는 뒤에 이어 붙인다.
 * 검출(detections[].partCode)은 같은 partCode 의 번호를 받는다 — 견적 항목 응답에는 detectionIds 가 없어 코드로 잇는다.
 * 돌려주는 값: [{ n, partCode, name, method, damage, min, median, max, na, low, refCaseCount }]
 */
export function numberParts(estimateItems = [], analysisParts = []) {
  const rows = []
  const seen = new Set()
  for (const it of estimateItems) {
    seen.add(it.partCode)
    rows.push({
      n: rows.length + 1, partCode: it.partCode, name: it.partNameKo || it.partCode,
      method: it.repairMethodDisplayName || it.repairMethod || '', damage: damageTypeLabel(it.damageType),
      min: it.itemMin, median: it.itemMedian, max: it.itemMax,
      na: it.itemMedian == null, low: !!it.lowConfidence, refCaseCount: it.refCaseCount ?? null,
    })
  }
  for (const p of analysisParts) {
    if (seen.has(p.partCode)) continue
    seen.add(p.partCode)
    rows.push({
      n: rows.length + 1, partCode: p.partCode, name: p.partNameKo || p.partCode,
      method: p.repairMethodDisplayName || '', damage: damageTypeLabel(p.damageType),
      min: null, median: null, max: null, na: true, low: false, refCaseCount: null,
    })
  }
  return rows
}
