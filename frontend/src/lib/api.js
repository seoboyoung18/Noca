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

/**
 * 닉네임 수정. 갱신된 프로필(MemberProfileResponse)을 돌려준다.
 * 서버 규칙: 앞뒤 공백 제거 후 2~12자, 금칙어 불가 — 위반 시 400 INVALID_REQUEST 와 구체적 메시지. 중복 검사는 없다.
 */
export const updateNickname = (nickname) => http.patch('/api/members/me', { nickname }).then(data)

/* ===== 프로필 이미지 — presigned 직접 업로드 3단계 (MemberController · ProfileImageService) =====
 *   ① POST /api/members/me/profile-image/upload-url { contentType, size } → { uploadKey, url, expiresAt, requiredHeaders }
 *   ② PUT  {url}  브라우저 → S3 직접. requiredHeaders 를 그대로 싣고, 신고한 size 와 같은 바이트를 올린다 (다르면 S3 403)
 *   ③ PUT  /api/members/me/profile-image { uploadKey } → 갱신된 프로필(profileImageUrl 은 10분짜리 presigned GET)
 * 허용 형식 image/jpeg · image/png · image/heic, 최대 5MB, URL 유효 10분. 저장소 미구성 서버는 503 SERVICE_UNAVAILABLE.
 */
export const PROFILE_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/heic']
export const PROFILE_IMAGE_MAX_BYTES = 5 * 1024 * 1024 // app.profile-image.max-file-size-bytes 와 동일. 최종 판정은 서버

export const issueProfileImageUploadUrl = (contentType, size) =>
  http.post('/api/members/me/profile-image/upload-url', { contentType, size }).then(data)

/**
 * ② S3 presigned PUT. 세션 쿠키를 보내지 않는 별도 요청이라 axios 인스턴스를 쓰지 않는다.
 * requiredHeaders 는 키 이름을 하드코딩하지 않고 그대로 펼친다 (사고 이미지 API 와 대소문자가 다름).
 * onProgress(0~100) 로 진행률을 준다 — 서버는 전송 중인 바이트를 보지 못하므로 진행률은 FE 몫.
 */
export function uploadToPresignedUrl(url, file, requiredHeaders = {}, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    for (const [k, v] of Object.entries(requiredHeaders)) {
      if (k.toLowerCase() === 'content-length') continue // 브라우저가 body 에서 계산. 직접 설정하면 거부한다
      xhr.setRequestHeader(k, v)
    }
    xhr.upload.onprogress = (e) => { if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100)) }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new ApiError(xhr.status, 'UPLOAD_FAILED', xhr.status === 403 ? '업로드가 거절되었어요. 파일을 다시 선택해 주세요.' : '파일 업로드에 실패했어요.'))
    }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK_ERROR', '파일을 업로드하지 못했어요. 네트워크를 확인해 주세요.'))
    xhr.send(file)
  })
}

export const completeProfileImage = (uploadKey) => http.put('/api/members/me/profile-image', { uploadKey }).then(data)

/** 이미지 삭제(기본 이미지로). 204 가 아니라 갱신된 프로필을 돌려준다 */
export const deleteProfileImage = () => http.delete('/api/members/me/profile-image').then(data)

/**
 * 회원 탈퇴. 204, 본문 없음.
 * 서버가 개인식별정보를 지우는 소프트 삭제를 하고 같은 요청에서 세션을 끊어 SESSION 쿠키를 삭제한다.
 * 소셜 연결 해제(카카오 unlink·구글 revoke)는 서버에 아직 없다(S15P21A307-102).
 */
export const withdraw = () => http.delete('/api/members/me')
