<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import CheckBox from '../components/CheckBox.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const quit = ref(false)
const ack = ref(false)
function openQuit() { ack.value = false; quit.value = true }
function doQuit() { quit.value = false; store.agreed = false; router.push('/landing') }
</script>

<template>
  <Screen>
    <AppHeader title="계정 관리" back="/my" line />
    <div class="body" style="padding-top:20px">
      <div class="lbl">연결된 계정</div>
      <div class="card row" style="margin-top:8px;padding:20px 16px;gap:12px">
        <span class="flex1" style="display:flex;flex-direction:column;gap:8px">
          <span style="font-size:16px;font-weight:700">김싸피</span>
          <span style="font-size:14px;color:var(--text-2)">카카오 · ssa***@kakao.com</span>
        </span>
        <span class="kk">카카오</span>
      </div>
      <div style="margin-top:32px;display:flex;justify-content:center">
        <button style="padding:4px;font-size:14px;color:var(--text-3)" @click="openQuit">회원 탈퇴</button>
      </div>
    </div>
    <div class="spacer"></div>

    <BottomSheet v-model="quit">
      <h2 class="st">정말 탈퇴할까요?</h2>
      <p class="sd">탈퇴하면 아래 정보가 모두 삭제되고 복구할 수 없어요.</p>
      <ul class="dl">
        <li>등록한 차량 {{ store.vehicles.length }}대</li>
        <li>사고 접수 {{ store.history.length }}건과 업로드한 사진</li>
        <li>생성한 리포트·정비 체크리스트</li>
      </ul>
      <label class="ack" @click.prevent="ack = !ack">
        <CheckBox :on="ack" />
        <span style="font-size:14px">안내를 확인했습니다</span>
      </label>
      <div class="acts">
        <button class="btn outline" @click="quit = false">취소</button>
        <button class="btn danger bold" :disabled="!ack" @click="doQuit">탈퇴</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.kk { flex: 0 0 auto; height: 24px; padding: 0 10px; border-radius: 12px; background: var(--kakao); font-size: 12px; font-weight: 600; color: var(--text); display: flex; align-items: center; }
.ack { margin-top: 16px; display: flex; align-items: center; gap: 10px; cursor: pointer; }
</style>
