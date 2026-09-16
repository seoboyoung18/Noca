<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useVehicleStore } from '../stores/vehicles'
import { MODEL_YEAR_MAX, MODEL_YEAR_MIN, isUnsupportedVehicle, vehicleName, vehicleSpec } from '../data/vehicles'

// 내 차량 — GET /api/vehicles/me · PATCH(연식만) · DELETE(소프트 삭제)
// 대표 차량 뱃지·차량번호·최근 사고 접수는 서버에 없는 값이라 표시하지 않는다 (화면-서버 대조표 화면 20)
const router = useRouter()
const vs = useVehicleStore()

// idle | loading | error
const state = ref('idle')
// 로딩·오류가 아닌데 차량이 없을 때만 빈 상태 화면
const isEmpty = computed(() => state.value !== 'loading' && state.value !== 'error' && !vs.vehicles.length)
const loadError = ref('')
onMounted(load)
async function load() {
  state.value = 'loading'
  loadError.value = ''
  try { await vs.loadVehicles(true); state.value = 'idle' }
  catch (e) { state.value = 'error'; loadError.value = e.status === 0 ? e.message : '차량 목록을 불러오지 못했어요.' }
}

const sh = reactive({ more: false, year: false, del: false })
const curId = ref(null)
const cur = computed(() => vs.vehicles.find((v) => v.vehicleId === curId.value) || null)
const busy = ref(false)
const actionError = ref('')

function openMore(v) { curId.value = v.vehicleId; actionError.value = ''; sh.more = true }

/* 연식 수정 — 모델은 바꿀 수 없다(보내면 400). 모델을 바꾸려면 삭제 후 재등록 */
const years = Array.from({ length: new Date().getFullYear() + 1 - MODEL_YEAR_MIN + 1 }, (_, i) => new Date().getFullYear() + 1 - i)
  .filter((y) => y <= MODEL_YEAR_MAX)
function openYear() { sh.more = false; actionError.value = ''; sh.year = true }
async function pickYear(y) {
  if (busy.value || !cur.value) return
  if (y === cur.value.modelYear) { sh.year = false; return }
  busy.value = true
  actionError.value = ''
  try { await vs.changeYear(cur.value.vehicleId, y); sh.year = false }
  catch (e) { actionError.value = e.status === 400 ? e.message : e.status === 404 ? '차량을 찾을 수 없어요. 목록을 새로 고칩니다.' : '연식을 저장하지 못했어요.'; if (e.status === 404) load() }
  finally { busy.value = false }
}

/* 삭제 — 소프트 삭제라 사고 이력은 남는다. 멱등이라 중복 클릭에도 안전 */
function askDelete() { sh.more = false; actionError.value = ''; sh.del = true }
async function doDelete() {
  if (busy.value || !cur.value) return
  busy.value = true
  actionError.value = ''
  try { await vs.remove(cur.value.vehicleId); sh.del = false }
  catch (e) { actionError.value = e.status === 404 ? '이미 삭제된 차량이에요.' : '차량을 삭제하지 못했어요.'; if (e.status === 404) { sh.del = false; load() } }
  finally { busy.value = false }
}
</script>

<template>
  <Screen>
    <AppHeader title="차량 관리" back="/my" line>
      <template #right>
        <button class="act" @click="router.push('/vehicles/new?from=my')">+ 추가</button>
      </template>
    </AppHeader>

    <!-- 빈 상태 — 사고 이력 화면과 같이 별도 컨테이너(.body.col)에 두어 화면 세로 중앙에 놓는다 -->
    <div v-if="isEmpty" class="body col">
      <div class="empty">
        <img src="/assets/logo-small.png" alt="">
        <b>등록된 차량이 없어요</b>
        <p>차량을 등록하면<br>더 정확한 견적을 받을 수 있어요</p>
        <button class="btn" style="margin-top:20px;width:auto;padding:0 24px;height:44px" @click="router.push('/vehicles/new?from=my')">차량 등록하기</button>
      </div>
    </div>

    <div v-else class="body scroll" style="padding-top:20px">
      <!-- 로딩 -->
      <div v-if="state === 'loading' && !vs.vehicles.length" class="stack">
        <div v-for="i in 2" :key="i" class="card skel" style="height:96px"></div>
      </div>

      <!-- 오류 -->
      <div v-else-if="state === 'error'" class="empty" style="margin-top:80px">
        <b>{{ loadError }}</b>
        <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:44px" @click="load">다시 시도</button>
      </div>

      <!-- 목록 -->
      <div v-else class="stack">
        <div v-for="v in vs.vehicles" :key="v.vehicleId" class="card" style="padding:16px">
          <div class="row between">
            <span class="row" style="gap:8px;min-width:0">
              <span class="nowrap" style="font-size:16px;font-weight:600;overflow:hidden;text-overflow:ellipsis">{{ vehicleName(v) }}</span>
              <span v-if="isUnsupportedVehicle(v)" class="tag gray">분석 미지원</span>
            </span>
            <button class="more" :aria-label="vehicleName(v) + ' 더보기'" @click="openMore(v)">
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><circle cx="9" cy="4" r="1.3" fill="#8B95A1"/><circle cx="9" cy="9" r="1.3" fill="#8B95A1"/><circle cx="9" cy="14" r="1.3" fill="#8B95A1"/></svg>
            </button>
          </div>
          <div class="sub" style="margin-top:6px">{{ v.modelYear }}년식 · {{ vehicleSpec(v) }}</div>
        </div>
      </div>

      <p v-if="vs.vehicles.length" class="sub center" style="margin-top:16px;font-size:12px">차량을 삭제해도 사고 이력은 남아요</p>
    </div>
    <div class="spacer"></div>

    <!-- 더보기: 연식 수정 · 삭제 (모델 변경은 서버가 허용하지 않아 삭제 후 재등록으로 안내) -->
    <BottomSheet v-model="sh.more">
      <p class="st">{{ vehicleName(cur) }}</p>
      <p class="sd" style="margin-top:6px">{{ cur?.modelYear }}년식 · {{ vehicleSpec(cur) }}</p>
      <div class="mlist" style="margin-top:16px">
        <button class="mrow" @click="openYear"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#4E36E4" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#4E36E4" stroke-width="1.6"/></svg></span><span class="ml">연식 수정<small>차량 모델은 바꿀 수 없어요. 다른 차량은 삭제 후 새로 등록해 주세요</small></span></button>
        <button class="mrow del" @click="askDelete"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#D14343" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"/></svg></span><span class="ml">삭제</span></button>
      </div>
      <div class="acts" style="margin-top:12px"><button class="btn outline" @click="sh.more = false">닫기</button></div>
    </BottomSheet>

    <!-- 연식 선택 -->
    <BottomSheet v-model="sh.year">
      <p class="st">연식 수정</p>
      <p class="sd" style="margin-top:6px">{{ vehicleName(cur) }}</p>
      <div class="opts scroll" :class="{ dim: busy }">
        <button v-for="y in years" :key="y" class="opt" :class="{ on: y === cur?.modelYear }" :disabled="busy" @click="pickYear(y)">
          <span class="flex1">{{ y }}년</span>
          <svg v-if="y === cur?.modelYear" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
      <p v-if="actionError" class="sub err" role="alert">{{ actionError }}</p>
      <div class="acts" style="margin-top:12px"><button class="btn outline" :disabled="busy" @click="sh.year = false">{{ busy ? '저장 중…' : '닫기' }}</button></div>
    </BottomSheet>

    <!-- 삭제 확인 -->
    <BottomSheet v-model="sh.del">
      <h2 class="st">이 차량을 삭제할까요?</h2>
      <p class="sd"><b style="color:var(--text)">{{ vehicleName(cur) }}</b>이(가) 내 차량 목록에서 사라져요.<br>이 차량으로 접수한 사고 이력은 그대로 남습니다.</p>
      <p v-if="actionError" class="sub err" role="alert">{{ actionError }}</p>
      <div class="acts">
        <button class="btn outline" :disabled="busy" @click="sh.del = false">취소</button>
        <button class="btn danger bold" :disabled="busy" @click="doDelete">{{ busy ? '삭제 중…' : '삭제' }}</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.more { width: 32px; height: 32px; margin: -7px -7px -7px 0; display: flex; align-items: center; justify-content: center; border-radius: 16px; }
.more:hover { background: var(--bg); }
.skel { background: var(--bg); border-color: transparent; animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: .55; } }
.opts { margin-top: 12px; max-height: 320px; overflow-y: auto; border: 1px solid var(--line); border-radius: 12px; }
.opts.dim { opacity: .55; pointer-events: none; }
.opt { width: 100%; height: 48px; padding: 0 16px; display: flex; align-items: center; font-size: 15px; color: var(--text); border-top: 1px solid var(--line); text-align: left; }
.opt:first-child { border-top: 0; }
.opt.on { color: var(--primary); font-weight: 500; }
.opt:hover { background: var(--bg-2); }
.err { margin-top: 10px; color: var(--danger-2); }
</style>
