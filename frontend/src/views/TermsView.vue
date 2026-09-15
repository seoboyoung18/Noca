<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import CheckBox from '../components/CheckBox.vue'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { fetchSignupContext, logout as apiLogout, signup } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'
import { TERMS_DOCS } from '../data/terms'

const router = useRouter()
const store = useAppStore()
const auth = useAuthStore()

// 화면의 약관 키 → 서버 TermsType (backend TermsPolicy.REQUIRED: SERVICE, PRIVACY 둘 다 필수)
// 체크 상태는 스토어(termsChecked)에 두어 전문 화면을 보고 돌아와도 유지된다
// 항목 아래 요약(summary)은 전문 데이터(data/terms.js)에서 가져와 본문과 어긋나지 않게 한다
const terms = ref([
  { key: 'tos', type: 'SERVICE', label: '이용약관', doc: '/terms/service', summary: TERMS_DOCS.service.summary, on: store.agreed || store.termsChecked.SERVICE },
  { key: 'privacy', type: 'PRIVACY', label: '개인정보 처리방침', doc: '/terms/privacy', summary: TERMS_DOCS.privacy.summary, on: store.agreed || store.termsChecked.PRIVACY },
])
const all = computed(() => terms.value.every((t) => t.on))
function setTerm(t, v) { t.on = v; store.termsChecked[t.type] = v }
function toggleAll() { const v = !all.value; terms.value.forEach((t) => setTerm(t, v)) }

// 가입 대기 세션 정보(GET /api/auth/signup). 소셜 닉네임을 그대로 가입 닉네임으로 쓰되 12자(DB 상한)로 자른다.
// 서버가 닉네임을 거절(400)한 경우에만 입력란을 보여 화면 구성을 유지한다.
const ctx = ref(null)
const nickname = ref('')
const needNick = ref(false)
const notice = ref('')
const busy = ref(false)
let timer = null

onMounted(async () => {
  if (AUTH_GUARD_OFF) return
  try {
    ctx.value = await fetchSignupContext()
    nickname.value = (ctx.value.socialNickname || '').trim().slice(0, 12)
  } catch (e) {
    if (e.status === 409) { await auth.refresh(); router.replace('/home'); return } // 이미 가입된 세션
    if (e.status === 401) {
      notice.value = '소셜 로그인이 필요해요. 로그인 화면으로 이동합니다.'
      timer = setTimeout(() => router.replace('/login'), 1200)
    } else notice.value = e.message
  }
})
onUnmounted(() => clearTimeout(timer))

async function agree() {
  if (!all.value || busy.value) return
  // 백엔드 없이 화면만 보는 모드: 기존 프로토타입 흐름 유지
  if (AUTH_GUARD_OFF) { store.agreed = true; router.push('/home'); return }
  if (!ctx.value) { router.replace('/login'); return }

  const nick = nickname.value.trim()
  if (nick.length < 2 || nick.length > 12) { needNick.value = true; notice.value = '닉네임은 2~12자로 입력해 주세요.'; return }

  busy.value = true
  notice.value = ''
  try {
    const me = await signup(nick, terms.value.map((t) => t.type))
    // 소셜 이름은 가입 뒤 서버가 버리므로 여기서 회원 ID 에 묶어 보관 — 계정 관리 "연결된 계정" 표시용
    auth.rememberSocialName(me?.memberId, ctx.value?.socialNickname)
    auth.setMember(me)
    store.agreed = true
    router.replace(auth.consumeNext('/home'))
  } catch (e) {
    if (e.status === 400) { needNick.value = true; notice.value = e.message } // 닉네임 정책 위반 등 — 서버 문구가 구체적이다
    else if (e.status === 409) { await auth.refresh(); router.replace('/home') }
    else if (e.status === 401) notice.value = '가입 대기 시간(10분)이 지났어요. 다시 로그인해 주세요.'
    else notice.value = e.message
  } finally {
    busy.value = false
  }
}

// 동의하지 않음: 가입 대기 세션을 끊고 로그인 화면으로 (로그아웃은 세션이 없어도 204)
async function decline() {
  if (!AUTH_GUARD_OFF) { try { await apiLogout() } catch (e) { /* 이미 끊긴 세션 */ } }
  auth.clear()
  router.push('/login')
}
</script>

<template>
  <Screen>
    <AppHeader title="약관 동의" line />
    <div class="body" style="padding-top:32px">
      <h1 class="h1 sm">서비스 이용을 위해<br>동의가 필요합니다</h1>
      <p class="sub" style="margin-top:8px">동의를 완료해야 계정이 생성됩니다</p>

      <button class="all" @click="toggleAll">
        <CheckBox :on="all" />
        <span>약관에 모두 동의합니다</span>
      </button>

      <div class="stack" style="margin-top:12px;gap:8px">
        <!-- 위: 체크박스·이름(동의 토글)과 "보기"(전문 화면) · 아래: 문서 핵심 요약 -->
        <div v-for="t in terms" :key="t.key" class="acc">
          <div class="acc-top">
            <button class="agree" :aria-pressed="t.on" @click="setTerm(t, !t.on)">
              <CheckBox :on="t.on" />
              <span class="tag">필수</span>
              <span class="flex1" style="text-align:left;font-size:15px;font-weight:500">{{ t.label }}</span>
            </button>
            <button class="view" :aria-label="t.label + ' 전문 보기'" @click="router.push(t.doc)">
              보기
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
          <ul class="sum">
            <li v-for="(s, i) in t.summary" :key="i">{{ s }}</li>
          </ul>
        </div>
      </div>

      <!-- 가입 조건 안내 (이용약관 제4조·제8조 요약) -->
      <p class="note">가입은 만 14세 이상만 가능하며, 회원 계정은 가입 시 사용한 소셜 계정(카카오·구글)과 연결됩니다. AI 모델 학습에 콘텐츠를 활용하는 것은 별도로 선택 동의한 경우에만 이루어집니다.</p>

      <!-- 소셜 닉네임으로 가입할 수 없을 때만 노출 -->
      <div v-if="needNick" class="field" style="margin-top:20px">
        <label class="fl" for="nick">닉네임</label>
        <input id="nick" v-model="nickname" class="inp" maxlength="12" placeholder="2~12자" @keyup.enter="agree">
      </div>
      <p v-if="notice" class="sub err" role="alert">{{ notice }}</p>
      <!-- 스크롤 끝에서 마지막 요소가 하단 버튼 영역에 붙지 않도록 여백 -->
      <div style="height:28px"></div>
    </div>
    <div class="foot">
      <button class="btn bold" :disabled="!all || busy" style="font-size:17px;font-weight:700" @click="agree">{{ busy ? '가입 중…' : '동의하고 시작하기' }}</button>
      <button class="tbtn" style="margin-top:12px" @click="decline">동의하지 않음</button>
    </div>
  </Screen>
</template>

<style scoped>
.all { margin-top: 28px; width: 100%; height: 56px; padding: 0 16px; background: var(--bg); border-radius: 12px; display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 600; color: var(--text); text-align: left; }
.acc { width: 100%; border: 1px solid var(--line); border-radius: 12px; background: var(--white); overflow: hidden; }
.acc-top { height: 56px; padding: 0 0 0 16px; display: flex; align-items: center; }
.acc .agree { flex: 1 1 auto; min-width: 0; height: 100%; display: flex; align-items: center; gap: 10px; text-align: left; background: none; }
.acc .view { flex: 0 0 auto; height: 100%; padding: 0 14px 0 10px; display: flex; align-items: center; gap: 2px; font-size: 13px; color: var(--text-3); border-left: 1px solid var(--line); }
.acc .view:hover { background: var(--bg-2); color: var(--text-2); }
.sum { margin: 0; padding: 10px 16px 12px 34px; border-top: 1px solid var(--line); background: var(--bg); }
.sum li { font-size: 12px; line-height: 1.55; color: var(--text-2); }
.sum li + li { margin-top: 4px; }
.note { margin-top: 12px; padding: 12px 14px; border-radius: 10px; background: var(--bg); font-size: 12px; line-height: 1.55; color: var(--text-3); }
.err { margin-top: 12px; color: var(--danger-2); }
</style>
