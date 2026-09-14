<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useAppStore } from '../stores/app'
import { REGIONS, findRegion, regionAddresses, regionLabel } from '../data/regions'
import { KAKAO_KEY, loadKakao, searchRegions } from '../lib/kakao'

const router = useRouter()
const store = useAppStore()

const sido = ref(store.shopSido)
const gu = ref(store.shopRegion)
const open = ref(gu.value ? '' : 'gu') // 'sido' | 'gu' | ''

const gus = computed(() => findRegion(sido.value)?.gus ?? [])
const hasGu = computed(() => gus.value.length > 0)
const valid = computed(() => !!sido.value && (!hasGu.value || !!gu.value))

/* ===== 읍·면·동 (선택) — 카카오 주소 검색으로 하위 행정구역을 찾아 좌표까지 확보 ===== */
const dong = ref(store.shopDong) // { label, addr, lat, lng } | null
const dongQ = ref('')
const dongResults = ref([])
// idle | searching | ok | empty | error | nokey
const dongState = ref(KAKAO_KEY ? 'idle' : 'nokey')
const dongReady = computed(() => !!sido.value && (!hasGu.value || !!gu.value)) // 상위 지역이 정해져야 검색 가능
const parentLabel = computed(() => regionLabel(sido.value, hasGu.value ? gu.value : ''))
let kakao = null
let timer = null

onMounted(async () => {
  if (!KAKAO_KEY) return
  try { kakao = await loadKakao() } catch (e) { dongState.value = 'error' }
})
onBeforeUnmount(() => clearTimeout(timer))

// 두 글자 이상 입력하면 잠시 기다렸다가 자동 검색
watch(dongQ, (q) => {
  clearTimeout(timer)
  dongResults.value = []
  if (q.trim().length < 2) { if (dongState.value !== 'nokey' && dongState.value !== 'error') dongState.value = 'idle'; return }
  timer = setTimeout(() => searchDong(q), 350)
})

async function searchDong(q) {
  q = (q ?? dongQ.value).trim()
  if (!kakao || !dongReady.value || q.length < 2) return
  clearTimeout(timer)
  dongState.value = 'searching'
  try {
    const list = await searchRegions(kakao, regionAddresses(sido.value, hasGu.value ? gu.value : ''), q)
    if (dongQ.value.trim() !== q) return // 입력이 바뀐 뒤 도착한 응답은 무시
    dongResults.value = list
    dongState.value = list.length ? 'ok' : 'empty'
  } catch (e) {
    dongState.value = 'error'
  }
}
function pickDong(d) { dong.value = d; dongQ.value = ''; dongResults.value = [] }
function clearDong() { dong.value = null; dongQ.value = '' }

function pickSido(s) {
  sido.value = s
  gu.value = ''
  clearDong()
  open.value = findRegion(s)?.gus.length ? 'gu' : ''
}
function pickGu(g) { gu.value = g; clearDong(); open.value = '' }
function apply() {
  if (!valid.value) return
  store.shopSido = sido.value
  store.shopRegion = hasGu.value ? gu.value : ''
  store.shopDong = dong.value
  store.shopConsent = 'region'
  router.push('/shops')
}
</script>

<template>
  <Screen>
    <AppHeader title="지역 선택" back="/shops" line />
    <div class="body scroll">
      <h1 class="h1 sm" style="margin-top:28px">어느 지역에서 찾을까요?</h1>

      <div class="field" style="margin-top:24px">
        <span class="fl">시 · 도</span>
        <button class="sel" @click="open = open === 'sido' ? '' : 'sido'">
          <span class="v">{{ sido }}</span>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true" :style="open === 'sido' ? 'transform:rotate(180deg)' : ''"><path d="M5.5 8.5L10 13l4.5-4.5" stroke="#8B95A1" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <div v-if="open === 'sido'" class="opts">
          <button v-for="r in REGIONS" :key="r.sido" class="opt" :class="{ on: r.sido === sido }" @click="pickSido(r.sido)">
            <span class="flex1">{{ r.sido }}</span>
            <svg v-if="r.sido === sido" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </div>
      </div>

      <div class="field" style="margin-top:20px">
        <span class="fl">시 · 군 · 구</span>
        <template v-if="hasGu">
          <button class="sel" @click="open = open === 'gu' ? '' : 'gu'">
            <span class="v" :class="{ ph: !gu }">{{ gu || '선택하세요' }}</span>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true" :style="open === 'gu' ? 'transform:rotate(180deg)' : ''"><path d="M5.5 8.5L10 13l4.5-4.5" stroke="#8B95A1" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <div v-if="open === 'gu'" class="opts">
            <button v-for="g in gus" :key="g" class="opt" :class="{ on: g === gu }" @click="pickGu(g)">
              <span class="flex1">{{ g }}</span>
              <svg v-if="g === gu" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </template>
        <div v-else class="sel" style="color:var(--text-2)">
          <span class="v">{{ sido }} 전체</span>
        </div>
        <p v-if="!hasGu" class="sub" style="margin-top:8px;font-size:12px">이 지역은 하위 시·군·구 없이 전체 범위로 검색해요</p>
      </div>

      <!-- 읍·면·동 (선택): 이름을 입력해 카카오 주소 검색 결과에서 고른다. 시 아래 구(장안구 등)도 같은 방식으로 검색 -->
      <div class="field" style="margin-top:20px">
        <span class="fl">읍 · 면 · 동 <span class="optag">선택</span></span>

        <div v-if="dong" class="sel picked">
          <span class="v"><b>{{ dong.label }}</b><span class="addr">{{ dong.addr }}</span></span>
          <button class="xbtn" aria-label="읍·면·동 선택 해제" @click="clearDong">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          </button>
        </div>

        <template v-else>
          <div class="inpwrap">
            <input
              v-model="dongQ" class="inp" type="search" enterkeyhint="search" autocomplete="off"
              :disabled="!dongReady || dongState === 'nokey' || dongState === 'error'"
              :placeholder="dongReady ? '예) 역삼동, 배방읍, 장안구' : '시·군·구를 먼저 선택하세요'"
              @keyup.enter="searchDong()"
            >
            <svg v-if="dongState === 'searching'" class="spin" width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><circle cx="9" cy="9" r="7" stroke="#EEEBFD" stroke-width="2.5"/><circle cx="9" cy="9" r="7" stroke="#4E36E4" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="44" stroke-dashoffset="31"/></svg>
          </div>

          <div v-if="dongResults.length" class="opts">
            <button v-for="d in dongResults" :key="d.addr" class="opt" @click="pickDong(d)">
              <span class="flex1">{{ d.label }}</span>
              <span class="sub" style="font-size:12px">{{ d.addr }}</span>
            </button>
          </div>
          <p v-else-if="dongState === 'empty'" class="sub hint">'{{ dongQ.trim() }}'에 해당하는 읍·면·동을 찾지 못했어요.<br>이름을 정확히 입력하거나 비워두면 {{ parentLabel }} 전체에서 찾아요</p>
          <p v-else-if="dongState === 'nokey' || dongState === 'error'" class="sub hint">지도 서비스를 불러오지 못해 읍·면·동 검색을 사용할 수 없어요. {{ parentLabel }} 전체에서 찾아요</p>
          <p v-else class="sub hint">비워두면 {{ dongReady ? parentLabel + ' 전체' : '선택한 지역 전체' }}에서 찾아요. 시 아래 구(예: 장안구)도 검색할 수 있어요</p>
        </template>
      </div>
      <div style="height:20px"></div>
    </div>
    <div class="foot">
      <button class="btn" :disabled="!valid" @click="apply">이 지역에서 찾기</button>
    </div>
  </Screen>
</template>

<style scoped>
.opts { margin-top: 6px; border: 1px solid var(--line); border-radius: 12px; background: var(--white); overflow: hidden; max-height: 336px; overflow-y: auto; scrollbar-width: none; }
.opts::-webkit-scrollbar { display: none; }
.opt { width: 100%; height: 48px; padding: 0 16px; display: flex; align-items: center; font-size: 15px; color: var(--text); text-align: left; }
.opt + .opt { border-top: 1px solid var(--line); }
.opt.on { color: var(--primary); font-weight: 500; }
.opt:hover { background: var(--bg-2); }
.optag { margin-left: 6px; font-size: 12px; font-weight: 500; color: var(--text-3); }
.inpwrap { position: relative; }
.inpwrap .inp { padding-right: 44px; }
.inpwrap .spin { position: absolute; right: 14px; top: 50%; margin-top: -5px; animation: dcspin 1s linear infinite; }
.hint { margin-top: 8px; font-size: 12px; line-height: 1.5; }
.sel.picked { border-color: var(--primary); }
.sel.picked .v { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.sel.picked b { font-weight: 600; white-space: nowrap; }
.sel.picked .addr { font-size: 12px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.xbtn { flex: 0 0 28px; width: 28px; height: 28px; border-radius: 14px; display: flex; align-items: center; justify-content: center; color: var(--text-3); background: var(--bg); }
.xbtn:hover { background: var(--bg-2); color: var(--text); }
</style>
