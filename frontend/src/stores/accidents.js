import { defineStore } from 'pinia'
import { fetchMyAccidents } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'
import { useAuthStore } from './auth'

const PAGE_SIZE = 20 // 서버 기본값과 같다 (상한 100)

// 서버 목록 모양 그대로의 프로토타입 목업 (VITE_AUTH_GUARD=off 로 백엔드 없이 화면을 볼 때만)
const daysAgo = (n) => new Date(Date.now() - n * 86400000).toISOString()
const MOCK_ACCIDENTS = () => [
  { accidentId: 1, vehicleId: 1, vehicleInputType: 'REGISTERED', modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021, createdAt: daysAgo(2),
    status: 'ESTIMATED', imageCount: 4, thumbnailUrl: '/assets/avante-damage.png', thumbnailExpiresAt: null, estimateId: 1, estimatedCostMin: 980000, estimatedCostMedian: 1240000, estimatedCostMax: 1620000 },
  { accidentId: 2, vehicleId: 2, vehicleInputType: 'REGISTERED', modelId: 44, manufacturer: '기아', modelName: '쏘렌토', vehicleType: 'SUV', carClass: 'Full-size', modelYear: 2019, createdAt: daysAgo(24),
    status: 'ANALYZING', imageCount: 3, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: null, estimatedCostMin: null, estimatedCostMedian: null, estimatedCostMax: null },
  { accidentId: 3, vehicleId: 1, vehicleInputType: 'REGISTERED', modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021, createdAt: daysAgo(35),
    status: 'ANALYSIS_FAILED', imageCount: 2, thumbnailUrl: null, thumbnailExpiresAt: null, estimateId: null, estimatedCostMin: null, estimatedCostMedian: null, estimatedCostMax: null },
]

/* ----- 숨긴 이력 (S15P21A307-531) -----
 * 서버에 사고 삭제 API 가 없어 "삭제" 대신 이 기기에서만 보이지 않게 한다. 숨긴 accidentId 를 회원별 localStorage 에 둔다.
 * 서버 기록·마이페이지 건수(accidentCount)는 그대로다. 삭제 API 가 생기면 hide() 안을 API 호출로 바꾸면 된다.
 */
const HIDDEN_KEY = (memberId) => `noka.hiddenAccidents.${memberId ?? 'guest'}`
function readHidden(key) {
  try { const v = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(v) ? v.filter((n) => Number.isInteger(n)) : [] } catch { return [] }
}
function writeHidden(key, ids) {
  try { ids.length ? localStorage.setItem(key, JSON.stringify(ids)) : localStorage.removeItem(key) } catch { /* 저장소를 못 쓰면 이 세션에서만 숨겨진다 */ }
}

/**
 * 내 사고 이력 — GET /api/accidents/me 의 페이지 응답을 이어 붙여 담는다.
 * 항목 모양은 서버 그대로(AccidentSummaryResponse): 공통 10필드 + 목록 전용 8필드(status·imageCount·thumbnail*·estimateId·estimatedCost*).
 * 썸네일 URL 은 10분짜리 서명 URL 이라 화면에 들어올 때마다 새로 받는다(load(true)). 홈의 "최근 사고" 도 이 목록의 첫 건을 쓰면 된다.
 */
export const useAccidentStore = defineStore('accidents', {
  state: () => ({
    all: [], // 서버가 준 항목 전부(숨긴 것 포함)
    hidden: [], // 이 기기에서 숨긴 accidentId
    page: -1, // 마지막으로 받은 페이지. -1 = 아직 없음
    hasNext: false,
    total: null, // totalElements. null = 아직 모름
    loading: false,
    error: '',
  }),

  getters: {
    loaded: (s) => s.page >= 0,
    /** 화면에 보이는 항목 — 숨긴 것을 뺀 목록. 사고 이력·홈 카드가 이것만 쓴다 */
    items: (s) => s.all.filter((a) => !s.hidden.includes(a.accidentId)),
    /** 받은 항목 중 숨긴 건수 */
    hiddenCount: (s) => s.all.filter((a) => s.hidden.includes(a.accidentId)).length,
    /** 화면용 총 건수 — 서버 총계에서 숨긴 건수를 뺀다 */
    visibleTotal: (s) => (s.total == null ? null : Math.max(0, s.total - s.all.filter((a) => s.hidden.includes(a.accidentId)).length)),
  },

  actions: {
    /** 첫 페이지부터 다시 받는다. force 가 아니고 이미 받았으면 그대로 둔다 */
    async load(force = false) {
      this.hidden = readHidden(this.hiddenKey())
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
          this.all = MOCK_ACCIDENTS(); this.page = 0; this.hasNext = false; this.total = this.all.length
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

    hiddenKey() { return HIDDEN_KEY(useAuthStore().me?.memberId) },
    /** 이 기기에서 숨긴다(서버 호출 없음) */
    hide(accidentId) {
      if (!this.hidden.includes(accidentId)) this.hidden = [...this.hidden, accidentId]
      writeHidden(this.hiddenKey(), this.hidden)
    },
    unhide(accidentId) {
      this.hidden = this.hidden.filter((id) => id !== accidentId)
      writeHidden(this.hiddenKey(), this.hidden)
    },
    unhideAll() {
      this.hidden = []
      writeHidden(this.hiddenKey(), [])
    },

    reset() { this.$reset() },
  },
})
