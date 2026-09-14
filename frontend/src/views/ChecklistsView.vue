<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
function open(c) { store.setTitle(c.car); router.push('/checklist') }
</script>

<template>
  <Screen>
    <AppHeader title="나의 체크리스트" back="/home" line />

    <div v-if="store.checklists.length" class="body scroll" style="padding-top:16px">
      <div class="stack" style="gap:12px">
        <button v-for="c in store.checklists" :key="c.id" class="card cl" @click="open(c)">
          <div class="row" style="align-items:baseline;gap:8px">
            <span class="d">{{ c.date }}</span>
            <span class="t">{{ c.car }}</span>
          </div>
          <div class="desc">
            <span style="display:flex;flex-direction:column;gap:4px;text-align:left">
              <span style="font-size:14px;font-weight:600">{{ c.type }}</span>
              <span class="sub">{{ c.fix }}</span>
            </span>
          </div>
        </button>
      </div>
      <p class="sub center" style="margin-top:20px">예상 견적 분석이 끝나면 체크리스트가 자동으로 만들어져요</p>
    </div>

    <div v-else class="body col" style="padding:0 40px;align-items:center;justify-content:center">
      <svg width="64" height="64" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M8.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10A1.5 1.5 0 0 0 18.5 19V5A1.5 1.5 0 0 0 17 3.5h-1.5" stroke="#D1D6DB" stroke-width="1.5" stroke-linecap="round"/><rect x="8.5" y="2" width="7" height="3.4" rx="1.2" stroke="#D1D6DB" stroke-width="1.5"/><path d="M9 13l2.2 2.2L15.5 11" stroke="#D1D6DB" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
      <div style="margin-top:16px;font-size:15px;font-weight:500;color:var(--text-2)">아직 만든 체크리스트가 없어요</div>
      <p class="sub center" style="margin-top:6px">견적을 받고 나면 정비소에서<br>확인할 질문을 만들어 드려요</p>
    </div>
    <div class="spacer"></div>
  </Screen>
</template>

<style scoped>
.cl { width: 100%; padding: 16px; text-align: left; }
.cl:hover { background: var(--bg-2); }
.d { font-size: 17px; font-weight: 700; color: var(--primary); }
.t { font-size: 17px; font-weight: 700; color: var(--text); }
.desc { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--line); display: flex; align-items: center; gap: 10px; }
</style>
