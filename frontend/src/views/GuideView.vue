<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'

/* ===== 촬영 가이드 (S05) — 목업 "NOCA 목업_촬영 가이드 수정" S05_촬영가이드 기준 =====
 * 2026-09-16 팀 결정으로 촬영 컷이 10장 → 1장이 됐다(backend S15P21A307-519, shooting-guide.json recommendedCount 1).
 * 사용자는 파손 부위가 보이는 사진 한 장만 올리므로 각도별 슬라이드 대신
 * 올바른 예시 1장 + 촬영 요령 2가지 + 분석이 어려운 사진 예시 2장으로 구성한다.
 * 예시 이미지는 서버가 주지 않고 FE 번들(public/assets/guide-*.jpg, 목업 원본 PNG 를 960px JPEG 로 축소)에 둔다 — 가이드 API 는 코드·문구만 준다.
 */
const router = useRouter()

const TIPS = [
  '파손 부위가 화면의 절반 이상 차도록 가까이서',
  '밝은 곳에서 초점을 맞춰 파손 부위 전체가 잘 보이게',
]
const BAD = [
  { src: '/assets/guide-bad-blur.jpg', alt: '흔들림과 빛 반사가 심한 사진', text: '흔들림·빛 반사가 심함' },
  { src: '/assets/guide-bad-far.jpg', alt: '촬영 거리가 너무 먼 사진', text: '촬영 거리가 너무 멂' },
]

function toUpload() { router.push('/claim/upload') }
</script>

<template>
  <Screen>
    <!-- 건너뛰기는 하단 "사진 올리기" 와 동작이 같아 두지 않는다 -->
    <AppHeader title="촬영 가이드" back="/claim/vehicle" />
    <div class="prog"><i style="width:50%"></i></div>

    <div class="body scroll" style="padding-top:20px">
      <p class="step">2 / 4 · 촬영 가이드</p>
      <h1 class="h1 sm" style="margin-top:6px">파손 부위가 잘 보이게<br>사진을 찍어주세요</h1>

      <!-- 올바른 예시 -->
      <div class="shot">
        <img src="/assets/guide-good.jpg" alt="올바른 촬영 예시">
        <span class="badge">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          올바른 예시
        </span>
      </div>
      <ul class="tips">
        <li v-for="t in TIPS" :key="t"><i></i>{{ t }}</li>
      </ul>

      <!-- 분석이 어려운 사진 -->
      <div class="lbl" style="margin-top:24px">이런 사진은 분석이 어려워요</div>
      <div class="bad">
        <figure v-for="b in BAD" :key="b.src">
          <span class="thumb">
            <img :src="b.src" :alt="b.alt">
            <span class="x" aria-hidden="true">
              <svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="#FFFFFF" stroke-width="2.2" stroke-linecap="round"/></svg>
            </span>
          </span>
          <figcaption>{{ b.text }}</figcaption>
        </figure>
      </div>
      <div style="height:16px"></div>
    </div>

    <div class="foot">
      <button class="btn" @click="toUpload">사진 올리기</button>
    </div>
  </Screen>
</template>

<style scoped>
.shot { position: relative; margin-top: 20px; width: 100%; aspect-ratio: 8 / 5; border-radius: 16px; background: var(--bg-2); overflow: hidden; }
.shot img { width: 100%; height: 100%; object-fit: cover; display: block; }
.badge { position: absolute; left: 10px; top: 10px; height: 26px; padding: 0 9px; border-radius: 6px; background: var(--primary); color: #fff; font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 4px; }
.tips { margin: 12px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.tips li { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--text); }
.tips i { flex: 0 0 6px; width: 6px; height: 6px; border-radius: 3px; background: var(--primary); }
.bad { margin-top: 8px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.bad figure { margin: 0; display: flex; flex-direction: column; gap: 8px; }
.thumb { position: relative; width: 100%; aspect-ratio: 156 / 110; border-radius: 12px; overflow: hidden; background: var(--bg-2); display: block; }
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.x { position: absolute; left: 8px; top: 8px; width: 22px; height: 22px; border-radius: 11px; background: var(--danger); display: flex; align-items: center; justify-content: center; }
.bad figcaption { font-size: 13px; line-height: 1.4; color: var(--text-2); }
</style>
