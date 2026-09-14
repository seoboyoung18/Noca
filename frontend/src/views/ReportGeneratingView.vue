<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'

const router = useRouter()
let t
onMounted(() => { t = setTimeout(() => router.replace('/report'), 1600) })
onUnmounted(() => clearTimeout(t))
</script>

<template>
  <Screen>
    <header class="hdr"><span class="ttl">리포트 생성 중</span></header>
    <div class="prog"><i style="width:100%"></i></div>
    <div class="body col" style="padding:0">
      <div style="flex:0 0 clamp(60px, 22vh, 201px)"></div>
      <div style="display:flex;flex-direction:column;align-items:center;text-align:center">
        <svg width="72" height="72" viewBox="0 0 72 72" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite">
          <circle cx="36" cy="36" r="33" stroke="#EEEBFD" stroke-width="6"/>
          <circle cx="36" cy="36" r="33" stroke="#4E36E4" stroke-width="6" stroke-linecap="round" stroke-dasharray="52 155" transform="rotate(-90 36 36)"/>
        </svg>
        <h1 class="h1 sm" style="margin-top:28px">리포트를 만들고 있어요</h1>
        <p style="margin-top:8px;font-size:14px;color:var(--text-3)">잠시만 기다려 주세요</p>
      </div>
      <div class="flex1"></div>
      <p class="sub center" style="font-size:12px;padding-bottom:40px">완성되면 PDF로 저장할 수 있어요</p>
    </div>
  </Screen>
</template>
