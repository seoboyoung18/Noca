<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import { useAccidentStore } from '../stores/accidents'
import { useEstimatePdf } from '../lib/estimatePdf'
import { vehicleName } from '../data/vehicles'
import { accidentCost, accidentDate, accidentRoute, accidentStatus, groupAccidents } from '../data/accidents'

/* ===== 사고 이력 (S12d) — GET /api/accidents/me =====
 * 서버가 createdAt 내림차순 페이지로 주고, 그룹("이번 주"·"8월")·한글 배지·금액 포맷은 FE 가 만든다.
 * 썸네일은 10분짜리 서명 URL 이라 화면에 들어올 때마다 목록을 새로 받는다.
 * 삭제(편집 모드)는 서버에 사고 삭제 API 가 없어 두지 않는다 — 사고는 견적·PDF 의 근거라 보존된다.
 */
const router = useRouter()
const store = useAccidentStore()
const groups = computed(() => groupAccidents(store.items))
const broken = reactive({}) // 만료·404 로 깨진 썸네일 accidentId → 자리표시로 대체

onMounted(() => store.load(true))

function open(a) { router.push(accidentRoute(a)) }

/* ===== PDF 파일 받기 — 견적 id 기준 (요청 → 생성 대기 → 302 다운로드) =====
 * 판정은 estimateId !== null 하나(§5-3). null 이면 받을 PDF 가 없다는 뜻이라 버튼을 끈다.
 * 한 번에 한 건만 진행하고, 진행 중인 행만 "생성 중…" 으로 표시한다.
 */
const toast = ref('')
let tt
function showToast(msg, ms = 1600) {
  toast.value = msg
  clearTimeout(tt)
  tt = setTimeout(() => { toast.value = '' }, ms)
}
const { step: pdfStep, busyId: pdfBusyId, message: pdfMessage, download: downloadPdf } = useEstimatePdf()
watch(pdfMessage, (m) => { if (m) showToast(m, pdfStep.value === 'error' ? 2800 : 1600) })
function pdf(a) { if (a.estimateId) downloadPdf(a.estimateId) }
// estimateId 가 null 인 행과 busyId(null) 가 같다고 판정되지 않도록 estimateId 존재를 먼저 본다
const pdfBusyFor = (a) => !!a.estimateId && pdfBusyId.value === a.estimateId
const pdfLabel = (a) => (pdfBusyFor(a) ? (pdfStep.value === 'generating' ? 'PDF 생성 중…' : '준비 중…') : 'PDF 파일 받기')
</script>

<template>
  <Screen>
    <!-- 홈("전체 보기")과 마이페이지(통계 카드) 두 곳에서 들어오므로 뒤로가기는 거쳐 온 화면으로. 직접 진입이면 홈 -->
    <AppHeader title="사고 이력" back="/home" back-history />

    <!-- 첫 로딩 -->
    <div v-if="!store.loaded && store.loading" class="body col" role="status">
      <p class="sub center" style="margin:auto 0">사고 이력을 불러오고 있어요…</p>
    </div>

    <!-- 첫 로딩 실패 -->
    <div v-else-if="!store.loaded && store.error" class="body col">
      <div class="empty">
        <b>{{ store.error }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="store.load(true)">다시 시도</button>
      </div>
    </div>

    <!-- 목록 -->
    <div v-else-if="store.items.length" class="body scroll" style="padding-top:20px">
      <div class="row between">
        <span class="lbl">총 {{ store.total ?? store.items.length }}건</span>
      </div>
      <template v-for="g in groups" :key="g.label">
        <div class="lbl" style="margin-top:16px">{{ g.label }}</div>
        <div class="card" style="margin-top:8px;padding:0 12px">
          <div v-for="a in g.items" :key="a.accidentId" class="item clickable" role="link" tabindex="0" @click="open(a)" @keydown.enter="open(a)">
            <!-- 썸네일: 서명 URL 이 없거나(사진 없음·전처리 전) 깨지면 자리표시 -->
            <span class="th">
              <img v-if="a.thumbnailUrl && !broken[a.accidentId]" :src="a.thumbnailUrl" alt="" @error="broken[a.accidentId] = true">
              <svg v-else width="44" height="24" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
            </span>
            <span class="flex1" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px;min-width:0">
              <span class="nowrap" style="font-size:16px;font-weight:700">{{ vehicleName(a) }}</span>
              <span class="row" style="gap:6px">
                <span class="sub nowrap" style="font-size:12px">{{ accidentDate(a.createdAt) }}</span>
                <span class="tag md" :class="accidentStatus(a.status).cls">{{ accidentStatus(a.status).text }}</span>
              </span>
              <!-- 셋째 줄: 견적이 있으면 금액, 없으면 올린 사진 장수. estimatedCost* 가 null 이면 "0원" 으로 그리지 않는다 -->
              <span v-if="accidentCost(a)" class="cost">예상 {{ accidentCost(a) }}</span>
              <span v-else-if="a.imageCount" class="sub" style="font-size:12px">사진 {{ a.imageCount }}장</span>
            </span>
            <button class="pdf" :disabled="!a.estimateId || pdfBusyId !== null" :aria-busy="pdfBusyFor(a)" @click.stop="pdf(a)">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M8 2.5v8M4.5 7.5L8 11l3.5-3.5M3 13.5h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>{{ pdfLabel(a) }}
            </button>
          </div>
        </div>
      </template>

      <!-- 다음 페이지 -->
      <button v-if="store.hasNext" class="btn outline muted" style="margin-top:16px" :disabled="store.loading" @click="store.loadMore()">
        {{ store.loading ? '불러오는 중…' : '더 보기' }}
      </button>
      <p v-if="store.loaded && store.error" class="sub center" style="margin-top:12px;color:var(--danger-2)" role="alert">{{ store.error }}</p>
      <div style="height:24px"></div>
    </div>

    <!-- 비어 있음 -->
    <div v-else class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>아직 접수한 사고가 없어요</b>
        <p>사고가 나면 사진을 찍어<br>예상 수리비를 확인해보세요</p>
        <button class="btn" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="router.push('/claim/vehicle')">첫 견적 받기</button>
      </div>
    </div>
    <div class="spacer"></div>
    <Toast :show="!!toast">{{ toast }}</Toast>
  </Screen>
</template>

<style scoped>
.item { padding: 16px 0; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 12px; }
.item:last-child { border-bottom: 0; }
.item.clickable { cursor: pointer; }
.th { flex: 0 0 60px; width: 60px; height: 48px; border-radius: 10px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.cost { font-size: 13px; font-weight: 600; color: var(--primary); }
.pdf { flex: 0 0 auto; height: 40px; padding: 0 10px; white-space: nowrap; border: 1px solid var(--primary-200); border-radius: 10px; background: var(--primary-50); font-size: 13px; font-weight: 600; color: var(--primary); display: flex; align-items: center; gap: 5px; }
.pdf:hover { background: var(--primary-100); }
.pdf:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
</style>
