<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { AUTH_GUARD_OFF } from '../router'
import { fetchAccidentEstimates, fetchAccidentImages, fetchAnalysisResult, fetchEstimate } from '../lib/api'
import { imageThumb } from '../data/accidents'
import { useChecklistStore } from '../stores/checklist'
import {
  LABOR_ONLY_NOTICE, confidenceLabel, detectionBox, exclusionText, numberParts, wonOne, wonRange, wonShort,
} from '../data/estimates'

/* ===== 예상 견적 · 분석 결과 (S07b, 목업 A안 — 사진 위 번호 콜아웃) =====
 * 사고 기준 경로 셋을 함께 부른다.
 *   GET /api/accidents/{id}/analysis/result  손상 부위·사진별 검출 좌표 (사진 URL 없음)
 *   GET /api/accidents/{id}/images           사진 RESIZED URL — imageId 로 결과와 맞춤
 *   GET /api/accidents/{id}/estimates → GET /api/estimates/{estimateId}   금액·항목·신뢰도
 * 검출 bbox 는 AI 분석 축소본 픽셀이고 width/height 도 같은 기준이라, 4:3 cover 틀에 맞춰 퍼센트로 환산해 RESIZED 위에 푸른 바운딩박스로 그린다(detectionBox).
 * 번호는 견적 항목 순서를 따르고, 부품이 매칭되지 않은 검출(partCode 없음)은 번호 없이 박스만 그린다.
 * 부품별 내역 카드를 누르면 그 부품(partCode)의 박스만 남기고 나머지는 숨긴다 — 다른 사진에만 있으면 그 사진으로 넘긴다. 다시 누르면 전체.
 * 견적은 AI 콜백이 만들므로 결과가 있어도 견적이 아직 없을 수 있다 — 그때는 부위 목록만 보이고 금액은 "산정 중".
 * 견적을 받으면 정비 체크리스트 생성을 서버 큐에 넣는다(서버는 자동 생성하지 않음) — 이미 있으면 409 라 조용히 넘어간다.
 */
const route = useRoute()
const router = useRouter()
const accidentId = Number(route.query.accidentId) || null
const estimateId = ref(Number(route.query.estimateId) || null)

const loading = ref(true)
const error = ref('')
const result = ref(null) // AnalysisResultResponse
const estimate = ref(null) // EstimateResponse
const imageUrls = ref({}) // imageId → RESIZED URL
const notice = ref(false)
const photo = ref(0)

async function load() {
  if (!accidentId) { applyMock(); return } // 목업 진입(쿼리 없음) — 서버 없이 화면 확인
  loading.value = true
  error.value = ''
  try {
    const [res, imgs] = await Promise.all([
      fetchAnalysisResult(accidentId),
      fetchAccidentImages(accidentId).catch(() => null), // 저장소 없으면 url 만 null — 결과는 그대로 그린다
    ])
    result.value = res
    imageUrls.value = Object.fromEntries((imgs?.images || []).map((im) => [im.imageId, imageThumb(im, 'RESIZED')]))
    if (!estimateId.value) estimateId.value = (await fetchAccidentEstimates(accidentId))[0]?.estimateId ?? null
    if (estimateId.value) {
      estimate.value = await fetchEstimate(estimateId.value)
      useChecklistStore().ensureRequested(accidentId) // 기다리지 않는다 — 체크리스트 화면이 상태를 이어 받는다
    }
  } catch (e) {
    if (e.status === 401) return
    error.value = e.status === 404 ? '사고를 찾을 수 없어요.' : e.status === 0 ? e.message : '분석 결과를 불러오지 못했어요.'
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ----- 분석 상태 분기 ----- */
// none(요청 전) | running | failed | done
const stage = computed(() => {
  const s = result.value?.status
  if (!s) return 'none'
  if (s === 'COMPLETED') return 'done'
  if (s === 'FAILED') return 'failed'
  return 'running'
})

/* ----- 부위 번호·항목 ----- */
const parts = computed(() => numberParts(estimate.value?.items || [], result.value?.parts || []))
const partNo = (code) => parts.value.find((p) => p.partCode === code)?.n ?? null
const hasNa = computed(() => parts.value.some((p) => p.na))

/* ----- 사진 + 콜아웃 ----- */
const photos = computed(() => (result.value?.images || []).map((im) => ({
  imageId: im.imageId,
  url: imageUrls.value[im.imageId] || '',
  excluded: !!im.excluded,
  reason: exclusionText(im.exclusionReason),
  boxes: im.excluded ? [] : (Array.isArray(im.detections) ? im.detections : [])
    .map((d, i) => ({ id: d.detectionId || `${im.imageId}:${i}`, partCode: d.partCode || null, n: partNo(d.partCode), ...(detectionBox(d, im.width, im.height) || {}) }))
    .filter((b) => b.w != null),
})))
const current = computed(() => photos.value[photo.value] || null)

/* ----- 부품 선택 → 그 부품의 박스만 ----- */
const selected = ref(null) // partCode | null
const hasBox = (code) => photos.value.some((p) => p.boxes.some((b) => b.partCode === code))
function selectPart(p) {
  if (!hasBox(p.partCode)) return // 검출 좌표가 없는 항목은 강조할 박스가 없다
  if (selected.value === p.partCode) { selected.value = null; return }
  selected.value = p.partCode
  if (!current.value?.boxes.some((b) => b.partCode === p.partCode)) {
    const k = photos.value.findIndex((ph) => ph.boxes.some((b) => b.partCode === p.partCode))
    if (k > -1) photo.value = k
  }
}
const selectedName = computed(() => parts.value.find((p) => p.partCode === selected.value)?.name || '')

/* ----- 금액·신뢰도·고지 ----- */
const est = computed(() => estimate.value)
const priceRange = computed(() => wonRange(est.value?.totalMin, est.value?.totalMax))
const priceMedian = computed(() => wonOne(est.value?.totalMedian))
const confidence = computed(() => confidenceLabel(est.value?.confidenceGrade))
const notices = computed(() => {
  const list = est.value?.notices?.length ? est.value.notices : []
  // 문구 테이블 전이라 서버 notices 가 비어 있다. 부품비가 원천에 없어 null 인 견적은 공임 기준 고지를 FE 가 붙인다
  const laborOnly = (est.value?.items || []).some((it) => it.partCostMedian == null && it.itemMedian != null)
  return list.length || !laborOnly ? list : [LABOR_ONLY_NOTICE]
})

function makeReport() {
  const query = {}
  if (accidentId) query.accidentId = accidentId
  if (estimateId.value) query.estimateId = estimateId.value
  router.push({ path: '/report/generating', query })
}

/* ----- 목업 (쿼리 없음 · 가드 off) — 서버 응답 모양 그대로 ----- */
function applyMock() {
  const mockImg = '/assets/avante-damage.png'
  result.value = {
    jobId: 1, status: 'COMPLETED', failureReason: null,
    parts: [
      { partCode: 'FRONT_BUMPER', partNameKo: '프론트 범퍼', damageType: 'Breakage', repairMethodDisplayName: '교환' },
      { partCode: 'HEAD_LAMP_L', partNameKo: '헤드램프(좌)', damageType: 'Breakage', repairMethodDisplayName: '교환' },
      { partCode: 'FRONT_FENDER_L', partNameKo: '앞휀더(좌)', damageType: 'Crushed', repairMethodDisplayName: '판금 후 도장' },
      { partCode: 'FRONT_DOOR_L', partNameKo: '앞도어(좌)', damageType: 'Scratched', repairMethodDisplayName: null },
    ],
    images: [{
      imageId: 1, width: 1600, height: 1200, excluded: false, exclusionReason: null,
      detections: [
        { detectionId: '1:a', partCode: 'FRONT_BUMPER', geometry: { bboxFormat: 'XYWH', bbox: { x: 610, y: 310, width: 640, height: 380 } } },
        { detectionId: '1:b', partCode: 'FRONT_FENDER_L', geometry: { bboxFormat: 'XYWH', bbox: { x: 320, y: 710, width: 395, height: 210 } } },
        { detectionId: '1:c', partCode: 'HEAD_LAMP_L', geometry: { bboxFormat: 'XYWH', bbox: { x: 120, y: 260, width: 300, height: 200 } } },
      ],
    }],
  }
  imageUrls.value = { 1: mockImg }
  estimate.value = {
    estimateId: 1, estimable: true, totalMin: 980000, totalMedian: 1240000, totalMax: 1620000, refCaseTotal: 34, confidenceGrade: 'MEDIUM', notices: [],
    items: [
      { partCode: 'FRONT_BUMPER', partNameKo: '프론트 범퍼', repairMethodDisplayName: '교환', itemMin: 420000, itemMedian: 500000, itemMax: 580000, refCaseCount: 14, lowConfidence: false, partCostMedian: null },
      { partCode: 'HEAD_LAMP_L', partNameKo: '헤드램프(좌)', repairMethodDisplayName: '교환', itemMin: 340000, itemMedian: 400000, itemMax: 470000, refCaseCount: 11, lowConfidence: false, partCostMedian: null },
      { partCode: 'FRONT_FENDER_L', partNameKo: '앞휀더(좌)', repairMethodDisplayName: '판금 후 도장', itemMin: 240000, itemMedian: 300000, itemMax: 380000, refCaseCount: 9, lowConfidence: false, partCostMedian: null },
      { partCode: 'FRONT_DOOR_L', partNameKo: '앞도어(좌)', repairMethodDisplayName: null, itemMin: null, itemMedian: null, itemMax: null, refCaseCount: 0, lowConfidence: true, partCostMedian: null },
    ],
  }
  estimateId.value = AUTH_GUARD_OFF ? 1 : null
  loading.value = false
}
</script>

<template>
  <Screen>
    <AppHeader title="예상 견적" back="/history" back-history line />

    <!-- 로딩 -->
    <div v-if="loading" class="body col" role="status">
      <p class="sub center" style="margin:auto 0">분석 결과를 불러오고 있어요…</p>
    </div>

    <!-- 오류 -->
    <div v-else-if="error" class="body col">
      <div class="empty">
        <b>{{ error }}</b>
        <button class="btn outline" style="margin-top:16px;width:auto;padding:0 20px;height:44px" @click="load">다시 시도</button>
      </div>
    </div>

    <!-- 분석이 끝나지 않음 -->
    <div v-else-if="stage !== 'done'" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>{{ stage === 'running' ? '아직 분석이 진행 중이에요' : stage === 'failed' ? '분석을 완료하지 못했어요' : '아직 분석을 요청하지 않았어요' }}</b>
        <p>{{ stage === 'running' ? '분석이 끝나면 예상 견적을 보여드려요' : stage === 'failed' ? '사진을 다시 올리고 분석을 요청해 주세요' : '사진을 올리고 분석을 요청하면 결과를 볼 수 있어요' }}</p>
        <button class="btn" style="margin-top:20px;width:auto;padding:0 24px;height:46px"
          @click="router.replace(stage === 'running' ? { path: '/claim/analyzing', query: { accidentId } } : { path: '/claim/upload', query: { accidentId } })">
          {{ stage === 'running' ? '진행 상태 보기' : '사진 올리기' }}
        </button>
      </div>
    </div>

    <div v-else class="body scroll" style="padding-top:16px">
      <div class="notice">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style="flex:0 0 16px"><circle cx="8" cy="8" r="6.6" stroke="#B7791F" stroke-width="1.5"/><path d="M8 4.6v4" stroke="#B7791F" stroke-width="1.5" stroke-linecap="round"/><circle cx="8" cy="11.1" r="0.85" fill="#B7791F"/></svg>
        <span class="flex1">AI 추정치이며 법적 효력이 없습니다</span>
        <button class="more" @click="notice = true">자세히</button>
      </div>

      <!-- 예상 수리비 — 견적 유무·산정 가능 여부로 3분기 -->
      <div class="price">
        <div class="sub">예상 수리비</div>
        <template v-if="est && est.estimable">
          <div class="big">{{ priceRange }}</div>
          <div v-if="priceMedian" class="sub" style="margin-top:8px">중앙값 {{ priceMedian }}</div>
          <div class="range"><i></i></div>
          <div class="row" style="margin-top:14px;gap:6px">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M7 1.4l4.6 1.8v3.4c0 3-1.9 5.2-4.6 6-2.7-.8-4.6-3-4.6-6V3.2z" :stroke="est.confidenceGrade === 'LOW' ? '#B7791F' : '#4E36E4'" stroke-width="1.4" stroke-linejoin="round"/></svg>
            <span style="font-size:12px;color:var(--text-2)">
              <template v-if="confidence">신뢰도 {{ confidence }}</template>
              <template v-if="est.refCaseTotal != null"> · 유사 사례 {{ est.refCaseTotal }}건 기준</template>
            </span>
          </div>
          <p v-if="est.confidenceGrade === 'LOW'" class="lowc">참조 사례가 적어 실제 금액과 차이가 클 수 있어요</p>
        </template>
        <template v-else-if="est">
          <div class="big" style="font-size:20px">산정할 수 없어요</div>
          <p class="sub" style="margin-top:8px;line-height:1.5">{{ est.nonEstimableReason || '참조할 수리 사례가 부족해 금액을 계산하지 못했어요.' }}</p>
        </template>
        <template v-else>
          <div class="big" style="font-size:20px">견적을 산정하고 있어요</div>
          <p class="sub" style="margin-top:8px;line-height:1.5">손상 부위 분석은 끝났고 금액 계산이 남았어요. 잠시 후 다시 확인해 주세요.</p>
        </template>
      </div>
      <p v-for="n in notices" :key="n.code" class="sub" style="margin-top:8px;font-size:11px;line-height:1.5">{{ n.message }}</p>

      <!-- 인식된 손상 부위 — 사진 위 번호 콜아웃 (A안) -->
      <div v-if="photos.length" style="margin-top:24px">
        <div class="row between">
          <span class="sec">인식된 손상 부위</span>
          <button v-if="selected" class="sub only" @click="selected = null">{{ selectedName }}만 표시 · 전체 보기</button>
          <span v-else class="sub" style="font-size:12px">{{ photos.length > 1 ? `${photo + 1} / ${photos.length}` : `${parts.length}곳 인식` }}</span>
        </div>
        <div class="shot" :class="{ noimg: !current?.url }">
          <img v-if="current?.url" :src="current.url" alt="손상 부위 사진">
          <span v-else class="sub">사진을 불러올 수 없어요</span>
          <div v-for="b in current?.boxes || []" :key="b.id" class="box" :class="{ off: selected && b.partCode !== selected }"
            :style="{ left: b.l + '%', top: b.t + '%', width: b.w + '%', height: b.h + '%' }">
            <span v-if="b.n" class="num">{{ b.n }}</span>
          </div>
          <span v-if="current?.excluded" class="excl">분석 제외 · {{ current.reason }}</span>
        </div>
        <div v-if="photos.length > 1" class="thumbs">
          <button v-for="(p, k) in photos" :key="p.imageId" class="th" :class="{ on: k === photo }" :aria-label="`사진 ${k + 1}`" @click="photo = k">
            <img v-if="p.url" :src="p.url" alt="">
          </button>
        </div>
      </div>

      <!-- 부품별 내역 -->
      <div style="margin-top:24px">
        <div class="sec">부품별 내역</div>
        <div v-if="parts.length" class="stack" style="margin-top:12px;gap:8px">
          <button v-for="p in parts" :key="p.partCode" type="button" class="part" :class="{ on: selected === p.partCode, static: !hasBox(p.partCode) }"
            :aria-pressed="selected === p.partCode" @click="selectPart(p)">
            <span class="pn" :class="{ na: p.na }">{{ p.n }}</span>
            <span class="flex1" style="display:flex;flex-direction:column;gap:3px;min-width:0">
              <span class="sec">{{ p.name }}</span>
              <span class="sub" style="font-size:12px">{{ p.na && !p.method ? (p.damage || '수리 방식 미정') : (p.method || '수리 방식 미정') }}</span>
            </span>
            <span v-if="!p.na" style="display:flex;flex-direction:column;align-items:flex-end;gap:3px">
              <span style="font-size:15px;font-weight:700">{{ wonOne(p.median) }}</span>
              <span class="sub" style="font-size:12px">{{ wonShort(p.min, p.max) }}<template v-if="p.low"> · <span style="color:var(--warn)">사례 부족</span></template></span>
            </span>
            <span v-else class="tag warn">산정 불가</span>
          </button>
        </div>
        <p v-else class="sub" style="margin-top:12px">인식된 손상 부위가 없어요</p>
        <p v-if="hasNa" class="sub" style="margin-top:8px;font-size:11px">산정 불가 항목은 총액에 포함되지 않았어요</p>
      </div>
      <div style="height:20px"></div>
    </div>

    <div v-if="!loading && !error && stage === 'done'" class="foot">
      <button class="btn" :disabled="!est" @click="makeReport">리포트 만들기</button>
    </div>

    <BottomSheet v-model="notice">
      <p class="st">AI 견적 고지 안내</p>
      <p class="sd">노카가 제공하는 견적은 AI가 사진과 과거 수리 사례를 바탕으로 추정한 참고 금액입니다. 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있으며, 본 견적은 보험 청구·법적 분쟁의 근거로 사용할 수 없습니다.</p>
      <div class="acts"><button class="btn bold" @click="notice = false">확인</button></div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.notice { display: flex; align-items: center; gap: 8px; background: var(--warn-bg); border-radius: 8px; padding: 12px 14px; font-size: 12px; color: var(--warn); }
.notice .more { font-size: 12px; font-weight: 500; color: var(--warn); text-decoration: underline; }
.price { margin-top: 14px; background: var(--bg-2); border-radius: 16px; padding: 20px 18px; }
.big { margin-top: 10px; font-size: 28px; font-weight: 700; color: var(--text); letter-spacing: -0.04em; white-space: nowrap; }
.range { position: relative; margin-top: 14px; height: 4px; border-radius: 2px; background: var(--line); }
.range i { position: absolute; left: 20%; right: 25%; top: 0; height: 4px; border-radius: 2px; background: var(--primary); }
.lowc { margin: 8px 0 0; font-size: 12px; color: var(--warn); }
.shot { position: relative; margin-top: 12px; width: 100%; aspect-ratio: 4 / 3; border-radius: 12px; overflow: hidden; background: var(--bg-2); display: flex; align-items: center; justify-content: center; }
.shot img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
/* 바운딩박스 — 서버 좌표 그대로 푸른색으로. 사진 위에서 잘 보이도록 바깥에 흰 테두리를 한 겹 더 둔다 */
.box { position: absolute; border: 2px solid var(--bbox); box-shadow: 0 0 0 1px rgba(255,255,255,.7), inset 0 0 0 1px rgba(255,255,255,.7); background: rgba(30,136,229,.12); border-radius: 3px; animation: fadein .2s ease-out; --bbox: #1E88E5; transition: opacity .18s ease; }
.box.off { opacity: 0; }
.only { font-size: 12px; color: #1E88E5; font-weight: 500; text-decoration: underline; }
.num { position: absolute; left: -2px; top: -2px; width: 20px; height: 20px; border-radius: 10px; background: var(--bbox); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 1.5px #fff; }
.excl { position: absolute; left: 10px; bottom: 10px; padding: 4px 8px; border-radius: 6px; background: rgba(25,31,40,.75); color: #fff; font-size: 11px; font-weight: 500; }
.thumbs { margin-top: 8px; display: flex; gap: 6px; }
.th { flex: 0 0 68px; width: 68px; height: 68px; border-radius: 8px; overflow: hidden; border: 1px solid var(--line); background: var(--bg-2); }
.th.on { border: 2px solid var(--primary); }
.th img { width: 100%; height: 100%; object-fit: cover; }
.part { width: 100%; text-align: left; background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px; display: flex; align-items: center; gap: 10px; transition: border-color .15s ease, background .15s ease; }
.part.on { border-color: #1E88E5; background: rgba(30,136,229,.06); box-shadow: inset 0 0 0 1px #1E88E5; }
.part.on .pn { background: #1E88E5; }
.part.static { cursor: default; }
.pn { flex: 0 0 20px; width: 20px; height: 20px; border-radius: 10px; background: var(--primary); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.pn.na { background: var(--line-2); }
</style>
