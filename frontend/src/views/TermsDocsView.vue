<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { TERMS_DOCS } from '../data/terms'

// 마이페이지 "약관 및 개인정보 처리방침" → 문서 선택 메뉴. 각 행을 누르면 전문 화면으로 간다.
const router = useRouter()
const docs = [
  { key: 'service', path: '/terms/service' },
  { key: 'privacy', path: '/terms/privacy' },
].map((d) => ({ ...d, ...TERMS_DOCS[d.key] }))
</script>

<template>
  <Screen>
    <AppHeader title="약관 및 개인정보 처리방침" back="/my" line />
    <div class="body scroll" style="padding-top:20px">
      <div class="mlist">
        <button v-for="d in docs" :key="d.key" class="mrow" @click="router.push(d.path)">
          <span class="mi">
            <svg v-if="d.key === 'service'" width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M5 3.5h7l3 3v10H5z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/><path d="M7.5 9h5M7.5 12h5" stroke="#4E36E4" stroke-width="1.5" stroke-linecap="round"/></svg>
            <svg v-else width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 3l6 2.2v4.6c0 3.4-2.5 5.9-6 7.2-3.5-1.3-6-3.8-6-7.2V5.2z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/><path d="M7.5 10l1.8 1.8L12.8 8.3" stroke="#4E36E4" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <span class="ml">{{ d.title }}<small>버전 {{ d.version }} · 시행일 {{ d.effective }}</small></span>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
      <p class="sub" style="margin-top:16px">가입 시 동의한 약관의 현행 버전입니다.</p>
    </div>
  </Screen>
</template>
