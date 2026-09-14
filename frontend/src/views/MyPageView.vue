<script setup>
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import Avatar from '../components/Avatar.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const sh = reactive({ avatar: false, logout: false, notice: false })
function setAvatar(v) { store.hasAvatar = v; sh.avatar = false }
function logout() { sh.logout = false; store.agreed = false; router.push('/landing') }
</script>

<template>
  <Screen>
    <AppHeader title="마이페이지" back="/home" />

    <div class="body scroll" style="padding-top:12px">
      <div class="row" style="gap:16px">
        <Avatar :size="64" :font-size="24">
          <button class="avedit" aria-label="프로필 편집" @click="sh.avatar = true">
            <svg width="11" height="11" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#FFFFFF" stroke-width="2" stroke-linejoin="round"/></svg>
          </button>
        </Avatar>
        <div style="display:flex;flex-direction:column;align-items:flex-start;gap:8px">
          <span style="font-size:20px;font-weight:700">김싸피</span>
          <span class="kakao">
            <svg width="11" height="11" viewBox="0 0 12 12" fill="#191F28" aria-hidden="true"><path d="M6 1.5C3.2 1.5 1 3.3 1 5.5c0 1.4.9 2.6 2.3 3.3L2.8 11l2.4-1.6c.3 0 .5.1.8.1 2.8 0 5-1.8 5-4S8.8 1.5 6 1.5z"/></svg>카카오 로그인
          </span>
        </div>
      </div>

      <div class="stats">
        <button class="stat" @click="router.push('/my/vehicles')"><b>{{ store.vehicles.length }}</b><span>내 차량<Chev /></span></button>
        <button class="stat" @click="router.push('/history')"><b>{{ store.history.length }}</b><span>사고 이력<Chev /></span></button>
        <button class="stat" @click="router.push('/checklists')"><b>{{ store.checklists.length }}</b><span>체크리스트<Chev /></span></button>
      </div>

      <div class="lbl" style="margin-top:24px">계정 · 정보</div>
      <div class="mlist" style="margin-top:8px">
        <button class="mrow" @click="router.push('/my/account')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="7" r="3.2" stroke="#4E36E4" stroke-width="1.5"/><path d="M4 16.5c.8-2.6 3-4 6-4s5.2 1.4 6 4" stroke="#4E36E4" stroke-width="1.5" stroke-linecap="round"/></svg></span>
          <span class="ml">계정 관리</span><ChevR />
        </button>
        <button class="mrow" @click="router.push('/my/notifications')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M5.5 13.5V9a4.5 4.5 0 0 1 9 0v4.5l1.2 1.5H4.3z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/><path d="M8.5 16.5a1.5 1.5 0 0 0 3 0" stroke="#4E36E4" stroke-width="1.5"/></svg></span>
          <span class="ml">알림 설정</span><ChevR />
        </button>
        <button class="mrow" @click="router.push('/terms')">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 3l6 2.2v4.6c0 3.4-2.5 5.9-6 7.2-3.5-1.3-6-3.8-6-7.2V5.2z" stroke="#4E36E4" stroke-width="1.5" stroke-linejoin="round"/></svg></span>
          <span class="ml">약관 및 개인정보 처리방침</span><ChevR />
        </button>
        <button class="mrow" @click="sh.notice = true">
          <span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7" stroke="#4E36E4" stroke-width="1.5"/><path d="M10 9v4.5M10 6.5h.01" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round"/></svg></span>
          <span class="ml">AI 견적 고지 안내</span><ChevR />
        </button>
      </div>

      <div style="margin-top:24px;display:flex;flex-direction:column;align-items:center;gap:12px">
        <button style="padding:4px;font-size:14px;color:var(--text-2)" @click="sh.logout = true">로그아웃</button>
        <span style="font-size:13px;color:var(--text-4)">NOCA v0.1.0 (Beta)</span>
      </div>
      <div style="height:24px"></div>
    </div>
    <div class="spacer"></div>

    <BottomSheet v-model="sh.avatar">
      <p class="st">프로필 사진</p>
      <div class="mlist">
        <button class="mrow" @click="setAvatar(true)"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><path d="M4 8a2 2 0 012-2h2l1.5-2h5L16 6h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2z"/><circle cx="12" cy="13" r="3.5"/></svg></span><span class="ml">촬영하기</span></button>
        <button class="mrow" @click="setAvatar(true)"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 15l5-5 4 4 3-3 6 6"/><circle cx="16" cy="9" r="1.5"/></svg></span><span class="ml">앨범에서 선택</span></button>
        <button class="mrow" @click="setAvatar(false)"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"/></svg></span><span class="ml">기본 이미지로</span></button>
      </div>
      <div class="acts" style="margin-top:12px"><button class="btn outline" @click="sh.avatar = false">닫기</button></div>
    </BottomSheet>

    <BottomSheet v-model="sh.logout">
      <p class="st">로그아웃할까요?</p>
      <p class="sd">다시 이용하려면 카카오·구글 로그인이 필요해요.</p>
      <div class="acts">
        <button class="btn outline" @click="sh.logout = false">취소</button>
        <button class="btn bold" @click="logout">로그아웃</button>
      </div>
    </BottomSheet>

    <BottomSheet v-model="sh.notice">
      <p class="st">AI 견적 고지 안내</p>
      <p class="sd">노카가 제공하는 견적은 AI가 사진과 과거 수리 사례를 바탕으로 추정한 참고 금액입니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있으며, 본 견적은 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="sh.notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<script>
import { h } from 'vue'
const Chev = () => h('svg', { width: 12, height: 12, viewBox: '0 0 12 12', fill: 'none', 'aria-hidden': 'true' }, [h('path', { d: 'M4.5 2.5L8 6l-3.5 3.5', stroke: '#8B95A1', 'stroke-width': '1.4', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })])
const ChevR = () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none', 'aria-hidden': 'true' }, [h('path', { d: 'M6 3.5L10.5 8 6 12.5', stroke: '#B0B8C1', 'stroke-width': '1.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })])
export default { components: { Chev, ChevR } }
</script>

<style scoped>
.avedit { position: absolute; right: -2px; bottom: -2px; width: 22px; height: 22px; border: 2px solid var(--white); border-radius: 11px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.kakao { display: flex; align-items: center; gap: 5px; height: 24px; padding: 0 9px; border-radius: 12px; background: var(--kakao); font-size: 12px; font-weight: 600; color: var(--text); }
.stats { margin-top: 20px; padding: 20px 0; border: 1px solid var(--line); border-radius: 12px; display: flex; }
.stat { flex: 1 1 0; border-right: 1px solid var(--line); display: flex; flex-direction: column; align-items: center; gap: 6px; }
.stat:last-child { border-right: 0; }
.stat b { font-size: 24px; font-weight: 700; color: var(--text); }
.stat span { display: flex; align-items: center; gap: 2px; font-size: 13px; color: var(--text-2); }
</style>
