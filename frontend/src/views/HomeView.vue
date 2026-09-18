<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import LogoMark from '../components/LogoMark.vue'
import Avatar from '../components/Avatar.vue'
import AccidentRow from '../components/AccidentRow.vue'
import { useAuthStore } from '../stores/auth'
import { useAccidentStore } from '../stores/accidents'
import { AUTH_GUARD_OFF } from '../router'
import { fetchAnalysisProgress } from '../lib/api'
import { vehicleName } from '../data/vehicles'
import { accidentRoute, analysisProgressView } from '../data/accidents'

/* ===== 홈 (S03) — 사고 이력 첫 페이지로 "진행 중"·"최근 사고" 를 채운다 =====
 * 홈 전용 API 는 없다(화면-서버 대조표 화면 4·5). GET /api/accidents/me 첫 페이지를 사고 스토어로 받아
 *  - 진행 중: status=ANALYZING 인 사고. 가장 최근 1건은 진행 상태 API(GET .../analysis)를 3초마다 받아 단계·퍼센트를 보인다.
 *    분석이 끝나면(COMPLETED·FAILED) 목록을 다시 받고, 그 사고는 사용자가 카드를 눌러 결과를 보기 전까지 "진행 완료" 카드로 이 섹션에 남는다
 *    (누르고 돌아오면 아래 사고 이력 미리보기로 내려간다). 아직 안 본 완료 건은 회원별 localStorage 에 둔다 — 홈을 떠나 있는 동안 끝난 분석도 같게 다룬다
 *  - 최근 사고(미리보기): 진행 중·진행 완료(미확인)를 뺀 최근 3건. 카드는 사고 이력 화면과 같은 AccidentRow(PDF 버튼은 두지 않음 — 이력 화면에서)
 * 썸네일은 10분 서명 URL 이라 홈에 들어올 때마다 새로 받는다.
 */
const router = useRouter()
const auth = useAuthStore()
const accidents = useAccidentStore()

/* ----- 진행 완료(미확인) 기록 ----- */
const SEEN_KEY = () => `noka.homeAnalyzing.${auth.me?.memberId ?? 'guest'}`
const watching = ref([]) // 홈에서 "분석 중" 으로 본 accidentId. 완료 뒤 결과를 보기 전까지 진행 중 섹션에 남긴다
function readWatching() { try { const v = JSON.parse(localStorage.getItem(SEEN_KEY()) || '[]'); return Array.isArray(v) ? v : [] } catch { return [] } }
function writeWatching(ids) { watching.value = ids; try { ids.length ? localStorage.setItem(SEEN_KEY(), JSON.stringify(ids)) : localStorage.removeItem(SEEN_KEY()) } catch { /* 저장소 없으면 세션 안에서만 */ } }

const analyzing = computed(() => accidents.items.filter((a) => a.status === 'ANALYZING'))
const busy = computed(() => analyzing.value[0] || null)
/** 분석이 끝났는데 아직 결과를 안 본 사고 — 진행 중 섹션에 "진행 완료" 로 남는다 */
const finished = computed(() => accidents.items.filter((a) => a.status !== 'ANALYZING' && watching.value.includes(a.accidentId)))
const busyCount = computed(() => analyzing.value.length + finished.value.length)
/** 미리보기 — 진행 중·진행 완료(미확인) 제외 최근 3건 */
const recent = computed(() => accidents.items.filter((a) => a.status !== 'ANALYZING' && !watching.value.includes(a.accidentId)).slice(0, 3))
const loaded = computed(() => accidents.loaded)

// 분석 중으로 보인 사고는 기록해 둔다(끝났을 때 "진행 완료" 로 남기기 위해)
watch(analyzing, (list) => {
  const add = list.map((a) => a.accidentId).filter((id) => !watching.value.includes(id))
  if (add.length) writeWatching([...watching.value, ...add])
})

/* ----- 진행 중 카드의 단계·퍼센트 — 서버가 퍼센트를 주지 않으므로 doneStages/4 로 0·25·50·75·100 ----- */
const progress = ref(null)
let timer
const POLL_MS = 3000
let mockTicks = 0
async function loadProgress() {
  if (!busy.value) { progress.value = null; return }
  if (AUTH_GUARD_OFF) { // 목업: 두 번 확인한 뒤 완료로 — 진행 완료 카드 흐름을 볼 수 있게
    mockTicks++
    progress.value = mockTicks < 3 ? { status: 'PROCESSING', totalStages: 4, doneStages: 2, currentStage: 'MATCH' } : { status: 'COMPLETED', totalStages: 4, doneStages: 4, currentStage: null }
  } else {
    try { progress.value = await fetchAnalysisProgress(busy.value.accidentId) } catch (e) { /* 못 받아도 카드는 "분석 중" */ }
  }
  if (progress.value && (progress.value.status === 'COMPLETED' || progress.value.status === 'FAILED')) {
    // 끝났다 — 목록을 다시 받아 상태(ESTIMATED·ANALYSIS_FAILED)를 반영한다. 기록(watching)에 남아 있어 "진행 완료" 카드로 보인다
    const id = busy.value.accidentId // 상태를 바꾸면 busy 가 비므로 먼저 잡아 둔다
    if (AUTH_GUARD_OFF) {
      const patch = { status: 'ESTIMATED', estimateId: 1, estimatedCostMin: 860000, estimatedCostMedian: 1530000, estimatedCostMax: 1910000 }
      try { const o = JSON.parse(sessionStorage.getItem('noka.mockAccidentOverrides') || '{}'); o[id] = patch; sessionStorage.setItem('noka.mockAccidentOverrides', JSON.stringify(o)) } catch { /* 목업 전용 */ }
      const a = accidents.all.find((x) => x.accidentId === id)
      if (a) Object.assign(a, patch)
    } else await accidents.load(true)
    progress.value = null
  }
}
function schedule() { clearTimeout(timer); if (busy.value) timer = setTimeout(async () => { await loadProgress(); schedule() }, POLL_MS) }
const prog = computed(() => analysisProgressView(progress.value))
const ringOffset = computed(() => 138.2 * (1 - prog.value.percent / 100)) // 원둘레 138.2 (r=22)

onMounted(async () => {
  watching.value = readWatching()
  await accidents.load(true)
  // 홈을 떠나 있는 동안 끝난 분석도 "진행 완료" 로 남긴다 — 기록에 있는데 목록에서 사라진(숨김·페이지 밖) 건은 정리
  writeWatching(watching.value.filter((id) => accidents.all.some((a) => a.accidentId === id)))
  await loadProgress()
  schedule()
})
onUnmounted(() => clearTimeout(timer))

/* ----- 이동 ----- */
function openBusy() { router.push({ path: '/claim/analyzing', query: { accidentId: busy.value.accidentId } }) }
/** 진행 완료 카드 — 결과를 보러 가면 기록에서 빼서, 돌아왔을 때 아래 미리보기로 내려간다 */
function openFinished(a) {
  writeWatching(watching.value.filter((id) => id !== a.accidentId))
  router.push(accidentRoute(a))
}
function openRecent(a) { router.push(accidentRoute(a)) }

</script>

<template>
  <Screen>
    <div class="body col scroll" style="padding-top:20px">
      <div class="row" style="gap:8px">
        <LogoMark />
        <span class="brand flex1">노카</span>
        <button class="me" aria-label="마이페이지" @click="router.push('/my')"><Avatar /></button>
      </div>

      <p class="hello">안녕하세요, {{ auth.nickname || '김싸피' }}님</p>
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

      <!-- 진행 중 — 분석 중인 사고 + 끝났지만 아직 결과를 안 본 사고("진행 완료") -->
      <template v-if="busyCount">
        <div class="row between" style="margin-top:28px">
          <span class="sec">진행 중</span>
          <span class="sub">{{ busyCount }}건</span>
        </div>
        <button v-if="busy" class="busy" @click="openBusy">
          <span class="ring">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none" aria-hidden="true">
              <circle cx="24" cy="24" r="22" stroke="#FFFFFF" stroke-width="4"/>
              <circle cx="24" cy="24" r="22" stroke="#4E36E4" stroke-width="4" stroke-linecap="round" stroke-dasharray="138.2" :stroke-dashoffset="ringOffset" transform="rotate(-90 24 24)" style="transition:stroke-dashoffset .3s"/>
            </svg>
            <b>{{ progress ? prog.percent + '%' : '…' }}</b>
          </span>
          <span class="flex1" style="display:flex;flex-direction:column;text-align:left;min-width:0">
            <span class="bt nowrap">{{ vehicleName(busy) }} · 분석 중</span>
            <span class="bs">{{ progress ? prog.text : '진행 상태를 확인하고 있어요' }}</span>
            <span class="bar"><i :style="{ width: prog.percent + '%' }"></i></span>
          </span>
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 18px"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <!-- 진행 완료 — 누르면 결과 화면으로, 돌아오면 아래 사고 이력으로 내려간다 -->
        <button v-for="a in finished" :key="a.accidentId" class="busy done" :class="{ fail: a.status === 'ANALYSIS_FAILED' }" @click="openFinished(a)">
          <span class="ring">
            <svg v-if="a.status !== 'ANALYSIS_FAILED'" width="48" height="48" viewBox="0 0 48 48" fill="none" aria-hidden="true">
              <circle cx="24" cy="24" r="22" stroke="#4E36E4" stroke-width="4"/>
              <path d="M15.5 24.5l5.5 5.5 11.5-12" stroke="#4E36E4" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <svg v-else width="48" height="48" viewBox="0 0 48 48" fill="none" aria-hidden="true">
              <circle cx="24" cy="24" r="22" stroke="#D14343" stroke-width="4"/>
              <path d="M24 15v11" stroke="#D14343" stroke-width="3" stroke-linecap="round"/><circle cx="24" cy="32" r="2" fill="#D14343"/>
            </svg>
          </span>
          <span class="flex1" style="display:flex;flex-direction:column;text-align:left;min-width:0">
            <span class="bt nowrap">{{ vehicleName(a) }} · {{ a.status === 'ANALYSIS_FAILED' ? '분석 실패' : '진행 완료' }}</span>
            <span class="bs">{{ a.status === 'ANALYSIS_FAILED' ? '사진을 다시 올리고 분석을 요청해 주세요' : '분석이 끝났어요 · 눌러서 결과를 확인하세요' }}</span>
          </span>
          <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 18px"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </template>

      <!-- 사고 이력 미리보기 — 최근 3건, 카드는 사고 이력 화면과 동일 -->
      <template v-if="!loaded && accidents.loading">
        <div class="row between" style="margin-top:28px"><span class="sec">사고 이력</span></div>
        <div class="card skel" aria-busy="true"></div>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="recent.length">
        <div class="row between" style="margin-top:28px">
          <span class="sec">사고 이력</span>
          <button class="link" @click="router.push('/history')">전체 보기</button>
        </div>
        <AccidentRow v-for="a in recent" :key="a.accidentId" :a="a" @open="openRecent">
          <template #action>
            <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 18px"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </template>
        </AccidentRow>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="accidents.error && !loaded">
        <div class="row between" style="margin-top:28px"><span class="sec">사고 이력</span></div>
        <p class="sub" style="margin-top:12px">{{ accidents.error }} <button class="link" @click="accidents.load(true)">다시 시도</button></p>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="!busyCount">
        <div class="col flex1" style="margin-top:28px;display:flex;flex-direction:column">
          <span class="sec">사고 이력</span>
          <div class="empty">
            <img src="/assets/logo-small.png" alt="">
            <b>아직 접수한 사고가 없어요</b>
            <p>사고가 나면 사진을 찍어<br>예상 수리비를 확인해보세요</p>
          </div>
        </div>
      </template>
      <div v-else style="height:24px"></div>
    </div>
    <div class="spacer"></div>
  </Screen>
</template>

<style scoped>
/* .body.col 은 flex 컬럼 + overflow 라 내용이 화면보다 길어지면 고정 높이 자식(CTA·타일·카드)이 줄어든다 — 줄지 않게 고정 */
.body.col > * { flex-shrink: 0; }
.brand { font-size: 20px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; }
.me { flex: 0 0 44px; width: 44px; height: 44px; margin: -6px -6px -6px 0; display: flex; align-items: center; justify-content: center; border-radius: 22px; }
.hello { margin-top: 28px; font-size: 14px; color: var(--text-2); }
.cta { flex: 0 0 92px; margin-top: 20px; width: 100%; height: 92px; padding: 0 20px; border-radius: 16px; background: var(--primary); text-align: left; display: flex; align-items: center; gap: 12px; transition: background .15s; }
.cta:hover { background: var(--primary-dark); }
.ct { font-size: 18px; font-weight: 700; color: #fff; }
.cs { margin-top: 4px; font-size: 13px; color: rgba(255,255,255,.75); }
.tiles { margin-top: 10px; display: flex; gap: 10px; }
.tile { position: relative; flex: 1 1 0; min-width: 0; height: 92px; padding: 0 16px; border-radius: 16px; background: var(--primary-50); overflow: hidden; text-align: left; display: flex; flex-direction: column; justify-content: center; }
.tile:hover { background: var(--primary-100); }
.tile .wm { position: absolute; }
.tile b { position: relative; font-size: 15px; font-weight: 700; color: var(--text); white-space: nowrap; }
.tile span { position: relative; margin-top: 4px; font-size: 12px; color: var(--text-3); white-space: nowrap; }
.busy { flex: 0 0 84px; margin-top: 12px; width: 100%; height: 84px; padding: 0 14px; background: var(--primary-soft); border-radius: 12px; display: flex; align-items: center; gap: 14px; }
.busy.done { background: var(--primary-50); border: 1px solid var(--primary-200); }
.busy.done.fail { background: var(--danger-bg); border-color: transparent; }
.ring { position: relative; flex: 0 0 48px; width: 48px; height: 48px; display: flex; align-items: center; justify-content: center; }
.ring b { position: absolute; font-size: 11px; font-weight: 600; color: var(--primary); }
.bt { font-size: 15px; font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; }
.bs { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.bar { margin-top: 8px; display: block; height: 4px; border-radius: 2px; background: #fff; overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 2px; background: var(--primary); transition: width .3s; }
.skel { margin-top: 8px; height: 106px; background: var(--bg-2); border-color: transparent; }
</style>
