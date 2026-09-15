<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useVehicleStore } from '../stores/vehicles'
import { MODEL_YEAR_MAX, MODEL_YEAR_MIN, groupManufacturers, sortModels, vehicleSpec } from '../data/vehicles'

// 차량 등록 — GET /api/vehicle-models(51종 전체) 로 고르고 POST /api/vehicles { modelId, modelYear } 로 등록.
// 제조사·유형·차급은 모델을 고르면 결정되므로 화면에서는 표시만 한다. 목록에 없는 차량 직접 입력은 서버 구조상 불가(문서 0-3).
const router = useRouter()
const route = useRoute()
const vs = useVehicleStore()
const backTo = route.query.from === 'my' ? '/my/vehicles' : '/claim/vehicle'

// idle | loading | error
const state = ref('loading')
const loadError = ref('')
onMounted(loadModels)
async function loadModels() {
  state.value = 'loading'
  loadError.value = ''
  try { await vs.loadModels(); state.value = 'idle' }
  catch (e) { state.value = 'error'; loadError.value = e.status === 0 ? e.message : '차량 목록을 불러오지 못했어요.' }
}

const maker = ref('')      // manufacturer 문자열
const modelId = ref(null)  // 등록 본문에 보낼 값
const year = ref(null)
const sheet = ref(null)    // 'maker' | 'model' | 'year'

// 제조사는 5그룹 순서, 모델명은 가나다순 — 서버 응답 순서는 의미가 없어 FE 가 정렬한다 (문서 3-4)
const makerGroups = computed(() => groupManufacturers(vs.models))
const models = computed(() => sortModels(vs.models.filter((m) => m.manufacturer === maker.value)))
const model = computed(() => vs.models.find((m) => m.modelId === modelId.value) || null)
const years = Array.from({ length: new Date().getFullYear() + 1 - MODEL_YEAR_MIN + 1 }, (_, i) => new Date().getFullYear() + 1 - i)
  .filter((y) => y <= MODEL_YEAR_MAX)

const sheetTitle = computed(() => ({ maker: '제조사', model: '차량명', year: '연식' })[sheet.value] || '')
const valid = computed(() => !!modelId.value && !!year.value)

/* 검색 — 제조사·차량명을 한 입력란에서 찾는다. 51종이 이미 메모리에 있어 서버 호출 없이 거른다.
 * "벤츠 e", "아반떼", "쏘렌토 기아" 처럼 띄어쓴 단어를 모두 포함하는 모델만 남긴다 (대소문자·공백 무시) */
const query = ref('')
const norm = (s) => (s || '').toLowerCase().replace(/\s+/g, '')
const results = computed(() => {
  const words = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return []
  return sortModels(vs.models.filter((m) => {
    const hay = norm(m.manufacturer + m.modelName)
    return words.every((w) => hay.includes(w.replace(/\s+/g, '')))
  })).slice(0, 30)
})
const searching = computed(() => query.value.trim().length > 0)
function pickResult(m) { maker.value = m.manufacturer; modelId.value = m.modelId; query.value = '' }

function pickMaker(b) { if (b !== maker.value) { maker.value = b; modelId.value = null }; sheet.value = null }
function pickModel(m) { modelId.value = m.modelId; sheet.value = null }
function pickYear(y) { year.value = y; sheet.value = null }
function openModelSheet() { if (maker.value) sheet.value = 'model' }

const busy = ref(false)
const submitError = ref('')
async function submit() {
  if (!valid.value || busy.value) return
  busy.value = true
  submitError.value = ''
  try {
    await vs.add(modelId.value, year.value) // 201 — 응답에 모델 정보가 모두 있어 재조회 불필요
    router.replace(backTo)
  } catch (e) {
    // 400 은 error.code 로 분기하고 message 는 그대로 표시 (파싱 금지 — 문서 1-2)
    submitError.value = e.status === 400 ? e.message : e.status === 0 ? e.message : '차량을 등록하지 못했어요. 잠시 후 다시 시도해 주세요.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Screen>
    <AppHeader title="차량 등록" :back="backTo" line />
    <div class="body" style="padding-top:28px;display:flex;flex-direction:column;gap:20px">
      <!-- 모델 목록 로딩 실패 -->
      <div v-if="state === 'error'" class="empty" style="margin-top:60px">
        <b>{{ loadError }}</b>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:44px" @click="loadModels">다시 시도</button>
      </div>

      <template v-else>
        <!-- 검색: 제조사·차량명 통합. 결과를 고르면 아래 제조사·차량명이 함께 채워진다 -->
        <div class="field">
          <span class="fl">차량 검색</span>
          <div class="srch">
            <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="9" cy="9" r="5.5" stroke="#8B95A1" stroke-width="1.6"/><path d="M13.5 13.5L17 17" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round"/></svg>
            <input v-model="query" class="inp" type="search" enterkeyhint="search" autocomplete="off" :disabled="state === 'loading'" placeholder="예) 아반떼, 벤츠 E, 기아 쏘렌토">
            <button v-if="query" class="clr" aria-label="검색어 지우기" @click="query = ''">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
            </button>
          </div>
          <div v-if="searching" class="res">
            <button v-for="m in results" :key="m.modelId" class="opt" :class="{ on: m.modelId === modelId }" @click="pickResult(m)">
              <span class="flex1"><b>{{ m.manufacturer }}</b> {{ m.modelName }}<small class="spec">{{ vehicleSpec(m) }}</small></span>
              <svg v-if="m.modelId === modelId" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <p v-if="!results.length" class="none">
              '{{ query.trim() }}'에 해당하는 차량이 없어요.<br>
              현재 등록할 수 있는 차량은 {{ vs.models.length }}종이며, 목록에 없는 차량은 아직 직접 등록할 수 없어요.
            </p>
          </div>
          <!-- 검색 안내 — 등록 가능 범위(마스터 모델만)를 미리 알려 목록에 없는 차량을 찾다 막히는 일을 줄인다 -->
          <p v-if="!searching" class="sub" style="margin-top:8px;font-size:12px;line-height:1.55">
            제조사나 차량명을 입력해 찾거나, 아래에서 직접 선택할 수 있어요.
          </p>
        </div>

        <div class="field">
          <span class="fl">제조사</span>
          <button class="sel" :disabled="state === 'loading'" @click="sheet = 'maker'">
            <span class="v" :class="{ ph: !maker }">{{ maker || (state === 'loading' ? '불러오는 중…' : '선택하세요') }}</span><ChevDown />
          </button>
        </div>
        <div class="field">
          <span class="fl">차량명</span>
          <button class="sel" :disabled="!maker" @click="openModelSheet">
            <span class="v" :class="{ ph: !model }">{{ model?.modelName || (maker ? '선택하세요' : '제조사를 먼저 선택하세요') }}</span><ChevDown />
          </button>
          <!-- 유형·차급은 모델에서 결정되는 표시 전용 값 -->
          <p class="sub" style="margin-top:8px;font-size:12px">{{ model ? vehicleSpec(model) + ' — 자동으로 설정됩니다' : '차량 유형과 차급은 차량명을 고르면 자동으로 설정됩니다' }}</p>
        </div>
        <div class="field">
          <span class="fl">연식</span>
          <button class="sel" @click="sheet = 'year'"><span class="v" :class="{ ph: !year }">{{ year ? year + '년' : '선택하세요' }}</span><ChevDown /></button>
        </div>
        <p v-if="submitError" class="sub err" role="alert">{{ submitError }}</p>
      </template>
    </div>
    <div class="foot">
      <button class="btn" :disabled="!valid || busy || state !== 'idle'" @click="submit">{{ busy ? '등록 중…' : '등록' }}</button>
    </div>

    <BottomSheet :model-value="!!sheet" @update:model-value="sheet = null">
      <p class="st">{{ sheetTitle }}</p>
      <div class="opts scroll">
        <!-- 제조사: 5그룹 순서, 그룹 제목 포함 -->
        <template v-if="sheet === 'maker'">
          <template v-for="g in makerGroups" :key="g.group">
            <div class="grp">{{ g.group }}</div>
            <button v-for="b in g.brands" :key="b" class="opt" :class="{ on: b === maker }" @click="pickMaker(b)">
              <span class="flex1">{{ b }}</span>
              <svg v-if="b === maker" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </template>
        </template>
        <!-- 차량명: 선택한 제조사의 모델, 가나다순 -->
        <template v-else-if="sheet === 'model'">
          <button v-for="m in models" :key="m.modelId" class="opt" :class="{ on: m.modelId === modelId }" @click="pickModel(m)">
            <span class="flex1">{{ m.modelName }}<small class="spec">{{ vehicleSpec(m) }}</small></span>
            <svg v-if="m.modelId === modelId" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </template>
        <!-- 연식 -->
        <template v-else>
          <button v-for="y in years" :key="y" class="opt" :class="{ on: y === year }" @click="pickYear(y)">
            <span class="flex1">{{ y }}년</span>
            <svg v-if="y === year" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </template>
      </div>
    </BottomSheet>
  </Screen>
</template>

<script>
import { h } from 'vue'
const ChevDown = () => h('svg', { width: 20, height: 20, viewBox: '0 0 20 20', fill: 'none', 'aria-hidden': 'true' }, [
  h('path', { d: 'M5.5 8L10 12.5L14.5 8', stroke: '#8B95A1', 'stroke-width': '1.7', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
])
export default { components: { ChevDown } }
</script>

<style scoped>
.sel:disabled { color: var(--text-3); background: var(--bg); }
.srch { position: relative; }
.srch > svg { position: absolute; left: 14px; top: 50%; margin-top: 4px; transform: translateY(-50%); pointer-events: none; }
.srch .inp { padding-left: 40px; padding-right: 40px; }
.srch .inp::-webkit-search-cancel-button { display: none; }
.clr { position: absolute; right: 10px; top: 50%; margin-top: 4px; transform: translateY(-50%); width: 28px; height: 28px; border-radius: 14px; display: flex; align-items: center; justify-content: center; color: var(--text-3); background: var(--bg); }
.res { margin-top: 8px; max-height: 264px; overflow-y: auto; border: 1px solid var(--line); border-radius: 12px; background: var(--white); }
.res .opt b { font-weight: 600; margin-right: 4px; }
.none { padding: 16px; font-size: 13px; line-height: 1.55; color: var(--text-3); }
.opts { margin-top: 12px; max-height: 360px; overflow-y: auto; border: 1px solid var(--line); border-radius: 12px; }
.grp { padding: 10px 16px 6px; font-size: 12px; font-weight: 600; color: var(--text-3); background: var(--bg); border-top: 1px solid var(--line); }
.grp:first-child { border-top: 0; }
.opt { width: 100%; height: 48px; padding: 0 16px; display: flex; align-items: center; font-size: 15px; color: var(--text); border-top: 1px solid var(--line); text-align: left; }
.opt:first-child { border-top: 0; }
.opt.on { color: var(--primary); font-weight: 500; }
.opt:hover { background: var(--bg-2); }
.spec { margin-left: 8px; font-size: 12px; font-weight: 400; color: var(--text-3); }
.err { color: var(--danger-2); }
</style>
