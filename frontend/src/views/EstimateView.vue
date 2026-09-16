<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'

const router = useRouter()
const route = useRoute()
// accidentId·estimateId 쿼리를 리포트 화면까지 그대로 넘긴다 — 리포트의 PDF 다운로드가 estimateId 로 동작한다
function makeReport() { router.push({ path: '/report/generating', query: route.query }) }
const photo = ref(0)
const notice = ref(false)
const photos = ['정면', '45도', '왼쪽', '오른콽']
const boxes = [
  [{ n: 1, l: 38.1, t: 25.8, w: 40, h: 31.7 }, { n: 3, l: 20, t: 59.2, w: 24.7, h: 17.5 }],
  [{ n: 2, l: 30, t: 30, w: 36, h: 28 }],
  [{ n: 3, l: 14, t: 52, w: 30, h: 22 }],
  [{ n: 4, l: 52, t: 40, w: 32, h: 34 }],
]
const parts = [
  { n: 1, name: '프론트 범퍼', fix: '교환', price: '50만원', range: '42~58만' },
  { n: 2, name: '헤드램프(좌)', fix: '교환', price: '40만원', range: '34~47만' },
  { n: 3, name: '앞휀더(좌)', fix: '판금 후 도장', price: '30만원', range: '24~38만' },
  { n: 4, name: '앞도어(좌)', fix: '사례 부족', na: true },
]
</script>

<template>
  <Screen>
    <AppHeader title="예상 견적" back="/history" line />

    <div class="body scroll" style="padding-top:16px">
      <div class="notice">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 16px"><circle cx="8" cy="8" r="6.6" stroke="#B7791F" stroke-width="1.5"/><path d="M8 4.6v4" stroke="#B7791F" stroke-width="1.5" stroke-linecap="round"/><circle cx="8" cy="11.1" r="0.85" fill="#B7791F"/></svg>
        <span class="flex1">AI 추정치이며 법적 효력이 없습니다</span>
        <button class="more" @click="notice = true">자세히</button>
      </div>

      <div class="price">
        <div class="sub">예상 수리비</div>
        <div class="big">98만 ~ 162만원</div>
        <div class="sub" style="margin-top:8px">중앙값 124만원</div>
        <div class="range"><i></i></div>
        <div class="row" style="margin-top:14px;gap:6px">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M7 1.4l4.6 1.8v3.4c0 3-1.9 5.2-4.6 6-2.7-.8-4.6-3-4.6-6V3.2z" stroke="#4E36E4" stroke-width="1.4" stroke-linejoin="round"/></svg>
          <span style="font-size:12px;color:var(--text-2)">신뢰도 보통 · 유사 사례 34건 기준</span>
        </div>
      </div>

      <div style="margin-top:24px">
        <div class="row between">
          <span class="sec">인식된 손상 부위</span>
          <span class="sub" style="font-size:12px">{{ photo + 1 }} / {{ photos.length }}</span>
        </div>
        <div class="shot">
          <img src="/assets/avante-damage.png" :alt="photos[photo] + ' 손상 사진'">
          <div v-for="b in boxes[photo]" :key="b.n" class="box" :style="{ left: b.l + '%', top: b.t + '%', width: b.w + '%', height: b.h + '%' }">
            <span class="num">{{ b.n }}</span>
          </div>
        </div>
        <div class="thumbs">
          <button v-for="(p, k) in photos" :key="k" class="th" :class="{ on: k === photo }" :aria-label="p" @click="photo = k">
            <img src="/assets/avante-damage.png" alt="">
          </button>
        </div>
      </div>

      <div style="margin-top:24px">
        <div class="sec">부품별 내역</div>
        <div class="stack" style="margin-top:12px;gap:8px">
          <div v-for="p in parts" :key="p.n" class="part">
            <span class="pn" :class="{ na: p.na }">{{ p.n }}</span>
            <span class="flex1" style="display:flex;flex-direction:column;gap:3px">
              <span class="sec">{{ p.name }}</span>
              <span class="sub" style="font-size:12px">{{ p.fix }}</span>
            </span>
            <span v-if="!p.na" style="display:flex;flex-direction:column;align-items:flex-end;gap:3px">
              <span style="font-size:15px;font-weight:700">{{ p.price }}</span>
              <span class="sub" style="font-size:12px">{{ p.range }}</span>
            </span>
            <span v-else class="tag warn">산정 불가</span>
          </div>
        </div>
        <p class="sub" style="margin-top:8px;font-size:11px">총액에 포함되지 않았어요</p>
      </div>
      <div style="height:20px"></div>
    </div>

    <div class="foot">
      <button class="btn" @click="makeReport">리포트 만들기</button>
    </div>

    <BottomSheet v-model="notice">
      <p class="st">AI 견적 고지 안내</p>
      <p class="sd">노카가 제공하는 견적은 AI가 사진과 과거 수리 사례를 바탕으로 추정한 참고 금액입니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있으며, 본 견적은 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.notice { display: flex; align-items: center; gap: 8px; background: var(--warn-bg); border-radius: 8px; padding: 12px 14px; font-size: 12px; color: var(--warn); }
.notice .more { font-size: 12px; font-weight: 500; color: var(--warn); text-decoration: underline; }
.price { margin-top: 14px; background: var(--bg-2); border-radius: 16px; padding: 20px 18px; }
.big { margin-top: 10px; font-size: 28px; font-weight: 700; color: var(--text); letter-spacing: -0.04em; white-space: nowrap; }
.range { position: relative; margin-top: 14px; height: 4px; border-radius: 2px; background: var(--line); }
.range i { position: absolute; left: 20%; right: 25%; top: 0; height: 4px; border-radius: 2px; background: var(--primary); }
.shot { position: relative; margin-top: 12px; width: 100%; aspect-ratio: 4 / 3; border-radius: 12px; overflow: hidden; }
.shot img { width: 100%; height: 100%; object-fit: cover; }
.box { position: absolute; border: 2px solid var(--primary); background: rgba(78,54,228,.08); border-radius: 4px; animation: fadein .2s ease-out; }
.num { position: absolute; left: -2px; top: -2px; width: 20px; height: 20px; border-radius: 10px; background: var(--primary); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.thumbs { margin-top: 8px; display: flex; gap: 6px; }
.th { flex: 0 0 68px; width: 68px; height: 68px; border-radius: 8px; overflow: hidden; border: 1px solid var(--line); }
.th.on { border: 2px solid var(--primary); }
.th img { width: 100%; height: 100%; object-fit: cover; }
.part { border: 1px solid var(--line); border-radius: 12px; padding: 14px; display: flex; align-items: center; gap: 10px; }
.pn { flex: 0 0 20px; width: 20px; height: 20px; border-radius: 10px; background: var(--primary); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.pn.na { background: var(--line-2); }
</style>
