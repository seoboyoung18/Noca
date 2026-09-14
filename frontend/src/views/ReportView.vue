<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'

const router = useRouter()
const read = ref(false)
const toast = ref('')
let tt

function onScroll(e) {
  const el = e.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 24) read.value = true
}
function showToast(msg) {
  toast.value = msg
  clearTimeout(tt)
  tt = setTimeout(() => { toast.value = '' }, 1600)
}

const rows = [
  { name: '프론트 범퍼', sev: '심각', cls: 'red', fix: '교환', amt: '500,000' },
  { name: '헤드램프(좌)', sev: '중간', cls: 'warn', fix: '교환', amt: '400,000' },
  { name: '앞휀더(좌)', sev: '중간', cls: 'warn', fix: '판금·도장', amt: '300,000' },
  { name: '앞도어(좌)', sev: '경미', cls: 'gray', fix: '도장', amt: '산정 불가', na: true },
]
</script>

<template>
  <Screen>
    <AppHeader title="리포트 미리보기" back="/estimate" line>
      <template #right>
        <button class="icn" aria-label="공유" @click="showToast('리포트 링크를 복사했어요')">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 13.5V3.5M6.5 7L10 3.5L13.5 7" stroke="#191F28" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.5 12.5v3a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-3" stroke="#191F28" stroke-width="1.7" stroke-linecap="round"/></svg>
        </button>
      </template>
    </AppHeader>

    <div class="body rbody scroll" @scroll="onScroll">
      <div class="paper">
        <div class="row between">
          <span style="font-size:12px;font-weight:500;color:var(--primary)">AI 차량 파손 견적 리포트</span>
          <span class="row" style="gap:6px">
            <span class="mk"><svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
            <span style="font-size:12px;font-weight:600">노카</span>
          </span>
        </div>
        <div style="margin-top:8px;font-size:18px;font-weight:700;letter-spacing:-0.03em">현대 아반떼 · 전면 사고</div>
        <div class="sub" style="margin-top:6px;font-size:11px">R-20260905-0031 · 2026년 9월 5일 14:32 생성</div>

        <div class="rh">1. 차량 정보</div>
        <div class="rt"><span>차량</span><b>현대 아반떼</b></div>
        <div class="rt"><span>연식</span><b>2021년</b></div>
        <div class="rt"><span>차급</span><b>준중형</b></div>

        <div class="rh">2. 사고 정보</div>
        <div class="rt"><span>접수 일시</span><b>2026.09.05 14:31</b></div>
        <div class="rt"><span>사진</span><b>4장 (정면·45도·왼쪽·오른쪽)</b></div>

        <div class="rh">3. 파손 이미지</div>
        <div class="imgs">
          <img src="/assets/avante-damage.png" alt="">
          <span class="ph-stripe"></span><span class="ph-stripe"></span><span class="ph-stripe"></span>
        </div>
        <p class="sub" style="margin-top:8px;font-size:11px">정면 · 45도 · 왼쪽 · 오른쪽</p>

        <div class="rh">4. 부품별 파손 및 예상 수리비</div>
        <div style="height:12px"></div>
        <div v-for="r in rows" :key="r.name" class="tr">
          <span class="flex1" style="font-size:13px">{{ r.name }}</span>
          <span class="tag" :class="r.cls" style="font-weight:400;padding:3px 8px">{{ r.sev }}</span>
          <span style="flex:0 0 62px;font-size:13px;color:var(--text-2)">{{ r.fix }}</span>
          <span style="flex:0 0 auto;font-size:13px;font-weight:600" :style="r.na ? 'color:var(--warn)' : ''">{{ r.amt }}</span>
        </div>

        <div class="total">
          <div class="row between">
            <span style="font-size:14px;font-weight:700">총 예상 수리비</span>
            <span style="font-size:16px;font-weight:700;letter-spacing:-0.03em">98만 ~ 162만원</span>
          </div>
          <div class="sub" style="margin-top:6px;font-size:12px;text-align:right">중앙값 124만원</div>
          <p class="sub" style="margin-top:8px;font-size:11px">앞도어(좌)는 근거 사례 부족으로 총액에서 제외했습니다</p>
        </div>

        <div class="rh">5. 산정 근거</div>
        <p class="rp">동일 차종·차급의 실제 수리 사례를 기준으로 부품별 중앙값을 산출했습니다. 손상 심각도는 AI 모델의 분류 결과를 3단계로 요약한 값입니다.</p>
        <p style="margin-top:10px;font-size:12px;color:var(--text-2)">참고 사례 34건 · 신뢰도 보통</p>

        <div class="rh">6. 견적서 검증</div>
        <p class="rp" style="color:var(--text-3)">아직 정비소 견적서 검증을 진행하지 않았습니다. 검증 후 리포트를 다시 생성하면 이 항목이 포함됩니다.</p>

        <div class="rnote">본 리포트는 AI가 사진을 바탕으로 추정한 참고 자료이며 법적 효력이 없습니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있습니다.</div>

        <p class="rend" :class="{ ok: read }">{{ read ? '확인 완료 · PDF로 저장할 수 있어요' : '내용을 끝까지 확인하면 다운로드할 수 있어요' }}</p>
      </div>
    </div>

    <div class="foot">
      <button class="btn" :disabled="!read" @click="showToast('리포트 PDF를 저장했어요')">
        <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M9 2.5v9M5.5 8L9 11.5L12.5 8" stroke="#FFFFFF" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M3.5 13.5h11" stroke="#FFFFFF" stroke-width="1.7" stroke-linecap="round"/></svg>
        PDF 다운로드
      </button>
    </div>
    <Toast :show="!!toast">{{ toast }}</Toast>
  </Screen>
</template>

<style scoped>
.rbody { background: var(--bg-2); padding: 16px; }
.paper { background: var(--white); border: 1px solid var(--line); border-radius: 12px; padding: 18px; }
.mk { width: 20px; height: 20px; border-radius: 5px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.rh { margin-top: 24px; font-size: 14px; font-weight: 700; color: var(--text); padding-bottom: 8px; border-bottom: 1px solid var(--text); }
.rt { height: 40px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px dashed var(--line); font-size: 13px; }
.rt span { color: var(--text-3); }
.rt b { font-weight: 600; color: var(--text); text-align: right; }
.imgs { margin-top: 12px; display: flex; gap: 6px; }
.imgs img, .imgs span { flex: 1 1 0; aspect-ratio: 1; border-radius: 8px; object-fit: cover; display: block; min-width: 0; }
.tr { height: 44px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); }
.total { margin-top: 16px; background: var(--bg-2); border-radius: 8px; padding: 14px; }
.rp { margin-top: 10px; font-size: 13px; line-height: 1.6; color: var(--text-2); }
.rnote { margin-top: 20px; background: var(--warn-bg); border-radius: 8px; padding: 14px; font-size: 12px; line-height: 1.6; color: var(--warn); }
.rend { margin-top: 16px; font-size: 12px; color: var(--text-3); text-align: center; }
.rend.ok { color: var(--primary); font-weight: 500; }
</style>
