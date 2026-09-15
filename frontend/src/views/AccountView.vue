<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import CheckBox from '../components/CheckBox.vue'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'
import { useVehicleStore } from '../stores/vehicles'
import { fetchProfile, withdraw } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'

const router = useRouter()
const store = useAppStore()
const auth = useAuthStore()

// 연결된 계정 — 소셜 계정 이름(가입 때 받은 값, 닉네임을 바꿔도 고정) · 로그인 방식 · 이메일(있을 때만)
// 소셜 이름은 서버 값 → 가입 시 브라우저에 보관한 값 순으로 쓰고, 없으면 줄을 비운다 (앱 닉네임을 반복하지 않음)
const PROVIDER_LABEL = { KAKAO: '카카오', GOOGLE: '구글' }
const provider = computed(() => auth.me?.provider || 'KAKAO')
const providerLabel = computed(() => PROVIDER_LABEL[provider.value] || provider.value)
const socialName = computed(() => (AUTH_GUARD_OFF && !auth.me ? '김싸피' : auth.socialName))
// 서버는 이메일을 저장하지 않아(member.email 항상 NULL) 대부분 비어 있다. 있을 때만 보여 준다
const email = computed(() => auth.me?.email || '')

// 탈퇴 안내의 건수 — GET /api/members/me 의 vehicleCount·accidentCount. 못 받으면 목업 수치
const profile = ref(null)
const vehicleCount = computed(() => profile.value?.vehicleCount ?? store.vehicles.length)
const accidentCount = computed(() => profile.value?.accidentCount ?? store.history.length)

onMounted(async () => {
  if (AUTH_GUARD_OFF || !auth.isMember) return
  try { profile.value = await fetchProfile() } catch (e) { /* 건수는 안내용이라 실패해도 화면은 유지 */ }
})

const quit = ref(false)
const ack = ref(false)
const busy = ref(false)
const notice = ref('')
function openQuit() { ack.value = false; notice.value = ''; quit.value = true }

// DELETE /api/members/me — 204. 서버가 세션까지 끊으므로 FE 는 로컬 상태만 비우고 랜딩으로
async function doQuit() {
  if (!ack.value || busy.value) return
  if (AUTH_GUARD_OFF) { quit.value = false; store.agreed = false; router.replace('/landing'); return } // 백엔드 없는 화면 확인 모드
  busy.value = true
  notice.value = ''
  try {
    await withdraw()
  } catch (e) {
    if (e.status !== 401) { // 401 은 이미 끊긴 세션 — 탈퇴된 것과 같으므로 그대로 진행
      notice.value = e.status === 0 ? e.message : '탈퇴 처리에 실패했어요. 잠시 후 다시 시도해 주세요.'
      busy.value = false
      return
    }
  }
  busy.value = false
  quit.value = false
  store.agreed = false
  auth.forgetSocialName(auth.me?.memberId) // 탈퇴한 회원의 소셜 이름 보관값 정리
  auth.clear()
  useVehicleStore().reset()
  router.replace('/landing')
}
</script>

<template>
  <Screen>
    <AppHeader title="계정 관리" back="/my" line />
    <div class="body" style="padding-top:20px">
      <div class="lbl">연결된 계정</div>
      <div class="card row" style="margin-top:8px;padding:20px 16px;gap:12px">
        <span class="flex1" style="display:flex;flex-direction:column;gap:8px">
          <span v-if="socialName" style="font-size:16px;font-weight:700">{{ socialName }}</span>
          <span :style="socialName ? 'font-size:14px;color:var(--text-2)' : 'font-size:16px;font-weight:700'">{{ providerLabel }} 계정으로 로그인{{ email ? ' · ' + email : '' }}</span>
        </span>
        <span class="kk" :class="{ gg: provider === 'GOOGLE' }">{{ providerLabel }}</span>
      </div>
      <div style="margin-top:32px;display:flex;justify-content:center">
        <button style="padding:4px;font-size:14px;color:var(--text-3)" @click="openQuit">회원 탈퇴</button>
      </div>
    </div>
    <div class="spacer"></div>

    <BottomSheet v-model="quit">
      <h2 class="st">정말 탈퇴할까요?</h2>
      <p class="sd">탈퇴하면 아래 정보가 모두 삭제되고 복구할 수 없어요.</p>
      <ul class="dl">
        <li>등록한 차량 {{ vehicleCount }}대</li>
        <li>사고 접수 {{ accidentCount }}건과 업로드한 사진</li>
        <li>생성한 리포트·정비 체크리스트</li>
      </ul>
      <label class="ack" @click.prevent="ack = !ack">
        <CheckBox :on="ack" />
        <span style="font-size:14px">안내를 확인했습니다</span>
      </label>
      <p v-if="notice" class="sub err" role="alert">{{ notice }}</p>
      <div class="acts">
        <button class="btn outline" :disabled="busy" @click="quit = false">취소</button>
        <button class="btn danger bold" :disabled="!ack || busy" @click="doQuit">{{ busy ? '탈퇴 중…' : '탈퇴' }}</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.kk { flex: 0 0 auto; height: 24px; padding: 0 10px; border-radius: 12px; background: var(--kakao); font-size: 12px; font-weight: 600; color: var(--text); display: flex; align-items: center; }
.kk.gg { background: var(--white); border: 1px solid var(--line); color: #4285F4; }
.ack { margin-top: 16px; display: flex; align-items: center; gap: 10px; cursor: pointer; }
.err { margin-top: 12px; color: var(--danger-2); }
</style>
