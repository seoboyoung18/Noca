import { defineStore } from 'pinia'
import { fetchAccidentEstimates, fetchAnalysisProgress, fetchEstimate, fetchMyAccidents, setAccidentHidden } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'

const PAGE_SIZE = 20 // 서버 기본값과 같다 (상한 100)

// 서버 목록 모양 그대로의 프로토타입 목업 (VITE_AUTH_GUARD=off 로 백엔드 없이 화면을 볼 때만)
const daysAgo = (n) => new Date(Date.now() - n * 86400000).toISOString()
const MOCK_ACCIDENTS = () => [
  { accidentId: 1, vehicleId: 1, vehicleInputType: 'REGISTERED', modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021, createdAt: daysAgo(2),
    status: 'ESTIMATED', hiddenAt: null, imageCount: 4, thumbnailUrl: '/assets/avante-damage.png', thumbnailExpiresAt: null, estimateId: 1, estimatedCostMin: 980000, estimatedCostMedian: 1240000, estimatedCostMax: 1620000, checklistStatus: 'COMPLETED' },
  { accidentId: 2, vehicleId: 2, vehicleInputType: 'REGISTERED', modelId: 44, manufacturer: '기아', modelName: '쏘렌토', vehicleType: 'SUV', carClass: 'Full-size', modelYear: 2019, createdAt: daysAgo(24),
    status: 'ANALYZING', hiddenAt: null, imageCount: 3, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: null, estimatedCostMin: null, estimatedCostMedian: null, estimatedCostMax: null, checklistStatus: null },
  { accidentId: 3, vehicleId: 1, vehicleInputType: 'REGISTERED', modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021, createdAt: daysAgo(35),
    status: 'ANALYSIS_FAILED', hiddenAt: null, imageCount: 2, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: null, estimatedCostMin: null, estimatedCostMedian: null, estimatedCostMax: null, checklistStatus: null },
  { accidentId: 4, vehicleId: 2, vehicleInputType: 'REGISTERED', modelId: 44, manufacturer: '기아', modelName: '쏘렌토', vehicleType: 'SUV', carClass: 'Full-size', modelYear: 2019, createdAt: daysAgo(58),
    status: 'ESTIMATED', hiddenAt: null, imageCount: 5, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: 2, estimatedCostMin: 410000, estimatedCostMedian: 530000, estimatedCostMax: 690000, checklistStatus: 'COMPLETED' },
  { accidentId: 5, vehicleId: 1, vehicleInputType: 'REGISTERED', modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021, createdAt: daysAgo(96),
    status: 'REPAIR_RECORDED', hiddenAt: null, imageCount: 3, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: 3, estimatedCostMin: 220000, estimatedCostMedian: 280000, estimatedCostMax: 350000, checklistStatus: 'COMPLETED' },
]

/* ----- 목업 전용 보관 (가드 off) -----
 * 화면이 바꾼 상태(숨김·분석 완료)를 다시 받아도 유지되게 sessionStorage 에 덧씌운다. 서버가 있을 때는 쓰지 않는다.
 */
const MOCK_HIDDEN_KEY = 'noka.mockHiddenAccidents'
const readMockHidden = () => { try { const v = JSON.parse(sessionStorage.getItem(MOCK_HIDDEN_KEY) || '[]'); return Array.isArray(v) ? v : [] } catch { return [] } }
const writeMockHidden = (ids) => { try { sessionStorage.setItem(MOCK_HIDDEN_KEY, JSON.stringify(ids)) } catch { /* 목업 전용 */ } }
function mockAll() {
  let over = {}
  try { over = JSON.parse(sessionStorage.getItem('noka.mockAccidentOverrides') || '{}') } catch { over = {} }
  return MOCK_ACCIDENTS().map((a) => (over[a.accidentId] ? { ...a, ...over[a.accidentId] } : a))
}

/**
 * 내 사고 이력 — GET /api/accidents/me 의 페이지 응답을 이어 붙여 담는다.
 * 항목 모양은 서버 그대로(AccidentSummaryResponse): 공통 필드 + 목록 전용(status·hiddenAt·imageCount·thumbnail*·estimateId·estimatedCost*·checklistStatus).
 * 썸네일 URL 은 10분짜리 서명 URL 이라 화면에 들어올 때마다 새로 받는다(load(true)). 홈의 "사고 이력" 미리보기도 이 목록을 쓴다.
 *
 * 화면의 "제거하기" 는 서버의 감추기다(PATCH .../hidden, S15P21A307-554) — 지우지 않고 목록에서만 뺀다. 기기를 바꾸거나 캐시를 지워도 유지된다.
 * 기본 조회는 감춘 사고를 아예 싣지 않으므로 화면이 걸러낼 필요가 없고, 감춘 목록은 "제거한 이력" 을 열 때만 includeHidden 으로 따로 받는다.
 *
 * 분석은 했는데 견적이 나오지 않은 사고는 목록에서 뺀다(S15P21A307-564) — 재분석 기능이 없어 사용자가 할 수 있는 일이 없다.
 *  - 분석 실패(ANALYSIS_FAILED)는 status 로 바로 가른다
 *  - 산정 불가(분석 COMPLETED 인데 estimable=false)는 목록 응답에서 "사진만 올린 사고" 와 똑같이 IMAGES_UPLOADED 로 보인다
 *    (estimateId 는 산정된 견적에만 붙는다). 그래서 IMAGES_UPLOADED 인 사고만 분석 상태를 한 번 물어 COMPLETED 면 뺀다
 */
export const useAccidentStore = defineStore('accidents', {
  state: () => ({
    all: [], // 보이는 사고(서버가 숨긴 건 빼고 준다)
    page: -1, // 마지막으로 받은 페이지. -1 = 아직 없음
    hasNext: false,
    total: null, // totalElements. null = 아직 모름
    loading: false,
    error: '',

    unestimated: [], // 분석은 끝났는데 견적이 없는 사고 id — 목록에서 뺀다
    needPart: [], // 분석은 끝났지만 부품을 못 찾아(PART_NOT_RESOLVED) 사용자가 부위를 골라야 하는 사고 id — 목록에 남기고 "부위 선택 필요" 로 보인다
    checked: {}, // accidentId → true. IMAGES_UPLOADED 사고의 분석 상태를 이미 물어본 것

    hidden: [], // 숨긴 사고 — "숨긴 이력" 을 열 때만 받는다
    hiddenLoaded: false,
    hiddenLoading: false,
    hiddenError: '',
  }),

  getters: {
    loaded: (s) => s.page >= 0,
    /** 화면에 보이는 항목 — 감춘 사고는 서버가 이미 뺐고, 분석 실패·산정 불가 사고는 여기서 뺀다.
     *  부위 선택이 필요한 사고는 남기되 상태를 PART_NOT_RESOLVED 로 바꿔 배지·이동 경로가 달라지게 한다(data/accidents.js) */
    items: (s) => s.all
      .filter((a) => a.status !== 'ANALYSIS_FAILED' && !s.unestimated.includes(a.accidentId))
      .map((a) => (s.needPart.includes(a.accidentId) ? { ...a, status: 'PART_NOT_RESOLVED' } : a)),
    /** 보이는 건수 — 서버 총계에서 이 페이지에서 걸러낸 만큼 뺀다(아직 안 받은 페이지의 것은 모른다) */
    visibleTotal() { return this.total == null ? null : Math.max(0, this.total - (this.all.length - this.items.length)) },
    hiddenCount: (s) => s.hidden.length,
  },

  actions: {
    /** 첫 페이지부터 다시 받는다. force 가 아니고 이미 받았으면 그대로 둔다 */
    async load(force = false) {
      if (this.loaded && !force) return
      this.page = -1
      this.hasNext = false
      await this.loadMore(true)
    },

    /** 다음 페이지를 이어 받는다. 실패하면 error 에 문구를 남기고 기존 항목은 유지한다 */
    async loadMore(reset = false) {
      if (this.loading) return
      if (!reset && this.loaded && !this.hasNext) return
      this.loading = true
      this.error = ''
      try {
        if (AUTH_GUARD_OFF) {
          const hidden = readMockHidden()
          this.all = mockAll().filter((a) => !hidden.includes(a.accidentId))
          this.page = 0; this.hasNext = false; this.total = this.all.length
          return
        }
        const res = await fetchMyAccidents(this.page + 1, PAGE_SIZE)
        const got = res.accidents || []
        await this.markUnestimated(got) // 목록을 그리기 전에 가른다 — 그린 뒤 사라지면 깜빡인다
        this.all = reset ? got : [...this.all, ...got]
        this.page = res.page ?? this.page + 1
        this.hasNext = !!res.hasNext
        this.total = res.totalElements ?? this.all.length
      } catch (e) {
        if (e.status === 401) return // main.js 가 로그인으로 보낸다
        this.error = e.status === 0 ? e.message : '사고 이력을 불러오지 못했어요.'
      } finally {
        this.loading = false
      }
    },

    /**
     * IMAGES_UPLOADED 사고 중 분석이 끝난(COMPLETED) 것을 가른다 — 견적이 나왔으면 ESTIMATED 였을 것이다.
     * 부위를 골라 다시 분석할 수 있으면(서버 partSelectionAvailable, S15P21A307-568) needPart 에 넣어 목록에 남기고,
     * 아니면 unestimated 에 넣어 뺀다. 그 값을 안 주는 서버면 최신 견적의 사유가 PART_NOT_RESOLVED 인지로 대신 본다.
     * 사고마다 한 번만 묻고(checked), 못 받아 오면 보여 둔다. 목업은 status 만으로 가른다
     */
    async markUnestimated(list) {
      if (AUTH_GUARD_OFF) return
      const targets = list.filter((a) => a.status === 'IMAGES_UPLOADED' && !this.checked[a.accidentId])
      if (!targets.length) return
      await Promise.all(targets.map(async (a) => {
        this.checked = { ...this.checked, [a.accidentId]: true }
        try {
          const p = await fetchAnalysisProgress(a.accidentId)
          if (p?.status !== 'COMPLETED') return
          const selectable = typeof p.partSelectionAvailable === 'boolean' ? p.partSelectionAvailable : await this._partUnresolved(a.accidentId)
          if (selectable) { if (!this.needPart.includes(a.accidentId)) this.needPart = [...this.needPart, a.accidentId] }
          else if (!this.unestimated.includes(a.accidentId)) this.unestimated = [...this.unestimated, a.accidentId]
        } catch { /* 모르면 보여 둔다 */ }
      }))
    },

    /** 최신 견적의 산정 불가 사유가 "부품 미확정" 인가 — partSelectionAvailable 을 안 주는 서버용 */
    async _partUnresolved(accidentId) {
      const [latest] = await fetchAccidentEstimates(accidentId)
      if (!latest?.estimateId) return false
      const est = await fetchEstimate(latest.estimateId)
      return !est?.estimable && est?.nonEstimableReason === 'PART_NOT_RESOLVED'
    },

    /** 목록에 보이는 사고인가 — items 게터와 같은 기준 */
    isVisible(a) { return a.status !== 'ANALYSIS_FAILED' && !this.unestimated.includes(a.accidentId) },

    /**
     * 화면에 보이는 사고의 전체 건수 — 마이페이지 "사고 이력" 숫자. 서버 accidentCount 는 분석 실패·산정 불가까지 세므로
     * 목록과 어긋난다. 전 페이지를 받아 같은 기준으로 센다(최대 300건 — 요청 폭주 방지, 체크리스트 건수와 같은 상한).
     */
    async countVisible(max = 300) {
      if (AUTH_GUARD_OFF) { if (!this.loaded) await this.load(true); return this.items.length }
      let n = 0, seen = 0
      for (let page = 0; seen < max; page++) {
        const res = await fetchMyAccidents(page, 100)
        const got = res.accidents || []
        await this.markUnestimated(got)
        n += got.filter((a) => this.isVisible(a)).length
        seen += got.length
        if (!res.hasNext || !got.length) break
      }
      return n
    },

    /**
     * 숨긴 사고만 받는다 — includeHidden 조회에서 hiddenAt 이 있는 것만 고른다.
     * 기본 목록과 달리 "숨긴 이력" 화면을 열 때만 부른다(평소에 요청을 늘리지 않으려고).
     */
    async loadHidden() {
      if (this.hiddenLoading) return
      this.hiddenLoading = true
      this.hiddenError = ''
      try {
        if (AUTH_GUARD_OFF) {
          const ids = readMockHidden()
          this.hidden = mockAll().filter((a) => ids.includes(a.accidentId)).map((a) => ({ ...a, hiddenAt: new Date().toISOString() }))
        } else {
          const res = await fetchMyAccidents(0, 100, true)
          this.hidden = (res.accidents || []).filter((a) => a.hiddenAt)
        }
        this.hiddenLoaded = true
      } catch (e) {
        if (e.status !== 401) this.hiddenError = e.status === 0 ? e.message : '제거한 이력을 불러오지 못했어요.'
      } finally {
        this.hiddenLoading = false
      }
    },

    /**
     * 목록에서 감춘다 — 지우는 것이 아니다. 사진·분석·견적·체크리스트는 그대로 남는다.
     * 성공하면 보이는 목록에서 빼고 총 건수를 줄인다(다시 받지 않는다 — 페이지 위치가 흔들린다).
     */
    async hide(accidentId) {
      if (AUTH_GUARD_OFF) writeMockHidden([...new Set([...readMockHidden(), accidentId])])
      else await setAccidentHidden(accidentId, true)
      const found = this.all.find((a) => a.accidentId === accidentId)
      this.all = this.all.filter((a) => a.accidentId !== accidentId)
      if (this.total != null) this.total = Math.max(0, this.total - 1)
      if (found) this.hidden = [{ ...found, hiddenAt: new Date().toISOString() }, ...this.hidden.filter((a) => a.accidentId !== accidentId)]
    },

    /** 한 건만 되돌린다. 성공하면 숨긴 목록에서 빼고 보이는 목록을 다시 받는다(원래 순서 자리로 돌아가야 한다) */
    async unhide(accidentId) {
      if (AUTH_GUARD_OFF) writeMockHidden(readMockHidden().filter((id) => id !== accidentId))
      else await setAccidentHidden(accidentId, false)
      this.hidden = this.hidden.filter((a) => a.accidentId !== accidentId)
      await this.load(true)
    },

    reset() { this.$reset() },
  },
})
