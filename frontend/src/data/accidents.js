// 사고 이력 표시 규칙 — 서버는 코드·시각·원 단위 금액만 주고 한글 라벨·그룹·포맷은 FE 몫이다.
// (Docs/Api/김재원 담당 백엔드 API — FE 인수인계.md §5-3, backend AccidentHistoryStatus)

/** 목록 status(유도값) → 배지. 우선순위는 서버가 정해 내려보낸다 (실패 > 분석 중 > 견적 완료 > 사진 등록 > 접수) */
export const ACCIDENT_STATUS = {
  RECEIVED: { text: '접수 완료', cls: 'gray' },
  IMAGES_UPLOADED: { text: '사진 등록', cls: 'gray' },
  ANALYZING: { text: '분석 중', cls: 'gray' },
  ANALYSIS_FAILED: { text: '분석 실패', cls: 'red' },
  ESTIMATED: { text: '견적 완료', cls: '' },
  REPAIR_RECORDED: { text: '수리 완료', cls: '' }, // 계약에는 있지만 서버가 아직 유도하지 않는다
}
export const accidentStatus = (code) => ACCIDENT_STATUS[code] || { text: '접수 완료', cls: 'gray' }

/**
 * 항목을 눌렀을 때 갈 곳.
 * - 견적이 있으면 결과 화면 (estimateId 가 있으면 함께 — 리포트 PDF 입구)
 * - 분석 중이면 분석 중 화면 (진행 단계 폴링)
 * - 접수만 됐거나 사진만 있거나 실패했으면 업로드 화면 — 사진을 확인하고 분석을 (다시) 요청하는 곳.
 *   분석 중 화면으로 바로 보내지 않는 이유: 그 화면은 진입 즉시 분석을 요청하므로 행을 누르는 것만으로 재분석이 시작된다
 */
export function accidentRoute(a) {
  const q = { accidentId: a.accidentId }
  if (a.status === 'ESTIMATED' || a.status === 'REPAIR_RECORDED') {
    return { path: '/estimate', query: a.estimateId ? { ...q, estimateId: a.estimateId } : q }
  }
  if (a.status === 'ANALYZING') return { path: '/claim/analyzing', query: q }
  return { path: '/claim/upload', query: q }
}

/** 원 → "만" 단위 정수. 12,400,00 → 124 */
const man = (won) => Math.round(won / 10000)

/**
 * 예상 수리비 문구. 견적이 없으면(estimatedCost* 전부 null — 지금은 항상) null 을 돌려준다.
 * "0원" 으로 그리면 안 되므로 호출하는 쪽이 null 을 "산정 전" 으로 처리한다.
 */
export function accidentCost(a) {
  const { estimatedCostMin: min, estimatedCostMax: max, estimatedCostMedian: med } = a
  if (min != null && max != null && min !== max) return `${man(min)}만 ~ ${man(max)}만원`
  const one = med ?? min ?? max
  return one == null ? null : `${man(one)}만원`
}

/** 접수 시각 → "9월 5일" (올해) · "2025.11.02" (지난해 이전) */
export function accidentDate(createdAt, now = new Date()) {
  const d = new Date(createdAt)
  if (Number.isNaN(d.getTime())) return ''
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}월 ${d.getDate()}일`
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
}

/** 그룹 라벨 — 최근 7일은 "이번 주", 올해는 "M월", 그 전은 "YYYY년 M월". 목록이 createdAt 내림차순이라 그룹도 순서대로 쌓인다 */
export function accidentGroupLabel(createdAt, now = new Date()) {
  const d = new Date(createdAt)
  if (Number.isNaN(d.getTime())) return '기타'
  if (now - d < 7 * 24 * 60 * 60 * 1000) return '이번 주'
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}월`
  return `${d.getFullYear()}년 ${d.getMonth() + 1}월`
}

/* ===== 사고 사진 (GET /api/accidents/{id}/images) ===== */

/** 촬영 가이드(shooting-guide.json) 각도 코드 9종의 한글 라벨. 정본은 GET /api/guides/shooting 의 shots[].title */
export const ANGLE_LABEL = {
  FRONT: '전면', REAR: '후면', LEFT: '좌측', RIGHT: '우측',
  FRONT_LEFT: '좌전방 45°', FRONT_RIGHT: '우전방 45°', REAR_LEFT: '좌후방 45°', REAR_RIGHT: '우후방 45°',
  DAMAGE_CLOSE: '손상 근접',
}
/** angleCode 는 nullable — 가이드를 건너뛴 업로드나 컬럼 생성 전 사진은 각도가 없다 */
export const angleLabel = (code) => (code ? ANGLE_LABEL[code] || code : '각도 미지정')

/** 이미지 한 장의 표시용 URL. THUMBNAIL 우선, 없으면 RESIZED. 서명 실패면 '' */
export function imageThumb(image, prefer = 'THUMBNAIL') {
  const assets = image?.assets || []
  return (assets.find((a) => a.variant === prefer && a.url) || assets.find((a) => a.url))?.url || ''
}

/** 대표 이미지 — 업로드가 끝난 첫 사진의 썸네일. 사고 이력 목록의 thumbnailUrl 과 같은 사진이다 */
export function firstThumbnail(images) {
  for (const im of images || []) {
    if (im.uploadState !== 'COMPLETED') continue
    const url = imageThumb(im)
    if (url) return url
  }
  return ''
}

/** 내림차순 목록을 라벨 순서대로 묶는다. [{ label, items }] */
export function groupAccidents(list, now = new Date()) {
  const groups = []
  for (const a of list) {
    const label = accidentGroupLabel(a.createdAt, now)
    let g = groups[groups.length - 1]
    if (!g || g.label !== label) { g = { label, items: [] }; groups.push(g) }
    g.items.push(a)
  }
  return groups
}
