import { defineStore } from 'pinia'
import { ApiError, fetchMe, logout as apiLogout } from '../lib/api'

// 로그인 뒤 돌아갈 경로. 서버가 OAuth 성공 후 항상 FE '/' 로 보내므로 세션 스토리지에 잠시 보관한다.
const NEXT_KEY = 'noka.auth.next'
let inflight = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // unknown: 아직 확인 안 함 · guest: 비로그인 · pending: 소셜 인증만 끝난 가입 대기 · member: 로그인 완료
    status: 'unknown',
    me: null, // { memberId, nickname, email, provider, role }
    offline: false, // 서버에 연결하지 못해 guest 로 처리한 경우
  }),

  getters: {
    isMember: (s) => s.status === 'member',
    isPending: (s) => s.status === 'pending',
    nickname: (s) => s.me?.nickname || '',
  },

  actions: {
    /** 세션 상태를 서버에 다시 묻는다. */
    async refresh() {
      this.offline = false
      try {
        this.me = await fetchMe()
        this.status = 'member'
      } catch (e) {
        this.me = null
        if (e instanceof ApiError && e.status === 403 && e.code === 'SIGNUP_REQUIRED') this.status = 'pending'
        else {
          this.status = 'guest'
          this.offline = e instanceof ApiError && e.status === 0
        }
      }
      return this.status
    },

    /** 아직 확인하지 않았을 때만 서버에 묻는다. 동시에 여러 곳에서 불러도 요청은 한 번이다. */
    ensure() {
      if (this.status !== 'unknown') return Promise.resolve(this.status)
      if (!inflight) inflight = this.refresh().finally(() => { inflight = null })
      return inflight
    },

    setMember(me) { this.me = me; this.status = 'member' },
    /** 닉네임 수정 성공 뒤 세션 정보만 갱신 — 홈·마이페이지·계정 관리 표시가 함께 바뀐다 */
    setNickname(nickname) { if (this.me) this.me = { ...this.me, nickname } },
    setPending() { this.me = null; this.status = 'pending' },
    clear() { this.me = null; this.status = 'guest' },

    /** 서버 세션을 끊는다. 서버가 실패해도 로컬 상태는 비운다. */
    async logout() {
      try { await apiLogout() } catch (e) { /* 이미 끊긴 세션 등 — 로컬 정리만 하면 된다 */ }
      this.clear()
    },

    rememberNext(path) {
      try { if (path && path !== '/') sessionStorage.setItem(NEXT_KEY, path) } catch (e) { /* 비공개 모드 등 */ }
    },
    consumeNext(fallback = '/home') {
      try {
        const next = sessionStorage.getItem(NEXT_KEY)
        sessionStorage.removeItem(NEXT_KEY)
        return next || fallback
      } catch (e) { return fallback }
    },
  },
})
