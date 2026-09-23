// 분석 결과·예상 견적 표시 규칙 — 서버는 코드·원 단위 금액·픽셀 좌표(AI 분석 축소본 기준)만 주고 라벨·포맷·환산은 FE 몫이다.
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

/** 500000 → "500,000" (리포트 표기). null 이면 '' */
export const wonComma = (won) => (won == null ? '' : Number(won).toLocaleString('ko-KR'))

/** ISO 시각 → "2026년 9월 5일 14:32" · compact 면 "2026.09.05 14:31" */
export function formatDateTime(iso, compact = false) {
  const d = new Date(iso)
  if (!iso || Number.isNaN(d.getTime())) return ''
  const p = (n) => String(n).padStart(2, '0')
  if (compact) return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  return `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일 ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 산정 근거 완화 단계 → 문구. MODEL(동일 차량명) · PRICE_TIER(비슷한 가격대) · ALL(전체 사례).
 *
 * CAR_CLASS 는 지우지 않는다. 2026-09-17 이전에 저장된 견적의 근거에 그 값이 남아 있어,
 * 빼면 옛 견적을 열었을 때 근거 문구가 빈칸이 된다. 새로 만들어지지는 않는다.
 */
export const FALLBACK_LABEL = {
  MODEL: '동일 차량명 사례 기준',
  PRICE_TIER: '비슷한 가격대 사례 기준',
  CAR_CLASS: '동일 차급 사례 기준',
  ALL: '전체 사례 기준',
}
export const fallbackLabel = (stage) => FALLBACK_LABEL[stage] || ''

/* ----- 좌표 환산 ----- */
/**
 * 검출 한 건의 도형을 사진 픽셀 좌표 그대로 돌려준다 — SVG viewBox(사진 width×height) 위에 그리므로 퍼센트 환산이 필요 없다.
 *   rect     : bbox(XYWH · XYXY 방어) → { x, y, w, h } · 없으면 null
 *   polygons : geometry.polygons(세그멘테이션 윤곽, 조각별 배열) → "x,y x,y …" 문자열 배열. 점은 {x,y} 또는 [x,y] 둘 다 받는다. 3점 미만은 버린다
 */
export function detectionShapes(det) {
  const g = det?.geometry || {}
  let rect = null
  const b = g.bbox
  if (b) {
    let x = Number(b.x), y = Number(b.y), w = Number(b.width), h = Number(b.height)
    if ((g.bboxFormat || 'XYWH').toUpperCase() === 'XYXY') { w = Number(b.x2 ?? b.width) - x; h = Number(b.y2 ?? b.height) - y }
    if (![x, y, w, h].some((v) => Number.isNaN(v)) && w > 0 && h > 0) rect = { x, y, w, h }
  }
  const polygons = []
  for (const poly of Array.isArray(g.polygons) ? g.polygons : []) {
    const pts = (Array.isArray(poly) ? poly : []).map((pt) => (Array.isArray(pt) ? [Number(pt[0]), Number(pt[1])] : [Number(pt?.x), Number(pt?.y)]))
      .filter(([x, y]) => !Number.isNaN(x) && !Number.isNaN(y))
    if (pts.length >= 3) polygons.push(pts.map(([x, y]) => `${x},${y}`).join(' '))
  }
  return { rect, polygons }
}

/**
 * 코너 하이라이트 — 박스의 네 모서리만 꺾쇠(ㄱ 모양)로 그리는 SVG path. 꺾쇠 길이는 짧은 변의 18%.
 * 사각형을 다 그리면 선이 손상 부위를 가리고 사진이 답답해진다 — 모서리만 남겨도 범위는 읽힌다.
 * 견적 화면과 리포트 미리보기가 같은 모양을 그리도록 여기 한 곳에 둔다(components/DetectionOverlay 가 쓴다).
 */
export function cornerPath(r) {
  const len = Math.max(3, Math.min(r.w, r.h) * 0.18)
  const { x, y, w, h } = r
  return [
    `M${x} ${y + len}V${y}H${x + len}`,
    `M${x + w - len} ${y}H${x + w}V${y + len}`,
    `M${x + w} ${y + h - len}V${y + h}H${x + w - len}`,
    `M${x + len} ${y + h}H${x}V${y + h - len}`,
  ].join(' ')
}

/**
 * 분석 결과의 사진 하나(result.images[i])에서 그릴 도형 목록 — DetectionOverlay 의 marks.
 * [{ id, partCode, rect, polygons }]. 제외된 사진·크기를 모르는 사진은 빈 배열이고, 도형이 하나도 없는 검출은 뺀다.
 */
export function detectionMarks(im) {
  if (!im || im.excluded || !im.width || !im.height) return []
  return (Array.isArray(im.detections) ? im.detections : [])
    .map((d, i) => ({ id: d.detectionId || `${im.imageId}:${i}`, partCode: d.partCode || null, ...detectionShapes(d) }))
    .filter((m) => m.rect || m.polygons.length)
}

/**
 * 사진 픽셀의 한 점을 화면에 보이는 사진 영역 대비 퍼센트로. 라벨처럼 <b>크기가 고정돼야 하는 HTML 요소</b>를 얹을 때 쓴다
 * (도형은 SVG viewBox 가 알아서 맞추므로 환산이 필요 없다). 사진 틀은 고정 비율(frame, 기본 4:3) + cover 라 잘려 나간 가장자리를 빼고 센다.
 * 보이는 영역 밖이면 null.
 */
export function pointToPercent(x, y, width, height, frame = 4 / 3) {
  if (!width || !height || Number.isNaN(Number(x)) || Number.isNaN(Number(y))) return null
  let ox = 0, oy = 0, vw = width, vh = height
  if (width / height > frame) { vw = height * frame; ox = (width - vw) / 2 } else { vh = width / frame; oy = (height - vh) / 2 }
  const l = ((Number(x) - ox) / vw) * 100
  const t = ((Number(y) - oy) / vh) * 100
  if (l < -20 || l > 120 || t < -20 || t > 120) return null
  return { l: Math.max(0, Math.min(100, l)), t: Math.max(0, Math.min(100, t)) }
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

/* ----- 리포트 미리보기를 본 견적 — 이 브라우저에만 저장 (S15P21A307-564) -----
 * 견적 화면의 아래 버튼을 "리포트 만들기" 에서 "리포트 보기" 로 바꾸는 근거 하나. PDF 를 받지 않았어도 미리보기를 한 번 봤으면
 * 다음부터는 "만들기" 를 권하지 않는다. localStorage 는 막힐 수 있어 읽고 쓸 때 모두 감싼다 */
const SEEN_KEY = (estimateId) => `noka.reportSeen.${estimateId}`
export function hasSeenReport(estimateId) { try { return !!estimateId && localStorage.getItem(SEEN_KEY(estimateId)) === '1' } catch { return false } }
export function markReportSeen(estimateId) { try { if (estimateId) localStorage.setItem(SEEN_KEY(estimateId), '1') } catch { /* 저장 못 하면 다음에도 "만들기" 로 보인다 */ } }
