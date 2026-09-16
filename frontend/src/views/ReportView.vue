<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import { fetchAccidentEstimates, fetchEstimateReport } from '../lib/api'
import { useEstimatePdf } from '../lib/estimatePdf'
import { carClassLabel, vehicleName, vehicleTypeLabel } from '../data/vehicles'
import { angleLabel } from '../data/accidents'
import {
  confidenceLabel, damageTypeLabel, fallbackLabel, formatDateTime, wonComma, wonOne, wonRange,
} from '../data/estimates'

/* ===== 리포트 미리보기 (S08) — GET /api/estimates/{estimateId}/report =====
 * 견적 기준 경로다. PDF(/api/estimates/{id}/pdf)와 1:1 이라 같은 estimateId 로 본문과 파일을 받는다.
 * 서버가 견적·근거·검증·고지 문구를 조립해 주므로 화면은 새로 계산하지 않고 그대로 그린다 — 화면과 PDF 숫자가 어긋나지 않게.
 * estimateId 는 ?estimateId= 로 받고, ?accidentId= 만 있으면 사고의 최신 견적에서 찾는다. 둘 다 없으면(목업 진입) 서버 없이 목업.
 */
const route = useRoute()
const router = useRouter()
function goHome() { router.replace('/home') } // 리포트는 접수 흐름의 끝 — 뒤로가기로 생성 중 화면에 되돌아가지 않게

const estimateId = ref(Number(route.query.estimateId) || null)
const accidentId = Number(route.query.accidentId) || null
const loading = ref(true)
const error = ref('')
const report = ref(null)

async function load() {
  if (!estimateId.value && !accidentId) { report.value = MOCK_REPORT; loading.value = false; return }
  loading.value = true
  error.value = ''
  try {
    if (!estimateId.value) {
      estimateId.value = (await fetchAccidentEstimates(accidentId))[0]?.estimateId ?? null
      if (!estimateId.value) { error.value = '아직 산출된 견적이 없어 리포트를 만들 수 없어요.'; return }
    }
    report.value = await fetchEstimateReport(estimateId.value)
  } catch (e) {
    if (e.status === 401) return
    error.value = e.status === 404 ? '견적을 찾을 수 없어요.' : e.status === 0 ? e.message : '리포트를 불러오지 못했어요.'
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ----- 표시용 파생값 ----- */
const est = computed(() => report.value?.estimate || null)
const vehicle = computed(() => report.value?.vehicle || null)
const items = computed(() => est.value?.items || [])
const naItems = computed(() => items.value.filter((it) => it.itemMedian == null))
const images = computed(() => report.value?.images || [])
const angles = computed(() => images.value.map((im) => angleLabel(im.angleCode)).join(' · '))
const basisItems = computed(() => (report.value?.basis?.items || []).filter((b) => b.basisAvailable && b.narrative))
const fallback = computed(() => fallbackLabel(report.value?.basis?.items?.find((b) => b.fallbackStage)?.fallbackStage))
const validation = computed(() => report.value?.validation || null)
const title = computed(() => vehicleName(vehicle.value) || '차량 정보 없음')
const subtitle = computed(() => (items.value.length ? `손상 ${items.value.length}곳` : est.value && !est.value.estimable ? '산정 불가' : ''))

/* ----- 끝까지 읽어야 PDF 활성 ----- */
const read = ref(false)
function onScroll(e) {
  const el = e.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 24) read.value = true
}

const toast = ref('')
let tt
function showToast(msg, ms = 1600) { toast.value = msg; clearTimeout(tt); tt = setTimeout(() => { toast.value = '' }, ms) }

/* ===== PDF 다운로드 — 견적 id 기준 (요청 → 생성 대기 → 302 다운로드) ===== */
const { step: pdfStep, busyId: pdfBusyId, message: pdfMessage, download: downloadPdf } = useEstimatePdf()
const pdfBusy = computed(() => pdfBusyId.value !== null)
const pdfLabel = computed(() =>
  pdfStep.value === 'checking' ? '준비 중…' : pdfStep.value === 'generating' ? 'PDF 생성 중…' : 'PDF 다운로드')
watch(pdfMessage, (m) => { if (m) showToast(m, pdfStep.value === 'error' ? 2800 : 1600) })
// 정식 PDF 는 서버(EstimatePdfController)만 만든다 — 견적이 없으면 받을 파일이 없다고 안내한다
function onPdf() {
  if (!estimateId.value) return showToast('아직 산출된 견적이 없어 PDF를 만들 수 없어요.', 2400)
  downloadPdf(estimateId.value)
}

/* ----- 목업 (쿼리 없음) — 서버 응답 모양 그대로 ----- */
const MOCK_REPORT = {
  vehicle: { manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021 },
  accident: { accidentId: 1, createdAt: '2026-09-05T05:31:00Z' },
  images: [{ imageId: 1, angleCode: 'DAMAGE_CLOSE', overlayUrl: '/assets/avante-damage.png' }],
  estimate: {
    estimateId: 1, estimable: true, totalMin: 980000, totalMedian: 1240000, totalMax: 1620000, refCaseTotal: 34, confidenceGrade: 'MEDIUM', notices: [],
    items: [
      { partCode: 'FRONT_BUMPER', partNameKo: '프론트 범퍼', damageType: 'Breakage', repairMethodDisplayName: '교환', itemMedian: 500000, itemMin: 420000, itemMax: 580000, refCaseCount: 14, lowConfidence: false },
      { partCode: 'HEAD_LAMP_L', partNameKo: '헤드램프(좌)', damageType: 'Breakage', repairMethodDisplayName: '교환', itemMedian: 400000, itemMin: 340000, itemMax: 470000, refCaseCount: 11, lowConfidence: false },
      { partCode: 'FRONT_FENDER_L', partNameKo: '앞휀더(좌)', damageType: 'Crushed', repairMethodDisplayName: '판금 후 도장', itemMedian: 300000, itemMin: 240000, itemMax: 380000, refCaseCount: 9, lowConfidence: false },
      { partCode: 'FRONT_DOOR_L', partNameKo: '앞도어(좌)', damageType: 'Scratched', repairMethodDisplayName: '도장', itemMedian: null, itemMin: null, itemMax: null, refCaseCount: 0, lowConfidence: true },
    ],
  },
  basis: { items: [
    { partCode: 'FRONT_BUMPER', partNameKo: '프론트 범퍼', basisAvailable: true, fallbackStage: 'CAR_CLASS', narrative: '동일 차급 사례 14건의 교환 비용 중앙값으로 산정했습니다.' },
    { partCode: 'HEAD_LAMP_L', partNameKo: '헤드램프(좌)', basisAvailable: true, fallbackStage: 'CAR_CLASS', narrative: '동일 차급 사례 11건의 교환 비용 중앙값으로 산정했습니다.' },
  ] },
  validation: null,
  legalNotice: '본 리포트는 AI가 사진을 바탕으로 추정한 참고 자료이며 법적 효력이 없습니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있습니다.',
  guidanceNotice: null,
  generatedAt: '2026-09-05T05:32:00Z',
}
</script>

<template>
  <Screen>
    <!-- 공유(링크 복사) 버튼은 서버 공유 링크 API(3차·미구현)가 없어 제거. 생기면 #right 슬롯에 다시 둔다 -->
    <AppHeader title="리포트 미리보기" back="/estimate" back-history line />

    <!-- 로딩 -->
    <div v-if="loading" class="body col" role="status">
      <p class="sub center" style="margin:auto 0">리포트를 불러오고 있어요…</p>
    </div>

    <!-- 오류 · 견적 없음 -->
    <div v-else-if="error" class="body col">
      <div class="empty">
        <b>{{ error }}</b>
        <p>견적이 산출된 뒤 다시 열어 주세요</p>
        <div class="row" style="margin-top:16px;gap:8px">
          <button class="btn outline" style="width:auto;padding:0 20px;height:44px" @click="load">다시 시도</button>
          <button class="btn" style="width:auto;padding:0 20px;height:44px" @click="goHome">홈으로</button>
        </div>
      </div>
    </div>

    <div v-else class="body rbody scroll" @scroll="onScroll">
      <div class="paper">
        <div class="row between">
          <span style="font-size:12px;font-weight:500;color:var(--primary)">AI 차량 파손 견적 리포트</span>
          <span class="row" style="gap:6px">
            <span class="mk"><svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
            <span style="font-size:12px;font-weight:600">노카</span>
          </span>
        </div>
        <div style="margin-top:8px;font-size:18px;font-weight:700;letter-spacing:-0.03em">{{ title }}<template v-if="subtitle"> · {{ subtitle }}</template></div>
        <div class="sub" style="margin-top:6px;font-size:11px">{{ formatDateTime(report.generatedAt) }} 생성<template v-if="estimateId"> · 견적 #{{ estimateId }}</template></div>

        <div class="rh">1. 차량 정보</div>
        <div class="rt"><span>차량</span><b>{{ title }}</b></div>
        <div v-if="vehicle?.modelYear" class="rt"><span>연식</span><b>{{ vehicle.modelYear }}년</b></div>
        <div v-if="vehicle?.carClass" class="rt"><span>차급</span><b>{{ carClassLabel(vehicle.carClass) }}</b></div>
        <div v-if="vehicle?.vehicleType" class="rt"><span>차종</span><b>{{ vehicleTypeLabel(vehicle.vehicleType) }}</b></div>

        <div class="rh">2. 사고 정보</div>
        <div class="rt"><span>접수 일시</span><b>{{ formatDateTime(report.accident?.createdAt, true) || '-' }}</b></div>
        <div class="rt"><span>사진</span><b>{{ images.length }}장<template v-if="angles"> ({{ angles }})</template></b></div>

        <div class="rh">3. 파손 이미지</div>
        <div v-if="images.length" class="imgs" :class="{ one: images.length === 1 }">
          <template v-for="im in images" :key="im.imageId">
            <img v-if="im.overlayUrl" :src="im.overlayUrl" :alt="angleLabel(im.angleCode) + ' 분석 이미지'">
            <span v-else class="ph-stripe noimg">분석 이미지 없음</span>
          </template>
        </div>
        <p class="sub" style="margin-top:8px;font-size:11px">{{ angles || '분석에 쓰인 사진이 없어요' }}</p>

        <div class="rh">4. 부품별 파손 및 예상 수리비</div>
        <template v-if="est && est.estimable">
          <div style="height:12px"></div>
          <div v-for="it in items" :key="it.partCode" class="tr">
            <span class="flex1" style="font-size:13px">{{ it.partNameKo || it.partCode }}</span>
            <span v-if="damageTypeLabel(it.damageType)" class="tag gray" style="font-weight:400;padding:3px 8px">{{ damageTypeLabel(it.damageType) }}</span>
            <span style="flex:0 0 auto;max-width:78px;font-size:12px;color:var(--text-2);text-align:right">{{ it.repairMethodDisplayName || it.repairMethod || '-' }}</span>
            <span style="flex:0 0 68px;text-align:right;font-size:13px;font-weight:600" :style="it.itemMedian == null ? 'color:var(--warn)' : ''">{{ it.itemMedian == null ? '산정 불가' : wonComma(it.itemMedian) }}</span>
          </div>

          <div class="total">
            <div class="row between">
              <span style="font-size:14px;font-weight:700">총 예상 수리비</span>
              <span style="font-size:16px;font-weight:700;letter-spacing:-0.03em">{{ wonRange(est.totalMin, est.totalMax) || '-' }}</span>
            </div>
            <div v-if="est.totalMedian != null" class="sub" style="margin-top:6px;font-size:12px;text-align:right">중앙값 {{ wonOne(est.totalMedian) }}</div>
            <p v-if="naItems.length" class="sub" style="margin-top:8px;font-size:11px">{{ naItems.map((i) => i.partNameKo).join(', ') }}는 근거 사례 부족으로 총액에서 제외했습니다</p>
            <p v-for="n in est.notices || []" :key="n.code" class="sub" style="margin-top:6px;font-size:11px">{{ n.message }}</p>
          </div>
        </template>
        <template v-else-if="est">
          <p class="rp" style="color:var(--warn)">예상 수리비를 산정할 수 없었습니다. {{ est.nonEstimableReason || '참조할 수리 사례가 부족합니다.' }}</p>
        </template>

        <div class="rh">5. 산정 근거</div>
        <template v-if="basisItems.length">
          <p v-for="b in basisItems" :key="b.partCode" class="rp"><b style="color:var(--text)">{{ b.partNameKo }}</b> — {{ b.narrative }}</p>
        </template>
        <p v-else class="rp">동일 차종·차급의 실제 수리 사례를 기준으로 부품별 중앙값을 산출했습니다.</p>
        <p v-if="est?.refCaseTotal != null || confidenceLabel(est?.confidenceGrade) || fallback" style="margin-top:10px;font-size:12px;color:var(--text-2)">
          <template v-if="est?.refCaseTotal != null">참고 사례 {{ est.refCaseTotal }}건</template>
          <template v-if="confidenceLabel(est?.confidenceGrade)"> · 신뢰도 {{ confidenceLabel(est.confidenceGrade) }}</template>
          <template v-if="fallback"> · {{ fallback }}</template>
        </p>

        <!-- 견적서 검증 — 연결된 검증 결과가 있을 때만 (S15P21A307-338) -->
        <template v-if="validation">
          <div class="rh">6. 견적서 검증</div>
          <div class="rt"><span>판정</span><b>{{ validation.gradeDisplayName || validation.grade || '-' }}</b></div>
          <div v-if="validation.claimedTotal != null" class="rt"><span>정비소 청구액</span><b>{{ wonComma(validation.claimedTotal) }}원</b></div>
          <div v-if="validation.aiTotalMin != null" class="rt"><span>AI 예상 범위</span><b>{{ wonRange(validation.aiTotalMin, validation.aiTotalMax) }}</b></div>
          <p v-if="validation.summary" class="rp">{{ validation.summary }}</p>
        </template>

        <div class="rnote">{{ report.legalNotice }}</div>
        <p v-if="report.guidanceNotice" class="rp" style="font-size:12px">{{ report.guidanceNotice }}</p>

        <p class="rend" :class="{ ok: read }">{{ read ? '확인 완료 · PDF로 저장할 수 있어요' : '내용을 끝까지 확인하면 다운로드할 수 있어요' }}</p>
      </div>
    </div>

    <div v-if="!loading && !error" class="foot row">
      <button class="btn" style="flex:1 1 auto" :disabled="!read || pdfBusy" :aria-busy="pdfBusy" @click="onPdf">
        <svg v-if="pdfBusy" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite"><circle cx="9" cy="9" r="7" stroke="rgba(255,255,255,.35)" stroke-width="2"/><circle cx="9" cy="9" r="7" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-dasharray="12 32" transform="rotate(-90 9 9)"/></svg>
        <svg v-else width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M9 2.5v9M5.5 8L9 11.5L12.5 8" stroke="#FFFFFF" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M3.5 13.5h11" stroke="#FFFFFF" stroke-width="1.7" stroke-linecap="round"/></svg>
        {{ pdfLabel }}
      </button>
      <!-- 홈으로 — 다운로드 여부와 무관하게 언제나 나갈 수 있어야 하므로 read 조건을 걸지 않는다 -->
      <button class="btn outline home" aria-label="홈으로" @click="goHome">
        <svg width="22" height="22" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M2.5 8.5L9 3l6.5 5.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M4.5 7.5v7h9v-7" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M7.5 14.5v-4h3v4" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>
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
.rt { min-height: 40px; padding: 8px 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px dashed var(--line); font-size: 13px; }
.rt span { color: var(--text-3); flex: 0 0 auto; }
.rt b { font-weight: 600; color: var(--text); text-align: right; }
.imgs { margin-top: 12px; display: flex; gap: 6px; }
.imgs img, .imgs span { flex: 1 1 0; aspect-ratio: 1; border-radius: 8px; object-fit: cover; display: block; min-width: 0; }
.imgs.one img, .imgs.one span { aspect-ratio: 4 / 3; }
.imgs .noimg { display: flex; align-items: center; justify-content: center; font-size: 12px; color: var(--text-3); }
.tr { min-height: 44px; padding: 6px 0; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); }
.total { margin-top: 16px; background: var(--bg-2); border-radius: 8px; padding: 14px; }
.rp { margin-top: 10px; font-size: 13px; line-height: 1.6; color: var(--text-2); }
.rnote { margin-top: 20px; background: var(--warn-bg); border-radius: 8px; padding: 14px; font-size: 12px; line-height: 1.6; color: var(--warn); }
.rend { margin-top: 16px; font-size: 12px; color: var(--text-3); text-align: center; }
.rend.ok { color: var(--primary); font-weight: 500; }
.home { flex: 0 0 52px; width: 52px; padding: 0; color: var(--text-2); } /* 아이콘만 — 이름은 aria-label 로 */
</style>
