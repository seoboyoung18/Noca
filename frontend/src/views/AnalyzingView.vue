<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import { fetchAnalysisProgress, requestAnalysis, resolveAnalysisPart } from '../lib/api'
import { ANALYSIS_STAGES } from '../data/accidents'

/*
 * 분석 중 (S07) — 진행 게이지는 <b>예상 소요 시간</b>으로 순차 진행시키고, 서버 단계는 그보다 앞서 있을 때만 따른다.
 *   POST /api/accidents/{id}/analysis  → 접수(202). 409 면 이미 진행 중이라 조회로 이어 간다
 *   GET  /api/accidents/{id}/analysis  → 2초 간격 폴링. status·doneStages·currentStage·startedAt
 *   COMPLETED → 4/4 를 채운 뒤 결과 화면 · FAILED → 실패 상태(다시 시도 가능)
 *
 * 서버의 doneStages 는 실제로는 1에 머물다 끝날 때 4로 뛴다(단계 전이가 콜백 한 번에 일어난다). 그대로 그리면 게이지가 1단계에서
 * 멈춰 있다가 완료 때 4로 점프해 "멈췄나" 싶어진다. 그래서 단계마다 예상 소요 시간(EXPECTED_SEC, 분석 응답 시간 측정 S15P21A307-159
 * 기준 — 8장 중앙값 14초·최대 18초에 견적 구간을 더한 값)을 두고, 시작 시각(startedAt)부터 흐른 시간으로 단계를 순차 진행시킨다.
 *  - 시간으로는 3단계(마지막 단계 진행 중)까지만 간다 — 4/4 는 서버가 COMPLETED 라고 할 때만
 *  - 마지막 단계는 예상보다 오래 걸려도 전체 95% 를 향해 천천히 차오른다(1 − e^(−t/τ)) — 멈춘 것처럼 보이지 않게, 다 된 것처럼도 보이지 않게
 *  - 서버가 시간 예측보다 앞서 있으면(doneStages 가 더 큼) 그쪽을 따른다. 뒤처져 있으면 무시한다
 *  - QUEUED(순서 대기) 동안은 시계를 돌리지 않는다
 * accidentId 는 라우트 쿼리(?accidentId=)로 받는다. 사고 접수·사진 업로드 화면이 연결되면 그쪽에서 넘겨 준다.
 * 쿼리가 없으면(프로토타입 경로) 서버 없이 모의 단계 시퀀스를 흘려 화면 전환만 보여 준다.
 */
const router = useRouter()
const route = useRoute()
const accidentId = Number(route.query.accidentId) || null
// 부위 확정 재분석(S15P21A307-564): 견적 화면에서 사용자가 고른 부품으로 다시 산정할 때 estimateId·partCode 가 함께 온다
const resolveEstimateId = Number(route.query.estimateId) || null
const resolvePartCode = typeof route.query.partCode === 'string' ? route.query.partCode : ''

// 서버 단계 코드(AnalysisStageType 선언 순서) → 화면 문구
// 단계 문구는 홈 "진행 중" 카드와 공유한다 (data/accidents.js)
const STAGES = ANALYSIS_STAGES
const TOTAL = STAGES.length
const POLL_MS = 2000
/** 단계별 예상 소요(초): 사진 확인(워커 대기 포함) · 손상 검출 · 부품 연결 · 수리비 계산. 합 26초 */
const EXPECTED_SEC = [6, 10, 5, 5]
const TICK_MS = 250 // 게이지 갱신 주기 — 폴링(2초)보다 촘촘해야 사이가 이어져 보인다
const EXCLUDE_REASON = { NOT_VEHICLE: '차량이 아닌 사진', RATIO_BELOW_THRESHOLD: '차량이 너무 작게 찍힌 사진' }

// failureReason 코드 → 안내 문구. 서버(AnalysisRequestFailure·AnalysisCallbackService)와 AI 서버 오류 명세의 코드 그대로.
// photo=true 인 사유는 같은 사진으로 다시 분석해도 결과가 같으므로 재촬영으로 이끈다
const FAIL_TEXT = {
  ALL_IMAGES_EXCLUDED: { text: '올린 사진이 모두 분석에서 제외됐어요. 차량 전체와 손상 부위가 잘 보이도록 다시 촬영해 주세요.', photo: true },
  NO_IMAGE: { text: '분석할 사진이 없어요. 사진을 먼저 올려 주세요.', photo: true },
  IMAGE_FETCH_FAILED: { text: '사진을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.' },
  MODEL_ERROR: { text: 'AI 분석 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.' },
  INVALID_MODEL_OUTPUT: { text: 'AI 분석 결과를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.' },
  INTERNAL: { text: 'AI 서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요.' },
  AI_UNREACHABLE: { text: 'AI 서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.' },
  STORAGE_UNAVAILABLE: { text: '사진 저장소에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.' },
  ABANDONED: { text: '분석이 제한 시간 안에 끝나지 않았어요. 다시 시도해 주세요.' },
}
const FAIL_DEFAULT = '일시적인 문제일 수 있어요. 잠시 후 다시 시도해 주세요.'

// 서버 상태
const status = ref(null)        // QUEUED | PROCESSING | COMPLETED | FAILED | null(대기·미요청)
const doneStages = ref(0)       // DONE 인 단계 수
const currentStage = ref(null)  // RUNNING 인 단계 코드
const failureReason = ref('')
const excluded = ref([])        // 분석에서 제외된 사진 [{ imageId, reason }]
const fatal = ref('')           // 요청 자체가 거절된 경우의 안내 (400·404·503 등)

/* ----- 예상 소요 시간 기반 진행 -----
 * shownDone = 화면에 "끝났다" 고 보이는 단계 수. 시간(startedAt 부터 흐른 초가 누적 예상치를 넘을 때마다 +1, 최대 TOTAL-1)과
 * 서버(doneStages) 중 큰 쪽. 단계가 바뀐 시각(stageAt)부터 그 단계 안의 채움(frac)을 센다.
 */
const now = ref(Date.now())
const startedAt = ref(null)   // 시계의 0점 — 서버 startedAt, 없으면 PROCESSING 을 처음 본 시각
const shownDone = ref(0)
const stageAt = ref(Date.now())
let ticker = null
const CUM = EXPECTED_SEC.map((_, i) => EXPECTED_SEC.slice(0, i + 1).reduce((a, b) => a + b, 0)) // 누적 경계(초)

function timeDone() {
  if (!startedAt.value) return 0
  const sec = (now.value - startedAt.value) / 1000
  let n = 0
  while (n < TOTAL - 1 && sec >= CUM[n]) n++
  return n
}
function advance() {
  now.value = Date.now()
  if (status.value === 'COMPLETED' || status.value === 'FAILED') return
  const target = Math.min(TOTAL - 1, Math.max(timeDone(), doneStages.value))
  if (target > shownDone.value) { shownDone.value = target; stageAt.value = now.value }
}
function startTicker() { if (!ticker) ticker = setInterval(advance, TICK_MS) }
function stopTicker() { clearInterval(ticker); ticker = null }
function resetProgress() { startedAt.value = null; shownDone.value = 0; stageAt.value = Date.now(); now.value = Date.now() }

// 화면 파생값: cur = 진행 중인 단계 index(0-based). 완료면 TOTAL
const cur = computed(() => (status.value === 'COMPLETED' ? TOTAL : Math.min(shownDone.value, TOTAL - 1)))
/** 현재 단계 안의 채움 비율. 마지막 단계는 그 칸의 80%(전체 95%)를 향해 천천히(멈춘 듯 보이지 않게), 그 앞 단계는 예상 시간에 맞춰 선형으로 */
const frac = computed(() => {
  if (status.value !== 'PROCESSING' && !(status.value === 'QUEUED' && startedAt.value)) return 0
  const t = Math.max(0, (now.value - stageAt.value) / 1000)
  const exp = EXPECTED_SEC[cur.value] || 5
  return cur.value >= TOTAL - 1 ? Math.min(0.8, 1 - Math.exp(-t / exp)) : Math.min(1, t / exp)
})
const pct = computed(() => (status.value === 'COMPLETED' ? 100 : ((shownDone.value + frac.value) / TOTAL) * 100))
const stageText = computed(() => {
  if (status.value === 'FAILED') return '분석을 완료하지 못했어요'
  if (status.value === 'COMPLETED') return '분석이 끝났어요'
  if (status.value === 'QUEUED' && !shownDone.value) return '분석 순서를 기다리고 있어요'
  if (!status.value) return '분석을 준비하고 있어요'
  return STAGES[cur.value].text
})
const failed = computed(() => status.value === 'FAILED' || !!fatal.value)
const failInfo = computed(() => FAIL_TEXT[failureReason.value] || null)
const failText = computed(() => fatal.value || failInfo.value?.text || FAIL_DEFAULT)
// 매핑에 없는 코드는 원인 추적을 위해 코드도 작게 보여 준다
const failCode = computed(() => (!fatal.value && failureReason.value && !failInfo.value ? failureReason.value : ''))
// 사진 문제(전부 제외·사진 없음)면 재촬영으로. 요청 거절 400 도 사진 없음이라 같은 처리
const photoProblem = computed(() => !!failInfo.value?.photo || fatal.value.startsWith('분석할 사진이 없어요'))
function reupload() { router.replace({ path: '/claim/upload', query: accidentId ? { accidentId } : {} }) }
const excludedText = computed(() => {
  if (!excluded.value.length) return ''
  const kinds = [...new Set(excluded.value.map((e) => EXCLUDE_REASON[e.reason] || '기준에 맞지 않는 사진'))]
  return `사진 ${excluded.value.length}장(${kinds.join(', ')})은 분석에서 제외됐어요`
})

let timer = null
let stopped = false
let busy = false

function apply(p) {
  status.value = p?.status ?? null
  doneStages.value = p?.doneStages ?? 0
  currentStage.value = p?.currentStage ?? null
  failureReason.value = p?.failureReason || ''
  excluded.value = p?.excludedImages || []
  // 시계의 0점: 서버가 시작 시각을 주면 그것, 아니면 PROCESSING 을 처음 본 지금. QUEUED 동안은 돌리지 않는다
  if (status.value === 'PROCESSING' && !startedAt.value) {
    const t = p?.startedAt ? Date.parse(p.startedAt) : NaN
    startedAt.value = Number.isNaN(t) ? Date.now() : Math.min(t, Date.now())
    stageAt.value = Date.now()
  }
  if (status.value === 'COMPLETED') shownDone.value = TOTAL
  if (status.value === 'PROCESSING' || status.value === 'QUEUED') startTicker(); else stopTicker()
  advance()
}

function finish() {
  // 4/4 가 채워지는 것을 잠깐 보여 준 뒤 결과로
  timer = setTimeout(() => router.replace({ path: '/estimate', query: { accidentId } }), 700)
}

async function tick() {
  if (stopped) return
  try {
    const p = await fetchAnalysisProgress(accidentId)
    apply(p)
    if (p.status === 'COMPLETED') { finish(); return }
    if (p.status === 'FAILED') return // 재시도 버튼으로만 다시 시작
  } catch (e) {
    if (e.status === 401 || e.status === 404) { fatal.value = e.status === 404 ? '사고 정보를 찾을 수 없어요.' : ''; return } // 반복해도 같다 — 멈춘다
    // 네트워크 일시 오류 등은 다음 tick 에서 다시 본다
  }
  // setInterval 대신 setTimeout 재귀 — 응답이 느릴 때 요청이 겹치지 않게
  timer = setTimeout(tick, POLL_MS)
}

async function start() {
  if (busy) return
  busy = true
  fatal.value = ''
  try {
    // 부위 확정 재분석이면 그 API 로, 아니면 일반 분석 요청. 둘 다 202 — 응답이 진행 상태와 같은 모양이라 바로 그린다
    const p = resolvePartCode && resolveEstimateId
      ? await resolveAnalysisPart(resolveEstimateId, resolvePartCode)
      : await requestAnalysis(accidentId)
    apply(p)
  } catch (e) {
    if (e.status !== 409) { // 409 = 이미 진행 중 → 조회로 이어 간다
      fatal.value = resolvePartCode && e.status === 404 ? '부위 확정 재분석은 서버 준비 중이에요. 준비되면 여기서 바로 이어집니다.'
        : resolvePartCode && e.status === 400 ? '고를 수 없는 부위예요. 다른 부위를 골라 주세요.'
        : e.status === 400 ? '분석할 사진이 없어요. 사진을 먼저 올려 주세요.'
        : e.status === 404 ? '사고 정보를 찾을 수 없어요.'
        : e.status === 503 ? '지금은 AI 분석을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.'
        : e.message || '분석을 시작하지 못했어요.'
      busy = false
      return
    }
  }
  busy = false
  tick()
}

function retry() {
  clearTimeout(timer)
  status.value = null
  doneStages.value = 0
  currentStage.value = null
  failureReason.value = ''
  resetProgress()
  start()
}

/* 프로토타입 경로 — accidentId 없이 들어온 경우 서버 대신 모의 단계를 흘린다 (홈·사고 이력 목업 진입용) */
function runMock() {
  const seq = [
    [400, { status: 'QUEUED', doneStages: 0, currentStage: null }],
    [1000, { status: 'PROCESSING', doneStages: 0, currentStage: 'PREPROCESS' }],
    [1400, { status: 'PROCESSING', doneStages: 1, currentStage: 'DETECT' }],
    [1600, { status: 'PROCESSING', doneStages: 2, currentStage: 'MATCH' }],
    [1400, { status: 'PROCESSING', doneStages: 3, currentStage: 'ESTIMATE' }],
    [1200, { status: 'COMPLETED', doneStages: 4, currentStage: null }],
  ]
  let i = 0
  const next = () => {
    if (stopped || i >= seq.length) return
    const [delay, p] = seq[i++]
    timer = setTimeout(() => { apply(p); if (p.status === 'COMPLETED') router.replace('/estimate'); else next() }, delay)
  }
  next()
}

onMounted(() => { accidentId ? start() : runMock() })
onUnmounted(() => { stopped = true; clearTimeout(timer); stopTicker() }) // 화면을 떠나면 폴링·게이지 타이머를 반드시 끈다
</script>

<template>
  <Screen>
    <header class="hdr"><span class="ttl">분석 중</span></header>
    <div class="prog"><i style="width:100%"></i></div>

    <div class="body col fixed" style="padding:0">
      <div class="gap"></div>

      <!-- 실패 · 요청 거절 -->
      <template v-if="failed">
        <div class="c">
          <span class="failic">
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 8v5M12 16.5h.01" stroke="#C0392B" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="12" r="9.25" stroke="#C0392B" stroke-width="1.5"/></svg>
          </span>
          <h1 class="h1" style="margin-top:28px">{{ fatal ? '분석을 시작할 수 없어요' : photoProblem ? '사진을 다시 올려 주세요' : '분석을 완료하지 못했어요' }}</h1>
          <p style="margin-top:8px;font-size:14px;color:var(--text-3);line-height:1.55;padding:0 32px">{{ failText }}</p>
          <p v-if="failCode" class="sub" style="margin-top:6px;font-size:11px;color:var(--text-4)">오류 코드 {{ failCode }}</p>
        </div>
        <div style="margin-top:28px;padding:0 40px;display:flex;flex-direction:column;gap:10px">
          <!-- 사진 문제: 같은 사진으로 다시 분석해도 결과가 같다 → 업로드 화면으로 -->
          <button v-if="photoProblem" class="btn" @click="reupload">사진 다시 올리기</button>
          <button v-else-if="!fatal || fatal.includes('잠시 후')" class="btn" @click="retry">다시 분석하기</button>
          <button class="btn outline muted" @click="router.replace('/home')">홈으로</button>
        </div>
      </template>

      <!-- 진행 중 · 완료 -->
      <template v-else>
        <div class="c">
          <svg width="72" height="72" viewBox="0 0 72 72" fill="none" aria-hidden="true" :style="status === 'COMPLETED' ? '' : 'animation:dcspin 1.4s linear infinite'">
            <circle cx="36" cy="36" r="33" stroke="#EEEBFD" stroke-width="6"/>
            <circle cx="36" cy="36" r="33" stroke="#4E36E4" stroke-width="6" stroke-linecap="round" stroke-dasharray="207.3" :stroke-dashoffset="status === 'COMPLETED' ? 0 : 62.2" transform="rotate(-90 36 36)"/>
          </svg>
          <h1 class="h1" style="margin-top:28px">{{ status === 'COMPLETED' ? '분석이 끝났어요' : '손상을 분석하고 있어요' }}</h1>
          <p style="margin-top:8px;font-size:14px;color:var(--text-3)">{{ stageText }}</p>
        </div>

        <div style="margin-top:24px;padding:0 40px">
          <div class="bar"><i :style="{ width: pct + '%' }"></i></div>
          <div style="margin-top:10px;text-align:right;font-size:12px;font-weight:600;color:var(--text-2)">{{ Math.min(shownDone, TOTAL) }} / {{ TOTAL }}</div>
        </div>

        <div class="list">
          <div v-for="(s, k) in STAGES" :key="s.code" class="li">
            <span class="ic">
              <svg v-if="k < shownDone || status === 'COMPLETED'" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M3.5 9.6L7 13L14.5 5" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
              <svg v-else-if="k === cur && status !== 'QUEUED'" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite"><circle cx="9" cy="9" r="7" stroke="#EEEBFD" stroke-width="2.5"/><circle cx="9" cy="9" r="7" stroke="#4E36E4" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="44" stroke-dashoffset="31"/></svg>
              <span v-else class="pend"></span>
            </span>
            <span class="tx" :class="{ now: k === cur && status !== 'COMPLETED', later: k > cur }">{{ s.text }}</span>
          </div>
        </div>

        <p v-if="excludedText" class="sub" style="margin-top:20px;padding:0 40px;font-size:12px;line-height:1.5">{{ excludedText }}</p>

        <div class="flex1"></div>
        <div class="center" style="padding-bottom:16px">
          <p class="sub" style="font-size:12px">창을 닫아도 분석은 계속돼요</p>
          <p class="sub" style="font-size:12px;margin-top:4px">사고 이력에서 결과를 확인할 수 있어요</p>
        </div>
      </template>
    </div>
  </Screen>
</template>

<style scoped>
.gap { flex: 0 0 clamp(60px, 22vh, 201px); }
.c { display: flex; flex-direction: column; align-items: center; text-align: center; }
.failic { width: 72px; height: 72px; border-radius: 36px; background: var(--danger-bg); display: flex; align-items: center; justify-content: center; }
.bar { height: 6px; border-radius: 3px; background: var(--line); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 3px; background: var(--primary); transition: width .4s; }
.list { margin-top: 32px; padding: 0 40px; display: flex; flex-direction: column; }
.li { height: 36px; display: flex; align-items: center; gap: 10px; }
.ic { flex: 0 0 18px; height: 18px; display: flex; align-items: center; justify-content: center; }
.pend { width: 18px; height: 18px; border-radius: 9px; border: 1.5px solid var(--line-2); display: block; }
.tx { font-size: 14px; color: var(--text-2); }
.tx.now { font-weight: 600; color: var(--text); }
.tx.later { color: var(--primary-disabled); }
</style>
