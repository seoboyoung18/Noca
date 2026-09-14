<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'

const router = useRouter()
const fade = ref(false)
let t1, t2

onMounted(() => {
  t1 = setTimeout(() => {
    fade.value = true
    t2 = setTimeout(() => router.replace('/landing'), 250)
  }, 1400)
})
onUnmounted(() => { clearTimeout(t1); clearTimeout(t2) })
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
