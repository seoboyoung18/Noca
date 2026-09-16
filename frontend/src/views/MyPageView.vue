<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import Avatar from '../components/Avatar.vue'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { useVehicleStore } from '../stores/vehicles'
import { useAccidentStore } from '../stores/accidents'
import {
  PROFILE_IMAGE_MAX_BYTES, PROFILE_IMAGE_TYPES, completeProfileImage, deleteProfileImage,
  fetchMyAccidents, fetchRepairChecklistStatus, issueProfileImageUploadUrl, updateNickname, uploadToPresignedUrl,
} from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'

const router = useRouter()
const store = useAppStore()
const auth = useAuthStore()
const sh = reactive({ avatar: false, logout: false, notice: false, nick: false })

/* ===== 통계 카드 — 서버 값과 동기화 =====
 * 내 차량·사고 이력: GET /api/members/me 의 vehicleCount·accidentCount
 * 체크리스트: 건수 API 가 없어 사고 목록(GET /api/accidents/me)을 받아 사고별 체크리스트 상태를 조회하고
 *             COMPLETED 인 건수를 센다. 사고 수만큼 요청이 나가므로 동시 5건으로 제한한다.
 * null 은 아직 모름(로딩 중·조회 실패) → '–' 로 표시
 */
const counts = reactive({ vehicles: null, accidents: null, checklists: null })
const fmt = (n) => (n === null || n === undefined ? '–' : n)
const CHECKLIST_SCAN_MAX = 300 // 사고가 이보다 많으면 최근 300건까지만 센다 (요청 폭주 방지)

async function countCompletedChecklists() {
  const ids = []
  for (let page = 0; ids.length < CHECKLIST_SCAN_MAX; page++) {
    const res = await fetchMyAccidents(page, 100)
    ids.push(...(res.accidents || []).map((a) => a.accidentId))
    if (!res.hasNext) break
  }
  let done = 0
  let i = 0
  const worker = async () => {
    while (i < ids.length) {
      const id = ids[i++]
      try { if ((await fetchRepairChecklistStatus(id))?.status === 'COMPLETED') done++ } catch (e) { /* 개별 실패는 0으로 친다 */ }
    }
  }
  await Promise.all(Array.from({ length: Math.min(5, ids.length) }, worker))
  return done
}

async function loadStats() {
  if (AUTH_GUARD_OFF) { // 백엔드 없는 화면 확인 모드 — 기존 목업 수치
    counts.vehicles = store.vehicles.length; counts.accidents = store.history.length; counts.checklists = store.checklists.length
    return
  }
  // 프로필 이미지 URL(10분 presigned)도 여기서 함께 갱신된다
  const profile = await auth.loadProfile()
  if (profile) { counts.vehicles = profile.vehicleCount ?? null; counts.accidents = profile.accidentCount ?? null }
  try { counts.checklists = await countCompletedChecklists() } catch (e) { counts.checklists = null }
}

onMounted(loadStats)

/* ===== 프로필 이미지 — 발급(①) → S3 PUT(②) → 완료 통보(③) ===== */
const albumInput = ref(null)
// idle | issuing | uploading | completing | deleting
const imgStep = ref('idle')
const imgPercent = ref(0)
const imgError = ref('')
const imgBusy = computed(() => imgStep.value !== 'idle')
const imgStatus = computed(() => ({
  issuing: '업로드 준비 중…', uploading: `업로드 중 ${imgPercent.value}%`, completing: '적용 중…', deleting: '삭제 중…',
})[imgStep.value] || '')
const EXT_TYPE = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', heic: 'image/heic' }

function openAvatarSheet() { imgError.value = ''; sh.avatar = true }
function pickAlbum() { if (!imgBusy.value) albumInput.value?.click() }

// 브라우저가 HEIC 등의 type 을 비워 보내는 경우가 있어 확장자로 보완한다
function fileType(file) {
  if (file.type) return file.type.toLowerCase()
  const ext = (file.name.split('.').pop() || '').toLowerCase()
  return EXT_TYPE[ext] || ''
}

async function onFilePicked(e) {
  const file = e.target.files?.[0]
  e.target.value = '' // 같은 파일을 다시 골라도 change 가 나게
  if (!file) return
  imgError.value = ''

  if (AUTH_GUARD_OFF) { store.hasAvatar = true; sh.avatar = false; return } // 백엔드 없는 화면 확인 모드

  // 서버와 같은 기준으로 먼저 거른다 — 5MB 를 다 올린 뒤 거절당하지 않게. 최종 판정은 서버
  const type = fileType(file)
  if (!PROFILE_IMAGE_TYPES.includes(type)) { imgError.value = 'JPG, PNG, HEIC 형식만 올릴 수 있어요.'; return }
  if (file.size > PROFILE_IMAGE_MAX_BYTES) { imgError.value = `프로필 이미지는 ${PROFILE_IMAGE_MAX_BYTES / 1024 / 1024}MB 이하여야 해요.`; return }

  try {
    imgStep.value = 'issuing'
    // ① 신고한 size 와 실제 바이트가 같아야 한다 — 파일을 그대로 올리므로 file.size 를 쓴다
    const issued = await issueProfileImageUploadUrl(type, file.size)
    imgStep.value = 'uploading'
    imgPercent.value = 0
    // ② 브라우저 → S3 직접
    await uploadToPresignedUrl(issued.url, file, issued.requiredHeaders, (p) => { imgPercent.value = p })
    imgStep.value = 'completing'
    // ③ 서버가 staging 객체를 검증·변환해 서비스 버킷으로 옮기고 갱신된 프로필을 준다
    const profile = await completeProfileImage(issued.uploadKey)
    auth.setProfile(profile)
    store.hasAvatar = false
    sh.avatar = false
  } catch (err) {
    // 400(형식·크기·업로드 미완료)·503(저장소 미구성)은 서버 문구가 구체적이라 그대로 보여 준다
    imgError.value = err.message || '프로필 이미지를 변경하지 못했어요.'
  } finally {
    imgStep.value = 'idle'
  }
}

async function resetAvatar() {
  if (imgBusy.value) return
  imgError.value = ''
  if (AUTH_GUARD_OFF) { store.hasAvatar = false; sh.avatar = false; return }
  if (!auth.profileImageUrl) { sh.avatar = false; return } // 이미 기본 이미지
  try {
    imgStep.value = 'deleting'
    const profile = await deleteProfileImage()
    auth.setProfile(profile)
    sh.avatar = false
  } catch (err) {
    imgError.value = err.message || '기본 이미지로 바꾸지 못했어요.'
  } finally {
    imgStep.value = 'idle'
  }
}

/* ===== 닉네임 수정 — PATCH /api/members/me ===== */
// 서버 NicknamePolicy 와 같은 범위(앞뒤 공백 제거 후 2~12자). 금칙어는 서버가 판정해 400 메시지로 알려 준다
const NICK_MIN = 2
const NICK_MAX = 12
const nick = ref('')
const nickBusy = ref(false)
const nickError = ref('')
const nickTrimmed = computed(() => nick.value.trim())
const nickValid = computed(() => nickTrimmed.value.length >= NICK_MIN && nickTrimmed.value.length <= NICK_MAX)
const nickChanged = computed(() => nickTrimmed.value !== (auth.nickname || ''))

function openNick() {
  nick.value = auth.nickname || ''
  nickError.value = ''
  sh.nick = true
}
async function saveNick() {
  if (!nickValid.value || nickBusy.value) return
  const value = nickTrimmed.value
  if (!nickChanged.value) { sh.nick = false; return }
  if (AUTH_GUARD_OFF) { auth.setNickname(value); sh.nick = false; return } // 백엔드 없는 화면 확인 모드
  nickBusy.value = true
  nickError.value = ''
  try {
    const profile = await updateNickname(value)
    auth.setNickname(profile?.nickname ?? value)
    sh.nick = false
  } catch (e) {
    // 400: 길이·금칙어 — 서버 문구가 구체적이라 그대로 보여 준다. 401 은 전역 처리(로그인 화면)로 넘어간다
    nickError.value = e.status === 400 ? e.message : e.status === 0 ? e.message : '닉네임을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.'
  } finally {
    nickBusy.value = false
  }
}
// POST /api/auth/logout (204) 로 서버 세션을 끊은 뒤 랜딩으로
async function logout() { sh.logout = false; store.agreed = false; await auth.logout(); useVehicleStore().reset(); useAccidentStore().reset(); router.replace('/landing') }
</script>

<template>
  <Screen>
    <AppHeader title="마이페이지" back="/home" />

    <div class="body scroll" style="padding-top:12px">
      <div class="row" style="gap:16px">
        <Avatar :size="64" :font-size="24">
          <button class="avedit" aria-label="프로필 사진 변경" @click="openAvatarSheet">
            <svg width="11" height="11" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#FFFFFF" stroke-width="2" stroke-linejoin="round"/></svg>
          </button>
        </Avatar>
        <div style="display:flex;flex-direction:column;align-items:flex-start;gap:8px">
          <span class="row" style="gap:6px">
            <span style="font-size:20px;font-weight:700">{{ auth.nickname || '김싸피' }}</span>
            <button class="nkedit" aria-label="닉네임 수정" @click="openNick">
              <svg width="14" height="14" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>
            </button>
          </span>
          <!-- 로그인 방식 — 세션의 provider(KAKAO | GOOGLE)에 따라 표시. 세션 정보가 없으면 기존 목업대로 카카오 -->
          <span v-if="auth.me?.provider === 'GOOGLE'" class="kakao google">
            <b class="gm" aria-hidden="true">G</b>구글 로그인
          </span>
          <span v-else class="kakao">
            <svg width="11" height="11" viewBox="0 0 12 12" fill="#191F28" aria-hidden="true"><path d="M6 1.5C3.2 1.5 1 3.3 1 5.5c0 1.4.9 2.6 2.3 3.3L2.8 11l2.4-1.6c.3 0 .5.1.8.1 2.8 0 5-1.8 5-4S8.8 1.5 6 1.5z"/></svg>카카오 로그인
          </span>
        </div>
      </div>

      <div class="stats">
        <button class="stat" @click="router.push('/my/vehicles')"><b :class="{ pending: counts.vehicles === null }">{{ fmt(counts.vehicles) }}</b><span>내 차량<Chev /></span></button>
        <button class="stat" @click="router.push('/history')"><b :class="{ pending: counts.accidents === null }">{{ fmt(counts.accidents) }}</b><span>사고 이력<Chev /></span></button>
        <button class="stat" @click="router.push('/checklists')"><b :class="{ pending: counts.checklists === null }">{{ fmt(counts.checklists) }}</b><span>체크리스트<Chev /></span></button>
      </div>

      <div class="lbl" style="margin-top:24px">계정 · 정보</div>
      <div class="mlist" style="margin-top:8px">
        <button class="mrow" @click="router.push('/my/account')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="7" r="3.2" stroke="#4E36E4" stroke-width="1.5"/><path d="M4 16.5c.8-2.6 3-4 6-4s5.2 1.4 6 4" stroke="#4E36E4" stroke-width="1.5" stroke-linecap="round"/></svg></span>
          <span class="ml">계정 관리</span><ChevR />
        </button>
        <button class="mrow" @click="router.push('/my/notifications')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M5.5 13.5V9a4.5 4.5 0 0 1 9 0v4.5l1.2 1.5H4.3z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/><path d="M8.5 16.5a1.5 1.5 0 0 0 3 0" stroke="#4E36E4" stroke-width="1.5"/></svg></span>
          <span class="ml">알림 설정</span><ChevR />
        </button>
        <!-- /terms 는 가입용 동의 화면이라 회원은 가드에 걸려 홈으로 돌아간다. 문서 열람은 별도 메뉴 화면으로 -->
        <button class="mrow" @click="router.push('/terms/docs')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 3l6 2.2v4.6c0 3.4-2.5 5.9-6 7.2-3.5-1.3-6-3.8-6-7.2V5.2z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/></svg></span>
          <span class="ml">약관 및 개인정보 처리방침</span><ChevR />
        </button>
        <button class="mrow" @click="sh.notice = true">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7" stroke="#4E36E4" stroke-width="1.5"/><path d="M10 9v4.5M10 6.5h.01" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round"/></svg></span>
          <span class="ml">AI 견적 고지 안내</span><ChevR />
        </button>
      </div>

      <div style="margin-top:24px;display:flex;flex-direction:column;align-items:center;gap:12px">
        <button style="padding:4px;font-size:14px;color:var(--text-2)" @click="sh.logout = true">로그아웃</button>
        <span style="font-size:13px;color:var(--text-4)">NOCA v0.1.0 (Beta)</span>
      </div>
      <div style="height:24px"></div>
    </div>
    <div class="spacer"></div>

    <BottomSheet v-model="sh.avatar">
      <p class="st">프로필 사진</p>
      <!-- 숨긴 파일 입력. 모바일에서는 갤러리(또는 카메라 포함 선택 창)가 열린다 -->
      <input ref="albumInput" type="file" accept="image/jpeg,image/png,image/heic,.jpg,.jpeg,.png,.heic" hidden @change="onFilePicked">
      <div class="mlist" :class="{ dim: imgBusy }">
        <button class="mrow" :disabled="imgBusy" @click="pickAlbum"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 15l5-5 4 4 3-3 6 6"/><circle cx="16" cy="9" r="1.5"/></svg></span><span class="ml">앨범에서 선택<small>JPG · PNG · HEIC, 5MB 이하</small></span></button>
        <button class="mrow" :disabled="imgBusy" @click="resetAvatar"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"/></svg></span><span class="ml">기본 이미지로</span></button>
      </div>
      <p v-if="imgBusy" class="sub" style="margin-top:12px" role="status">
        <svg width="14" height="14" viewBox="0 0 18 18" fill="none" aria-hidden="true" style="vertical-align:-2px;margin-right:6px;animation:dcspin 1s linear infinite"><circle cx="9" cy="9" r="7" stroke="#EEEBFD" stroke-width="2.5"/><circle cx="9" cy="9" r="7" stroke="#4E36E4" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="44" stroke-dashoffset="31"/></svg>{{ imgStatus }}
      </p>
      <p v-if="imgError" class="sub err" role="alert">{{ imgError }}</p>
      <div class="acts" style="margin-top:12px"><button class="btn outline" :disabled="imgBusy" @click="sh.avatar = false">닫기</button></div>
    </BottomSheet>

    <BottomSheet v-model="sh.nick">
      <p class="st">닉네임 변경</p>
      <div class="field" style="margin-top:20px">
        <label class="fl" for="nickInput">닉네임</label>
        <input
          id="nickInput" v-model="nick" class="inp" :maxlength="NICK_MAX" autocomplete="off" enterkeyhint="done"
          placeholder="2~12자" :disabled="nickBusy" @keyup.enter="saveNick"
        >
      </div>
      <div class="row between" style="margin-top:8px">
        <span class="sub" style="font-size:12px">한글·영문·숫자 모두 사용할 수 있어요. 앞뒤 공백은 저장 시 제거돼요</span>
        <span class="sub nowrap" :class="{ over: nickTrimmed.length > 0 && !nickValid }" style="font-size:12px">{{ nickTrimmed.length }}/{{ NICK_MAX }}</span>
      </div>
      <p v-if="nickError" class="sub err" role="alert">{{ nickError }}</p>
      <div class="acts">
        <button class="btn outline" :disabled="nickBusy" @click="sh.nick = false">취소</button>
        <button class="btn bold" :disabled="!nickValid || nickBusy" @click="saveNick">{{ nickBusy ? '저장 중…' : '저장' }}</button>
      </div>
    </BottomSheet>

    <BottomSheet v-model="sh.logout">
      <p class="st">로그아웃할까요?</p>
      <p class="sd">다시 이용하려면 카카오·구글 로그인이 필요해요.</p>
      <div class="acts">
        <button class="btn outline" @click="sh.logout = false">취소</button>
        <button class="btn bold" @click="logout">로그아웃</button>
      </div>
    </BottomSheet>

    <BottomSheet v-model="sh.notice">
      <p class="st">AI 견적 고지 안내</p>
      <p class="sd">노카가 제공하는 견적은 AI가 사진과 과거 수리 사례를 바탕으로 추정한 참고 금액입니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있으며, 본 견적은 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="sh.notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<script>
import { h } from 'vue'
const Chev = () => h('svg', { width: 12, height: 12, viewBox: '0 0 12 12', fill: 'none', 'aria-hidden': 'true' }, [h('path', { d: 'M4.5 2.5L8 6l-3.5 3.5', stroke: '#8B95A1', 'stroke-width': '1.4', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })])
const ChevR = () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none', 'aria-hidden': 'true' }, [h('path', { d: 'M6 3.5L10.5 8 6 12.5', stroke: '#B0B8C1', 'stroke-width': '1.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })])
export default { components: { Chev, ChevR } }
</script>

<style scoped>
.avedit { position: absolute; right: -2px; bottom: -2px; width: 22px; height: 22px; border: 2px solid var(--white); border-radius: 11px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.kakao { display: flex; align-items: center; gap: 5px; height: 24px; padding: 0 9px; border-radius: 12px; background: var(--kakao); font-size: 12px; font-weight: 600; color: var(--text); }
.kakao.google { background: var(--white); border: 1px solid var(--line); }
.nkedit { width: 26px; height: 26px; border-radius: 13px; display: flex; align-items: center; justify-content: center; color: var(--text-3); background: var(--bg); }
.mlist.dim { opacity: .55; pointer-events: none; }
.nkedit:hover { background: var(--bg-2); color: var(--text); }
.over { color: var(--danger-2); }
.err { margin-top: 10px; color: var(--danger-2); }
.gm { font-family: Arial, sans-serif; font-size: 12px; font-weight: 700; color: #4285F4; }
.stats { margin-top: 20px; padding: 20px 0; border: 1px solid var(--line); border-radius: 12px; display: flex; }
.stat { flex: 1 1 0; border-right: 1px solid var(--line); display: flex; flex-direction: column; align-items: center; gap: 6px; }
.stat:last-child { border-right: 0; }
.stat b { font-size: 24px; font-weight: 700; color: var(--text); }
.stat b.pending { color: var(--text-4); }
.stat span { display: flex; align-items: center; gap: 2px; font-size: 13px; color: var(--text-2); }
</style>
