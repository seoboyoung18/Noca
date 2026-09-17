<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useAccidentStore } from '../stores/accidents'
import { useEstimatePdf } from '../lib/estimatePdf'
import { vehicleName } from '../data/vehicles'
import { useAccidentDesc } from '../lib/accidentDesc'
import { accidentRoute, accidentStatus, groupAccidents } from '../data/accidents'

/* ===== 사고 이력 (S12d) — GET /api/accidents/me =====
 * 서버가 createdAt 내림차순 페이지로 주고, 그룹("이번 주"·"8월")·한글 배지·금액 포맷은 FE 가 만든다.
 * 썸네일은 10분짜리 서명 URL 이라 화면에 들어올 때마다 목록을 새로 받는다.
 * 삭제는 서버에 사고 삭제 API 가 없어 두지 않는다 — 대신 "숨기기"(이 기기 localStorage, 사고 스토어 hidden)로 목록에서만 뺀다.
 * 사고와 체크리스트는 1:1 이라 숨기면 체크리스트도 함께 숨겨진다고 안내한다. 더보기(세로 ⋮) → 이력 숨기기 → 카드마다 숨기기 버튼 → 확인 시트.
 * 행 구성(S15P21A307-531): 사고마다 카드 하나 — 썸네일 | 차량명+상태 배지 · 사고 설명 | 오른쪽 세로 중앙에 PDF 아이콘 버튼.
 * 예상 금액·사진 장수·접수일은 행에서 뺐다(금액은 견적 화면·홈 카드에서, 날짜는 그룹 라벨로).
 * 사고 설명은 목록 API 에 없어 견적이 있는 행만 견적 항목으로 조립한다(lib/accidentDesc — 나의 체크리스트와 공유).
 */
const router = useRouter()
const store = useAccidentStore()
const groups = computed(() => groupAccidents(store.items))
const broken = reactive({}) // 만료·404 로 깨진 썸네일 accidentId → 자리표시로 대체

onMounted(() => store.load(true))

function open(a) { if (!hideMode.value) router.push(accidentRoute(a)) }

/* ===== 더보기 메뉴 · 숨기기 모드 ===== */
const menu = ref(false)
const hideMode = ref(false) // 켜지면 카드 이동이 멈추고 PDF 자리에 숨기기 버튼이 나온다
const target = ref(null) // 확인 시트가 가리키는 사고
const lastHidden = ref(null) // 토스트의 "되돌리기" 대상
function startHide() { menu.value = false; hideMode.value = true }
function restoreAll() { menu.value = false; store.unhideAll(); lastHidden.value = null; showToast('숨긴 이력을 모두 되돌렸어요') }
function askHide(a) { target.value = a }
function confirmHide() {
  const a = target.value
  target.value = null
  if (!a) return
  store.hide(a.accidentId)
  lastHidden.value = a.accidentId
  showToast('이력을 숨겼어요', 4000)
  if (!store.items.length) hideMode.value = false // 다 숨겼으면 모드 종료 → "모두 숨김" 안내로
}
function undoHide() { if (lastHidden.value != null) store.unhide(lastHidden.value); lastHidden.value = null; toast.value = '' }

/* ===== 사고 설명 — 견적 항목의 부위·손상 유형으로 조립 (lib/accidentDesc) ===== */
const { ensure: ensureDesc, text: descText } = useAccidentDesc()
watch(() => store.items, ensureDesc, { immediate: true })

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
    <AppHeader title="사고 이력" back="/home" back-history>
      <template #right>
        <!-- 숨기기 모드에서는 "완료", 평소에는 더보기(세로 점 3개). 받은 이력이 있을 때만 -->
        <button v-if="hideMode" class="act strong" @click="hideMode = false">완료</button>
        <button v-else-if="store.all.length" class="icn" aria-label="더보기" :aria-expanded="menu" @click="menu = !menu">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="#191F28"/><circle cx="10" cy="10" r="1.8" fill="#191F28"/><circle cx="10" cy="16" r="1.8" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>
    <!-- 더보기 메뉴 -->
    <div v-if="menu" class="menu-dim" @click="menu = false"></div>
    <div v-if="menu" class="menu" role="menu">
      <button role="menuitem" :disabled="!store.items.length" @click="startHide">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M2 2l12 12M6.6 6.7A2 2 0 0 0 9.3 9.4M4.4 4.5C2.9 5.5 1.9 6.9 1.5 8c1.2 3 3.7 4.8 6.5 4.8 1.1 0 2.1-.3 3-.7M7 3.3c.3 0 .7-.1 1-.1 2.8 0 5.3 1.8 6.5 4.8-.3.8-.8 1.6-1.4 2.3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        이력 숨기기
      </button>
      <button role="menuitem" :disabled="!store.hiddenCount" @click="restoreAll">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8a5 5 0 1 0 1.5-3.6M3 2.5v3h3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        숨긴 이력 되돌리기<span v-if="store.hiddenCount" class="cnt">{{ store.hiddenCount }}</span>
      </button>
    </div>

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
        <span class="lbl">총 {{ store.visibleTotal ?? store.items.length }}건</span>
        <span v-if="hideMode" class="sub" style="font-size:12px;color:var(--primary)">숨길 이력을 선택하세요</span>
      </div>
      <template v-for="g in groups" :key="g.label">
        <div class="lbl" style="margin-top:16px">{{ g.label }}</div>
        <div v-for="a in g.items" :key="a.accidentId" class="card item" :class="{ clickable: !hideMode, picking: hideMode }" :role="hideMode ? null : 'link'" :tabindex="hideMode ? -1 : 0" @click="open(a)" @keydown.enter="open(a)">
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
            <span class="sub" style="font-size:13px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">{{ descText(a) }}</span>
          </span>
          <!-- 숨기기 모드: PDF 자리에 숨기기 버튼 -->
          <button v-if="hideMode" class="pdf hide" :aria-label="`${vehicleName(a)} 이력 숨기기`" @click.stop="askHide(a)">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M3 3l18 18M10 10.6A2.5 2.5 0 0 0 13.4 14M6.6 6.7C4.6 8 3.1 9.9 2.5 12c1.7 4.2 5.3 6.8 9.5 6.8 1.6 0 3.1-.4 4.4-1M10.5 5.3c.5-.1 1-.1 1.5-.1 4.2 0 7.8 2.6 9.5 6.8-.5 1.2-1.2 2.3-2 3.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
            <span class="pl">숨기기</span>
          </button>
          <!-- PDF 받기 — 아이콘 버튼(문서+내려받기 화살표, 아래 "PDF"). 견적이 없는 행도 같은 자리에 비활성으로 둬 행마다 배치가 같게 -->
          <button v-else class="pdf" :class="{ busy: pdfBusyFor(a) }" :disabled="!a.estimateId || pdfBusyId !== null" :aria-busy="pdfBusyFor(a)"
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

    <!-- 받은 이력은 있는데 모두 숨김 -->
    <div v-else-if="store.all.length" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>모든 이력을 숨겼어요</b>
        <p>숨긴 이력은 이 기기에서만 보이지 않아요<br>더보기에서 언제든 되돌릴 수 있어요</p>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="restoreAll">숨긴 이력 되돌리기</button>
      </div>
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
    <Toast :show="!!toast">{{ toast }}<button v-if="lastHidden != null && toast === '이력을 숨겼어요'" class="undo" @click="undoHide">되돌리기</button></Toast>

    <!-- 숨기기 확인 -->
    <BottomSheet :model-value="!!target" @update:model-value="target = null">
      <p class="st">이 사고 이력을 숨길까요?</p>
      <p class="sd"><b>해당 이력을 숨기면 연결된 체크리스트도 함께 숨겨집니다.</b><br>이 기기에서만 보이지 않게 되며 서버의 기록과 견적은 그대로 남아요. 더보기 메뉴에서 언제든 되돌릴 수 있어요.</p>
      <div v-if="target" class="acts">
        <button class="btn outline" @click="target = null">취소</button>
        <button class="btn bold" @click="confirmHide">숨기기</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.item { margin-top: 8px; padding: 14px; display: flex; align-items: center; gap: 14px; }
.item.clickable { cursor: pointer; }
.item.picking { border-color: var(--primary-200); }
.menu-dim { position: absolute; inset: 0; z-index: 5; }
.menu { position: absolute; top: 48px; right: 12px; z-index: 6; min-width: 190px; padding: 6px; background: var(--white); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 8px 24px rgba(25,31,40,.12); animation: sheetup .15s ease-out; }
.menu button { width: 100%; height: 42px; padding: 0 10px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; color: var(--text); text-align: left; }
.menu button:hover { background: var(--bg-2); }
.menu button:disabled { color: var(--text-4); }
.menu .cnt { margin-left: auto; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px; background: var(--primary-100); color: var(--primary); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.undo { pointer-events: auto; margin-left: 12px; font-size: 13px; font-weight: 700; color: var(--primary-200); text-decoration: underline; }
.th { flex: 0 0 76px; width: 76px; height: 76px; border-radius: 12px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.pdf { flex: 0 0 48px; width: 48px; height: 48px; border: 1px solid var(--primary-200); border-radius: 12px; background: var(--primary-50); color: var(--primary); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1px; }
.pdf:hover { background: var(--primary-100); }
.pdf:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
.pl { font-size: 9px; font-weight: 700; letter-spacing: 0.02em; line-height: 1; }
.pdf.hide { border-color: var(--danger-bg); background: var(--danger-bg); color: var(--danger-2); }
.pdf.hide:hover { background: #FBDDDD; }
.spin { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--primary-200); border-top-color: var(--primary); animation: dcspin .8s linear infinite; }
</style>
