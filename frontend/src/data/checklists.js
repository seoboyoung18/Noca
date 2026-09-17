// 정비 체크리스트 표시 규칙 — 서버는 상태 코드·source·문장만 주고 탭 묶음·문구·목업은 FE 몫이다.
// (backend RepairChecklistStatusResponse · RepairChecklistItemResponse · RepairChecklistFailure)

/** 생성 상태 문구. null(요청 전)·QUEUED·PROCESSING 은 화면에서 "만드는 중" 으로 묶는다 */
export const CHECKLIST_STATUS_TEXT = {
  QUEUED: '생성 대기 중', PROCESSING: '생성 중', COMPLETED: '완료', FAILED: '생성 실패',
}
export const checklistStatusText = (status) => (status ? CHECKLIST_STATUS_TEXT[status] || status : '생성 전')

/** 아직 만들어지는 중인가(요청 전 포함) — 폴링 대상 */
export const checklistPending = (status) => !status || status === 'QUEUED' || status === 'PROCESSING'

/** 실패 코드(RepairChecklistFailure) → 사용자 문구 */
export const CHECKLIST_FAIL_TEXT = {
  LLM_UNAVAILABLE: 'AI 서버가 준비되지 않아 체크리스트를 만들지 못했어요.',
  LLM_CALL_FAILED: 'AI 서버 응답이 없어 체크리스트를 만들지 못했어요.',
  INVALID_RESPONSE: 'AI 응답을 읽지 못해 체크리스트를 만들지 못했어요.',
  ABANDONED: '생성 시간이 너무 오래 걸려 중단됐어요.',
  INTERNAL: '서버 문제로 체크리스트를 만들지 못했어요.',
}
export const checklistFailText = (code) => CHECKLIST_FAIL_TEXT[code] || '체크리스트를 만들지 못했어요.'

/**
 * 탭 — 이전 화면 구성 그대로. 서버 항목에는 분류가 없어(source 만) FE 가 나눈다:
 *   공통       = COMMON
 *   부품       = AI 항목 중 문장에 견적 부위명이 들어 있는 것(부위별 그룹) + 직접 추가(구분 "부품")
 *   함께 점검  = AI 항목 중 어느 부위에도 해당하지 않는 것(사진에 없는 부품·숨은 손상 후보) + 직접 추가(구분 "함께 점검")
 * 백엔드가 항목에 partCode·category 를 실어 주면 이 추정을 그 값으로 바꾼다(요청 중).
 */
export const CHECKLIST_TABS = [
  { key: 'common', label: '공통' },
  { key: 'parts', label: '부품' },
  { key: 'hidden', label: '함께 점검' },
]
export const USER_CATEGORIES = ['common', 'parts', 'hidden']

/**
 * 서버 항목 → 화면 항목(CheckItem 이 쓰는 모양). displayOrder 순.
 * editable: 문안 수정·삭제가 되는 항목(USER 만). 체크·메모는 모든 항목 가능
 */
export function checklistItemView(it) {
  return {
    id: it.itemId, text: it.content, done: !!it.checked, memo: it.memo || '',
    source: it.source, editable: it.source === 'USER', order: it.displayOrder ?? 0,
  }
}

/** 부위명의 비교용 형태들 — "헤드램프(좌)" → ["헤드램프(좌)", "헤드램프"], 공백 제거 */
function partKeys(name = '') {
  const full = name.replace(/\s+/g, '')
  const base = full.replace(/\(.*?\)/g, '')
  return [...new Set([full, base].filter((k) => k.length >= 2))]
}

/**
 * 항목을 탭으로 나눈다.
 * @param items         서버 항목
 * @param estimateItems 견적 항목(partCode·partNameKo·repairMethodDisplayName) — 부위별 그룹의 기준. 없으면 AI 항목은 모두 "함께 점검"
 * @param userCats      직접 추가 항목의 구분 { [itemId]: 'common'|'parts'|'hidden' } (이 기기 저장). 없으면 'parts'
 * @returns { common: [], parts: [{ id, name, fix, items }], hidden: [], counts: { common, parts, hidden } }
 */
export function groupChecklistItems(items = [], estimateItems = [], userCats = {}) {
  const sorted = [...items].sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0))
  const common = [], hidden = []
  const partGroups = estimateItems.map((e) => ({ id: e.partCode, name: e.partNameKo || e.partCode, fix: e.repairMethodDisplayName || '', keys: partKeys(e.partNameKo || ''), items: [] }))
  const custom = { id: 'custom', name: '직접 추가', fix: '', items: [] }
  for (const raw of sorted) {
    const it = checklistItemView(raw)
    if (raw.source === 'COMMON') { common.push(it); continue }
    if (raw.source === 'USER') {
      const cat = userCats[raw.itemId] || 'parts'
      if (cat === 'common') common.push(it)
      else if (cat === 'hidden') hidden.push(it)
      else custom.items.push(it)
      continue
    }
    // AI — 문장에 부위명이 들어 있으면 그 부위로, 여러 부위가 걸리면 먼저 나오는 부위로
    const text = (raw.content || '').replace(/\s+/g, '')
    let best = null, bestPos = Infinity
    for (const g of partGroups) for (const k of g.keys) { const pos = text.indexOf(k); if (pos > -1 && pos < bestPos) { best = g; bestPos = pos } }
    if (best) best.items.push(it)
    else hidden.push(it)
  }
  const parts = [...partGroups.filter((g) => g.items.length), ...(custom.items.length ? [custom] : [])].map(({ keys, ...g }) => g)
  return { common, parts, hidden, counts: { common: common.length, parts: parts.reduce((n, g) => n + g.items.length, 0), hidden: hidden.length } }
}

/**
 * AI 한 줄 요약 — 서버 응답에 아직 없다(백엔드 요청 3). 응답에 summary·focus 가 실리면 그 값을 쓰고, 없으면 이 목업 문구를 보인다.
 * 연동 시: aiSummary(cl) 의 폴백만 지우면 된다.
 */
export const MOCK_AI_SUMMARY = {
  summary: '저속 전면 추돌로 범퍼·헤드램프가 크게 파손되고 좌측 펜더까지 충격이 이어진 사고예요.',
  focus: '범퍼 뒤 냉각 부품(라디에이터·콘덴서) 손상 여부와 헤드램프 교환 시 광축 조정 포함 여부를 꼭 확인하세요.',
}
export const aiSummary = (cl) => (cl?.summary ? { summary: cl.summary, focus: cl.focus || '' } : MOCK_AI_SUMMARY)

/** 기본 안내 문구 — 서버 notice 가 없을 때(시드가 빠진 환경) */
export const CHECKLIST_DEFAULT_NOTICE = '사고 내용을 바탕으로 AI가 생성한 참고용 체크리스트입니다. 실제 정비 범위와 방식은 정비 전문가 점검에 따라 달라질 수 있어요.'

/* ----- 목업 (가드 off) — 서버 응답 모양 그대로 ----- */
const iso = (min) => new Date(Date.now() - min * 60000).toISOString()
export function mockChecklist(accidentId) {
  if (Number(accidentId) !== 1) return { checklistId: null, status: null, failureReason: null, createdAt: null, completedAt: null, regeneratedAt: null, items: [], progress: { completed: 0, total: 0 }, notice: null }
  const items = [
    { itemId: 101, source: 'COMMON', commonCode: 'ESTIMATE_DOCUMENT', content: '견적서 서면 수령·항목별 금액 확인', checked: true, memo: null, displayOrder: 1, checkedAt: iso(30) },
    { itemId: 102, source: 'COMMON', commonCode: 'PART_GRADE', content: '부품 등급(순정/OEM/재생/중고)과 부품 번호 확인', checked: true, memo: null, displayOrder: 2, checkedAt: iso(28) },
    { itemId: 103, source: 'COMMON', commonCode: 'WORK_PHOTO', content: '작업 전·후 사진 요청', checked: false, memo: null, displayOrder: 3, checkedAt: null },
    { itemId: 104, source: 'COMMON', commonCode: 'REPLACED_PART', content: '교체된 부품 실물 확인', checked: false, memo: null, displayOrder: 4, checkedAt: null },
    { itemId: 105, source: 'COMMON', commonCode: 'WARRANTY', content: '보증 기간·보증서 발급', checked: false, memo: null, displayOrder: 5, checkedAt: null },
    { itemId: 106, source: 'COMMON', commonCode: 'DURATION_LOANER', content: '예상 소요 기간과 대차 여부', checked: false, memo: null, displayOrder: 6, checkedAt: null },
    { itemId: 107, source: 'AI', commonCode: null, content: '프론트 범퍼 교환 시 내부 브래킷과 고정 클립 손상 여부를 함께 확인해 달라고 요청하세요', checked: false, memo: null, displayOrder: 7, checkedAt: null },
    { itemId: 108, source: 'AI', commonCode: null, content: '헤드램프(좌) 교환 후 광축 조정이 작업에 포함되는지 확인하세요', checked: false, memo: '조정비 별도인지 물어보기', displayOrder: 8, checkedAt: null },
    { itemId: 109, source: 'AI', commonCode: null, content: '앞휀더(좌) 판금 후 도장 색상이 주변 패널과 맞는지 확인을 요청하세요', checked: false, memo: null, displayOrder: 9, checkedAt: null },
    { itemId: 110, source: 'AI', commonCode: null, content: '범퍼 뒤 라디에이터·에어컨 콘덴서에 변형이나 누수가 없는지 점검을 요청하세요', checked: false, memo: null, displayOrder: 10, checkedAt: null },
    { itemId: 111, source: 'USER', commonCode: null, content: '범퍼 교체 후 번호판 재부착 여부', checked: false, memo: null, displayOrder: 11, checkedAt: null },
  ]
  return {
    checklistId: 1, status: 'COMPLETED', failureReason: null, createdAt: iso(60), completedAt: iso(59), regeneratedAt: null,
    items, progress: { completed: items.filter((i) => i.checked).length, total: items.length }, notice: CHECKLIST_DEFAULT_NOTICE,
  }
}
