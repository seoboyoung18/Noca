<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import Toast from '../components/Toast.vue'
import { useAppStore } from '../stores/app'
import { useAccidentStore } from '../stores/accidents'
import { useAccidentDesc } from '../lib/accidentDesc'
import { vehicleName } from '../data/vehicles'
import { accidentDateShort } from '../data/accidents'

/* ===== 나의 체크리스트 (S14c) =====
 * 체크리스트는 견적과 1:1 로 만들어지므로 목록은 사고 목록(사고 스토어) 중 견적이 있는 사고(estimateId) 로 만든다.
 * 체크리스트 목록 API 는 따로 없다. 상세(/checklist)는 아직 목업 스토어라 사고 id 만 쿼리로 넘긴다.
 * 숨기기는 사고 이력과 같은 스토어 hidden 을 쓴다 — 체크리스트를 숨기면 연결된 사고 이력도 함께 숨겨진다(1:1).
 *   카드마다 더보기(⋮) → "체크리스트 숨기기" → 확인 시트. 화면 우측 상단 더보기에는 "숨긴 체크리스트 나타내기" 만 둔다.
 */
const router = useRouter()
const app = useAppStore()
const store = useAccidentStore()
const { ensure: ensureDesc, text: descText } = useAccidentDesc()

onMounted(() => store.load(true))
const list = computed(() => store.items.filter((a) => a.estimateId))
const hiddenCount = computed(() => store.all.filter((a) => a.estimateId && store.hidden.includes(a.accidentId)).length)
watch(list, ensureDesc, { immediate: true })

function open(a) {
  if (cardMenu.value != null) { cardMenu.value = null; return }
  app.setTitle(vehicleName(a))
  router.push({ path: '/checklist', query: { accidentId: a.accidentId } })
}

/* ===== 더보기 · 숨기기 ===== */
const menu = ref(false) // 화면 우측 상단 더보기
const cardMenu = ref(null) // 열려 있는 카드 메뉴의 accidentId
const target = ref(null) // 확인 시트가 가리키는 사고
const lastHidden = ref(null) // 토스트 "되돌리기" 대상
const toast = ref('')
let tt
function showToast(msg, ms = 1600) { toast.value = msg; clearTimeout(tt); tt = setTimeout(() => { toast.value = '' }, ms) }
function toggleCardMenu(a) { cardMenu.value = cardMenu.value === a.accidentId ? null : a.accidentId }
function askHide(a) { cardMenu.value = null; target.value = a }
function confirmHide() {
  const a = target.value
  target.value = null
  if (!a) return
  store.hide(a.accidentId)
  lastHidden.value = a.accidentId
  showToast('체크리스트를 숨겼어요', 4000)
}
function undoHide() { if (lastHidden.value != null) store.unhide(lastHidden.value); lastHidden.value = null; toast.value = '' }
function restoreAll() { menu.value = false; store.unhideAll(); lastHidden.value = null; showToast('숨긴 체크리스트를 모두 나타냈어요') }
</script>

<template>
  <Screen>
    <AppHeader title="나의 체크리스트" back="/home" back-history line>
      <template #right>
        <!-- 화면 더보기 — 숨긴 체크리스트 나타내기만. 받은 체크리스트가 있을 때만 -->
        <button v-if="list.length || hiddenCount" class="icn" aria-label="더보기" :aria-expanded="menu" @click="menu = !menu">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="#191F28"/><circle cx="10" cy="10" r="1.8" fill="#191F28"/><circle cx="10" cy="16" r="1.8" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>
    <div v-if="menu" class="menu-dim" @click="menu = false"></div>
    <div v-if="menu" class="menu top" role="menu">
      <button role="menuitem" :disabled="!hiddenCount" @click="restoreAll">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M1.5 8c1.2-3 3.7-4.8 6.5-4.8s5.3 1.8 6.5 4.8c-1.2 3-3.7 4.8-6.5 4.8S2.7 11 1.5 8z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.5"/></svg>
        숨긴 체크리스트 나타내기<span v-if="hiddenCount" class="cnt">{{ hiddenCount }}</span>
      </button>
    </div>

    <!-- 첫 로딩 -->
    <div v-if="!store.loaded && store.loading" class="body col" role="status">
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
            <!-- 카드 더보기 — 체크리스트 숨기기 -->
            <button class="more" :aria-label="`${vehicleName(a)} 체크리스트 더보기`" :aria-expanded="cardMenu === a.accidentId" @click.stop="toggleCardMenu(a)">
              <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="currentColor"/><circle cx="10" cy="10" r="1.8" fill="currentColor"/><circle cx="10" cy="16" r="1.8" fill="currentColor"/></svg>
            </button>
          </div>
          <div v-if="cardMenu === a.accidentId" class="menu card-menu" role="menu" @click.stop>
            <button role="menuitem" class="danger" @click="askHide(a)">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M2 2l12 12M6.6 6.7A2 2 0 0 0 9.3 9.4M4.4 4.5C2.9 5.5 1.9 6.9 1.5 8c1.2 3 3.7 4.8 6.5 4.8 1.1 0 2.1-.3 3-.7M7 3.3c.3 0 .7-.1 1-.1 2.8 0 5.3 1.8 6.5 4.8-.3.8-.8 1.6-1.4 2.3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
              체크리스트 숨기기
            </button>
          </div>
          <div class="desc">
            <span class="sub" style="font-size:14px;font-weight:500;text-align:left;min-width:0">{{ descText(a) }}</span>
          </div>
        </div>
      </div>
      <p class="sub center" style="margin-top:20px">예상 견적 분석이 끝나면 체크리스트가 자동으로 만들어져요</p>
    </div>

    <!-- 만들어진 체크리스트는 있는데 모두 숨김 -->
    <div v-else-if="hiddenCount" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>모든 체크리스트를 숨겼어요</b>
        <p>숨긴 체크리스트는 이 기기에서만 보이지 않아요<br>더보기에서 언제든 다시 나타낼 수 있어요</p>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="restoreAll">숨긴 체크리스트 나타내기</button>
      </div>
    </div>

    <!-- 비어 있음 -->
    <div v-else class="body col" style="padding:0 40px;align-items:center;justify-content:center">
      <svg width="64" height="64" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M8.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10A1.5 1.5 0 0 0 18.5 19V5A1.5 1.5 0 0 0 17 3.5h-1.5" stroke="#D1D6DB" stroke-width="1.5" stroke-linecap="round"/><rect x="8.5" y="2" width="7" height="3.4" rx="1.2" stroke="#D1D6DB" stroke-width="1.5"/><path d="M9 13l2.2 2.2L15.5 11" stroke="#D1D6DB" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
      <div style="margin-top:16px;font-size:15px;font-weight:500;color:var(--text-2)">아직 만든 체크리스트가 없어요</div>
      <p class="sub center" style="margin-top:6px">견적을 받고 나면 정비소에서<br>확인할 질문을 만들어 드려요</p>
    </div>
    <div class="spacer"></div>
    <Toast :show="!!toast">{{ toast }}<button v-if="lastHidden != null && toast === '체크리스트를 숨겼어요'" class="undo" @click="undoHide">되돌리기</button></Toast>

    <!-- 숨기기 확인 -->
    <BottomSheet :model-value="!!target" @update:model-value="target = null">
      <p class="st">이 체크리스트를 숨길까요?</p>
      <p class="sd"><b>해당 체크리스트를 숨기면 연결된 사고 이력도 함께 숨겨집니다.</b><br>이 기기에서만 보이지 않게 되며 서버의 기록과 견적은 그대로 남아요. 더보기 메뉴에서 언제든 다시 나타낼 수 있어요.</p>
      <div v-if="target" class="acts">
        <button class="btn outline" @click="target = null">취소</button>
        <button class="btn bold" @click="confirmHide">숨기기</button>
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
.undo { pointer-events: auto; margin-left: 12px; font-size: 13px; font-weight: 700; color: var(--primary-200); text-decoration: underline; }
</style>
