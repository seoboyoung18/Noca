<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'

const router = useRouter()
const steps = [
  { title: '정면', desc: '파손 부위가 화면의 절반 이상 차도록<br>가까이서 찍어주세요' },
  { title: '45도', desc: '파손 부위를 45도 방향에서<br>비스듬히 찍어주세요' },
  { title: '왼쪽', desc: '파손 부위의 왼쪽으로 이동해<br>측면이 보이도록 찍어주세요' },
  { title: '오른쪽', desc: '파손 부위의 오른쪽으로 이동해<br>측면이 보이도록 찍어주세요' },
]
const i = ref(0)
const last = computed(() => i.value === steps.length - 1)
function next() { last.value ? router.push('/claim/upload') : i.value++ }
function back() { i.value > 0 ? i.value-- : router.push('/claim/vehicle') }
</script>

<template>
  <Screen>
    <AppHeader title="촬영 가이드">
      <template #left>
        <button class="back" aria-label="뒤로가기" @click="back">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5L6 10l6.5 6.5" stroke="#191F28" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </template>
      <template #right>
        <button class="act" @click="router.push('/claim/upload')">건너뛰기</button>
      </template>
    </AppHeader>
    <div class="prog"><i style="width:50%"></i></div>

    <div class="body" style="padding-top:20px">
      <p class="step">2 / 4 · 촬영 가이드</p>
      <div :key="i" class="slide">
        <div class="shot">
          <img src="/assets/front-shot-example.png" :alt="steps[i].title + ' 촬영 예시'">
          <span class="badge">{{ i + 1 }} / {{ steps.length }}</span>
        </div>
        <div class="center" style="margin-top:24px">
          <div class="gt">{{ steps[i].title }}</div>
          <p class="gd" v-html="steps[i].desc"></p>
        </div>
      </div>
      <div class="dots">
        <span v-for="(s, k) in steps" :key="k" :class="{ on: k === i }" @click="i = k"></span>
      </div>
    </div>

    <div class="foot">
      <button class="btn" @click="next">{{ last ? '사진 올리기' : '다음 각도' }}</button>
    </div>
  </Screen>
</template>

<style scoped>
.slide { animation: fadein .25s ease-out; }
.shot { position: relative; margin-top: 20px; width: 100%; aspect-ratio: 4 / 3; border-radius: 16px; background: var(--bg-2); overflow: hidden; display: flex; align-items: center; justify-content: center; }
.shot img { width: 100%; height: 100%; object-fit: contain; }
.badge { position: absolute; left: 12px; top: 12px; background: var(--primary); color: #fff; font-size: 12px; font-weight: 500; padding: 5px 10px; border-radius: 6px; }
.gt { font-size: 20px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; }
.gd { margin-top: 8px; font-size: 14px; line-height: 1.5; color: var(--text-3); }
.dots { margin-top: 24px; display: flex; gap: 6px; justify-content: center; align-items: center; }
.dots span { width: 6px; height: 6px; border-radius: 3px; background: var(--line-2); transition: .25s; cursor: pointer; }
.dots span.on { width: 18px; background: var(--primary); }
</style>
