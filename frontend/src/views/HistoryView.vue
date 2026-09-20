<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import BottomSheet from '../components/BottomSheet.vue'
import AccidentRow from '../components/AccidentRow.vue'
import { useAccidentStore } from '../stores/accidents'
import { useEstimatePdf } from '../lib/estimatePdf'
import { vehicleName } from '../data/vehicles'
import { accidentRoute, groupAccidents } from '../data/accidents'

/* ===== 사고 이력 (S12d) — GET /api/accidents/me =====
 * 서버가 createdAt 내림차순 페이지로 주고, 그룹("이번 주"·"8월")·한글 배지·금액 포맷은 FE 가 만든다.
 * 썸네일은 10분짜리 서명 URL 이라 화면에 들어올 때마다 목록을 새로 받는다.
 * 화면 문구는 "제거하기" 지만 서버는 지우지 않고 감춘다(PATCH .../hidden, S15P21A307-554) — 사고 삭제 API 가 없고, 지우면 견적·리포트의 근거가 사라진다.
 * 지우는 것이 아니라 서버가 내 목록에서 자리만 감춘다 — 기기를 바꿔도 유지되고, 사진·분석·견적·체크리스트와 이미 받은 리포트 링크는 그대로다.
 * 사고와 체크리스트는 1:1 이라 제거하면 체크리스트도 함께 빠진다고 안내한다. 더보기(세로 ⋮) → 이력 제거하기 → 카드마다 제거 버튼 → 확인 시트.
 * 되돌리기는 "제거한 이력" 화면에서 건별로 한다 — 서버에 일괄 복구가 없고, 무엇을 되살리는지 보고 고르는 편이 안전하다.
 * 견적 PDF 도 같은 방식이다(S15P21A307-558) — 더보기 → 견적 다운로드 → 카드마다 PDF 버튼. 평소에는 카드에 두지 않는다.
 *   문구를 "견적" 으로 두는 건 받는 파일이 예상견적_{reportNo}.pdf 이기 때문이다 — 화면 문구와 내려받은 파일 이름이 어긋나지 않게.
 *   목록에 들어오는 대부분은 이력을 훑으러 온 것이지 파일을 받으러 온 것이 아니다. 카드마다 버튼이 서 있으면 누를 것이 둘이 되어 어느 쪽이 본동작인지 흐려진다.
 * 행 구성(S15P21A307-531): 사고마다 카드 하나(components/AccidentRow — 홈 미리보기와 공유) | 오른쪽 세로 중앙에 동작 자리(평소 화살표, 모드에서 PDF·제거 버튼).
 * 예상 금액·사진 장수·접수일은 행에서 뺐다(금액은 견적 화면·홈 카드에서, 날짜는 그룹 라벨로).
 * 사고 설명은 목록 API 에 없어 견적이 있는 행만 견적 항목으로 조립한다(lib/accidentDesc — 나의 체크리스트와 공유).
 */
const router = useRouter()
const store = useAccidentStore()
const groups = computed(() => groupAccidents(store.items))

onMounted(() => store.load(true))

function open(a) { if (!picking.value) router.push(accidentRoute(a)) }

/* ===== 더보기 메뉴 · 제거 모드 · 제거한 이력 화면 ===== */
const menu = ref(false)
const hideMode = ref(false) // 켜지면 카드 이동이 멈추고 동작 자리에 제거 버튼이 나온다
const pdfMode = ref(false) // 켜지면 카드 이동이 멈추고 동작 자리에 PDF 받기 버튼이 나온다
/** 카드를 "고르는" 모드 — 둘 중 하나라도 켜져 있으면 카드 이동을 멈춘다 */
const picking = computed(() => hideMode.value || pdfMode.value)
/** 받을 리포트가 하나도 없으면 모드에 들어가 봐야 전부 비활성이다 */
const anyEstimate = computed(() => store.items.some((a) => a.estimateId))
const showHidden = ref(false) // 제거한 이력 화면
const target = ref(null) // 확인 시트가 가리키는 사고
const lastHidden = ref(null) // 토스트의 "되돌리기" 대상
const busy = ref(false) // 제거·되돌리기 요청 중 — 연타 방지
function endPicking() { hideMode.value = false; pdfMode.value = false }
function startHide() { menu.value = false; pdfMode.value = false; hideMode.value = true }
function startPdf() { menu.value = false; hideMode.value = false; pdfMode.value = true }
function openHidden() { menu.value = false; endPicking(); showHidden.value = true; store.loadHidden() }
function closeHidden() { showHidden.value = false }
function askHide(a) { target.value = a }
async function confirmHide() {
  const a = target.value
  target.value = null
  if (!a || busy.value) return
  busy.value = true
  try {
    await store.hide(a.accidentId)
    lastHidden.value = a.accidentId
    showToast('이력을 제거했어요', 4000)
    if (!store.items.length) hideMode.value = false // 다 제거했으면 모드 종료 → 빈 화면 안내로
  } catch (e) {
    if (e?.status !== 401) showToast(e?.status === 404 ? '사고를 찾을 수 없어요.' : '제거하지 못했어요. 잠시 후 다시 시도해 주세요.', 2600)
  } finally { busy.value = false }
}
/** 한 건 되돌리기 — 토스트의 "되돌리기" 와 제거한 이력 화면의 버튼이 함께 쓴다 */
async function restore(accidentId, { fromToast = false } = {}) {
  if (busy.value) return
  busy.value = true
  try {
    await store.unhide(accidentId)
    if (fromToast) { lastHidden.value = null; toast.value = '' } else showToast('이력을 되돌렸어요')
  } catch (e) {
    if (e?.status !== 401) showToast(e?.status === 404 ? '사고를 찾을 수 없어요.' : '되돌리지 못했어요. 잠시 후 다시 시도해 주세요.', 2600)
  } finally { busy.value = false }
}
function undoHide() { if (lastHidden.value != null) restore(lastHidden.value, { fromToast: true }) }


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
        <!-- 고르는 모드(제거·리포트)에서는 "완료", 평소에는 더보기(세로 점 3개). 받은 이력이 있을 때만 -->
        <button v-if="picking" class="act strong" @click="endPicking">완료</button>
        <button v-else-if="showHidden" class="act strong" @click="closeHidden">완료</button>
        <button v-else-if="store.all.length || store.hiddenCount" class="icn" aria-label="더보기" :aria-expanded="menu" @click="menu = !menu">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="#191F28"/><circle cx="10" cy="10" r="1.8" fill="#191F28"/><circle cx="10" cy="16" r="1.8" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>
    <!-- 더보기 메뉴 -->
    <div v-if="menu" class="menu-dim" @click="menu = false"></div>
    <div v-if="menu" class="menu" role="menu">
      <button role="menuitem" :disabled="!anyEstimate" @click="startPdf">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4.3 1.8h5l3 3v8a1.3 1.3 0 0 1-1.3 1.3H4.3A1.3 1.3 0 0 1 3 12.8V3.1a1.3 1.3 0 0 1 1.3-1.3z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M9.3 1.8v3h3" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M8 7.2v3.6M6.2 9l1.8 1.8L9.8 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
        견적 다운로드
      </button>
      <button role="menuitem" :disabled="!store.items.length" @click="startHide">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M2.5 4.2h11M6.2 4.2V2.9h3.6v1.3M3.9 4.2l.6 8.2a1 1 0 0 0 1 .9h5a1 1 0 0 0 1-.9l.6-8.2M6.6 6.8v4M9.4 6.8v4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
        이력 제거하기
      </button>
      <button role="menuitem" @click="openHidden">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M1.5 8c1.2-3 3.7-4.8 6.5-4.8s5.3 1.8 6.5 4.8c-1.2 3-3.7 4.8-6.5 4.8S2.7 11 1.5 8z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.5"/></svg>
        제거한 이력 보기<span v-if="store.hiddenCount" class="cnt">{{ store.hiddenCount }}</span>
      </button>
    </div>

    <!-- 제거한 이력 — 건별 되돌리기 -->
    <div v-if="showHidden" class="body scroll" style="padding-top:20px">
      <div class="row between">
        <span class="lbl">제거한 이력 {{ store.hiddenCount }}건</span>
      </div>
      <p class="sub" style="margin-top:6px;font-size:12px;line-height:1.5">제거한 이력은 목록에서 빠져요. 사진·분석 결과·견적은 보관돼 있어 되돌릴 수 있어요.</p>

      <div v-if="store.hiddenLoading && !store.hiddenLoaded" class="sub center" style="padding:40px 0" role="status">제거한 이력을 불러오고 있어요…</div>
      <div v-else-if="store.hiddenError" class="empty" style="padding-top:40px">
        <b>{{ store.hiddenError }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="store.loadHidden()">다시 시도</button>
      </div>
      <template v-else-if="store.hidden.length">
        <AccidentRow v-for="a in store.hidden" :key="a.accidentId" :a="a" :clickable="false">
          <template #action>
            <button class="pdf back" :disabled="busy" :aria-label="`${vehicleName(a)} 이력 되돌리기`" title="되돌리기" @click.stop="restore(a.accidentId)">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3M4.5 4.5v4h4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
              <span class="pl">되돌리기</span>
            </button>
          </template>
        </AccidentRow>
      </template>
      <div v-else class="empty" style="padding-top:40px">
        <img src="/assets/logo-small.png" alt="">
        <b>제거한 이력이 없어요</b>
        <p>더보기에서 이력을 제거하면 여기에 모여요</p>
      </div>
      <div style="height:24px"></div>
    </div>

    <!-- 첫 로딩 -->
    <div v-else-if="!store.loaded && store.loading" class="body col" role="status">
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
        <span v-if="hideMode" class="sub" style="font-size:12px;color:var(--primary)">제거할 이력을 선택하세요</span>
        <span v-else-if="pdfMode" class="sub" style="font-size:12px;color:var(--primary)">견적을 받을 이력을 선택하세요</span>
      </div>
      <template v-for="g in groups" :key="g.label">
        <div class="lbl" style="margin-top:16px">{{ g.label }}</div>
        <!-- 카드는 홈 "최근 사고" 미리보기와 공유(AccidentRow). 오른쫽 동작만 여기서 끼운다 -->
        <AccidentRow v-for="a in g.items" :key="a.accidentId" :a="a" :clickable="!picking" :class="{ picking }" @open="open">
          <template #action>
            <!-- 제거 모드: PDF 자리에 제거 버튼 -->
            <button v-if="hideMode" class="pdf hide" :disabled="busy" :aria-label="`${vehicleName(a)} 이력 제거하기`" @click.stop="askHide(a)">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M3.5 6.3h17M9.3 6.3V4.3a1 1 0 0 1 1-1h3.4a1 1 0 0 1 1 1v2M5.8 6.3l.9 12.4a1.6 1.6 0 0 0 1.6 1.5h7.4a1.6 1.6 0 0 0 1.6-1.5l.9-12.4M10 10.2v6M14 10.2v6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
              <span class="pl">제거</span>
            </button>
            <!-- 리포트 모드: PDF 받기. 견적이 없는 행도 같은 자리에 비활성으로 둬 행마다 배치가 같게 -->
            <button v-else-if="pdfMode" class="pdf" :class="{ busy: pdfBusyFor(a) }" :disabled="!a.estimateId || pdfBusyId !== null" :aria-busy="pdfBusyFor(a)"
              :aria-label="pdfLabel(a)" :title="pdfLabel(a)" @click.stop="pdf(a)">
              <span v-if="pdfBusyFor(a)" class="spin" aria-hidden="true"></span>
              <svg v-else width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M6.5 2.5h7.5l4.5 4.5v12a2 2 0 0 1-2 2h-10a2 2 0 0 1-2-2v-14.5a2 2 0 0 1 2-2z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
                <path d="M14 2.5v4.5h4.5" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
                <path d="M12 10.5v6M9.3 14.2L12 16.9l2.7-2.7" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span class="pl">PDF</span>
            </button>
            <!-- 평소: 눌러서 들어가는 카드라는 표시만. 홈 미리보기와 같은 화살표다 -->
            <svg v-else width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 18px"><path d="M6 3.5L10.5 8L6 12.5" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </template>
        </AccidentRow>
      </template>

      <!-- 다음 페이지 -->
      <button v-if="store.hasNext" class="btn outline muted" style="margin-top:16px" :disabled="store.loading" @click="store.loadMore()">
        {{ store.loading ? '불러오는 중…' : '더 보기' }}
      </button>
      <p v-if="store.loaded && store.error" class="sub center" style="margin-top:12px;color:var(--danger-2)" role="alert">{{ store.error }}</p>
      <div style="height:24px"></div>
    </div>

    <!-- 보이는 이력이 없고 제거한 것만 있음 -->
    <div v-else-if="store.hiddenCount" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>모든 이력을 제거했어요</b>
        <p>제거한 이력은 목록에서 빠져 있어요<br>필요하면 하나씩 되돌릴 수 있어요</p>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="openHidden">제거한 이력 보기</button>
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
    <Toast :show="!!toast">{{ toast }}<button v-if="lastHidden != null && toast === '이력을 제거했어요'" class="undo" @click="undoHide">되돌리기</button></Toast>

    <!-- 제거 확인 -->
    <BottomSheet :model-value="!!target" @update:model-value="target = null">
      <p class="st">이 사고 이력을 제거할까요?</p>
      <p class="sd"><b>해당 이력을 제거하면 연결된 체크리스트도 함께 제거됩니다.</b><br>사진·분석 결과·견적은 보관되며, 더보기 &gt; 제거한 이력 보기에서 하나씩 되돌릴 수 있어요.</p>
      <div v-if="target" class="acts">
        <button class="btn outline" @click="target = null">취소</button>
        <button class="btn bold" :disabled="busy" @click="confirmHide">제거</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.picking { border-color: var(--primary-200); }
.menu-dim { position: absolute; inset: 0; z-index: 5; }
.menu { position: absolute; top: 48px; right: 12px; z-index: 6; min-width: 190px; padding: 6px; background: var(--white); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 8px 24px rgba(25,31,40,.12); animation: sheetup .15s ease-out; }
.menu button { width: 100%; height: 42px; padding: 0 10px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; color: var(--text); text-align: left; }
.menu button:hover { background: var(--bg-2); }
.menu button:disabled { color: var(--text-4); }
.menu .cnt { margin-left: auto; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px; background: var(--primary-100); color: var(--primary); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.undo { pointer-events: auto; margin-left: 12px; font-size: 13px; font-weight: 700; color: var(--primary-200); text-decoration: underline; }
.pdf { flex: 0 0 48px; width: 48px; height: 48px; border: 1px solid var(--primary-200); border-radius: 12px; background: var(--primary-50); color: var(--primary); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1px; }
.pdf:hover { background: var(--primary-100); }
.pdf:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
.pl { font-size: 9px; font-weight: 700; letter-spacing: 0.02em; line-height: 1; }
.pdf.hide { border-color: var(--danger-bg); background: var(--danger-bg); color: var(--danger-2); }
.pdf.hide:hover { background: #FBDDDD; }
.pdf.back .pl { font-size: 8px; }
.spin { width: 18px; height: 18px; border-radius: 50%; border: 2px solid var(--primary-200); border-top-color: var(--primary); animation: dcspin .8s linear infinite; }
</style>
