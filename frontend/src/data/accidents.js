import { damageTypeLabel } from './estimates'

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
/* ----- 사고 설명 문구 (사고 이력 행 둘째 줄) -----
 * 목록 API(AccidentSummaryResponse)에는 사고 유형 필드가 없다(대조표 화면 21 · 사고 유형 마스터 미구현).
 * 견적이 있는 사고는 견적 항목의 부위명·손상 유형으로 "프론트 범퍼, 헤드램프(좌) 외 1곳 파손" 처럼 조립하고,
 * 없는 사고는 진행 단계 문구로 대신한다.
 */
export const ACCIDENT_STAGE_TEXT = {
  RECEIVED: '사진 등록 전', IMAGES_UPLOADED: '분석 요청 전', ANALYZING: '손상 부위 분석 중',
  ANALYSIS_FAILED: '분석 결과 없음', ESTIMATED: '손상 정보 없음', REPAIR_RECORDED: '수리 완료',
}
export const accidentStageText = (status) => ACCIDENT_STAGE_TEXT[status] || ''

/** 견적 항목 → "부위1, 부위2 외 N곳 손상유형". 손상 유형은 가장 많이 나온 것 하나. 항목이 없으면 '' */
export function damageSummaryText(items = []) {
  const names = items.map((it) => it.partNameKo || it.partCode).filter(Boolean)
  if (!names.length) return ''
  const head = names.slice(0, 2).join(', ') + (names.length > 2 ? ` 외 ${names.length - 2}곳` : '')
  const count = {}
  for (const it of items) if (it.damageType) count[it.damageType] = (count[it.damageType] || 0) + 1
  const top = Object.entries(count).sort((a, b) => b[1] - a[1])[0]?.[0]
  const damage = top ? damageTypeLabel(top) : ''
  return damage ? `${head} ${damage}` : head
}

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

/* ===== 분석 진행 (GET /api/accidents/{id}/analysis) ===== */

/** 서버 단계 코드 → 화면 문구. 분석 중 화면과 홈 "진행 중" 카드가 같은 문구를 쓴다. 서버는 퍼센트·남은 시간을 주지 않는다 */
export const ANALYSIS_STAGES = [
  { code: 'PREPROCESS', text: '사진을 확인하고 있어요' },
  { code: 'DETECT', text: '손상 부위를 찾고 있어요' },
  { code: 'MATCH', text: '부품을 연결하고 있어요' },
  { code: 'ESTIMATE', text: '수리비를 계산하고 있어요' },
]
/** 진행 상태 응답 → { percent(0·25·50·75·100), text }. 요청 전(status null)·대기(QUEUED)는 0% */
export function analysisProgressView(p) {
  const total = p?.totalStages || 4
  const done = p?.doneStages || 0
  const percent = Math.round((done / total) * 100)
  const stage = ANALYSIS_STAGES.find((s) => s.code === p?.currentStage)
  const text = stage?.text || (p?.status === 'QUEUED' ? '분석 순서를 기다리고 있어요' : done >= total ? '결과를 정리하고 있어요' : '분석을 준비하고 있어요')
  return { percent, text }
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

/* ===== 사고 이미지 업로드 — 검증·오류 문구 (이미지 업로드 API — FE 인수인계.md §2-3·§3·§4) ===== */

/** 발급 400 의 error.code 와 완료 통보 results[].failureCode 가 같은 이름을 쓴다 — 매핑 한 벌 */
export const IMAGE_FAIL_TEXT = {
  TOO_MANY_IMAGES: '올릴 수 있는 사진 수를 넘었어요.',
  FILE_TOO_LARGE: '사진은 20MB 이하만 올릴 수 있어요. 크기를 줄여 주세요.',
  UNSUPPORTED_EXTENSION: 'JPG 또는 PNG 사진만 올릴 수 있어요.',
  UNSUPPORTED_CONTENT_TYPE: '파일이 손상된 것 같아요. 다른 사진을 골라 주세요.',
  SERVER_CONVERSION_UNSUPPORTED: 'HEIC 사진은 올릴 수 없어요. JPG로 변환한 뒤 올려 주세요.',
  INVALID_FILE_NAME: '파일 이름을 바꾼 뒤 다시 올려 주세요.',
  MISSING_FILE: '사진이 올라가지 않았어요. 다시 시도해 주세요.',
  SIGNATURE_MISMATCH: '이미지 파일이 아닌 것 같아요. 다른 사진을 골라 주세요.',
  SIZE_MISMATCH: '전송 중 문제가 있었어요. 다시 시도해 주세요.',
  PROCESSING_ERROR: '사진을 처리하지 못했어요. 다시 시도해 주세요.',
}
export const imageFailText = (code, fallback) => IMAGE_FAIL_TEXT[code] || fallback || '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.'

/**
 * 품질 판정 WARN 의 사용자 문구. 서버 qualityReason 은 "해상도 부족 — 짧은 변 600px (기준 720px)" 같은 개발자용 표기라
 * 앞머리로 종류를 가려 사용자용으로 바꾼다. 알 수 없는 사유는 일반 문구. (backend ImageQualityAssessor)
 */
export function qualityWarnText(reason = '') {
  if (reason.startsWith('해상도')) return '사진 해상도가 낮아요. 더 가까이서 찍은 사진으로 바꾸는 걸 권해요.'
  if (reason.startsWith('흔들림')) return '사진이 흔들렸거나 초점이 맞지 않은 것 같아요. 다시 찍은 사진으로 바꾸는 걸 권해요.'
  return '사진 품질이 낮을 수 있어요. 파손 부위가 선명하게 보이는지 확인해 주세요.'
}

/** 같은 imageId·같은 URL 로 다시 PUT 하면 풀리는 실패. 그 외는 파일 자체 문제라 다른 파일로 새로 발급해야 한다 */
export const RETRY_SAME_IMAGE = new Set(['MISSING_FILE', 'SIZE_MISMATCH', 'PROCESSING_ERROR'])

const ALLOWED_EXT = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png' }

/**
 * 파일 선택 시점의 클라이언트 검증 — 서버 규칙과 같은 값. 서버 400 을 받기 전에 걸러야 UX 가 산다(§7-2).
 * iOS 는 카메라 촬영본을 HEIC 로 줄 수 있어 accept 만으로는 못 막는다 — 확장자와 file.type 을 직접 본다(§3-1).
 * 돌려주는 값: { ok: true, contentType } 또는 { ok: false, code }
 */
export function validateAccidentImage(file, maxBytes = 20 * 1024 * 1024) {
  const name = file?.name || ''
  const ext = name.includes('.') ? name.split('.').pop().toLowerCase() : ''
  const type = (file?.type || '').toLowerCase()
  if (!file || !file.size) return { ok: false, code: 'MISSING_FILE' }
  if (ext === 'heic' || ext === 'heif' || type === 'image/heic' || type === 'image/heif') return { ok: false, code: 'SERVER_CONVERSION_UNSUPPORTED' }
  if (!ALLOWED_EXT[ext]) return { ok: false, code: 'UNSUPPORTED_EXTENSION' }
  if (type && type !== ALLOWED_EXT[ext]) return { ok: false, code: 'UNSUPPORTED_CONTENT_TYPE' }
  if (file.size > maxBytes) return { ok: false, code: 'FILE_TOO_LARGE' }
  if (name.length > 255 || /[\\/\u0000-\u001f]/.test(name)) return { ok: false, code: 'INVALID_FILE_NAME' }
  return { ok: true, contentType: ALLOWED_EXT[ext] }
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
