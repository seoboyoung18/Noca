<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import LogoMark from '../components/LogoMark.vue'
import Avatar from '../components/Avatar.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
</script>

<template>
  <Screen>
    <div class="body col scroll" style="padding-top:20px">
      <div class="row" style="gap:8px">
        <LogoMark />
        <span class="brand flex1">노카</span>
        <button class="me" aria-label="마이페이지" @click="router.push('/my')"><Avatar /></button>
      </div>

      <p class="hello">안녕하세요, 김싸피님</p>
      <h1 class="h1" style="margin-top:8px">사고가 났나요?</h1>

      <button class="cta" @click="router.push('/claim/vehicle')">
        <span class="flex1" style="display:flex;flex-direction:column">
          <span class="ct">예상 수리 견적 보기</span>
          <span class="cs">사진을 찍으면 예상 수리비를 알려드려요</span>
        </span>
        <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true"><path d="M4 11h13M12 6l5 5-5 5" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>

      <div class="tiles">
        <button class="tile" @click="router.push('/checklists')">
          <svg class="wm" width="118" height="118" viewBox="0 0 24 24" fill="none" aria-hidden="true" style="right:-24px;bottom:-16px"><path d="M4 12.5L9.5 18L20 6.5" stroke="rgba(78,54,228,.08)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          <b>정비 체크리스트</b><span>확인 항목 · 질문 목록</span>
        </button>
        <button class="tile" @click="router.push('/shops')">
          <svg class="wm" width="106" height="106" viewBox="0 0 24 24" fill="none" aria-hidden="true" style="right:-20px;bottom:-14px"><path d="M12 21.5c4.6-5.2 6.9-9 6.9-11.5a6.9 6.9 0 1 0-13.8 0c0 2.5 2.3 6.3 6.9 11.5Z" stroke="rgba(78,54,228,.08)" stroke-width="2.4" stroke-linejoin="round"/><circle cx="12" cy="9.7" r="2.6" stroke="rgba(78,54,228,.08)" stroke-width="2.4"/></svg>
          <b>주변 정비소</b><span>가까운 순 · 지도</span>
        </button>
      </div>

      <!-- 진행 중인 분석 -->
      <template v-if="store.homeMode === 'busy'">
        <div class="row between" style="margin-top:28px">
          <span class="sec">진행 중</span>
          <span class="sub">1건</span>
        </div>
        <button class="busy" @click="router.push('/claim/analyzing')">
          <span class="ring">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none" aria-hidden="true">
              <circle cx="24" cy="24" r="22" stroke="#FFFFFF" stroke-width="4"/>
              <circle cx="24" cy="24" r="22" stroke="#4E36E4" stroke-width="4" stroke-linecap="round" stroke-dasharray="138.2" stroke-dashoffset="44.2" transform="rotate(-90 24 24)"/>
            </svg>
            <b>68%</b>
          </span>
          <span class="flex1" style="display:flex;flex-direction:column;text-align:left">
            <span class="bt">기아 쏘렌토 · 분석 중</span>
            <span class="bs">부품을 연결하고 있어요</span>
            <span class="bar"><i style="width:68%"></i></span>
          </span>
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 18px"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </template>

      <!-- 최근 사고 -->
      <template v-if="store.homeMode !== 'empty'">
        <div class="row between" style="margin-top:28px">
          <span class="sec">최근 사고</span>
          <button class="link" @click="router.push('/history')">전체 보기</button>
        </div>
        <button class="recent" @click="router.push('/estimate')">
          <img src="/assets/avante-damage.png" alt="현대 아반떼 손상 사진">
          <span class="flex1" style="display:flex;flex-direction:column;gap:5px;text-align:left">
            <span class="sec">현대 아반떼</span>
            <span class="row" style="gap:6px"><span class="sub" style="font-size:12px">9월 5일</span><span class="tag">견적 완료</span></span>
          </span>
          <span class="amt">124만원</span>
        </button>
        <div style="height:24px"></div>
      </template>

      <template v-else>
        <div class="col flex1" style="margin-top:28px;display:flex;flex-direction:column">
          <span class="sec">최근 사고</span>
          <div class="empty">
            <img src="/assets/logo-small.png" alt="">
            <b>아직 접수한 사고가 없어요</b>
            <p>사고가 나면 사진을 찍어<br>예상 수리비를 확인해보세요</p>
          </div>
        </div>
      </template>
    </div>
    <div class="spacer"></div>
  </Screen>
</template>

<style scoped>
.brand { font-size: 20px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; }
.me { flex: 0 0 44px; width: 44px; height: 44px; margin: -6px -6px -6px 0; display: flex; align-items: center; justify-content: center; border-radius: 22px; }
.hello { margin-top: 28px; font-size: 14px; color: var(--text-2); }
.cta { margin-top: 20px; width: 100%; height: 92px; padding: 0 20px; border-radius: 16px; background: var(--primary); text-align: left; display: flex; align-items: center; gap: 12px; transition: background .15s; }
.cta:hover { background: var(--primary-dark); }
.ct { font-size: 18px; font-weight: 700; color: #fff; }
.cs { margin-top: 4px; font-size: 13px; color: rgba(255,255,255,.75); }
.tiles { margin-top: 10px; display: flex; gap: 10px; }
.tile { position: relative; flex: 1 1 0; min-width: 0; height: 92px; padding: 0 16px; border-radius: 16px; background: var(--primary-50); overflow: hidden; text-align: left; display: flex; flex-direction: column; justify-content: center; }
.tile:hover { background: var(--primary-100); }
.tile .wm { position: absolute; }
.tile b { position: relative; font-size: 15px; font-weight: 700; color: var(--text); white-space: nowrap; }
.tile span { position: relative; margin-top: 4px; font-size: 12px; color: var(--text-3); white-space: nowrap; }
.busy { margin-top: 12px; width: 100%; height: 84px; padding: 0 14px; background: var(--primary-soft); border-radius: 12px; display: flex; align-items: center; gap: 14px; }
.ring { position: relative; flex: 0 0 48px; width: 48px; height: 48px; display: flex; align-items: center; justify-content: center; }
.ring b { position: absolute; font-size: 11px; font-weight: 600; color: var(--primary); }
.bt { font-size: 15px; font-weight: 600; color: var(--text); }
.bs { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.bar { margin-top: 8px; display: block; height: 4px; border-radius: 2px; background: #fff; overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 2px; background: var(--primary); }
.recent { margin-top: 12px; width: 100%; height: 84px; padding: 0 14px; border: 1px solid var(--line); border-radius: 12px; display: flex; align-items: center; gap: 12px; background: var(--white); }
.recent:hover { background: var(--bg-2); }
.recent img { flex: 0 0 56px; width: 56px; height: 56px; border-radius: 8px; object-fit: cover; }
.amt { flex: 0 0 auto; white-space: nowrap; font-size: 15px; font-weight: 700; color: var(--text); }
</style>
