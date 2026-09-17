<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import { useAccidentStore } from '../stores/accidents'
import { useEstimatePdf } from '../lib/estimatePdf'
import { vehicleName } from '../data/vehicles'
import { AUTH_GUARD_OFF } from '../router'
import { fetchEstimate } from '../lib/api'
import { accidentRoute, accidentStageText, accidentStatus, damageSummaryText, groupAccidents } from '../data/accidents'

/* ===== 사고 이력 (S12d) — GET /api/accidents/me =====
 * 서버가 createdAt 내림차순 페이지로 주고, 그룹("이번 주"·"8월")·한글 배지·금액 포맷은 FE 가 만든다.
 * 썸네일은 10분짜리 서명 URL 이라 화면에 들어올 때마다 목록을 새로 받는다.
 * 삭제(편집 모드)는 서버에 사고 삭제 API 가 없어 두지 않는다 — 사고는 견적·PDF 의 근거라 보존된다.
 * 행 구성(S15P21A307-531): 사고마다 카드 하나 — 썸네일 | 차량명+상태 배지 · 사고 설명 | 오른쪽 세로 중앙에 PDF 아이콘 버튼.
 * 예상 금액·사진 장수·접수일은 행에서 뺐다(금액은 견적 화면·홈 카드에서, 날짜는 그룹 라벨로).
 * 사고 설명은 목록 API 에 없어 견적이 있는 행만 GET /api/estimates/{id} 로 항목을 받아 조립한다(행당 1회, 화면 밖 캐시).
 */
const router = useRouter()
const store = useAccidentStore()
const groups = computed(() => groupAccidents(store.items))
const broken = reactive({}) // 만료·404 로 깨진 썸네일 accidentId → 자리표시로 대체

onMounted(() => store.load(true))

function open(a) { router.push(accidentRoute(a)) }

/* ===== 사고 설명 — 견적 항목의 부위·손상 유형으로 조립 =====
 * 견적은 확정 뒤 바뀌지 않으므로 estimateId 별로 모듈 캐시에 둔다. 실패한 행은 단계 문구로 대신한다.
 */
const descCache = new Map() // estimateId → 문구
const desc = reactive({}) // accidentId → 문구
const MOCK_DESC = { 1: '프론트 범퍼, 헤드램프(좌) 외 2곳 파손' } // 가드 off 목업(견적 1)
async function loadDesc(a) {
  if (!a.estimateId) { desc[a.accidentId] = accidentStageText(a.status); return }
  if (descCache.has(a.estimateId)) { desc[a.accidentId] = descCache.get(a.estimateId); return }
  desc[a.accidentId] = '손상 정보 불러오는 중…'
  let text = ''
  try {
    text = AUTH_GUARD_OFF ? (MOCK_DESC[a.estimateId] || '') : damageSummaryText((await fetchEstimate(a.estimateId)).items)
  } catch { text = '' }
  text = text || accidentStageText(a.status)
  descCache.set(a.estimateId, text)
  desc[a.accidentId] = text
}
watch(() => store.items, (items) => { for (const a of items) if (!(a.accidentId in desc)) loadDesc(a) }, { immediate: true })

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
// 버튼은 아이콘 중심이라 문구는 aria-label·title 로만 붙인다
const pdfLabel = (a) => (pdfBusyFor(a) ? (pdfStep.value === 'generating' ? 'PDF 생성 중…' : '준비 중…') : a.estimateId ? 'PDF 파일 받기' : '견적이 없어 PDF 를 받을 수 없어요')
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
        <div v-for="a in g.items" :key="a.accidentId" class="card item clickable" role="link" tabindex="0" @click="open(a)" @keydown.enter="open(a)">
          <!-- 썸네일: 서명 URL 이 없거나(사진 없음·전처리 전) 깨지면 자리표시 -->
          <span class="th">
            <img v-if="a.thumbnailUrl && !broken[a.accidentId]" :src="a.thumbnailUrl" alt="" @error="broken[a.accidentId] = true">
            <svg v-else width="44" height="24" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
          </span>
          <span class="flex1" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px;min-width:0">
            <!-- 차량명 오른쪽에 상태 배지. 차량명이 길면 말줄임하고 배지는 줄지 않는다 -->
            <span class="row" style="gap:8px;max-width:100%">
              <span class="nowrap" style="font-size:16px;font-weight:700;overflow:hidden;text-overflow:ellipsis">{{ vehicleName(a) }}</span>
              <span class="tag" style="flex:0 0 auto" :class="accidentStatus(a.status).cls">{{ accidentStatus(a.status).text }}</span>
            </span>
            <span class="sub" style="font-size:13px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">{{ desc[a.accidentId] || accidentStageText(a.status) }}</span>
          </span>
          <!-- PDF 받기 — 아이콘 버튼(문서+내려받기 화살표, 아래 "PDF"). 견적이 없는 행도 같은 자리에 비활성으로 둬 행마다 배치가 같게 -->
          <button class="pdf" :class="{ busy: pdfBusyFor(a) }" :disabled="!a.estimateId || pdfBusyId !== null" :aria-busy="pdfBusyFor(a)"
            :aria-label="pdfLabel(a)" :title="pdfLabel(a)" @click.stop="pdf(a)">
            <span v-if="pdfBusyFor(a)" class="spin" aria-hidden="true"></span>
            <svg v-else width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6.5 2.5h7.5l4.5 4.5v12a2 2 0 0 1-2 2h-10a2 2 0 0 1-2-2v-14.5a2 2 0 0 1 2-2z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
              <path d="M14 2.5v4.5h4.5" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
              <path d="M12 10.5v6M9.3 14.2L12 16.9l2.7-2.7" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span class="pl">PDF</span>
          </button>
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
.item { margin-top: 8px; padding: 14px; display: flex; align-items: center; gap: 14px; }
.item.clickable { cursor: pointer; }
.th { flex: 0 0 76px; width: 76px; height: 76px; border-radius: 12px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.pdf { flex: 0 0 48px; width: 48px; height: 48px; border: 1px solid var(--primary-200); border-radius: 12px; background: var(--primary-50); color: var(--primary); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1px; }
.pdf:hover { background: var(--primary-100); }
.pdf:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
.pl { font-size: 9px; font-weight: 700; letter-spacing: 0.02em; line-height: 1; }
.spin { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--primary-200); border-top-color: var(--primary); animation: dcspin .8s linear infinite; }
</style>
