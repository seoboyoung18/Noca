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

/* ===== 차량 (VehicleController · VehicleModelController) — 전부 로그인 필요 =====
 * 응답 모양은 등록·수정·목록이 같다: { vehicleId, modelId, manufacturer, modelName, vehicleType, carClass, modelYear }
 * 제조사·유형·차급은 모델을 고르면 결정되므로 등록 본문은 { modelId, modelYear } 둘뿐이다.
 */

/** 활성 모델 51종 전체 (필터·페이지 없음, 약 5KB). 순서는 의미 없음 → FE 가 정렬 */
export const fetchVehicleModels = () => http.get('/api/vehicle-models').then((r) => r.data.data.vehicleModels || [])

/** 내 차량 목록. created_at DESC. 비어 있으면 [] (404 아님) */
export const fetchMyVehicles = () => http.get('/api/vehicles/me').then((r) => r.data.data.vehicles || [])

/** 차량 등록. 201 + 등록된 차량 (재조회 불필요). modelYear 1980~2100 */
export const createVehicle = (modelId, modelYear) => http.post('/api/vehicles', { modelId, modelYear }).then(data)

/** 연식 수정. 연식만 바꿀 수 있다 — modelId 를 보내면 400. 모델을 바꾸려면 삭제 후 재등록 */
export const updateVehicleYear = (vehicleId, modelYear) => http.patch(`/api/vehicles/${vehicleId}`, { modelYear }).then(data)

/** 차량 삭제. 204 · 소프트 삭제 · 멱등(이미 지운 차량도 204). 사고 이력은 남는다 */
export const deleteVehicle = (vehicleId) => http.delete(`/api/vehicles/${vehicleId}`)

/* ===== AI 분석 (AnalysisRequestController · AnalysisProgressController) =====
 * 사고 기준 경로. 서버는 퍼센트·남은 시간을 주지 않고 단계 수만 준다 — 화면은 doneStages/totalStages(항상 4)로 그린다.
 */

/** 분석 요청. 접수만 하고 202, 응답은 진행 상태와 같은 모양. 400(보낼 사진 없음)·404·409(이미 진행 중)·503(AI 설정 없음) */
export const requestAnalysis = (accidentId) => http.post(`/api/accidents/${accidentId}/analysis`).then(data)

/**
 * 분석 진행 상태. 미요청 사고는 200 + status null(빈 상태).
 * { jobId, status: QUEUED|PROCESSING|COMPLETED|FAILED|null, failureReason, startedAt, finishedAt,
 *   totalStages(4), doneStages, currentStage: PREPROCESS|DETECT|MATCH|ESTIMATE|null, stages[], excludedImages[] }
 */
export const fetchAnalysisProgress = (accidentId) => http.get(`/api/accidents/${accidentId}/analysis`).then(data)

/**
 * 분석 결과 (AnalysisResultController). 진행 상태와 같은 사고 기준 경로. 분석 전 사고는 빈 상태 200(status null).
 * { jobId, status, failureReason,
 *   parts[{ partCode, partNameKo, layoutZone, damageType, repairMethod, repairMethodDisplayName, confidence }],   // display_order 순
 *   images[{ imageId, angleCode, width, height(원본 픽셀), excluded, exclusionReason, detections[] }] }
 * detections 는 AI 원문 그대로 — geometry.bbox 는 XYWH, 원본 픽셀·좌상단 원점. 화면이 RESIZED 를 띄우면 width/height 로 비율 환산.
 * 사진 URL 은 주지 않는다 — GET .../images 의 RESIZED 를 imageId 로 맞춘다.
 */
export const fetchAnalysisResult = (accidentId) => http.get(`/api/accidents/${accidentId}/analysis/result`).then(data)

/* ===== 견적 · 견적 PDF (EstimateController · EstimatePdfController) ===== */

/**
 * 견적 상세. 생성 API 는 없고 AI 콜백이 결과와 함께 견적 버전을 쌓는다.
 * { estimateId, jobId, version, estimable, nonEstimableReason, laborRate, totalHq, totalMin, totalMedian, totalMax,
 *   refCaseTotal, confidenceGrade: HIGH|MEDIUM|LOW|null,
 *   items[{ estimateItemId, partCode, partNameKo, layoutZone, damageType, repairMethod, repairMethodDisplayName,
 *           standardHq, partCostMedian(원천에 없어 null), laborCostMedian, itemMin, itemMedian, itemMax, refCaseCount, lowConfidence }],
 *   notices[{ code, message }](문구 테이블 전이라 지금은 항상 []), createdAt }
 * estimable=false 면 금액이 전부 null 이고 nonEstimableReason 만 있다. 남의 견적은 404.
 */
export const fetchEstimate = (estimateId) => http.get(`/api/estimates/${estimateId}`).then(data)

/**
 * 사고 분석 견적 리포트 (EstimateReportController). 견적 기준 — PDF(/api/estimates/{id}/pdf)와 1:1. 동기 GET, 저장하지 않고 매번 조립.
 * { vehicle{ manufacturer, modelName, vehicleType, carClass, modelYear }(접수 당시 스냅샷), accident{ accidentId, createdAt },
 *   images[{ imageId, angleCode, overlayUrl(없으면 null → "분석 이미지 없음") }](제외 사진은 없음),
 *   estimate(EstimateResponse 그대로), basis(EstimateBasisResponse — items[].narrative·fallbackStage·costDistribution·refYearFrom/To),
 *   validation(견적서 검증 결과, 없으면 null → 섹션 생략), legalNotice(필수 고지), guidanceNotice(null 가능), generatedAt }
 * 남의 견적·없는 견적은 404.
 */
export const fetchEstimateReport = (estimateId) => http.get(`/api/estimates/${estimateId}/report`).then(data)

/** 사고의 견적 목록. 기본은 최신 1건만(latest=true). data 가 배열이며 견적이 없으면 [] — 첫 원소의 estimateId 가 PDF 입구 */
export const fetchAccidentEstimates = (accidentId, latest = true) =>
  http.get(`/api/accidents/${accidentId}/estimates`, { params: { latest } }).then((r) => r.data.data || [])

/**
 * PDF 생성 요청. 워커가 만들므로 202 로 접수만 되고 응답은 상태 조회와 같은 모양이다.
 * 409 = 이미 생성 중(정상 흐름으로 보고 상태 조회로 이어 간다) · 404 = 내 견적이 아님.
 * { reportNo, status: QUEUED|PROCESSING|COMPLETED|FAILED, retryCount(최대 3), failureReason(사용자용 문구), createdAt, completedAt }
 */
export const requestEstimatePdf = (estimateId) => http.post(`/api/estimates/${estimateId}/pdf`).then(data)

/** 가장 최근 PDF 요청 상태. 요청한 적이 없으면 200 + data null (오류 아님) */
export const fetchEstimatePdfStatus = (estimateId) => http.get(`/api/estimates/${estimateId}/pdf`).then(data)

/**
 * PDF 다운로드 진입 URL. 서버가 302 로 S3 서명 URL(5분 · attachment · 파일명 예상견적_{reportNo}.pdf)로 보낸다.
 * XHR 로 부르면 안 된다 — 리다이렉트를 따라간 S3 응답에 CORS 헤더가 없어 실패한다. 브라우저 이동으로만 쓴다.
 * 완료본이 없으면 409 · 보관소 미구성이면 503 이라, 상태가 COMPLETED 인 것을 확인한 뒤에만 이동한다.
 */
export const estimatePdfDownloadUrl = (estimateId) => `${API_BASE}/api/estimates/${estimateId}/pdf/download`

/* ===== 사고 · 정비 체크리스트 (AccidentController · RepairChecklistController) ===== */

/** 내 사고 이력. createdAt 내림차순. { accidents[], page, size, totalElements, totalPages, hasNext }. size 상한 100 */
export const fetchMyAccidents = (page = 0, size = 100) =>
  http.get('/api/accidents/me', { params: { page, size } }).then(data)

/**
 * 사고 상세. 목록 항목의 공통 10필드와 같은 모양이고 목록 전용 8필드(status·썸네일·견적)는 없다.
 * 차량 필드는 접수 당시 스냅샷. 없는 사고·남의 사고 모두 404 (403 은 존재 사실을 새기므로 쓰지 않음)
 */
export const fetchAccident = (accidentId) => http.get(`/api/accidents/${accidentId}`).then(data)

/**
 * 사고 이미지 목록 (AccidentImageController). 화면을 새로 열어도 무엇이 올라갔는지 알 수 있게 한다.
 * { total, completed, pending, maxCountPerAccident(20), remainingSlots,
 *   images[{ imageId, originalFilename, angleCode(null 가능), uploadState: PENDING|COMPLETED,
 *            qualityStatus: PASS|WARN, qualityReason, createdAt,
 *            assets[{ variant: RESIZED|THUMBNAIL, url, expiresAt, width, height, fileSize }] }] }
 * assets.url 은 조회용 서명 GET — 저장소 미구성·서명 실패면 null 이고 메타만 온다. 원본(ORIGINAL)은 EXIF 때문에 절대 오지 않는다.
 */
export const fetchAccidentImages = (accidentId) => http.get(`/api/accidents/${accidentId}/images`).then(data)

/**
 * 사고 접수. 등록 차량이면 { vehicleId }, 즉시 입력이면 { directVehicle } — 정확히 하나만. 201 + 사고 상세(공통 10필드).
 * 즉시 입력은 영구 차량을 만들어 중복 차량이 생기므로 FE 는 등록 차량 선택만 쓴다(§5-3·§15).
 */
export const createAccident = (vehicleId) => http.post('/api/accidents', { vehicleId }).then(data)

/* ===== 사고 이미지 업로드 — presigned 직접 업로드 3단계 (AccidentImageController) =====
 *   ① POST .../images/upload-urls { files:[{ originalFilename, contentType, size, angleCode? }] }
 *        → 201 { issuedCount, maxCountPerAccident, remainingSlots, files:[{ imageId, originalFilename, angleCode, s3Key, uploadUrl, uploadMethod, requiredHeaders, expiresAt }] }
 *        한 파일이라도 걸리면 전체 400 (행도 생기지 않음). 400 의 error.code 가 사유(FILE_TOO_LARGE·SERVER_CONVERSION_UNSUPPORTED …)
 *   ② PUT {uploadUrl}  브라우저 → S3 직접 (uploadToPresignedUrl 재사용 — requiredHeaders 그대로, 파일 가공 금지)
 *   ③ POST .../images { images:[{ imageId, size? }] }
 *        → 200 { requested, succeeded, failed, results:[{ imageId, status: COMPLETED|ALREADY_COMPLETED|FAILED, failureCode, failureMessage, qualityStatus, qualityReason, assets[] }] }
 *        부분 실패도 200 — results 를 장별로 판정한다. size 를 보내면 실제 크기와 대조(권장)
 * 제약: JPG·PNG(HEIC 는 서버 거절), 장당 20MB, 사고당 maxCountPerAccident(응답값 사용). 저장소 미구성이면 ①③④ 가 503.
 * 재시도는 같은 imageId·같은 uploadUrl 로 — 재발급하면 슬롯을 하나 더 먹는다.
 */
export const ACCIDENT_IMAGE_TYPES = ['image/jpeg', 'image/png']
export const ACCIDENT_IMAGE_MAX_BYTES = 20 * 1024 * 1024 // app.accident-image.max-file-size-bytes. 최종 판정은 서버

export const issueAccidentImageUploadUrls = (accidentId, files) =>
  http.post(`/api/accidents/${accidentId}/images/upload-urls`, { files }).then(data)

export const completeAccidentImages = (accidentId, images) =>
  http.post(`/api/accidents/${accidentId}/images`, { images }).then(data)

/** 이미지 1장 삭제. 204. 저장소 오브젝트까지 정리하므로 저장소 미구성이면 503 */
export const deleteAccidentImage = (accidentId, imageId) => http.delete(`/api/accidents/${accidentId}/images/${imageId}`)

/**
 * 사고별 정비 체크리스트 상태. 아직 요청하지 않은 사고는 200 + status null.
 * { checklistId, status: QUEUED|PROCESSING|COMPLETED|FAILED|null, failureReason, items[], notice, ... }
 */
export const fetchRepairChecklistStatus = (accidentId) =>
  http.get(`/api/accidents/${accidentId}/repair-checklist`).then(data)

/**
 * 회원 탈퇴. 204, 본문 없음.
 * 서버가 개인식별정보를 지우는 소프트 삭제를 하고 같은 요청에서 세션을 끊어 SESSION 쿠키를 삭제한다.
 * 소셜 연결 해제(카카오 unlink·구글 revoke)는 서버에 아직 없다(S15P21A307-102).
 */
export const withdraw = () => http.delete('/api/members/me')
