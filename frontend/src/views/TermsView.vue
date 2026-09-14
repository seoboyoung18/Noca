<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import CheckBox from '../components/CheckBox.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const terms = ref([
  { key: 'tos', label: '이용약관', on: store.agreed },
  { key: 'privacy', label: '개인정보 처리방침', on: store.agreed },
])
const all = computed(() => terms.value.every((t) => t.on))
function toggleAll() { const v = !all.value; terms.value.forEach((t) => { t.on = v }) }
function agree() {
  if (!all.value) return
  store.agreed = true
  router.push('/home')
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
        <button v-for="t in terms" :key="t.key" class="acc" @click="t.on = !t.on">
          <CheckBox :on="t.on" />
          <span class="tag">필수</span>
          <span class="flex1" style="text-align:left;font-size:15px;font-weight:500">{{ t.label }}</span>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </div>
    <div class="foot">
      <button class="btn bold" :disabled="!all" style="font-size:17px;font-weight:700" @click="agree">동의하고 시작하기</button>
      <button class="tbtn" style="margin-top:12px" @click="router.push('/login')">동의하지 않음</button>
    </div>
  </Screen>
</template>

<style scoped>
.all { margin-top: 28px; width: 100%; height: 56px; padding: 0 16px; background: var(--bg); border-radius: 12px; display: flex; align-items: center; gap: 10px; font-size: 15px; font-weight: 600; color: var(--text); text-align: left; }
.acc { width: 100%; height: 56px; padding: 0 16px; border: 1px solid var(--line); border-radius: 12px; display: flex; align-items: center; gap: 10px; background: var(--white); }
</style>
