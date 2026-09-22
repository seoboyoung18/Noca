<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import AccidentCard from '../components/AccidentCard.vue'
import Toast from '../components/Toast.vue'
import { useAccidentStore } from '../stores/accidents'
import { useVehicleStore } from '../stores/vehicles'
import { AUTH_GUARD_OFF } from '../router'
import {
  ACCIDENT_IMAGE_MAX_BYTES, completeAccidentImages, createAccident, deleteAccidentImage,
  fetchAccident, fetchAccidentImages, issueAccidentImageUploadUrls, uploadToPresignedUrl,
} from '../lib/api'
import { RETRY_SAME_IMAGE, imageFailText, imageThumb, qualityWarnText, validateAccidentImage } from '../data/accidents'

/* ===== 사진 업로드 (S06) — 목업 "NOCA 목업_촬영 가이드 수정" S06_사진업로드 기준, 한 장 체계 =====
 * 진입 경로 둘
 *  - 새 접수: 차량 선택 → 촬영 가이드 → 여기. 사고가 없으므로 첫 사진을 고르는 순간 POST /api/accidents 로 만든다
 *  - 이어서 진행: 사고 이력에서 ?accidentId= 로. 서버에 사진이 있으면 최신 1장을 미리보기로 보이고, 다시 선택하면 기존 사진을 지운다
 * 업로드는 3단계(발급 → S3 PUT → 완료 통보). 진행률·대기·실패 상태는 서버가 저장하지 않으므로 FE 상태다.
 * 저장소 미구성(503)은 장애가 아니라 "준비 중" 으로 그린다 — 버킷이 들어오면 계약 변경 없이 그 분기만 지나가지 않는다.
 * (Docs/Handover/이미지 업로드 API — FE 인수인계.md §0·§1·§2-3·§3·§4)
 */
const route = useRoute()
const router = useRouter()
const accidents = useAccidentStore()
const vs = useVehicleStore()

const accidentId = ref(Number(route.query.accidentId) || null)
const fromHistory = !!accidentId.value // 진입 시점 기준 — 새 접수 중 사고가 생겨도 뒤로가기는 가이드로

/* ----- 미리보기 카드 ----- */
const accident = ref(accidents.items.find((a) => a.accidentId === accidentId.value) || null)
const accidentLoading = ref(false)
const fatal = ref('')
const card = computed(() => (accidentId.value && accident.value
  ? { vehicle: accident.value, createdAt: accident.value.createdAt || '', status: accident.value.status || '', thumbnailUrl: accident.value.thumbnailUrl || '' }
  : { vehicle: vs.selected, caption: '접수 전' }))

async function loadAccident() {
  if (accident.value) return
  accidentLoading.value = true
  try { accident.value = await fetchAccident(accidentId.value) }
  catch (e) { if (e.status === 404) fatal.value = '사고를 찾을 수 없어요.' }
  finally { accidentLoading.value = false }
}

/* ----- 사진 한 장의 상태 (FE 몫: 대기·진행·실패 / 서버: PENDING·COMPLETED) ----- */
const photo = reactive({
  state: 'empty', // empty | issuing | uploading | completing | done | error
  file: null, contentType: '', previewUrl: '', serverUrl: '', percent: 0,
  imageId: null, uploadUrl: '', uploadMethod: 'PUT', requiredHeaders: null, expiresAt: null,
  error: '', code: '', retrySame: false,
})
const existingIds = ref([]) // 서버에 있는 이 사고의 이미지 id — 한 장 체계라 새로 올릴 때 지운다
const storageDown = ref(false)
const busy = computed(() => ['issuing', 'uploading', 'completing'].includes(photo.state))
const canAnalyze = computed(() => photo.state === 'done' && !!accidentId.value)
const previewSrc = computed(() => photo.previewUrl || photo.serverUrl) // 방금 고른 파일은 로컬 미리보기가 빠르다(§5-2)
const retryLabel = computed(() => (photo.retrySame || photo.code === 'NETWORK' || photo.code === 'STORAGE' ? '다시 시도' : '다른 사진 선택'))

const toast = ref('')
let tt
function showToast(msg, ms = 2000) { toast.value = msg; clearTimeout(tt); tt = setTimeout(() => { toast.value = '' }, ms) }
function revokePreview() { if (photo.previewUrl) { URL.revokeObjectURL(photo.previewUrl); photo.previewUrl = '' } }

/* 이어서 진행: 서버 사진 복구 — 이 API 는 저장소 없이도 200 (url 만 null) */
async function loadExisting() {
  try {
    const list = await fetchAccidentImages(accidentId.value)
    const imgs = list?.images || []
    existingIds.value = imgs.map((i) => i.imageId)
    const done = imgs.filter((i) => i.uploadState === 'COMPLETED')
    const latest = done[done.length - 1]
    if (latest) { photo.imageId = latest.imageId; photo.serverUrl = imageThumb(latest, 'RESIZED'); photo.state = 'done' }
  } catch (e) {
    if (e.status !== 401) showToast(e.status === 0 ? e.message : '올린 사진을 불러오지 못했어요.')
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', onBeforeUnload)
  if (!accidentId.value) { vs.loadVehicles().catch(() => {}); return }
  await loadAccident()
  if (!fatal.value) await loadExisting()
})
onBeforeUnmount(() => { window.removeEventListener('beforeunload', onBeforeUnload); revokePreview() })

/* 업로드 중 이탈 경고 — 서버가 관여하지 않는 FE 책임(§4-3) */
function onBeforeUnload(e) { if (busy.value) { e.preventDefault(); e.returnValue = '' } }
onBeforeRouteLeave(() => (busy.value ? window.confirm('사진을 올리는 중이에요. 지금 나가면 업로드가 중단돼요.') : true))

/* ----- 파일 선택 → 검증 → 3단계 업로드 ----- */
const input = ref(null)
function pick() { if (!busy.value) input.value?.click() }

async function onFile(e) {
  const file = e.target.files?.[0]
  e.target.value = '' // 같은 파일을 다시 골라도 change 가 나게
  if (!file) return
  const v = validateAccidentImage(file, ACCIDENT_IMAGE_MAX_BYTES)
  if (!v.ok) {
    // 이미 올린 사진이 있으면 그대로 두고 문구만 — 잘못 고른 파일 때문에 완료본을 잃지 않게
    if (photo.state === 'done') return showToast(imageFailText(v.code), 2800)
    return setError(v.code, false)
  }
  revokePreview()
  Object.assign(photo, { file, contentType: v.contentType, previewUrl: URL.createObjectURL(file), error: '', code: '', percent: 0 })
  await upload()
}

/** 새 접수면 첫 사진을 고르는 순간 사고를 만든다. 쿼리에 accidentId 를 실어 새로고침해도 이어서 진행되게 한다 */
async function ensureAccident() {
  if (accidentId.value) return true
  const vehicleId = vs.selectedVehicleId || vs.selected?.vehicleId
  if (!vehicleId) { setError('NO_VEHICLE', false, '차량을 먼저 선택해 주세요.'); return false }
  if (AUTH_GUARD_OFF) { accidentId.value = 9999; return true }
  const a = await createAccident(vehicleId)
  accidentId.value = a.accidentId
  accident.value = a
  accidents.reset() // 사고 이력 캐시 무효화 — 다음 진입 때 새 사고가 보이게
  router.replace({ path: route.path, query: { ...route.query, accidentId: a.accidentId } })
  return true
}

/** 한 장 체계 — 기존 서버 사진은 지우고 새로 올린다(재촬영 와이어프레임 "기존 사진은 삭제됩니다"). 이미 지워진 건(404)은 넘어간다 */
async function removeExisting() {
  for (const id of existingIds.value) {
    try { await deleteAccidentImage(accidentId.value, id) }
    catch (e) { if (e.status !== 404) throw e }
  }
  existingIds.value = []
  photo.serverUrl = ''
}

async function upload() {
  try {
    photo.state = 'issuing'
    photo.percent = 0
    if (!(await ensureAccident())) return
    if (AUTH_GUARD_OFF) return mockUpload()
    await removeExisting()
    // ① 발급 — 한 파일이라도 걸리면 전체 400 이고 행도 생기지 않는다. 각도는 1컷 가이드의 DAMAGE_CLOSE
    const issued = await issueAccidentImageUploadUrls(accidentId.value, [{
      originalFilename: photo.file.name, contentType: photo.contentType, size: photo.file.size, angleCode: 'DAMAGE_CLOSE',
    }])
    const f = issued.files[0]
    Object.assign(photo, { imageId: f.imageId, uploadUrl: f.uploadUrl, uploadMethod: f.uploadMethod || 'PUT', requiredHeaders: f.requiredHeaders || {}, expiresAt: f.expiresAt })
    existingIds.value = [f.imageId]
    await putAndComplete()
  } catch (e) { handleError(e) }
}

/** ② S3 PUT → ③ 완료 통보. 재시도는 같은 imageId·같은 URL 로 여기부터 다시(§4-1) */
async function putAndComplete() {
  photo.state = 'uploading'
  try { await uploadToPresignedUrl(photo.uploadUrl, photo.file, photo.requiredHeaders, (p) => { photo.percent = p }) }
  catch (e) { return setError('PUT_FAILED', true, e.message) }
  photo.state = 'completing'
  const res = await completeAccidentImages(accidentId.value, [{ imageId: photo.imageId, size: photo.file.size }])
  const r = (res.results || []).find((x) => x.imageId === photo.imageId) || res.results?.[0]
  if (!r || r.status === 'FAILED') {
    const code = r?.failureCode || 'PROCESSING_ERROR'
    return setError(code, RETRY_SAME_IMAGE.has(code), r?.failureMessage)
  }
  photo.state = 'done'
  photo.serverUrl = imageThumb(r, 'RESIZED') || ''
  accidents.reset() // 목록 status 가 IMAGES_UPLOADED 로 바뀐다
  // 품질 판정은 서버 설정이 꺼져 있어 지금은 항상 PASS. WARN 이 오면 재촬영 권유는 FE 몫(§5-3) — 서버 사유를 사용자 문구로 바꿔 토스트로.
  // 업로드는 성공이므로 분석 요청은 막지 않는다
  if (r.qualityStatus === 'WARN') showToast(qualityWarnText(r.qualityReason), 3500)
}

function setError(code, retrySame, serverMessage) {
  photo.state = 'error'
  photo.code = code
  photo.retrySame = retrySame
  photo.error = code === 'PUT_FAILED' ? (serverMessage || '사진을 전송하지 못했어요. 네트워크를 확인해 주세요.')
    : code === 'NO_VEHICLE' ? serverMessage
    : imageFailText(code, serverMessage)
}

function handleError(e) {
  if (e.status === 401) return // main.js 가 로그인으로 보낸다
  if (e.status === 503) { storageDown.value = true; photo.state = 'error'; photo.code = 'STORAGE'; photo.retrySame = false; photo.error = '이미지 저장소가 준비 중이에요. 잠시 후 다시 시도해 주세요.'; return }
  if (e.status === 404) { fatal.value = '사고를 찾을 수 없어요.'; return }
  if (e.status === 400) return setError(e.code, false, e.message) // 발급 400 — error.code 가 사유(§2-3)
  setError('NETWORK', true, e.status === 0 ? e.message : '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.')
}

async function retry() {
  if (!photo.file) return pick()
  const expired = photo.expiresAt && Date.parse(photo.expiresAt) - Date.now() < 30_000 // 만료 직전이면 재발급
  if (photo.retrySame && photo.imageId && photo.uploadUrl && !expired) {
    try { await putAndComplete() } catch (e) { handleError(e) }
    return
  }
  if (!photo.retrySame && photo.code !== 'NETWORK' && photo.code !== 'STORAGE') return pick() // 파일 자체 문제
  await upload() // 발급 전 실패·URL 만료 → 기존 imageId 지우고 새로 발급
}

function requestAnalysis() { router.push({ path: '/claim/analyzing', query: { accidentId: accidentId.value } }) }

/* 가드를 끈 화면 확인 모드 — 서버 없이 진행 표시만 흘려 본다 */
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
async function mockUpload() {
  photo.imageId = 1
  photo.state = 'uploading'
  for (let p = 0; p <= 100; p += 20) { await wait(120); photo.percent = p }
  photo.state = 'completing'
  await wait(300)
  photo.state = 'done'
}
</script>

<template>
  <Screen>
    <AppHeader title="사진 업로드" :back="fromHistory ? '/history' : '/claim/guide'" :back-history="fromHistory" />
    <div class="prog"><i style="width:75%"></i></div>

    <!-- 사고를 찾을 수 없음 -->
    <div v-if="fatal" class="body col">
      <div class="empty">
        <b>{{ fatal }}</b>
        <p>삭제되었거나 접근할 수 없는 사고예요</p>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="router.replace('/history')">사고 이력으로</button>
      </div>
    </div>

    <div v-else class="body scroll" style="padding-top:20px">
      <p class="step">3 / 4 · 사진 업로드</p>
      <h1 class="h1" style="margin-top:6px">파손 부위 사진을<br>올려주세요</h1>

      <AccidentCard style="margin-top:14px" :vehicle="card.vehicle" :created-at="card.createdAt" :status="card.status"
        :thumbnail-url="card.thumbnailUrl" :caption="card.caption" :loading="accidentLoading" />

      <!-- 사진 자리: 비어 있으면 선택, 있으면 미리보기 + 상태 -->
      <button v-if="photo.state === 'empty'" class="drop" @click="pick">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3" y="6" width="18" height="14" rx="2.5" stroke="#8B95A1" stroke-width="1.7"/><path d="M8.5 6l1.2-2h4.6l1.2 2" stroke="#8B95A1" stroke-width="1.7" stroke-linejoin="round"/><circle cx="12" cy="13" r="3.6" stroke="#8B95A1" stroke-width="1.7"/></svg>
        <b>사진 선택</b>
        <small>JPG · PNG, 20MB 이하</small>
      </button>
      <div v-else class="pv" :class="{ err: photo.state === 'error' }">
        <img v-if="previewSrc" :src="previewSrc" alt="업로드한 파손 부위 사진">
        <span v-else class="noimg">미리보기 없음</span>
        <span v-if="photo.state === 'done'" class="ok" aria-label="업로드 완료">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
        <span v-else-if="photo.state === 'error'" class="ok bad" aria-label="업로드 실패">
          <svg width="14" height="14" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 5.8v5" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round"/><circle cx="10" cy="13.8" r="1.1" fill="#FFFFFF"/></svg>
        </span>
        <div v-if="busy" class="veil" role="status" aria-live="polite">
          <span class="pct">{{ photo.state === 'uploading' ? photo.percent + '%' : photo.state === 'completing' ? '확인 중…' : '준비 중…' }}</span>
          <span class="pbar"><i :style="{ width: photo.state === 'uploading' ? photo.percent + '%' : photo.state === 'completing' ? '100%' : '0%' }"></i></span>
        </div>
        <button v-if="!busy" class="re" @click="pick">
          <svg width="14" height="14" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M16 9a6 6 0 0 0-10.5-3.2M4 11a6 6 0 0 0 10.5 3.2" stroke="#FFFFFF" stroke-width="1.6" stroke-linecap="round"/><path d="M15.5 3.5v3h-3M4.5 16.5v-3h3" stroke="#FFFFFF" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          다시 선택
        </button>
      </div>

      <!-- 실패 사유 + 재시도 -->
      <p v-if="photo.state === 'error'" class="errtxt" role="alert">
        {{ photo.error }}
        <button class="link" @click="retry">{{ retryLabel }}</button>
      </p>
      <p v-else-if="fromHistory && photo.state === 'done' && !photo.file" class="sub" style="margin-top:10px;font-size:12px">다시 선택하면 기존 사진은 삭제돼요</p>

      <!-- 안내 -->
      <div class="info">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 16px;margin-top:2px"><circle cx="8" cy="8" r="5.6" stroke="#4E5968" stroke-width="1.3"/><path d="M8 7.2v3.4M8 5.2h.01" stroke="#4E5968" stroke-width="1.4" stroke-linecap="round"/></svg>
        <span>파손 부위가 선명하게 보이는지 확인해주세요. 흐리거나 어두운 사진은 분석 정확도가 떨어져요.</span>
      </div>
      <div style="margin-top:12px">
        <button class="link" @click="router.push('/claim/guide')">촬영 가이드 다시 보기</button>
      </div>
      <div style="height:16px"></div>
    </div>

    <div v-if="!fatal" class="foot">
      <button class="btn" :disabled="!canAnalyze" @click="requestAnalysis">분석 요청</button>
    </div>

    <input ref="input" type="file" accept="image/jpeg,image/png" hidden @change="onFile">
    <Toast :show="!!toast">{{ toast }}</Toast>
  </Screen>
</template>

<style scoped>
.drop { margin-top: 20px; width: 100%; aspect-ratio: 4 / 3; border-radius: 16px; border: 1.5px dashed var(--line-2); background: var(--bg-2); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; }
.drop:hover { background: var(--bg); }
.drop b { margin-top: 4px; font-size: 15px; font-weight: 600; color: var(--text-2); }
.drop small { font-size: 12px; color: var(--text-3); }
.pv { position: relative; margin-top: 20px; width: 100%; aspect-ratio: 4 / 3; border-radius: 16px; overflow: hidden; background: var(--bg-2); display: flex; align-items: center; justify-content: center; }
.pv.err { outline: 1.5px solid var(--danger); }
.pv img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.noimg { font-size: 13px; color: var(--text-3); }
.ok { position: absolute; right: 10px; top: 10px; width: 26px; height: 26px; border-radius: 13px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.ok.bad { background: var(--danger); }
.re { position: absolute; right: 10px; bottom: 10px; height: 32px; padding: 0 12px; border-radius: 8px; background: rgba(25,31,40,.7); color: #fff; font-size: 12px; font-weight: 500; display: flex; align-items: center; gap: 6px; }
.veil { position: absolute; left: 0; right: 0; bottom: 0; padding: 10px 12px 12px; background: linear-gradient(to top, rgba(25,31,40,.75), rgba(25,31,40,0)); color: #fff; display: flex; flex-direction: column; gap: 6px; }
.pct { font-size: 12px; font-weight: 600; }
.pbar { display: block; height: 4px; border-radius: 2px; background: rgba(255,255,255,.35); overflow: hidden; }
.pbar i { display: block; height: 100%; background: #fff; border-radius: 2px; transition: width .2s; }
.errtxt { margin: 10px 0 0; font-size: 13px; line-height: 1.5; color: var(--danger-2); display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: center; }
.info { margin-top: 12px; padding: 12px 14px; background: var(--bg-2); border-radius: 8px; display: flex; gap: 8px; font-size: 12px; line-height: 1.55; color: var(--text-2); }
</style>
