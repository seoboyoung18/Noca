<script setup>
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useAppStore } from '../stores/app'

const store = useAppStore()
const rows = [
  { key: 'analysis', title: '분석 완료 알림', desc: '사진 분석이 끝나면 알려드려요' },
  { key: 'checklist', title: '체크리스트 생성 알림', desc: '정비 체크리스트가 만들어지면 알려드려요' },
  { key: 'marketing', title: '마케팅 정보 수신', desc: '이벤트·혜택 소식 (선택)' },
]
</script>

<template>
  <Screen>
    <AppHeader title="알림 설정" back="/my" line />
    <div class="body fixed" style="padding-top:20px">
      <div class="lbl">알림</div>
      <div class="card" style="margin-top:8px;padding:0 16px">
        <div v-for="r in rows" :key="r.key" class="trow">
          <span class="flex1" style="display:flex;flex-direction:column;gap:6px">
            <span style="font-size:16px;font-weight:700">{{ r.title }}</span>
            <span style="font-size:14px;line-height:1.5;color:var(--text-2)">{{ r.desc }}</span>
          </span>
          <button class="tog" :class="{ on: store.notifications[r.key] }" role="switch" :aria-checked="store.notifications[r.key]" :aria-label="r.title" @click="store.notifications[r.key] = !store.notifications[r.key]"><i></i></button>
        </div>
      </div>
      <div style="height:24px"></div>
    </div>
    <div class="spacer"></div>
  </Screen>
</template>

<style scoped>
.trow { padding: 20px 0; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 16px; }
.trow:last-child { border-bottom: 0; }
</style>
