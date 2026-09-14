// 백엔드 API 공통 클라이언트.
// 인증은 소셜 로그인 + 서버 세션이다 — 토큰이 없고 SESSION(HttpOnly) 쿠키 하나로 인증된다.
// withCredentials 가 없으면 브라우저가 쿠키를 싣지 않아 보호 API 가 전부 401 이 된다.
// (Docs/Api/김재원 담당 백엔드 API — FE 인수인계.md §4, backend SecurityConfig)
import axios from 'axios'

export const API_BASE = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '')

/** 서버 오류를 { status, code, message } 로 정규화한다. 401 은 본문이 없어 status 로만 판단한다. */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export const http = axios.create({ baseURL: API_BASE, withCredentials: true, timeout: 10000 })

// 401 · 403 SIGNUP_REQUIRED 를 한곳(main.js)에서 라우팅으로 연결하기 위한 훅
let authErrorHandler = null
export function onAuthError(fn) { authErrorHandler = fn }

http.interceptors.response.use(
  (res) => res,
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const status = error.response?.status ?? 0
    const body = error.response?.data?.error
    const code = body?.code ?? (status === 401 ? 'UNAUTHORIZED' : status === 0 ? 'NETWORK_ERROR' : 'UNKNOWN')
    const message = body?.message ?? (status === 0 ? '서버에 연결할 수 없어요.' : '요청을 처리할 수 없어요.')
    const err = new ApiError(status, code, message)
    if (status === 401 || (status === 403 && code === 'SIGNUP_REQUIRED')) authErrorHandler?.(err)
    // 어떤 상태에도 자동 재시도하지 않는다 (특히 429)
    return Promise.reject(err)
  },
)

const data = (res) => res.data.data

/* ===== 인증 · 회원 (backend AuthController · MemberController · SecurityConfig) ===== */

/** 소셜 로그인 진입. 브라우저 이동으로 호출한다 — 서버가 카카오 동의 화면으로 보내고, 끝나면 FE 로 리다이렉트한다. */
export const loginUrl = (provider = 'kakao') => `${API_BASE}/oauth2/authorization/${provider}`

/** 내 정보. 비로그인 401 · 가입 대기 403 SIGNUP_REQUIRED · 회원 { memberId, nickname, email, provider, role } */
export const fetchMe = () => http.get('/api/auth/me').then(data)

/** 가입 화면 데이터. 가입 대기 세션에서만 200 — { provider, socialNickname, requiredTerms: ['SERVICE','PRIVACY'] } */
export const fetchSignupContext = () => http.get('/api/auth/signup').then(data)

/** 약관 동의 + 가입. 성공하면 같은 세션이 로그인 세션으로 승격된다. nickname 2~12자, agreedTerms 는 TermsType 이름 배열 */
export const signup = (nickname, agreedTerms) => http.post('/api/auth/signup', { nickname, agreedTerms }).then(data)

/** 로그아웃. 204, 세션이 없어도 204 (멱등). POST 만 받는다 */
export const logout = () => http.post('/api/auth/logout')

/** 마이페이지 프로필 — { memberId, nickname, email, provider, profileImageUrl, vehicleCount, accidentCount, createdAt } */
export const fetchProfile = () => http.get('/api/members/me').then(data)
