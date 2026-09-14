<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import CheckItem from '../components/CheckItem.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()

const tab = ref('common')
const editing = ref(false)
const pop = ref(false)
const sh = reactive({ more: false, add: false, edit: false, title: false, regen: false, notice: false })

// 항목 추가
const addText = ref('')
const addCat = ref('common')
function openAdd() { sh.more = false; addText.value = ''; addCat.value = tab.value; sh.add = true }
function doAdd() {
  const t = addText.value.trim()
  if (!t) return
  store.addItem(addCat.value, t)
  tab.value = addCat.value
  sh.add = false
}

// 항목 수정
const cur = ref(null)
const edText = ref('')
const edMemo = ref('')
function openEdit(it) { cur.value = it; edText.value = it.text; edMemo.value = it.memo || ''; sh.edit = true }
function doSave() { if (cur.value) store.updateItem(cur.value, { text: edText.value.trim(), memo: edMemo.value.trim() }); sh.edit = false }
function doDelete() { if (cur.value) store.deleteItem(cur.value); sh.edit = false }

// 제목 수정
const tlText = ref('')
function openTitle() { tlText.value = store.checklistTitle; sh.title = true }
function saveTitle() { const t = tlText.value.trim(); if (t) store.setTitle(t); sh.title = false }

// 다시 생성
function regen() { sh.regen = false; store.regenerate(); router.push('/checklist/generating') }

const sevName = (s) => ({ 3: '심각', 2: '중간', 1: '경미' })[s] || ''
const sevCls = (s) => ({ 3: 'red', 2: 'warn', 1: 'gray' })[s] || 'gray'

// 팝오버 바깥 클릭 시 닫기
function onDoc(e) { if (!e.target.closest('.pop, .infob')) pop.value = false }
onMounted(() => document.addEventListener('click', onDoc))
onUnmounted(() => document.removeEventListener('click', onDoc))
</script>

<template>
  <Screen>
    <AppHeader title="정비 체크리스트" back="/checklists" line>
      <template #right>
        <button v-if="editing" class="act strong" @click="editing = false">완료</button>
        <button v-else class="icn" aria-label="더보기" @click="sh.more = true">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="4.5" r="1.6" fill="#191F28"/><circle cx="10" cy="10" r="1.6" fill="#191F28"/><circle cx="10" cy="15.5" r="1.6" fill="#191F28"/></svg>
        </button>
      </template>
    </AppHeader>

    <div class="body scroll" style="padding-top:16px">
      <div class="row" style="align-items:baseline;gap:8px">
        <span class="d">9/5</span>
        <span class="tt">{{ store.checklistTitle }}</span>
        <button v-if="editing" class="tedit" aria-label="제목 편집" @click="openTitle">
          <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#4E36E4" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#4E36E4" stroke-width="1.6"/></svg>
        </button>
      </div>
      <div class="row" style="margin-top:12px;gap:10px;position:relative">
        <span class="tag red md" style="align-self:flex-start">심각</span>
        <span class="flex1" style="display:flex;flex-direction:column;gap:4px">
          <span style="font-size:14px;font-weight:600">전면 추돌 · 범퍼·헤드램프·펜더</span>
          <span class="sub">교환 2곳 · 판금·도장 1곳 · 124만원</span>
        </span>
        <button class="infob" :class="{ on: pop }" aria-label="AI 한 줄 요약 보기" @click="pop = !pop">
          <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7" stroke="currentColor" stroke-width="1.5"/><path d="M10 9v4.5M10 6.5h.01" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
      </div>

      <button class="note" @click="sh.notice = true">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 16px;margin-top:2px"><circle cx="8" cy="8" r="5.6" stroke="#4E5968" stroke-width="1.3"/><path d="M8 7.2v3.4M8 5.2h.01" stroke="#4E5968" stroke-width="1.4" stroke-linecap="round"/></svg>
        <span>사고 내용을 바탕으로 AI가 생성한 참고용 체크리스트입니다. 실제 정비 범위와 방식은 정비 전문가 점검에 따라 달라질 수 있어요.</span>
      </button>

      <div class="clbox">
        <div class="tabs">
          <button :class="{ on: tab === 'common' }" @click="tab = 'common'"><span>공통</span><em>{{ store.counts.common }}</em></button>
          <button :class="{ on: tab === 'parts' }" @click="tab = 'parts'"><span>부품</span><em>{{ store.counts.parts }}</em></button>
          <button :class="{ on: tab === 'hidden' }" @click="tab = 'hidden'"><span>함께 점검</span><em>{{ store.counts.hidden }}</em></button>
        </div>

        <div v-if="tab === 'common'" class="pane">
          <CheckItem v-for="it in store.checklist.common" :key="it.id" :item="it" :editing="editing" @toggle="store.toggleItem(it)" @edit="openEdit(it)" />
          <p v-if="!store.checklist.common.length" class="sub center" style="padding:20px 0">항목이 없어요</p>
        </div>

        <div v-else-if="tab === 'parts'">
          <div v-for="p in store.checklist.parts" :key="p.id" class="pane grp">
            <div class="ph">
              <b>{{ p.name }}</b><span class="sub" v-if="p.fix">{{ p.fix }}</span>
              <span v-if="p.sev" class="tag" :class="sevCls(p.sev)" style="margin-left:auto">{{ sevName(p.sev) }}</span>
            </div>
            <CheckItem v-for="it in p.items" :key="it.id" :item="it" :editing="editing" @toggle="store.toggleItem(it)" @edit="openEdit(it)" />
          </div>
        </div>

        <div v-else class="pane">
          <p class="sub" style="padding:12px 0 2px">사진에 보이지 않는 손상 후보예요. 정비소에 함께 점검을 요청해 보세요.</p>
          <CheckItem v-for="it in store.checklist.hidden" :key="it.id" :item="it" :editing="editing" @toggle="store.toggleItem(it)" @edit="openEdit(it)" />
        </div>
      </div>
      <div style="height:20px"></div>
    </div>

    <div class="foot">
      <button class="btn bold" @click="router.push('/home')">
        <svg width="18" height="18" viewBox="0 0 22 22" fill="none" aria-hidden="true"><path d="M3.5 9.5L11 3.5l7.5 6v8a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1z" stroke="#FFFFFF" stroke-width="1.8" stroke-linejoin="round"/></svg>
        홈으로
      </button>
    </div>

    <!-- AI 한 줄 요약 팝오버 -->
    <div v-if="pop" class="pop">
      <div class="row between">
        <span class="aitag">AI 한 줄 요약</span>
        <button class="x" aria-label="닫기" @click="pop = false">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" stroke="#B0B8C1" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
      </div>
      <p style="margin-top:12px;font-size:14px;line-height:1.55">저속 전면 추돌로 범퍼·헤드램프가 크게 파손되고 좌측 펜더까지 충격이 이어진 사고예요.</p>
      <p style="margin-top:10px;font-size:14px;line-height:1.55;color:var(--text-2)"><strong style="font-weight:700;color:var(--primary)">중점 확인 권장</strong> 범퍼 뒤 냉각 부품(라디에이터·콘덴서) 손상 여부와 헤드램프 교환 시 광축 조정 포함 여부를 꼭 확인하세요.</p>
    </div>

    <!-- 더보기 -->
    <BottomSheet v-model="sh.more">
      <div class="mlist">
        <button class="mrow" @click="openAdd"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 4v12M4 10h12" stroke="#4E36E4" stroke-width="1.8" stroke-linecap="round"/></svg></span><span class="ml">항목 직접 추가</span></button>
        <button class="mrow" @click="sh.more = false; editing = true"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#4E36E4" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#4E36E4" stroke-width="1.6"/></svg></span><span class="ml">항목 편집 · 삭제</span></button>
        <button class="mrow" @click="sh.more = false; sh.regen = true"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M16 9a6 6 0 0 0-10.5-3.2M4 11a6 6 0 0 0 10.5 3.2" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round"/><path d="M15.5 3.5v3h-3M4.5 16.5v-3h3" stroke="#4E36E4" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="ml">다시 생성</span></button>
      </div>
      <div class="acts" style="margin-top:12px"><button class="btn outline" @click="sh.more = false">닫기</button></div>
    </BottomSheet>

    <!-- 항목 추가 -->
    <BottomSheet v-model="sh.add">
      <h2 class="st">항목 추가</h2>
      <div class="field" style="margin-top:20px">
        <label for="addText">확인할 내용</label>
        <input id="addText" v-model="addText" class="inp" placeholder="예) 범퍼 교체 후 번호판 재부착 여부" @keyup.enter="doAdd">
      </div>
      <div class="field" style="margin-top:20px">
        <span class="fl">구분</span>
        <div class="seg">
          <button :class="{ on: addCat === 'common' }" @click="addCat = 'common'">공통</button>
          <button :class="{ on: addCat === 'parts' }" @click="addCat = 'parts'">부품</button>
          <button :class="{ on: addCat === 'hidden' }" @click="addCat = 'hidden'">함께 점검</button>
        </div>
      </div>
      <div class="acts">
        <button class="btn outline" @click="sh.add = false">취소</button>
        <button class="btn bold" :disabled="!addText.trim()" @click="doAdd">추가</button>
      </div>
    </BottomSheet>

    <!-- 항목 수정 -->
    <BottomSheet v-model="sh.edit">
      <h2 class="st">항목 수정</h2>
      <div class="field" style="margin-top:20px">
        <label for="edText">확인할 내용</label>
        <textarea id="edText" v-model="edText" class="inp" rows="2"></textarea>
      </div>
      <div class="field" style="margin-top:16px">
        <label for="edMemo">메모 <span style="font-weight:400;color:var(--text-3)">(선택)</span></label>
        <input id="edMemo" v-model="edMemo" class="inp" placeholder="현장에서 확인한 내용">
      </div>
      <div class="acts">
        <button class="btn outline delbtn" style="flex:0 0 96px" @click="doDelete">삭제</button>
        <button class="btn bold" :disabled="!edText.trim()" @click="doSave">저장</button>
      </div>
    </BottomSheet>

    <!-- 제목 수정 -->
    <BottomSheet v-model="sh.title">
      <h2 class="st">체크리스트 이름</h2>
      <div class="field" style="margin-top:20px">
        <label for="tlText">이름</label>
        <input id="tlText" v-model="tlText" class="inp" @keyup.enter="saveTitle">
      </div>
      <p class="sub" style="margin-top:8px;font-size:12px">날짜(9/5)는 사고 접수일로 자동 표시돼요</p>
      <div class="acts">
        <button class="btn outline" @click="sh.title = false">취소</button>
        <button class="btn bold" :disabled="!tlText.trim()" @click="saveTitle">저장</button>
      </div>
    </BottomSheet>

    <!-- 다시 생성 -->
    <BottomSheet v-model="sh.regen">
      <h2 class="st">체크리스트를 다시 생성할까요?</h2>
      <p class="sd">현재 항목과 체크 상태가 새 항목으로 바뀌어요. 직접 추가한 항목은 유지됩니다.</p>
      <div class="acts">
        <button class="btn outline" @click="sh.regen = false">취소</button>
        <button class="btn bold" @click="regen">다시 생성</button>
      </div>
    </BottomSheet>

    <!-- AI 고지 -->
    <BottomSheet v-model="sh.notice">
      <h2 class="st">AI 견적 고지 안내</h2>
      <p class="sd">노카가 제공하는 견적과 체크리스트는 AI가 사진과 과거 수리 사례를 바탕으로 추정한 참고 자료입니다. 실제 정비 범위와 수리비는 정비소 점검 결과에 따라 달라질 수 있으며, 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="sh.notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.d { font-size: 18px; font-weight: 700; color: var(--primary); }
.tt { font-size: 18px; font-weight: 700; color: var(--text); }
.tedit { width: 28px; height: 28px; margin-left: 2px; display: flex; align-items: center; justify-content: center; align-self: center; border-radius: 14px; }
.tedit:hover { background: var(--primary-50); }
.infob { flex: 0 0 32px; width: 32px; height: 32px; border-radius: 16px; background: var(--primary-50); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.infob.on { background: var(--primary); color: #fff; }
.note { margin-top: 16px; width: 100%; padding: 12px 14px; background: var(--bg); border-radius: 8px; display: flex; gap: 8px; text-align: left; font-size: 12px; line-height: 1.55; color: var(--text-2); }
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
.aitag { border: 1px solid var(--primary-200); color: var(--primary); background: var(--white); font-size: 12px; font-weight: 600; padding: 5px 9px; border-radius: 6px; }
.x { width: 28px; height: 28px; margin: -4px -6px 0 0; display: flex; align-items: center; justify-content: center; }
.delbtn { color: var(--danger-2); border-color: var(--danger-bg); background: var(--danger-bg); }
.delbtn:hover { background: #F9DCDC; }
</style>
