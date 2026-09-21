<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import LogoMark from '../components/LogoMark.vue'
import GoogleMark from '../components/GoogleMark.vue'
import { loginUrl } from '../lib/api'

const route = useRoute()

// 소셜 인증 실패 시 서버(OAuth2FailureHandler)가 /login?error=코드 로 돌려보낸다. 코드만 오고 상세는 서버 로그에만 있다
const ERROR_MSG = {
  access_denied: '로그인이 취소되었어요. 다시 시도해 주세요.',
  withdrawn: '탈퇴한 계정이에요. 다른 계정으로 로그인해 주세요.',
  invalid_response: '로그인 정보를 확인할 수 없어요. 다시 시도해 주세요.',
  server_error: '일시적인 오류로 로그인하지 못했어요. 잠시 후 다시 시도해 주세요.',
}
const errorMsg = computed(() => (route.query.error ? ERROR_MSG[route.query.error] || ERROR_MSG.server_error : ''))

// 브라우저 이동으로 서버의 OAuth 진입점에 간다. 카카오 동의 → 서버 콜백 → FE '/'(회원) 또는 '/signup'(신규) 로 돌아온다
function login(provider) { window.location.assign(loginUrl(provider)) }
</script>

<template>
  <Screen>
    <AppHeader back="/landing" />
    <div class="body col fixed" style="justify-content:center;padding-bottom:120px">
      <div class="wrap">
        <LogoMark :size="64" :radius="16" :icon="34" />
        <h1 class="h1 sm" style="margin-top:24px;text-align:center">로그인하고 견적을 확인하세요</h1>
        <div class="stack" style="margin-top:32px;width:100%">
          <button class="btn kakao" @click="login('kakao')">
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><ellipse cx="9" cy="8" rx="8" ry="6.6" fill="#191919"/><path d="M5.6 12.6L4.2 16.4l4.2-2.6z" fill="#191919"/></svg>
            카카오로 시작하기
          </button>
          <button class="btn outline" @click="login('google')">
            <GoogleMark :size="18" />
            구글로 시작하기
          </button>
        </div>
        <p v-if="errorMsg" class="sub err" role="alert">{{ errorMsg }}</p>
        <p v-else class="sub" style="margin-top:20px;text-align:center">카카오·구글 계정으로 간편하게 시작해요</p>
      </div>
    </div>
  </Screen>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; align-items: center; }
.err { margin-top: 20px; text-align: center; color: var(--danger-2); }
</style>
