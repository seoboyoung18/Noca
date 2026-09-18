import { defineStore } from 'pinia'
import { fetchMyAccidents, setAccidentHidden } from '../lib/api'
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
 */
export const useAccidentStore = defineStore('accidents', {
  state: () => ({
    all: [], // 보이는 사고(서버가 숨긴 건 빼고 준다)
    page: -1, // 마지막으로 받은 페이지. -1 = 아직 없음
    hasNext: false,
    total: null, // totalElements. null = 아직 모름
    loading: false,
    error: '',

    hidden: [], // 숨긴 사고 — "숨긴 이력" 을 열 때만 받는다
    hiddenLoaded: false,
    hiddenLoading: false,
    hiddenError: '',
  }),

  getters: {
    loaded: (s) => s.page >= 0,
    /** 화면에 보이는 항목. 서버가 이미 걸렀으므로 그대로다 */
    items: (s) => s.all,
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
