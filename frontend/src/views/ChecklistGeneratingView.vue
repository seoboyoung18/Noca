<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'

const router = useRouter()
let t
onMounted(() => { t = setTimeout(() => router.replace('/checklist'), 1500) })
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
