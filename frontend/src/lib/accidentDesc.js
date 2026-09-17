import { reactive } from 'vue'
import { AUTH_GUARD_OFF } from '../router'
import { fetchEstimate } from './api'
import { accidentStageText, damageSummaryText } from '../data/accidents'

/**
 * 사고 한 줄 설명 — 사고 이력·나의 체크리스트 행의 둘째 줄.
 * 목록 API 에 사고 유형 필드가 없어, 견적이 있는 사고는 GET /api/estimates/{id} 항목의 부위명·손상 유형으로 조립하고
 * 없는 사고는 진행 단계 문구로 대신한다. 견적은 확정 뒤 바뀌지 않으므로 estimateId 별로 모듈 캐시에 둔다(화면을 오가도 재요청 없음).
 */
const cache = new Map() // estimateId → 문구
const desc = reactive({}) // accidentId → 문구
const MOCK_DESC = { 1: '프론트 범퍼, 헤드램프(좌) 외 2곳 파손' } // 가드 off 목업(견적 1)

async function load(a) {
  if (!a.estimateId) { desc[a.accidentId] = accidentStageText(a.status); return }
  if (cache.has(a.estimateId)) { desc[a.accidentId] = cache.get(a.estimateId); return }
  desc[a.accidentId] = '손상 정보 불러오는 중…'
  let text = ''
  try {
    text = AUTH_GUARD_OFF ? (MOCK_DESC[a.estimateId] || '') : damageSummaryText((await fetchEstimate(a.estimateId)).items)
  } catch { text = '' }
  text = text || accidentStageText(a.status)
  cache.set(a.estimateId, text)
  desc[a.accidentId] = text
}

export function useAccidentDesc() {
  /** 목록의 각 사고에 대해 아직 없는 문구만 받는다 */
  const ensure = (items) => { for (const a of items) if (!(a.accidentId in desc)) load(a) }
  /** 문구. 아직 없으면 단계 문구 */
  const text = (a) => desc[a.accidentId] || accidentStageText(a.status)
  return { desc, ensure, text }
}
