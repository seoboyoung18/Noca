<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import { useChecklistStore } from '../stores/checklist'
import { checklistPending } from '../data/checklists'

/* ===== 체크리스트 만들기 (S14a) =====
 * 사고 id 가 있으면 생성을 요청하고(없으면 QUEUED, 실패면 재시도, 진행 중이면 그대로) 상태가 끝날 때까지 2초마다 확인한 뒤 상세로 간다.
 * 상세 화면도 pending 상태를 스스로 그리므로 여기서 오래 붙잡지 않는다 — 요청 뒤 첫 응답이 오면 바로 상세로 넘긴다.
 * 사고 id 가 없으면(목업 진입) 1.5초 뒤 상세로.
 */
const route = useRoute()
const router = useRouter()
const accidentId = Number(route.query.accidentId) || null
const checklists = useChecklistStore()
let t
const go = () => router.replace(accidentId ? { path: '/checklist', query: { accidentId } } : '/checklist')

onMounted(async () => {
  if (!accidentId) { t = setTimeout(go, 1500); return }
  await checklists.ensureRequested(accidentId)
  const res = checklists.get(accidentId) || await checklists.load(accidentId)
  // 이미 완료돼 있으면 곧장, 아니면 잠깐 보여 준 뒤 상세로 (상세가 이어서 폴링)
  t = setTimeout(go, res && !checklistPending(res.status) ? 300 : 1500)
})
onUnmounted(() => clearTimeout(t))
</script>

<template>
  <Screen>
    <header class="hdr"><span class="ttl">체크리스트 만들기</span></header>
    <div class="prog"><i style="width:100%"></i></div>
    <div class="body col" style="padding:0">
      <div style="flex:0 0 clamp(60px, 22vh, 201px)"></div>
      <div style="display:flex;flex-direction:column;align-items:center;text-align:center;padding:0 40px">
        <svg width="72" height="72" viewBox="0 0 72 72" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite">
          <circle cx="36" cy="36" r="33" stroke="#EEEBFD" stroke-width="6"/>
          <circle cx="36" cy="36" r="33" stroke="#4E36E4" stroke-width="6" stroke-linecap="round" stroke-dasharray="52 155" transform="rotate(-90 36 36)"/>
        </svg>
        <h1 class="h1 sm" style="margin-top:28px">확인 항목을 정리하고 있어요</h1>
        <p style="margin-top:8px;font-size:14px;line-height:1.5;color:var(--text-3)">분석 결과와 예상 견적을 바탕으로<br>정비소에서 확인할 항목을 만들고 있어요</p>
      </div>
      <div class="flex1"></div>
      <p class="sub center" style="font-size:12px;padding-bottom:40px">완성되면 정비소에서 바로 확인할 수 있어요</p>
    </div>
  </Screen>
</template>
