<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import LogoMark from '../components/LogoMark.vue'

const router = useRouter()
</script>

<template>
  <Screen>
    <AppHeader back="/landing" />
    <div class="body col" style="justify-content:center;padding-bottom:120px">
      <div class="wrap">
        <LogoMark :size="64" :radius="16" :icon="34" />
        <h1 class="h1 sm" style="margin-top:24px;text-align:center">로그인하고 견적을 확인하세요</h1>
        <div class="stack" style="margin-top:32px;width:100%">
          <button class="btn kakao" @click="router.push('/terms')">
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><ellipse cx="9" cy="8" rx="8" ry="6.6" fill="#191919"/><path d="M5.6 12.6L4.2 16.4l4.2-2.6z" fill="#191919"/></svg>
            카카오로 시작하기
          </button>
          <button class="btn outline" @click="router.push('/terms')">
            <span class="g">G</span>
            구글로 시작하기
          </button>
        </div>
        <p class="sub" style="margin-top:20px;text-align:center">카카오·구글 계정으로 간편하게 시작해요</p>
      </div>
    </div>
  </Screen>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; align-items: center; }
.g { width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; font-family: Arial, sans-serif; font-size: 17px; font-weight: 700; color: #4285F4; }
</style>
