<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import CheckItem from '../components/CheckItem.vue'
import Toast from '../components/Toast.vue'
import { useAccidentStore } from '../stores/accidents'
import { useChecklistStore } from '../stores/checklist'
import { useAccidentDesc } from '../lib/accidentDesc'
import { fetchAccidentEstimates, fetchEstimate } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'
import { vehicleName } from '../data/vehicles'
import { accidentDateShort } from '../data/accidents'
import { wonOne } from '../data/estimates'
import {
  CHECKLIST_DEFAULT_NOTICE, CHECKLIST_TABS, aiSummary, checklistFailText, checklistPending, groupChecklistItems,
} from '../data/checklists'

/* ===== 정비 체크리스트 (S14b) — GET/POST /api/accidents/{id}/repair-checklist =====
 * 사고 id 는 쿼리(accidentId). 상단 날짜·차량명은 사고 스토어, 요약 줄은 견적(부위·수리 방식·중앙값), 항목은 체크리스트 스토어.
 * 서버가 자동 생성하지 않으므로 진입 시 ensureRequested → pending 이면 2초 폴링 → COMPLETED 면 목록, FAILED 면 실패 안내 + 다시 시도.
 * 단, <b>산정된 견적이 있을 때만</b> 만든다(S15P21A307-559, 견적 화면과 같은 규칙) — 산정 불가 사고는 "아직 견적이 산정되지 않았어요" 로 멈춘다.
 * 탭은 이전 구성(공통 / 부품 / 함께 점검). 서버 항목에 분류가 없어 AI 항목은 견적 부위명이 문장에 있으면 그 부위 그룹, 없으면 "함께 점검" 으로 FE 가 나눈다
 * (data/checklists groupChecklistItems). 직접 추가 항목의 구분은 이 기기에 저장. 심각도는 응답에 없어 두지 않는다.
 * AI 한 줄 요약은 응답에 아직 없어 목업 문구로 유지한다(aiSummary — 서버가 summary 를 주면 자동 전환, 백엔드 요청 3).
 * 문안 수정·삭제는 USER 항목만(서버 400), 체크·메모는 모든 항목.
 */
const route = useRoute()
const router = useRouter()
const accidentId = Number(route.query.accidentId) || (AUTH_GUARD_OFF ? 1 : null)
const accidents = useAccidentStore()
const checklists = useChecklistStore()
const { text: descText, ensure: ensureDesc } = useAccidentDesc()

const accident = computed(() => accidents.all.find((a) => a.accidentId === accidentId) || null)
const cl = computed(() => checklists.get(accidentId))
const loading = computed(() => !cl.value && checklists.loading[accidentId])
const error = computed(() => checklists.error[accidentId] || '')
const pending = computed(() => !!cl.value && checklistPending(cl.value.status))
const groups = computed(() => groupChecklistItems(cl.value?.items || [], estimate.value?.items || [], checklists.cats))
const progress = computed(() => cl.value?.progress || { completed: 0, total: 0 })
const notice = computed(() => cl.value?.notice || CHECKLIST_DEFAULT_NOTICE)
const ai = computed(() => aiSummary(cl.value))
const pop = ref(false)
// 팝오버 바깥 클릭 시 닫기
function onDoc(e) { if (!e.target.closest('.pop, .infob')) pop.value = false }
onMounted(() => document.addEventListener('click', onDoc))
onUnmounted(() => document.removeEventListener('click', onDoc))

/* ----- 요약 줄: 견적의 수리 방식 개수 · 중앙값 ----- */
const estimate = ref(null)
const summary = computed(() => {
  const items = estimate.value?.items || []
  const counts = {}
  for (const it of items) { const m = it.repairMethodDisplayName; if (m) counts[m] = (counts[m] || 0) + 1 }
  const parts = Object.entries(counts).map(([m, n]) => `${m} ${n}곳`)
  const median = wonOne(estimate.value?.totalMedian)
  if (median) parts.push(`예상 ${median}`)
  return parts.join(' · ')
})

const notEstimable = ref(false) // 산정된 견적이 없어 체크리스트를 만들지 않은 상태

/** 산정된 견적이 있는가 — 없으면 체크리스트를 만들지 않는다(견적 화면과 같은 규칙). 목업은 사고 목록의 estimateId 로 가른다 */
async function estimated() {
  if (AUTH_GUARD_OFF) return !!accident.value?.estimateId
  try {
    const latest = (await fetchAccidentEstimates(accidentId))[0]
    if (!latest) return false
    const est = await fetchEstimate(latest.estimateId)
    if (!estimate.value) estimate.value = est // 요약 줄(수리 방식 개수 · 중앙값)에도 쓴다
    return !!est.estimable
  } catch { return false } // 확인이 안 되면 만들지 않는다 — "다시 확인" 으로 재시도할 수 있다
}

async function init() {
  if (!accidentId) return
  notEstimable.value = false
  checklists.loadCats()
  if (!accidents.loaded) await accidents.load(true)
  const res = await checklists.load(accidentId)
  // 요청 전(null)이면 여기서 큐에 넣는다 — 견적 화면을 거치지 않고 들어온 경우. 산정된 견적이 없으면 만들지 않는다
  if (res && res.status === null) {
    if (await estimated()) await checklists.ensureRequested(accidentId)
    else { notEstimable.value = true; return }
  }
  if (checklistPending(checklists.get(accidentId)?.status)) checklists.startPolling(accidentId)
}
onMounted(init)
onUnmounted(() => checklists.stopPolling(accidentId))
watch(accident, (a) => {
  if (!a) return
  ensureDesc([a])
  if (a.estimateId && !estimate.value) {
    if (AUTH_GUARD_OFF) {
      estimate.value = { totalMedian: 1240000, items: [
        { partCode: 'FRONT_BUMPER', partNameKo: '프론트 범퍼', repairMethodDisplayName: '교환' }, { partCode: 'HEAD_LAMP_L', partNameKo: '헤드램프(좌)', repairMethodDisplayName: '교환' },
        { partCode: 'FRONT_FENDER_L', partNameKo: '앞휀더(좌)', repairMethodDisplayName: '판금 후 도장' }, { partCode: 'FRONT_DOOR_L', partNameKo: '앞도어(좌)', repairMethodDisplayName: null },
      ] }
    } else fetchEstimate(a.estimateId).then((e) => { estimate.value = e }).catch(() => {})
  }
}, { immediate: true })
// 재생성·재시도 뒤 상태가 pending 으로 바뀌면 폴링을 다시 건다
watch(() => cl.value?.status, (s, prev) => { if (checklistPending(s) && s !== null && !checklistPending(prev)) checklists.startPolling(accidentId) })

/* ----- 토스트 ----- */
const toast = ref('')
let tt
function showToast(msg, ms = 1600) { toast.value = msg; clearTimeout(tt); tt = setTimeout(() => { toast.value = '' }, ms) }
const failMsg = (e, fallback) => (e?.status === 400 ? 'AI·공통 항목은 문안을 바꿀 수 없어요.' : e?.status === 0 ? e.message : fallback)

/* ----- 탭 · 편집 ----- */
const tab = ref('common')
const editing = ref(false)
const sh = reactive({ more: false, add: false, edit: false, regen: false, notice: false })
const busy = ref(false)
async function run(fn, fallback) {
  if (busy.value) return
  busy.value = true
  try { await fn() } catch (e) { if (e?.status !== 401) showToast(failMsg(e, fallback), 2400) } finally { busy.value = false }
}

function toggle(it) { run(() => checklists.toggle(accidentId, it.id, !it.done), '체크 상태를 저장하지 못했어요.') }

// 항목 추가 (USER) — 구분(공통·부품·함께 점검)은 이 기기에만 저장된다(서버에 자리 없음)
const addText = ref('')
const addCat = ref('parts')
function openAdd() { sh.more = false; addText.value = ''; addCat.value = tab.value; sh.add = true }
function doAdd() {
  const t = addText.value.trim()
  if (!t) return
  run(async () => { await checklists.add(accidentId, t, addCat.value); tab.value = addCat.value; sh.add = false; showToast('항목을 추가했어요') }, '항목을 추가하지 못했어요.')
}

// 항목 수정 — USER 는 문안+메모, AI·COMMON 은 메모만
const cur = ref(null)
const edText = ref('')
const edMemo = ref('')
function openEdit(it) { cur.value = it; edText.value = it.text; edMemo.value = it.memo || ''; sh.edit = true }
function doSave() {
  const it = cur.value
  if (!it) return
  run(async () => {
    const text = edText.value.trim()
    if (it.editable && text && text !== it.text) await checklists.update(accidentId, it.id, text)
    const memo = edMemo.value.trim()
    if (memo !== (it.memo || '')) await checklists.setMemo(accidentId, it.id, memo)
    sh.edit = false
    showToast('저장했어요')
  }, '저장하지 못했어요.')
}
function doDelete() {
  const it = cur.value
  if (!it?.editable) return
  run(async () => { await checklists.remove(accidentId, it.id); sh.edit = false; showToast('항목을 삭제했어요') }, '삭제하지 못했어요.')
}

// 다시 생성 (COMPLETED) · 다시 시도 (FAILED)
function regen() {
  sh.regen = false
  run(() => checklists.regenerate(accidentId), '다시 생성을 요청하지 못했어요.')
}
function retry() { run(() => checklists.ensureRequested(accidentId), '다시 시도를 요청하지 못했어요.') }
</script>

<template>
  <Screen>
    <AppHeader title="정비 체크리스트" back="/checklists" back-history line>
      <template #right>
        <button v-if="editing" class="act strong" @click="editing = false">완료</button>
        <button v-else-if="cl?.status === 'COMPLETED'" class="icn" aria-label="더보기" @click="sh.more = true">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4" r="1.8" fill="#191F28"/><circle cx="10" cy="10" r="1.8" fill="#191F28"/><circle cx="10" cy="16" r="1.8" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>

    <!-- 사고 id 없음 -->
    <div v-if="!accidentId" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>어느 사고의 체크리스트인지 알 수 없어요</b>
        <p>나의 체크리스트에서 항목을 선택해 주세요</p>
        <button class="btn" style="margin-top:20px;width:auto;padding:0 24px;height:46px" @click="router.replace('/checklists')">나의 체크리스트</button>
      </div>
    </div>

    <!-- 첫 로딩 -->
    <div v-else-if="loading" class="body col" role="status">
      <p class="sub center" style="margin:auto 0">체크리스트를 불러오고 있어요…</p>
    </div>

    <!-- 조회 실패 -->
    <div v-else-if="!cl && error" class="body col">
      <div class="empty">
        <b>{{ error }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="init">다시 시도</button>
      </div>
    </div>

    <!-- 산정 전 — 견적이 나오기 전엔 체크리스트를 만들지 않는다 -->
    <div v-else-if="notEstimable" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>아직 견적이 산정되지 않았어요</b>
        <p>견적이 산정된 사고에만<br>정비 체크리스트를 만들어 드려요</p>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="init">다시 확인</button>
      </div>
    </div>

    <div v-else-if="cl" class="body scroll" style="padding-top:16px">
      <!-- 머리: 접수일 · 차량명 -->
      <div class="row" style="align-items:baseline;gap:8px">
        <span class="d">{{ accident ? accidentDateShort(accident.createdAt) : '' }}</span>
        <span class="tt nowrap" style="overflow:hidden;text-overflow:ellipsis">{{ accident ? vehicleName(accident) : '체크리스트' }}</span>
        <span v-if="cl.status === 'COMPLETED' && progress.total" class="checkcnt">{{ progress.completed }}/{{ progress.total }} 확인</span>
      </div>
      <div v-if="accident" class="row" style="margin-top:10px;gap:10px;position:relative">
        <span class="flex1" style="display:flex;flex-direction:column;gap:4px;min-width:0">
          <span style="font-size:14px;font-weight:600">{{ descText(accident) }}</span>
          <span v-if="summary" class="sub">{{ summary }}</span>
        </span>
        <!-- AI 한 줄 요약 — 서버 값이 생기기 전까지 목업 문구 -->
        <button v-if="cl.status === 'COMPLETED'" class="infob" :class="{ on: pop }" aria-label="AI 한 줄 요약 보기" :aria-expanded="pop" @click="pop = !pop">
          <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7" stroke="currentColor" stroke-width="1.5"/><path d="M10 9v4.5M10 6.5h.01" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
      </div>

      <!-- 만드는 중 (요청 전·대기·처리 중) -->
      <div v-if="pending" class="gen">
        <svg width="56" height="56" viewBox="0 0 72 72" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite">
          <circle cx="36" cy="36" r="33" stroke="#EEEBFD" stroke-width="6"/>
          <circle cx="36" cy="36" r="33" stroke="#4E36E4" stroke-width="6" stroke-linecap="round" stroke-dasharray="52 155" transform="rotate(-90 36 36)"/>
        </svg>
        <b>확인 항목을 정리하고 있어요</b>
        <p>분석 결과를 바탕으로 정비소에서 확인할 항목을 만들고 있어요.<br>{{ cl.status === 'PROCESSING' ? 'AI가 항목을 쓰고 있어요' : '잠시만 기다려 주세요' }}</p>
      </div>

      <!-- 실패 -->
      <div v-else-if="cl.status === 'FAILED'" class="gen fail">
        <svg width="44" height="44" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9.5" stroke="#D14343" stroke-width="1.6"/><path d="M12 7.5v5.5" stroke="#D14343" stroke-width="1.8" stroke-linecap="round"/><circle cx="12" cy="16.3" r="1.1" fill="#D14343"/></svg>
        <b>{{ checklistFailText(cl.failureReason) }}</b>
        <p>잠시 후 다시 시도해 주세요</p>
        <button class="btn" style="margin-top:16px;width:auto;padding:0 24px;height:44px" :disabled="busy" @click="retry">다시 시도</button>
      </div>

      <!-- 완료 -->
      <template v-else>
        <button class="note" @click="sh.notice = true">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 16px;margin-top:2px"><circle cx="8" cy="8" r="5.6" stroke="#4E5968" stroke-width="1.3"/><path d="M8 7.2v3.4M8 5.2h.01" stroke="#4E5968" stroke-width="1.4" stroke-linecap="round"/></svg>
          <span>{{ notice }}</span>
        </button>

        <div class="clbox">
          <div class="tabs">
            <button v-for="t in CHECKLIST_TABS" :key="t.key" :class="{ on: tab === t.key }" @click="tab = t.key"><span>{{ t.label }}</span><em>{{ groups.counts[t.key] }}</em></button>
          </div>

          <!-- 공통 -->
          <div v-if="tab === 'common'" class="pane">
            <CheckItem v-for="it in groups.common" :key="it.id" :item="it" :editing="editing" @toggle="toggle(it)" @edit="openEdit(it)" />
            <p v-if="!groups.common.length" class="sub center" style="padding:20px 0">항목이 없어요</p>
          </div>

          <!-- 부품 — 견적 부위별 그룹 + 직접 추가 -->
          <div v-else-if="tab === 'parts'">
            <div v-for="g in groups.parts" :key="g.id" class="pane grp">
              <div class="ph"><b>{{ g.name }}</b><span v-if="g.fix" class="sub">{{ g.fix }}</span></div>
              <CheckItem v-for="it in g.items" :key="it.id" :item="it" :editing="editing" @toggle="toggle(it)" @edit="openEdit(it)" />
            </div>
            <p v-if="!groups.parts.length" class="sub center" style="padding:20px 0">부위별 확인 항목이 없어요</p>
          </div>

          <!-- 함께 점검 — 사진에 보이지 않는 손상 후보 -->
          <div v-else class="pane">
            <p class="sub" style="padding:12px 0 2px">사진에 보이지 않는 손상 후보예요. 정비소에 함께 점검을 요청해 보세요.</p>
            <CheckItem v-for="it in groups.hidden" :key="it.id" :item="it" :editing="editing" @toggle="toggle(it)" @edit="openEdit(it)" />
            <p v-if="!groups.hidden.length" class="sub center" style="padding:20px 0">항목이 없어요</p>
          </div>
        </div>
      </template>
      <div style="height:20px"></div>
    </div>

    <div v-if="cl && !notEstimable" class="foot">
      <button class="btn bold" @click="router.push('/home')">
        <svg width="18" height="18" viewBox="0 0 22 22" fill="none" aria-hidden="true"><path d="M3.5 9.5L11 3.5l7.5 6v8a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1z" stroke="#FFFFFF" stroke-width="1.8" stroke-linejoin="round"/></svg>
        홈으로
      </button>
    </div>

    <Toast :show="!!toast">{{ toast }}</Toast>

    <!-- AI 한 줄 요약 팝오버 (목업 유지 — 서버 summary 연동 전) -->
    <div v-if="pop" class="pop">
      <div class="row between">
        <span class="aitag">AI 한 줄 요약</span>
        <button class="x" aria-label="닫기" @click="pop = false">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" stroke="#B0B8C1" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
      </div>
      <p style="margin-top:12px;font-size:14px;line-height:1.55">{{ ai.summary }}</p>
      <p v-if="ai.focus" style="margin-top:10px;font-size:14px;line-height:1.55;color:var(--text-2)"><strong style="font-weight:700;color:var(--primary)">중점 확인 권장</strong> {{ ai.focus }}</p>
    </div>

    <!-- 더보기 -->
    <BottomSheet v-model="sh.more">
      <div class="mlist">
        <button class="mrow" @click="openAdd"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 4v12M4 10h12" stroke="#4E36E4" stroke-width="1.8" stroke-linecap="round"/></svg></span><span class="ml">항목 직접 추가</span></button>
        <button class="mrow" @click="sh.more = false; editing = true"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#4E36E4" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#4E36E4" stroke-width="1.6"/></svg></span><span class="ml">항목 편집 · 메모</span></button>
        <button class="mrow" @click="sh.more = false; sh.regen = true"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M16 9a6 6 0 0 0-10.5-3.2M4 11a6 6 0 0 0 10.5 3.2" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round"/><path d="M15.5 3.5v3h-3M4.5 16.5v-3h3" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="ml">다시 생성</span></button>
      </div>
      <div class="acts" style="margin-top:12px"><button class="btn outline" @click="sh.more = false">닫기</button></div>
    </BottomSheet>

    <!-- 항목 추가 -->
    <BottomSheet v-model="sh.add">
      <h2 class="st">항목 추가</h2>
      <div class="field" style="margin-top:20px">
        <label for="addText">확인할 내용</label>
        <input id="addText" v-model="addText" class="inp" maxlength="100" placeholder="예) 범퍼 교체 후 번호판 재부착 여부" @keyup.enter="doAdd">
      </div>
      <div class="field" style="margin-top:20px">
        <span class="fl">구분</span>
        <div class="seg">
          <button v-for="t in CHECKLIST_TABS" :key="t.key" :class="{ on: addCat === t.key }" @click="addCat = t.key">{{ t.label }}</button>
        </div>
      </div>
      <p class="sub" style="margin-top:8px;font-size:12px">직접 추가한 항목은 다시 생성해도 유지돼요</p>
      <div class="acts">
        <button class="btn outline" @click="sh.add = false">취소</button>
        <button class="btn bold" :disabled="!addText.trim() || busy" @click="doAdd">추가</button>
      </div>
    </BottomSheet>

    <!-- 항목 수정 · 메모 -->
    <BottomSheet v-model="sh.edit">
      <h2 class="st">{{ cur?.editable ? '항목 수정' : '메모' }}</h2>
      <div class="field" style="margin-top:20px">
        <label for="edText">확인할 내용</label>
        <textarea id="edText" v-model="edText" class="inp" rows="2" :disabled="!cur?.editable" maxlength="100"></textarea>
        <p v-if="cur && !cur.editable" class="sub" style="margin-top:6px;font-size:12px">{{ cur.source === 'AI' ? 'AI 추천' : '공통' }} 항목은 문안을 바꿀 수 없어요. 메모만 남길 수 있어요.</p>
      </div>
      <div class="field" style="margin-top:16px">
        <label for="edMemo">메모 <span style="font-weight:400;color:var(--text-3)">(선택)</span></label>
        <input id="edMemo" v-model="edMemo" class="inp" maxlength="500" placeholder="현장에서 확인한 내용">
      </div>
      <div class="acts">
        <button v-if="cur?.editable" class="btn outline delbtn" style="flex:0 0 96px" :disabled="busy" @click="doDelete">삭제</button>
        <button v-else class="btn outline" @click="sh.edit = false">취소</button>
        <button class="btn bold" :disabled="busy || (cur?.editable && !edText.trim())" @click="doSave">저장</button>
      </div>
    </BottomSheet>

    <!-- 다시 생성 -->
    <BottomSheet v-model="sh.regen">
      <h2 class="st">체크리스트를 다시 생성할까요?</h2>
      <p class="sd">공통·AI 추천 항목과 체크 상태가 새 항목으로 바뀌어요. 직접 추가한 항목은 체크·메모까지 그대로 유지됩니다.</p>
      <div class="acts">
        <button class="btn outline" @click="sh.regen = false">취소</button>
        <button class="btn bold" :disabled="busy" @click="regen">다시 생성</button>
      </div>
    </BottomSheet>

    <!-- AI 고지 -->
    <BottomSheet v-model="sh.notice">
      <h2 class="st">AI 체크리스트 안내</h2>
      <p class="sd">{{ notice }} 노카가 제공하는 견적과 체크리스트는 참고 자료이며, 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="sh.notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.d { font-size: 18px; font-weight: 700; color: var(--primary); flex: 0 0 auto; }
.tt { font-size: 18px; font-weight: 700; color: var(--text); }
/* 확인 개수 배지. 클래스명을 .prog 로 두면 전역 진행바(.prog { height: 3px })와 겹쳐 배경이 글자 위 3px 띠로만 그려진다 */
.checkcnt { margin-left: auto; flex: 0 0 auto; font-size: 12px; font-weight: 600; line-height: 1.4; color: var(--primary); background: var(--primary-50); border-radius: 6px; padding: 4px 8px; }
.infob { flex: 0 0 32px; width: 32px; height: 32px; border-radius: 16px; background: var(--primary-50); color: var(--primary); display: flex; align-items: center; justify-content: center; align-self: center; }
.infob.on { background: var(--primary); color: #fff; }
.aitag { border: 1px solid var(--primary-200); color: var(--primary); background: var(--white); font-size: 12px; font-weight: 600; padding: 5px 9px; border-radius: 6px; }
.x { width: 28px; height: 28px; margin: -4px -6px 0 0; display: flex; align-items: center; justify-content: center; }
.note { margin-top: 16px; width: 100%; padding: 12px 14px; background: var(--bg); border-radius: 8px; display: flex; gap: 8px; text-align: left; font-size: 12px; line-height: 1.55; color: var(--text-2); }
.gen { margin-top: 28px; padding: 32px 24px; border: 1px solid var(--line); border-radius: 12px; display: flex; flex-direction: column; align-items: center; text-align: center; }
.gen b { margin-top: 18px; font-size: 16px; font-weight: 700; color: var(--text); }
.gen p { margin-top: 8px; font-size: 13px; line-height: 1.55; color: var(--text-3); }
.gen.fail b { color: var(--danger-2); }
.clbox { margin-top: 16px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.tabs { height: 48px; border-bottom: 1px solid var(--line); display: flex; }
.tabs button { flex: 1 1 0; display: flex; align-items: center; justify-content: center; gap: 6px; position: relative; }
.tabs button span { font-size: 14px; font-weight: 500; color: var(--text-3); }
.tabs button em { font-style: normal; font-size: 13px; color: var(--text-4); }
.tabs button.on span, .tabs button.on em { color: var(--primary); font-weight: 600; }
.tabs button.on::after { content: ""; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--primary); }
.pane { padding: 0 14px; }
.grp + .grp { border-top: 6px solid var(--bg-2); }
.ph { display: flex; align-items: center; gap: 8px; padding: 14px 0 4px; }
.ph b { font-size: 14.5px; font-weight: 600; }
.delbtn { color: var(--danger-2); border-color: var(--danger-bg); background: var(--danger-bg); }
.delbtn:hover { background: #F9DCDC; }
</style>
