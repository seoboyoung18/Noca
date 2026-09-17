import { defineStore } from 'pinia'
import { AUTH_GUARD_OFF } from '../router'
import {
  addRepairChecklistItem, checkRepairChecklistItem, deleteRepairChecklistItem, fetchRepairChecklistStatus,
  memoRepairChecklistItem, regenerateRepairChecklist, requestRepairChecklist, updateRepairChecklistItem,
} from '../lib/api'
import { checklistPending, mockChecklist } from '../data/checklists'
import { useAuthStore } from './auth'

const CAT_KEY = (memberId) => `noka.checklistCats.${memberId ?? 'guest'}`
function readCats(key) { try { const v = JSON.parse(localStorage.getItem(key) || '{}'); return v && typeof v === 'object' ? v : {} } catch { return {} } }
function writeCats(key, cats) { try { localStorage.setItem(key, JSON.stringify(cats)) } catch { /* 저장소 없으면 이 세션에서만 유지 */ } }

const POLL_MS = 2000
const POLL_MAX = 90 // 3분. 워커가 죽었을 때 무한 폴링을 막는다

/**
 * 사고별 정비 체크리스트 — GET /api/accidents/{id}/repair-checklist 응답을 accidentId 별로 담는다.
 * 서버가 자동 생성하지 않으므로 ensureRequested() 로 큐에 넣고(견적 화면·체크리스트 화면 진입 시), pending 이면 폴링한다.
 * 항목 조작은 서버 응답으로 해당 항목만 갈아 끼운다(낙관적 갱신 없음 — 실패하면 화면이 서버 상태 그대로 남는다).
 */
export const useChecklistStore = defineStore('checklist', {
  state: () => ({
    byAccident: {}, // accidentId → 상태 응답
    loading: {}, // accidentId → boolean
    error: {}, // accidentId → 문구
    timers: {}, // accidentId → setTimeout id (폴링)
    // 직접 추가 항목의 탭 구분(공통·부품·함께 점검). 서버에 저장 자리가 없어 이 기기에만 남는다 — 다른 기기에서는 "부품 > 직접 추가" 로 보인다
    userCats: null, // { [itemId]: 'common'|'parts'|'hidden' } — 첫 사용 시 localStorage 에서 읽음
  }),

  getters: {
    get: (s) => (accidentId) => s.byAccident[accidentId] || null,
    cats: (s) => s.userCats || {},
  },

  actions: {
    _set(accidentId, res) {
      this.byAccident = { ...this.byAccident, [accidentId]: res }
      // 진행도는 서버 값이지만 항목을 바꾼 뒤 다시 조회하지 않으므로 항목에서 다시 센다
      if (res?.items) res.progress = { completed: res.items.filter((i) => i.checked).length, total: res.items.length }
    },

    /** 상태 조회. 실패하면 error 에 남기고 기존 값은 유지 */
    async load(accidentId) {
      if (!accidentId) return null
      this.loading = { ...this.loading, [accidentId]: true }
      this.error = { ...this.error, [accidentId]: '' }
      try {
        const res = AUTH_GUARD_OFF ? (this.byAccident[accidentId] || mockChecklist(accidentId)) : await fetchRepairChecklistStatus(accidentId)
        this._set(accidentId, res)
        return res
      } catch (e) {
        if (e.status !== 401) this.error = { ...this.error, [accidentId]: e.status === 404 ? '사고를 찾을 수 없어요.' : e.status === 0 ? e.message : '체크리스트를 불러오지 못했어요.' }
        return null
      } finally {
        this.loading = { ...this.loading, [accidentId]: false }
      }
    },

    /**
     * 생성 요청 — 없으면 QUEUED, FAILED 면 재시도, 진행 중이면 그대로. COMPLETED(409)·GMS 없음(503)은 조용히 넘긴다.
     * 견적 화면이 견적을 처음 받았을 때와 체크리스트 화면에 들어왔을 때 부른다.
     */
    async ensureRequested(accidentId) {
      if (!accidentId) return null
      try {
        if (AUTH_GUARD_OFF) {
          const cur = this.byAccident[accidentId] || mockChecklist(accidentId)
          if (cur.status === 'COMPLETED') return cur
          this._set(accidentId, { ...cur, status: 'QUEUED', failureReason: null, items: [] })
          this._mockFinish(accidentId)
          return this.byAccident[accidentId]
        }
        const res = await requestRepairChecklist(accidentId)
        this._set(accidentId, res)
        return res
      } catch (e) {
        if (e.status === 409 || e.status === 503 || e.status === 401) return null
        this.error = { ...this.error, [accidentId]: e.status === 0 ? e.message : '체크리스트 생성을 요청하지 못했어요.' }
        return null
      }
    },

    /** 재생성. COMPLETED 일 때만. 상태가 QUEUED 로 바뀌면 화면이 폴링을 시작한다 */
    async regenerate(accidentId) {
      if (AUTH_GUARD_OFF) {
        const cur = this.byAccident[accidentId] || mockChecklist(accidentId)
        this._set(accidentId, { ...cur, status: 'QUEUED', items: cur.items.filter((i) => i.source === 'USER') })
        this._mockFinish(accidentId, true)
        return this.byAccident[accidentId]
      }
      const res = await regenerateRepairChecklist(accidentId)
      this._set(accidentId, res)
      return res
    },

    /** pending 이면 2초마다 다시 조회. 끝나면(COMPLETED·FAILED) 멈춘다 */
    startPolling(accidentId) {
      this.stopPolling(accidentId)
      let n = 0
      const tick = async () => {
        const res = await this.load(accidentId)
        if (!res || !checklistPending(res.status) || ++n >= POLL_MAX || res.status === null) { delete this.timers[accidentId]; return }
        this.timers[accidentId] = setTimeout(tick, POLL_MS)
      }
      this.timers[accidentId] = setTimeout(tick, POLL_MS)
    },
    stopPolling(accidentId) {
      clearTimeout(this.timers[accidentId])
      delete this.timers[accidentId]
    },

    /* ----- 항목 ----- */
    _replaceItem(accidentId, item) {
      const cur = this.byAccident[accidentId]
      if (!cur) return
      const items = cur.items.some((i) => i.itemId === item.itemId) ? cur.items.map((i) => (i.itemId === item.itemId ? item : i)) : [...cur.items, item]
      this._set(accidentId, { ...cur, items })
    },
    async toggle(accidentId, itemId, checked) {
      if (AUTH_GUARD_OFF) { const it = this._mockItem(accidentId, itemId); if (it) this._replaceItem(accidentId, { ...it, checked, checkedAt: checked ? new Date().toISOString() : null }); return }
      this._replaceItem(accidentId, await checkRepairChecklistItem(accidentId, itemId, checked))
    },
    async setMemo(accidentId, itemId, memo) {
      if (AUTH_GUARD_OFF) { const it = this._mockItem(accidentId, itemId); if (it) this._replaceItem(accidentId, { ...it, memo: memo || null }); return }
      this._replaceItem(accidentId, await memoRepairChecklistItem(accidentId, itemId, memo || null))
    },
    /** 직접 추가. category 는 화면 탭 구분(이 기기 저장) */
    async add(accidentId, content, category = 'parts') {
      let item
      if (AUTH_GUARD_OFF) {
        const cur = this.byAccident[accidentId]
        const order = Math.max(0, ...cur.items.map((i) => i.displayOrder || 0)) + 1
        item = { itemId: Date.now(), source: 'USER', commonCode: null, content, checked: false, memo: null, displayOrder: order, checkedAt: null }
      } else item = await addRepairChecklistItem(accidentId, content)
      this._replaceItem(accidentId, item)
      this.setCategory(item.itemId, category)
    },
    loadCats() { if (!this.userCats) this.userCats = readCats(CAT_KEY(useAuthStore().me?.memberId)) },
    setCategory(itemId, category) {
      this.loadCats()
      this.userCats = { ...this.userCats, [itemId]: category }
      writeCats(CAT_KEY(useAuthStore().me?.memberId), this.userCats)
    },
    async update(accidentId, itemId, content) {
      if (AUTH_GUARD_OFF) { const it = this._mockItem(accidentId, itemId); if (it) this._replaceItem(accidentId, { ...it, content }); return }
      this._replaceItem(accidentId, await updateRepairChecklistItem(accidentId, itemId, content))
    },
    async remove(accidentId, itemId) {
      if (!AUTH_GUARD_OFF) await deleteRepairChecklistItem(accidentId, itemId)
      const cur = this.byAccident[accidentId]
      if (cur) this._set(accidentId, { ...cur, items: cur.items.filter((i) => i.itemId !== itemId) })
      if (this.userCats && itemId in this.userCats) { const { [itemId]: _, ...rest } = this.userCats; this.userCats = rest; writeCats(CAT_KEY(useAuthStore().me?.memberId), rest) }
    },

    /* ----- 목업 도우미 ----- */
    _mockItem(accidentId, itemId) { return this.byAccident[accidentId]?.items.find((i) => i.itemId === itemId) || null },
    _mockFinish(accidentId, regen = false) {
      setTimeout(() => {
        const cur = this.byAccident[accidentId]
        if (!cur || cur.status !== 'QUEUED') return
        const fresh = mockChecklist(1)
        const user = regen ? cur.items.filter((i) => i.source === 'USER') : []
        this._set(accidentId, { ...fresh, checklistId: cur.checklistId || 1, regeneratedAt: regen ? new Date().toISOString() : null, items: [...fresh.items.filter((i) => i.source !== 'USER'), ...(regen ? user : fresh.items.filter((i) => i.source === 'USER'))] })
      }, 2500)
    },

    reset() { Object.keys(this.timers).forEach((k) => clearTimeout(this.timers[k])); this.$reset() },
  },
})
