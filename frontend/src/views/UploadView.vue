<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import AccidentCard from '../components/AccidentCard.vue'
import { useAppStore } from '../stores/app'
import { useAccidentStore } from '../stores/accidents'
import { useVehicleStore } from '../stores/vehicles'
import { fetchAccident, fetchAccidentImages } from '../lib/api'
import { angleLabel, firstThumbnail, imageThumb } from '../data/accidents'

/* ===== 사진 업로드 (S06) =====
 * 두 진입 경로가 있다.
 *  - 새 접수: 차량 선택 → 촬영 가이드 → 여기. 아직 사고가 없어 accidentId 가 없다 — 카드는 고른 차량으로, 슬롯은 기존 목업 그대로
 *  - 이어서 진행: 사고 이력에서 접수 완료·사진 등록·분석 실패 행을 눌러 ?accidentId= 로 들어온다 —
 *    카드는 사고(GET /api/accidents/{id} 또는 목록 캐시), 사진은 GET /api/accidents/{id}/images 의 실제 상태
 * 사진 업로드(presigned PUT)·사고 생성(POST /api/accidents)은 아직 연결하지 않았다 — 별도 이슈.
 */
const route = useRoute()
const router = useRouter()
const store = useAppStore()
const accidents = useAccidentStore()
const vs = useVehicleStore()

const accidentId = Number(route.query.accidentId) || null

/* ----- 미리보기 카드 ----- */
// 사고 이력에서 들어왔으면 목록 항목(status·thumbnailUrl 포함)이 캐시에 있다. 없으면 상세 API(공통 10필드)로 채운다
const accident = ref(accidents.items.find((a) => a.accidentId === accidentId) || null)
const accidentLoading = ref(false)
const fatal = ref('')

const images = ref(null) // AccidentImageListResponse
const imgLoading = ref(false)
const imgError = ref('')

const card = computed(() => {
  if (!accidentId) return { vehicle: vs.selected, caption: '접수 전' } // 새 접수 흐름 — 고른 차량
  return {
    vehicle: accident.value,
    createdAt: accident.value?.createdAt || '',
    status: accident.value?.status || '',
    // 대표 이미지 = 업로드 끝난 첫 사진 썸네일. 이미지 목록이 오기 전엔 목록 캐시의 thumbnailUrl(같은 사진)
    thumbnailUrl: firstThumbnail(images.value?.images) || accident.value?.thumbnailUrl || '',
  }
})

async function loadAccident() {
  if (accident.value) return
  accidentLoading.value = true
  try { accident.value = await fetchAccident(accidentId) }
  catch (e) { if (e.status === 404) fatal.value = '사고를 찾을 수 없어요.' }
  finally { accidentLoading.value = false }
}

async function loadImages() {
  imgLoading.value = true
  imgError.value = ''
  try { images.value = await fetchAccidentImages(accidentId) }
  catch (e) { if (e.status !== 401) imgError.value = e.status === 0 ? e.message : '사진 목록을 불러오지 못했어요.' }
  finally { imgLoading.value = false }
}

onMounted(async () => {
  if (!accidentId) { vs.loadVehicles().catch(() => {}); return }
  await loadAccident()
  if (!fatal.value) await loadImages()
})

/* ----- 서버 사진 슬롯 (accidentId 있을 때) ----- */
const serverSlots = computed(() => (images.value?.images || []).map((im) => ({
  key: im.imageId,
  label: angleLabel(im.angleCode),
  done: im.uploadState === 'COMPLETED',
  warn: im.qualityStatus === 'WARN',
  warnText: im.qualityReason || '',
  url: imageThumb(im),
})))
const completed = computed(() => images.value?.completed ?? 0)
const total = computed(() => images.value?.total ?? 0)
const remaining = computed(() => images.value?.remainingSlots ?? null)

function requestAnalysis() { router.push({ path: '/claim/analyzing', query: { accidentId } }) }

/* ----- 목업 슬롯 (새 접수 흐름 — 업로드 연결 전까지 기존 동작 유지) ----- */
const mockTotal = computed(() => store.uploads.length)
const mockDone = computed(() => store.uploadedCount)
const mockRemain = computed(() => mockTotal.value - mockDone.value)
</script>

<template>
  <Screen>
    <AppHeader title="사진 업로드" :back="accidentId ? '/history' : '/claim/guide'" :back-history="!!accidentId" />
    <div class="prog"><i style="width:75%"></i></div>

    <!-- 사고를 찾을 수 없음 -->
    <div v-if="fatal" class="body col">
      <div class="empty">
        <b>{{ fatal }}</b>
        <p>삭제되었거나 접근할 수 없는 사고예요</p>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="router.replace('/history')">사고 이력으로</button>
      </div>
    </div>

    <div v-else class="body scroll" style="padding-top:20px">
      <p class="step">3 / 4 · 사진 업로드</p>
      <h1 class="h1 sm" style="margin-top:6px">{{ accidentId ? '올린 사진을 확인해 주세요' : `${mockTotal}장을 모두 올려주세요` }}</h1>

      <!-- 미리보기 카드 — 차량·접수일·상태·대표 사진 -->
      <AccidentCard style="margin-top:14px" :vehicle="card.vehicle" :created-at="card.createdAt" :status="card.status"
        :thumbnail-url="card.thumbnailUrl" :caption="card.caption" :loading="accidentLoading" />

      <!-- 이어서 진행: 서버에 올라간 사진 -->
      <template v-if="accidentId">
        <div v-if="imgLoading && !images" class="grid" aria-busy="true">
          <div v-for="i in 2" :key="i" class="slot skel"></div>
        </div>
        <div v-else-if="serverSlots.length" class="grid">
          <div v-for="s in serverSlots" :key="s.key" class="slot" :class="{ warn: s.warn, pending: !s.done }" :title="s.warnText">
            <img v-if="s.done && s.url" :src="s.url" alt="">
            <span v-else-if="s.done" class="lbl2">미리보기 없음</span>
            <span v-else class="lbl2">업로드 미완료</span>
            <span v-if="s.done && !s.warn" class="ok">
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <span v-if="s.warn" class="ok bad" :aria-label="s.warnText || '품질 주의'">
              <svg width="13" height="13" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 5.8v5" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round"/><circle cx="10" cy="13.8" r="1.1" fill="#FFFFFF"/></svg>
            </span>
            <span class="cap">{{ s.label }}</span>
          </div>
        </div>
        <div v-else class="none">
          <p class="sub center">아직 올린 사진이 없어요</p>
        </div>
        <p v-if="imgError" class="sub" style="margin-top:12px;color:var(--danger-2)" role="alert">{{ imgError }} <button class="link" @click="loadImages">다시 시도</button></p>
        <p v-else-if="images" class="sub" style="margin-top:16px;font-size:12px">
          사진은 사고당 최대 {{ images.maxCountPerAccident }}장이에요{{ remaining !== null ? ` · ${remaining}장 더 올릴 수 있어요` : '' }}
        </p>
      </template>

      <!-- 새 접수: 기존 목업 슬롯 (업로드 연결 전) -->
      <template v-else>
        <div class="grid">
          <button v-for="u in store.uploads" :key="u.key" class="slot" :class="u.state" @click="store.fillSlot(u.key)" :aria-label="u.label + ' 사진'">
            <template v-if="u.state === 'done'">
              <img v-if="u.key === 'front'" src="/assets/avante-damage.png" alt="">
              <span class="ok">
                <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
              </span>
              <span class="cap">{{ u.label }}</span>
            </template>
            <template v-else-if="u.state === 'error'">
              <span class="dim"></span>
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true" style="position:relative"><circle cx="10" cy="10" r="8.2" stroke="#D93F45" stroke-width="1.8"/><path d="M10 5.8v5" stroke="#D93F45" stroke-width="1.8" stroke-linecap="round"/><circle cx="10" cy="13.8" r="1" fill="#D93F45"/></svg>
              <span class="retry">다시 찍기</span>
              <span class="cap">{{ u.label }}</span>
            </template>
            <template v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5.5v13M5.5 12h13" stroke="#8B95A1" stroke-width="1.8" stroke-linecap="round"/></svg>
              <span class="lbl2">{{ u.label }}</span>
            </template>
          </button>
        </div>
        <p class="sub" style="margin-top:16px;font-size:12px">흐리거나 어두운 사진은 다시 찍어주세요</p>
      </template>

      <div style="margin-top:12px">
        <button class="link" @click="router.push('/claim/guide')">촬영 가이드 다시 보기</button>
      </div>
      <div style="height:16px"></div>
    </div>

    <!-- 하단: 이어서 진행이면 서버 집계, 새 접수면 목업 집계 -->
    <div v-if="!fatal" class="foot">
      <template v-if="accidentId">
        <div class="row between">
          <span class="sub">{{ completed ? `${completed}장 올렸어요` : '올린 사진이 없어요' }}</span>
          <span style="font-size:13px;font-weight:600">{{ completed }} / {{ total }}</span>
        </div>
        <div class="bar"><i :style="{ width: total ? (completed / total) * 100 + '%' : '0%' }"></i></div>
        <button class="btn" style="margin-top:14px" :disabled="!completed" @click="requestAnalysis">분석 요청</button>
      </template>
      <template v-else>
        <div class="row between">
          <span class="sub">{{ mockRemain > 0 ? mockRemain + '장 남았어요' : '모두 올렸어요' }}</span>
          <span style="font-size:13px;font-weight:600">{{ mockDone }} / {{ mockTotal }}</span>
        </div>
        <div class="bar"><i :style="{ width: (mockDone / mockTotal) * 100 + '%' }"></i></div>
        <button class="btn" style="margin-top:14px" :disabled="mockDone < mockTotal" @click="router.push('/claim/analyzing')">분석 요청</button>
      </template>
    </div>
  </Screen>
</template>

<style scoped>
.grid { margin-top: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.slot { position: relative; width: 100%; aspect-ratio: 1; border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; background: var(--bg); background-image: repeating-linear-gradient(135deg, var(--bg) 0 8px, var(--stripe) 8px 16px); }
.slot img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.slot.empty { background: var(--bg-2); background-image: none; border: 1.5px dashed var(--line-2); }
.slot.empty:hover { background: var(--bg); }
.slot.error, .slot.warn { border: 1.5px solid var(--danger); }
.slot.pending { background-image: none; background: var(--bg-2); border: 1.5px dashed var(--line-2); }
.slot.skel { background-image: none; background: var(--bg-2); }
.dim { position: absolute; inset: 0; background: rgba(217,63,69,.15); }
.retry { position: relative; font-size: 12px; font-weight: 500; color: var(--danger); }
.lbl2 { font-size: 13px; color: var(--text-3); }
.ok { position: absolute; right: 8px; top: 8px; width: 24px; height: 24px; border-radius: 12px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.ok.bad { background: var(--danger); }
.cap { position: absolute; left: 8px; bottom: 8px; background: rgba(25,31,40,.7); color: #fff; font-size: 11px; padding: 3px 8px; border-radius: 4px; }
.none { margin-top: 20px; padding: 28px 0; border: 1.5px dashed var(--line-2); border-radius: 12px; }
.bar { margin-top: 10px; height: 4px; border-radius: 2px; background: var(--line); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 2px; background: var(--primary); transition: width .3s; }
</style>
