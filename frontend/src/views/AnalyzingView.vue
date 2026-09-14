<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'

const router = useRouter()
const steps = ['사진을 확인하고 있어요', '손상 부위를 찾고 있어요', '부품을 연결하고 있어요', '수리비를 계산하고 있어요']
const cur = ref(2) // 0-based: 현재 진행 중인 단계 (디자인 기준 3/4)
const timers = []
const pct = computed(() => ((cur.value + 1) / steps.length) * 100)

onMounted(() => {
  timers.push(setTimeout(() => { cur.value = 3 }, 1800))
  timers.push(setTimeout(() => { cur.value = 4 }, 3400))
  timers.push(setTimeout(() => router.replace('/estimate'), 4200))
})
onUnmounted(() => timers.forEach(clearTimeout))
</script>

<template>
  <Screen>
    <header class="hdr"><span class="ttl">분석 중</span></header>
    <div class="prog"><i style="width:100%"></i></div>

    <div class="body col scroll" style="padding:0">
      <div class="gap"></div>
      <div class="c">
        <svg width="72" height="72" viewBox="0 0 72 72" fill="none" aria-hidden="true" style="animation:dcspin 1.4s linear infinite">
          <circle cx="36" cy="36" r="33" stroke="#EEEBFD" stroke-width="6"/>
          <circle cx="36" cy="36" r="33" stroke="#4E36E4" stroke-width="6" stroke-linecap="round" stroke-dasharray="207.3" stroke-dashoffset="62.2" transform="rotate(-90 36 36)"/>
        </svg>
        <h1 class="h1 sm" style="margin-top:28px">손상을 분석하고 있어요</h1>
        <p style="margin-top:8px;font-size:14px;color:var(--text-3)">{{ steps[Math.min(cur, 3)] }}</p>
      </div>

      <div style="margin-top:24px;padding:0 40px">
        <div class="bar"><i :style="{ width: Math.min(pct, 100) + '%' }"></i></div>
        <div style="margin-top:10px;text-align:right;font-size:12px;font-weight:600;color:var(--text-2)">{{ Math.min(cur + 1, 4) }} / 4</div>
      </div>

      <div class="list">
        <div v-for="(s, k) in steps" :key="k" class="li">
          <span class="ic">
            <svg v-if="k < cur" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M3.5 9.6L7 13L14.5 5" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            <svg v-else-if="k === cur" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite"><circle cx="9" cy="9" r="7" stroke="#EEEBFD" stroke-width="2.5"/><circle cx="9" cy="9" r="7" stroke="#4E36E4" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="44" stroke-dashoffset="31"/></svg>
            <span v-else class="pend"></span>
          </span>
          <span class="tx" :class="{ now: k === cur, later: k > cur }">{{ s }}</span>
        </div>
      </div>

      <div class="flex1"></div>
      <div class="center" style="padding-bottom:40px">
        <p class="sub" style="font-size:12px">창을 닫아도 분석은 계속돼요</p>
        <p class="sub" style="font-size:12px;margin-top:4px">사고 이력에서 결과를 확인할 수 있어요</p>
      </div>
    </div>
  </Screen>
</template>

<style scoped>
.gap { flex: 0 0 clamp(60px, 22vh, 201px); }
.c { display: flex; flex-direction: column; align-items: center; text-align: center; }
.bar { height: 6px; border-radius: 3px; background: var(--line); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 3px; background: var(--primary); transition: width .4s; }
.list { margin-top: 32px; padding: 0 40px; display: flex; flex-direction: column; }
.li { height: 36px; display: flex; align-items: center; gap: 10px; }
.ic { flex: 0 0 18px; height: 18px; display: flex; align-items: center; justify-content: center; }
.pend { width: 18px; height: 18px; border-radius: 9px; border: 1.5px solid var(--line-2); display: block; }
.tx { font-size: 14px; color: var(--text-2); }
.tx.now { font-weight: 600; color: var(--text); }
.tx.later { color: var(--primary-disabled); }
</style>
