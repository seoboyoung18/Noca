<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import { useAuthStore } from '../stores/auth'
import { AUTH_GUARD_OFF } from '../router'

const router = useRouter()
const auth = useAuthStore()
const fade = ref(false)
let t1, t2
let alive = true

// 스플래시가 뜬 동안 세션을 확인한다. 소셜 로그인 성공 뒤 서버가 FE '/' 로 보내므로 여기서 갈 곳을 정한다.
// member → 홈(또는 로그인 전에 가려던 화면) · pending → 약관 동의 · guest → 랜딩
async function destination() {
  if (AUTH_GUARD_OFF) return '/landing'
  const status = await auth.refresh()
  if (status === 'member') return auth.consumeNext('/home')
  if (status === 'pending') return '/terms'
  return '/landing'
}

onMounted(() => {
  const wait = new Promise((r) => { t1 = setTimeout(r, 1400) })
  Promise.all([destination(), wait]).then(([to]) => {
    if (!alive) return
    fade.value = true
    t2 = setTimeout(() => router.replace(to), 250)
  })
})
onUnmounted(() => { alive = false; clearTimeout(t1); clearTimeout(t2) })
</script>

<template>
  <Screen class="splash" :class="{ fade }">
    <div class="mk">
      <svg width="40" height="40" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M2.5 18V10.25A6.5 6.5 0 0 1 15.5 10.25V14H20A2 2 0 0 1 20 18Z" fill="#FFFFFF"/></svg>
    </div>
    <div class="nm">노카</div>
    <div class="dots"><i></i><i></i><i></i></div>
  </Screen>
</template>
