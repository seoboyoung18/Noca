<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import Toast from '../components/Toast.vue'
import { useAppStore } from '../stores/app'
import { useEstimatePdf } from '../lib/estimatePdf'

const router = useRouter()
const store = useAppStore()
const editing = ref(false)
const toast = ref('')
let tt

const statusTag = { done: { text: '견적 완료', cls: '' }, busy: { text: '분석 중', cls: 'gray' }, fail: { text: '분석 실패', cls: 'red' } }

function showToast(msg, ms = 1600) {
  toast.value = msg
  clearTimeout(tt)
  tt = setTimeout(() => { toast.value = '' }, ms)
}

// 항목에 accidentId·estimateId 가 있으면(서버 목록 연결 뒤) 다음 화면으로 쿼리로 넘긴다. 목업 항목은 예전처럼 경로만 이동
function open(h) {
  if (editing.value) return
  const query = {}
  if (h.accidentId) query.accidentId = h.accidentId
  if (h.estimateId) query.estimateId = h.estimateId
  if (h.status === 'done') router.push({ path: '/estimate', query })
  else if (h.status === 'busy') router.push({ path: '/claim/analyzing', query: h.accidentId ? { accidentId: h.accidentId } : {} })
}

/* ===== PDF 파일 받기 — 견적 id 기준 (요청 → 생성 대기 → 302 다운로드) =====
 * 사고 이력 목록의 estimateId(GET /api/accidents/me) 가 입구다. null 이면 받을 PDF 가 없다는 뜻이라 버튼을 끈다.
 * 한 번에 한 건만 진행하고, 진행 중인 행은 "생성 중…" 으로 표시한다.
 */
const { step: pdfStep, busyId: pdfBusyId, message: pdfMessage, download: downloadPdf } = useEstimatePdf()
watch(pdfMessage, (m) => { if (m) showToast(m, pdfStep.value === 'error' ? 2800 : 1600) })

function pdf(h) {
  if (h.status !== 'done') return
  if (!h.estimateId) return showToast('아직 산출된 견적이 없어 PDF를 만들 수 없어요.', 2400)
  downloadPdf(h.estimateId)
}
</script>

<template>
  <Screen>
    <AppHeader title="사고 이력" back="/home">
      <template #right>
        <button v-if="editing" class="act strong" @click="editing = false">완료</button>
        <button v-else-if="store.history.length" class="icn" aria-label="편집" @click="editing = true">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#191F28" stroke-width="1.7" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#191F28" stroke-width="1.7"/></svg>
        </button>
      </template>
    </AppHeader>

    <div v-if="store.history.length" class="body scroll" style="padding-top:20px">
      <template v-for="(g, gi) in store.historyGroups" :key="g.label">
        <div class="lbl" :style="gi ? 'margin-top:24px' : ''">{{ g.label }}</div>
        <div class="card" style="margin-top:8px;padding:0 12px">
          <div v-for="h in g.items" :key="h.id" class="item" :class="{ clickable: !editing && h.status !== 'fail' }" @click="open(h)">
            <span class="th" :class="{ photo: h.photo }">
              <img v-if="h.photo" src="/assets/avante-damage.png" alt="">
              <svg v-else width="44" height="24" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
            </span>
            <span class="flex1" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px">
              <span class="nowrap" style="font-size:16px;font-weight:700">{{ h.car }}</span>
              <span class="row" style="gap:6px">
                <span class="sub" style="font-size:12px">{{ h.date }}</span>
                <span class="tag md" :class="statusTag[h.status].cls">{{ statusTag[h.status].text }}</span>
              </span>
            </span>
            <button v-if="editing" class="del" aria-label="이력 삭제" @click.stop="store.deleteHistory(h.id)">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 8h8" stroke="#D14343" stroke-width="1.8" stroke-linecap="round"/></svg>
            </button>
            <button v-else class="pdf" :disabled="h.status !== 'done' || pdfBusyId !== null" :aria-busy="pdfBusyId === h.estimateId" @click.stop="pdf(h)">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M8 2.5v8M4.5 7.5L8 11l3.5-3.5M3 13.5h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>{{ h.estimateId && pdfBusyId === h.estimateId ? (pdfStep === 'generating' ? 'PDF 생성 중…' : '준비 중…') : 'PDF 파일 받기' }}
            </button>
          </div>
        </div>
      </template>
      <div style="height:24px"></div>
    </div>

    <div v-else class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>아직 접수한 사고가 없어요</b>
        <p>사고가 나면 사진을 찍어<br>예상 수리비를 확인해보세요</p>
      </div>
    </div>
    <div class="spacer"></div>
    <Toast :show="!!toast">{{ toast }}</Toast>
  </Screen>
</template>

<style scoped>
.item { padding: 16px 0; border-bottom: 1px solid var(--line); display: flex; align-items: center; gap: 12px; }
.item:last-child { border-bottom: 0; }
.item.clickable { cursor: pointer; }
.th { flex: 0 0 60px; width: 60px; height: 48px; border-radius: 10px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.pdf { flex: 0 0 auto; height: 40px; padding: 0 10px; white-space: nowrap; border: 1px solid var(--primary-200); border-radius: 10px; background: var(--primary-50); font-size: 13px; font-weight: 600; color: var(--primary); display: flex; align-items: center; gap: 5px; }
.pdf:hover { background: var(--primary-100); }
.pdf:disabled { border-color: var(--line); background: var(--bg-2); color: var(--text-4); }
.del { flex: 0 0 32px; width: 32px; height: 32px; border-radius: 16px; background: var(--danger-bg); display: flex; align-items: center; justify-content: center; }
.del:hover { background: #F9DCDC; }
</style>
