<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import Toast from '../components/Toast.vue'
import { useAccidentStore } from '../stores/accidents'
import { useChecklistStore } from '../stores/checklist'
import { useAccidentDesc } from '../lib/accidentDesc'
import { vehicleName } from '../data/vehicles'
import { accidentDateShort } from '../data/accidents'
import { checklistStatusText } from '../data/checklists'

/* ===== 나의 체크리스트 (S14c) =====
 * 체크리스트는 견적과 1:1 로 만들어지므로 목록은 사고 목록(사고 스토어) 중 견적이 있는 사고(estimateId) 로 만든다.
 * 산정 불가 사고에는 체크리스트를 만들지 않는다(S15P21A307-559, 견적·상세 화면에서 막음). 그래서 checklistStatus 로 거르면 안 된다 — 규칙 전에 생긴 산정 불가 사고의 옛 체크리스트가 올라온다.
 * 체크리스트 목록 API 는 따로 없다. 카드마다 상태 조회(GET …/repair-checklist)로 "생성 중 · n/N 확인 · 실패" 배지를 단다. 상세에는 accidentId 쿼리.
 * 화면 문구는 "제거하기" 지만 서버는 지우지 않고 감춘다(PATCH .../hidden, S15P21A307-554) — 체크리스트를 제거하면 연결된 사고 이력도 함께 빠진다(1:1).
 *   카드마다 더보기(⋮) → "체크리스트 제거하기" → 확인 시트. 화면 우측 상단 더보기에서 "제거한 체크리스트 보기" 로 넘어가 건별로 되돌린다.
 */
const router = useRouter()
const store = useAccidentStore()
const checklists = useChecklistStore()
const { ensure: ensureDesc, text: descText } = useAccidentDesc()

onMounted(() => store.load(true))
const list = computed(() => store.items.filter((a) => a.estimateId))
/** 제거한 것 중 체크리스트가 있는 사고 — "제거한 체크리스트 보기" 에서 건별로 되돌린다 */
const hiddenList = computed(() => store.hidden.filter((a) => a.estimateId))
watch(list, (items) => { ensureDesc(items); for (const a of items) if (!checklists.get(a.accidentId)) checklists.load(a.accidentId) }, { immediate: true })
/** 카드 배지 — 완료면 진행도, 만드는 중·실패면 상태 문구, 요청 전이면 표시 없음 */
function badge(a) {
  const c = checklists.get(a.accidentId)
  if (!c || c.status === null) return null
  if (c.status === 'COMPLETED') return { text: `${c.progress?.completed ?? 0}/${c.progress?.total ?? 0} 확인`, cls: '' }
  if (c.status === 'FAILED') return { text: checklistStatusText(c.status), cls: 'red' }
  return { text: checklistStatusText(c.status), cls: 'gray' }
}

function open(a) {
  if (cardMenu.value != null) { cardMenu.value = null; return }
  if (showHidden.value) return
  router.push({ path: '/checklist', query: { accidentId: a.accidentId } })
}

/* ===== 더보기 · 제거 ===== */
const menu = ref(false) // 화면 우측 상단 더보기
const cardMenu = ref(null) // 열려 있는 카드 메뉴의 accidentId
const showHidden = ref(false) // 제거한 체크리스트 화면
const target = ref(null) // 확인 시트가 가리키는 사고
const lastHidden = ref(null) // 토스트 "되돌리기" 대상
const busy = ref(false) // 제거·되돌리기 요청 중 — 연타 방지
const toast = ref('')
let tt
function showToast(msg, ms = 1600) { toast.value = msg; clearTimeout(tt); tt = setTimeout(() => { toast.value = '' }, ms) }
function toggleCardMenu(a) { cardMenu.value = cardMenu.value === a.accidentId ? null : a.accidentId }
function askHide(a) { cardMenu.value = null; target.value = a }
function openHidden() { menu.value = false; showHidden.value = true; store.loadHidden() }
async function confirmHide() {
  const a = target.value
  target.value = null
  if (!a || busy.value) return
  busy.value = true
  try {
    await store.hide(a.accidentId)
    lastHidden.value = a.accidentId
    showToast('체크리스트를 제거했어요', 4000)
  } catch (e) {
    if (e?.status !== 401) showToast(e?.status === 404 ? '사고를 찾을 수 없어요.' : '제거하지 못했어요. 잠시 후 다시 시도해 주세요.', 2600)
  } finally { busy.value = false }
}
/** 한 건 되돌리기 — 토스트와 "제거한 체크리스트 보기" 가 함께 쓴다 */
async function restore(accidentId, { fromToast = false } = {}) {
  if (busy.value) return
  busy.value = true
  try {
    await store.unhide(accidentId)
    if (fromToast) { lastHidden.value = null; toast.value = '' } else showToast('체크리스트를 되돌렸어요')
  } catch (e) {
    if (e?.status !== 401) showToast(e?.status === 404 ? '사고를 찾을 수 없어요.' : '되돌리지 못했어요. 잠시 후 다시 시도해 주세요.', 2600)
  } finally { busy.value = false }
}
function undoHide() { if (lastHidden.value != null) restore(lastHidden.value, { fromToast: true }) }
</script>

<template>
  <Screen>
    <AppHeader title="나의 체크리스트" back="/home" back-history line>
      <template #right>
        <!-- 화면 더보기 — 제거한 체크리스트 보기만. 받은 체크리스트가 있을 때만 -->
        <button v-if="showHidden" class="act strong" @click="showHidden = false">완료</button>
        <button v-else-if="list.length || store.hiddenCount" class="icn" aria-label="더보기" :aria-expanded="menu" @click="menu = !menu">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="#191F28"/><circle cx="10" cy="10" r="1.8" fill="#191F28"/><circle cx="10" cy="16" r="1.8" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>
    <div v-if="menu" class="menu-dim" @click="menu = false"></div>
    <div v-if="menu" class="menu top" role="menu">
      <button role="menuitem" @click="openHidden">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M1.5 8c1.2-3 3.7-4.8 6.5-4.8s5.3 1.8 6.5 4.8c-1.2 3-3.7 4.8-6.5 4.8S2.7 11 1.5 8z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.5"/></svg>
        제거한 체크리스트 보기<span v-if="store.hiddenCount" class="cnt">{{ store.hiddenCount }}</span>
      </button>
    </div>

    <!-- 제거한 체크리스트 — 건별 되돌리기 -->
    <div v-if="showHidden" class="body scroll" style="padding-top:16px">
      <p class="sub" style="margin:0 0 12px;font-size:12px;line-height:1.5">제거한 체크리스트는 목록에서 빠져요. 항목과 사고 기록은 보관돼 있어 되돌릴 수 있어요.</p>
      <div v-if="store.hiddenLoading && !store.hiddenLoaded" class="sub center" style="padding:40px 0" role="status">제거한 체크리스트를 불러오고 있어요…</div>
      <div v-else-if="store.hiddenError" class="empty" style="padding-top:40px">
        <b>{{ store.hiddenError }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="store.loadHidden()">다시 시도</button>
      </div>
      <div v-else-if="hiddenList.length" class="stack" style="gap:12px">
        <div v-for="a in hiddenList" :key="a.accidentId" class="card cl" style="cursor:default">
          <div class="row" style="align-items:center;gap:8px">
            <span class="d">{{ accidentDateShort(a.createdAt) }}</span>
            <span class="t nowrap flex1" style="overflow:hidden;text-overflow:ellipsis;text-align:left">{{ vehicleName(a) }}</span>
            <button class="restore" :disabled="busy" @click="restore(a.accidentId)">되돌리기</button>
          </div>
          <div class="desc">
            <span class="sub" style="font-size:14px;font-weight:500;text-align:left;min-width:0">{{ descText(a) }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty" style="padding-top:40px">
        <img src="/assets/logo-small.png" alt="">
        <b>제거한 체크리스트가 없어요</b>
        <p>카드의 더보기에서 제거하면 여기에 모여요</p>
      </div>
      <div style="height:24px"></div>
    </div>

    <!-- 첫 로딩 -->
    <div v-else-if="!store.loaded && store.loading" class="body col" role="status">
      <p class="sub center" style="margin:auto 0">체크리스트를 불러오고 있어요…</p>
    </div>

    <!-- 첫 로딩 실패 -->
    <div v-else-if="!store.loaded && store.error" class="body col">
      <div class="empty">
        <b>{{ store.error }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="store.load(true)">다시 시도</button>
      </div>
    </div>

    <!-- 목록 -->
    <div v-else-if="list.length" class="body scroll" style="padding-top:16px">
      <div v-if="cardMenu != null" class="menu-dim" @click="cardMenu = null"></div>
      <div class="stack" style="gap:12px">
        <div v-for="a in list" :key="a.accidentId" class="card cl" :class="{ open: cardMenu === a.accidentId }" role="link" tabindex="0" @click="open(a)" @keydown.enter="open(a)">
          <div class="row" style="align-items:center;gap:8px">
            <span class="d">{{ accidentDateShort(a.createdAt) }}</span>
            <span class="t nowrap flex1" style="overflow:hidden;text-overflow:ellipsis;text-align:left">{{ vehicleName(a) }}</span>
            <!-- 카드 더보기 — 체크리스트 제거하기 -->
            <button class="more" :aria-label="`${vehicleName(a)} 체크리스트 더보기`" :aria-expanded="cardMenu === a.accidentId" @click.stop="toggleCardMenu(a)">
              <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="currentColor"/><circle cx="10" cy="10" r="1.8" fill="currentColor"/><circle cx="10" cy="16" r="1.8" fill="currentColor"/></svg>
            </button>
          </div>
          <div v-if="cardMenu === a.accidentId" class="menu card-menu" role="menu" @click.stop>
            <button role="menuitem" class="danger" @click="askHide(a)">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M2.5 4.2h11M6.2 4.2V2.9h3.6v1.3M3.9 4.2l.6 8.2a1 1 0 0 0 1 .9h5a1 1 0 0 0 1-.9l.6-8.2M6.6 6.8v4M9.4 6.8v4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
              체크리스트 제거하기
            </button>
          </div>
          <div class="desc">
            <span class="sub flex1" style="font-size:14px;font-weight:500;text-align:left;min-width:0">{{ descText(a) }}</span>
            <span v-if="badge(a)" class="tag" :class="badge(a).cls" style="flex:0 0 auto">{{ badge(a).text }}</span>
          </div>
        </div>
      </div>
      <p class="sub center" style="margin-top:20px">예상 견적 분석이 끝나면 체크리스트가 자동으로 만들어져요</p>
    </div>

    <!-- 보이는 체크리스트가 없고 제거한 것만 있음 -->
    <div v-else-if="store.hiddenCount" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>모든 체크리스트를 제거했어요</b>
        <p>제거한 체크리스트는 목록에서 빠져 있어요<br>필요하면 하나씩 되돌릴 수 있어요</p>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="openHidden">제거한 체크리스트 보기</button>
      </div>
    </div>

    <!-- 비어 있음 -->
    <div v-else class="body col" style="padding:0 40px;align-items:center;justify-content:center">
      <svg width="64" height="64" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M8.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10A1.5 1.5 0 0 0 18.5 19V5A1.5 1.5 0 0 0 17 3.5h-1.5" stroke="#D1D6DB" stroke-width="1.5" stroke-linecap="round"/><rect x="8.5" y="2" width="7" height="3.4" rx="1.2" stroke="#D1D6DB" stroke-width="1.5"/><path d="M9 13l2.2 2.2L15.5 11" stroke="#D1D6DB" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
      <div style="margin-top:16px;font-size:15px;font-weight:500;color:var(--text-2)">아직 생성된 체크리스트가 없어요</div>
      <p class="sub center" style="margin-top:6px">견적을 받고 나면 정비소에서<br>확인할 내용을 추천해 드려요</p>
    </div>
    <div class="spacer"></div>
    <Toast :show="!!toast">{{ toast }}<button v-if="lastHidden != null && toast === '체크리스트를 제거했어요'" class="undo" @click="undoHide">되돌리기</button></Toast>

    <!-- 제거 확인 -->
    <BottomSheet :model-value="!!target" @update:model-value="target = null">
      <p class="st">이 체크리스트를 제거할까요?</p>
      <p class="sd"><b>해당 체크리스트를 제거하면 연결된 사고 이력도 함께 제거됩니다.</b><br>항목·사고 기록·견적은 보관되며, 더보기 &gt; 제거한 체크리스트 보기에서 하나씩 되돌릴 수 있어요.</p>
      <div v-if="target" class="acts">
        <button class="btn outline" @click="target = null">취소</button>
        <button class="btn bold" :disabled="busy" @click="confirmHide">제거</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.cl { position: relative; width: 100%; padding: 16px; text-align: left; cursor: pointer; }
.cl:hover { background: var(--bg-2); }
.cl.open { z-index: 7; }
.d { font-size: 17px; font-weight: 700; color: var(--primary); flex: 0 0 auto; }
.t { font-size: 17px; font-weight: 700; color: var(--text); }
.desc { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--line); display: flex; align-items: center; gap: 10px; }
.more { flex: 0 0 32px; width: 32px; height: 32px; margin: -6px -8px -6px 0; border-radius: 16px; display: flex; align-items: center; justify-content: center; color: var(--text-3); }
.more:hover, .more[aria-expanded="true"] { background: var(--bg-2); color: var(--text); }
.menu-dim { position: absolute; inset: 0; z-index: 5; }
.menu { position: absolute; z-index: 6; min-width: 190px; padding: 6px; background: var(--white); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 8px 24px rgba(25,31,40,.12); animation: sheetup .15s ease-out; }
.menu.top { top: 48px; right: 12px; min-width: 220px; }
.card-menu { top: 46px; right: 10px; }
.menu button { width: 100%; height: 42px; padding: 0 10px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; color: var(--text); text-align: left; }
.menu button:hover { background: var(--bg-2); }
.menu button:disabled { color: var(--text-4); }
.menu button.danger { color: var(--danger-2); }
.menu .cnt { margin-left: auto; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px; background: var(--primary-100); color: var(--primary); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.restore { flex: 0 0 auto; height: 30px; padding: 0 12px; border: 1px solid var(--primary-200); border-radius: 8px; background: var(--primary-50); font-size: 12px; font-weight: 600; color: var(--primary); }
.restore:hover { background: var(--primary-100); }
.restore:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
.undo { pointer-events: auto; margin-left: 12px; font-size: 13px; font-weight: 700; color: var(--primary-200); text-decoration: underline; }
</style>
