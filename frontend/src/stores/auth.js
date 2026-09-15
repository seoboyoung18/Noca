import { defineStore } from 'pinia'
import { ApiError, fetchMe, fetchProfile, logout as apiLogout } from '../lib/api'

// 로그인 뒤 돌아갈 경로. 서버가 OAuth 성공 후 항상 FE '/' 로 보내므로 세션 스토리지에 잠시 보관한다.
const NEXT_KEY = 'noka.auth.next'
// 가입 때 소셜(카카오·구글)이 준 이름. 서버는 가입 뒤 이 값을 버리므로(GET /api/auth/signup 에만 있음)
// FE 가 회원 ID 별로 보관한다. 서버 응답에 socialName 이 생기면 그 값이 우선한다 — 그때 FE 수정 없이 전환된다.
const SOCIAL_KEY = (memberId) => `noka.socialName.${memberId}`
let inflight = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // unknown: 아직 확인 안 함 · guest: 비로그인 · pending: 소셜 인증만 끝난 가입 대기 · member: 로그인 완료
    status: 'unknown',
    me: null, // { memberId, nickname, email, provider, role }
    // GET /api/members/me 의 profileImageUrl — 10분짜리 presigned GET. 없으면 null (기본 아바타)
    profileImageUrl: null,
    // 연결된 소셜 계정의 이름. 닉네임을 바꿔도 변하지 않는다. 없으면 '' (표시 생략)
    socialName: '',
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
        this.syncSocialName(this.me)
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

    setMember(me) { this.me = me; this.status = 'member'; this.syncSocialName(me) },

    /* ----- 소셜 계정 이름: 서버 값 → 브라우저 보관 값 → 없음 ----- */
    syncSocialName(source) {
      if (source?.socialName) { this.socialName = source.socialName; return } // 서버가 내려주기 시작하면 이쪽이 우선
      const id = this.me?.memberId
      try { this.socialName = (id && localStorage.getItem(SOCIAL_KEY(id))) || '' } catch (e) { this.socialName = '' }
    },
    /** 가입 성공 직후 약관 화면이 호출 — GET /api/auth/signup 의 socialNickname 을 회원 ID 에 묶어 보관 */
    rememberSocialName(memberId, name) {
      const v = (name || '').trim()
      if (!memberId || !v) return
      try { localStorage.setItem(SOCIAL_KEY(memberId), v) } catch (e) { /* 저장 불가 환경이면 표시만 생략된다 */ }
      if (this.me?.memberId === memberId) this.socialName = v
    },
    /** 탈퇴 시 정리 */
    forgetSocialName(memberId) {
      if (!memberId) return
      try { localStorage.removeItem(SOCIAL_KEY(memberId)) } catch (e) { /* 무시 */ }
      this.socialName = ''
    },
    /** 닉네임 수정 성공 뒤 세션 정보만 갱신 — 홈·마이페이지·계정 관리 표시가 함께 바뀐다 */
    setNickname(nickname) { if (this.me) this.me = { ...this.me, nickname } },
    /** 프로필 응답(MemberProfileResponse)으로 닉네임·이미지 URL 갱신 */
    setProfile(profile) {
      if (!profile) return
      if (this.me && profile.nickname) this.me = { ...this.me, nickname: profile.nickname }
      this.profileImageUrl = profile.profileImageUrl || null
      if (profile.socialName) this.socialName = profile.socialName
    },
    /** 프로필 이미지 URL 을 새로 받는다. presigned URL 이 10분이라 화면 진입 때마다 부르는 편이 안전하다 */
    async loadProfile() {
      if (this.status !== 'member') return null
      try { const p = await fetchProfile(); this.setProfile(p); return p } catch (e) { return null }
    },
    setPending() { this.me = null; this.profileImageUrl = null; this.socialName = ''; this.status = 'pending' },
    clear() { this.me = null; this.profileImageUrl = null; this.socialName = ''; this.status = 'guest' },

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
