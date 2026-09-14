<script setup>
import { useRouter } from 'vue-router'
import AppHeader from '../components/common/AppHeader.vue'

const router = useRouter()

function handleBack() {
  router.back()
}

function handleKakaoLogin() {
  // TODO: 카카오 OAuth 연동 자리
}

function handleGoogleLogin() {
  // TODO: 구글 OAuth 연동 자리
}
</script>

<template>
  <div class="login">
    <AppHeader @back="handleBack" />

    <main class="login-content">
      <div class="logo-mark">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M2.5 18V10.25A6.5 6.5 0 0 1 15.5 10.25V14H20A2 2 0 0 1 20 18Z"
            fill="#FFFFFF"
          />
        </svg>
      </div>

      <h1 class="login-title">로그인하고 견적을 확인하세요</h1>

      <div class="oauth-buttons">
        <button type="button" class="oauth-button oauth-button--kakao" @click="handleKakaoLogin">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
            <ellipse cx="9" cy="8" rx="8" ry="6.6" fill="currentColor" />
            <path d="M5.6 12.6L4.2 16.4l4.2-2.6z" fill="currentColor" />
          </svg>
          카카오로 시작하기
        </button>

        <button type="button" class="oauth-button oauth-button--google" @click="handleGoogleLogin">
          <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
            <path
              fill="var(--color-google-blue)"
              d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z"
            />
            <path
              fill="var(--color-google-green)"
              d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9 18z"
            />
            <path
              fill="var(--color-google-yellow)"
              d="M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.347 6.173 0 7.548 0 9s.348 2.827.957 4.042l3.007-2.332z"
            />
            <path
              fill="var(--color-google-red)"
              d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z"
            />
          </svg>
          구글로 시작하기
        </button>
      </div>

      <p class="login-caption">카카오·구글 계정으로 간편하게 시작해요</p>
    </main>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.login-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0 20px;
}

.logo-mark {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: var(--color-brand-500);
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-title {
  margin: 24px 0 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.03em;
  text-align: center;
}

.oauth-buttons {
  margin-top: 32px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.oauth-button {
  width: 100%;
  height: var(--size-button-height);
  border: 0;
  border-radius: var(--radius-button);
  font-family: inherit;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: -0.02em;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.oauth-button--kakao {
  background: var(--color-kakao-bg);
  color: var(--color-kakao-text);
}

.oauth-button--google {
  background: var(--color-bg);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.login-caption {
  margin: 20px 0 0;
  font-size: 13px;
  color: var(--color-text-sub);
  text-align: center;
}
</style>
