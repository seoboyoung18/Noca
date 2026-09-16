<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import LogoMark from '../components/LogoMark.vue'
import Avatar from '../components/Avatar.vue'
import { useAuthStore } from '../stores/auth'
import { useAccidentStore } from '../stores/accidents'
import { AUTH_GUARD_OFF } from '../router'
import { fetchAnalysisProgress } from '../lib/api'
import { vehicleName } from '../data/vehicles'
import { accidentCost, accidentDate, accidentRoute, accidentStatus, analysisProgressView } from '../data/accidents'
import { wonOne } from '../data/estimates'

/* ===== 홈 (S03) — 사고 이력 첫 페이지로 "진행 중"·"최근 사고" 카드를 채운다 =====
 * 홈 전용 API 는 없다(화면-서버 대조표 화면 4·5). GET /api/accidents/me 첫 페이지를 사고 스토어로 받아
 *  - 진행 중: status=ANALYZING 인 사고들. 가장 최근 1건은 진행 상태 API(GET .../analysis)로 단계·퍼센트를 함께 보인다
 *  - 최근 사고: 진행 중을 제외한 가장 최근 1건. 없으면 빈 상태
 * 썸네일은 10분 서명 URL 이라 홈에 들어올 때마다 새로 받는다.
 */
const router = useRouter()
const auth = useAuthStore()
const accidents = useAccidentStore()

const analyzing = computed(() => accidents.items.filter((a) => a.status === 'ANALYZING'))
const busy = computed(() => analyzing.value[0] || null)
const recent = computed(() => accidents.items.find((a) => a.status !== 'ANALYZING') || null)
// 카드 오른쪽 금액은 목업처럼 중앙값 한 값으로(범위는 길어서 줄이 꺾인다). 중앙값이 없으면 범위, 견적이 없으면 비운다
const recentAmt = computed(() => (recent.value?.estimatedCostMedian != null ? wonOne(recent.value.estimatedCostMedian) : accidentCost(recent.value || {})))
const loaded = computed(() => accidents.loaded)

// 진행 중 카드의 단계·퍼센트 — 서버가 퍼센트를 주지 않으므로 doneStages/4 로 0·25·50·75·100 만 나온다
const progress = ref(null)
async function loadProgress() {
  progress.value = null
  if (!busy.value) return
  if (AUTH_GUARD_OFF) { progress.value = { status: 'PROCESSING', totalStages: 4, doneStages: 2, currentStage: 'MATCH' }; return }
  try { progress.value = await fetchAnalysisProgress(busy.value.accidentId) }
  catch (e) { /* 진행 상태를 못 받아도 카드는 "분석 중" 으로 보인다 */ }
}
const prog = computed(() => analysisProgressView(progress.value))
// 원둘레 138.2 (r=22) 기준 dashoffset
const ringOffset = computed(() => 138.2 * (1 - prog.value.percent / 100))

onMounted(async () => {
  await accidents.load(true)
  await loadProgress()
})

function openBusy() { router.push({ path: '/claim/analyzing', query: { accidentId: busy.value.accidentId } }) }
function openRecent() { router.push(accidentRoute(recent.value)) }
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

      <!-- 진행 중인 분석 — ANALYZING 사고가 있을 때만 -->
      <template v-if="busy">
        <div class="row between" style="margin-top:28px">
          <span class="sec">진행 중</span>
          <span class="sub">{{ analyzing.length }}건</span>
        </div>
        <button class="busy" @click="openBusy">
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
      </template>

      <!-- 최근 사고 -->
      <template v-if="!loaded && accidents.loading">
        <div class="row between" style="margin-top:28px"><span class="sec">최근 사고</span></div>
        <div class="recent skel" aria-busy="true"></div>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="recent">
        <div class="row between" style="margin-top:28px">
          <span class="sec">최근 사고</span>
          <button class="link" @click="router.push('/history')">전체 보기</button>
        </div>
        <button class="recent" @click="openRecent">
          <span class="th">
            <img v-if="recent.thumbnailUrl" :src="recent.thumbnailUrl" :alt="vehicleName(recent) + ' 손상 사진'">
            <svg v-else width="34" height="18" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
          </span>
          <span class="flex1" style="display:flex;flex-direction:column;gap:5px;text-align:left;min-width:0">
            <span class="sec nowrap">{{ vehicleName(recent) }}</span>
            <span class="row" style="gap:6px">
              <span class="sub" style="font-size:12px">{{ accidentDate(recent.createdAt) }}</span>
              <span class="tag" :class="accidentStatus(recent.status).cls">{{ accidentStatus(recent.status).text }}</span>
            </span>
          </span>
          <!-- 견적이 없으면(estimatedCost* null) 금액 칸을 비운다 — "0원" 금지 -->
          <span v-if="recentAmt" class="amt">{{ recentAmt }}</span>
        </button>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="accidents.error && !loaded">
        <div class="row between" style="margin-top:28px"><span class="sec">최근 사고</span></div>
        <p class="sub" style="margin-top:12px">{{ accidents.error }} <button class="link" @click="accidents.load(true)">다시 시도</button></p>
        <div style="height:24px"></div>
      </template>

      <template v-else-if="!busy">
        <div class="col flex1" style="margin-top:28px;display:flex;flex-direction:column">
          <span class="sec">최근 사고</span>
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
.bt { font-size: 15px; font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; }
.bs { margin-top: 4px; font-size: 12px; color: var(--text-3); }
.bar { margin-top: 8px; display: block; height: 4px; border-radius: 2px; background: #fff; overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 2px; background: var(--primary); transition: width .3s; }
.recent { margin-top: 12px; width: 100%; height: 84px; padding: 0 14px; border: 1px solid var(--line); border-radius: 12px; display: flex; align-items: center; gap: 12px; background: var(--white); }
.recent:hover { background: var(--bg-2); }
.recent.skel { background: var(--bg-2); border-color: transparent; }
.th { flex: 0 0 56px; width: 56px; height: 56px; border-radius: 8px; background: var(--text-3); overflow: hidden; display: flex; align-items: center; justify-content: center; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.amt { flex: 0 0 auto; white-space: nowrap; font-size: 15px; font-weight: 700; color: var(--text); }
</style>
