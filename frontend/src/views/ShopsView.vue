<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useAppStore } from '../stores/app'
import { KAKAO_KEY, formatDistance, geocodeAddress, loadKakao, searchRepairShops, shopKind } from '../lib/kakao'
import { regionAddresses, regionLabel } from '../data/regions'

const router = useRouter()
const store = useAppStore()

const mapEl = ref(null)
const ask = ref(false)
const radiusSheet = ref(false)
// idle | loading | locating | searching | ready | empty | denied | nokey | error
const status = ref('idle')
// 위치 실패 사유: denied | unavailable | timeout | insecure | unsupported
const geoReason = ref('')
const shops = ref([])
const selected = ref(null)
const moved = ref(false) // 검색 지점에서 지도를 움직였는지

// 결과 목록 시트: 헤더를 아래로 끌어내리면 접혀서 지도만 보이고, 접힌 헤더를 탭·위로 끌면 다시 펼쳐짐
const headEl = ref(null)
const listEl = ref(null) // 시트 전체 — 펼쳐진 높이를 재서 지도의 보이는 영역을 구한다
const sheetOpen = ref(true)
const dragging = ref(false)
const dragY = ref(0)
const peek = ref(56) // 접힌 상태에서 남겨둘 헤더 높이(px) — 실제 헤더 크기로 갱신
let y0 = 0

const sheetStyle = computed(() => ({
  transform: `translateY(calc(${sheetOpen.value ? '0px' : `100% - ${peek.value}px`} + ${dragY.value}px))`,
  transition: dragging.value ? 'none' : 'transform .28s cubic-bezier(.2,.8,.2,1)',
}))

let kakao = null
let map = null
let center = null
let overlays = []
let pinEls = new Map() // 정비소 id → 핀 요소. 고른 핀에만 번호를 보이기 위해 붙잡아 둔다
let hereOverlay = null

const mode = computed(() => store.shopConsent) // gps | region | map | null
const gps = computed(() => mode.value === 'gps')
const region = computed(() => mode.value === 'region')
const busy = computed(() => ['loading', 'locating', 'searching'].includes(status.value))
const statusText = computed(() => ({ loading: '지도를 불러오고 있어요', locating: '현재 위치를 확인하고 있어요', searching: '주변 정비소를 찾고 있어요' })[status.value] || '')
const regionName = computed(() => regionLabel(store.shopSido, store.shopRegion, store.shopDong?.label))
const listTitle = computed(() => region.value ? regionName.value : mode.value === 'map' ? '지도 위치 기준' : '가까운 순')

const GEO_MSG = {
  denied: { t: '위치 권한이 꺼져 있어요', d: '주소창 왼쪽 자물쇠(ⓘ) 아이콘 → 위치 → "허용"으로 바꾼 뒤<br>다시 요청하거나, 지도를 움직여 직접 찾아보세요' },
  unavailable: { t: '위치를 확인할 수 없어요', d: '기기의 위치 서비스가 꺼져 있거나 신호가 약해요.<br>지역을 선택하거나 지도에서 직접 찾아보세요' },
  timeout: { t: '위치 확인이 오래 걸려요', d: '잠시 후 다시 시도하거나<br>지역을 선택해 검색해 주세요' },
  insecure: { t: 'https 접속이 필요해요', d: 'IP 주소(http)로 접속하면 브라우저가 위치 정보를 막아요.<br><code>npm run dev:https</code>로 실행한 뒤 https 주소로 접속하거나,<br>지역 선택·지도 이동으로 찾아보세요' },
  unsupported: { t: '이 브라우저는 위치 정보를 지원하지 않아요', d: '지역을 선택하거나 지도에서 직접 찾아보세요' },
}
const geoMsg = computed(() => GEO_MSG[geoReason.value] || GEO_MSG.unavailable)

onMounted(async () => {
  if (!KAKAO_KEY) { status.value = 'nokey'; return }
  status.value = 'loading'
  try {
    kakao = await loadKakao()
    map = new kakao.maps.Map(mapEl.value, { center: new kakao.maps.LatLng(37.5665, 126.978), level: 5 })
    kakao.maps.event.addListener(map, 'dragend', () => { moved.value = true })
    // 지도 빈 곳을 탭하면 결과 시트를 접어 지도만 보이게 (드래그 후에는 click 이 발생하지 않음)
    kakao.maps.event.addListener(map, 'click', collapseSheet)
    status.value = 'idle'
  } catch (e) {
    status.value = 'error'
    return
  }
  if (mode.value === 'gps') startGps()
  else if (mode.value === 'region') startRegion()
  else if (mode.value === 'map') searchHere()
  else ask.value = true
})

onBeforeUnmount(clearOverlays)

function clearOverlays() {
  overlays.forEach((o) => o.setMap(null))
  overlays = []
  pinEls = new Map()
  if (hereOverlay) { hereOverlay.setMap(null); hereOverlay = null }
}

/* ===== 위치 확보 ===== */
function startGps() {
  ask.value = false
  if (!map) return
  if (!navigator.geolocation) { geoReason.value = 'unsupported'; status.value = 'denied'; return }
  // Geolocation 은 보안 컨텍스트(https 또는 localhost)에서만 동작
  if (!window.isSecureContext) { geoReason.value = 'insecure'; status.value = 'denied'; return }
  status.value = 'locating'
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      store.shopConsent = 'gps'
      center = new kakao.maps.LatLng(pos.coords.latitude, pos.coords.longitude)
      search()
    },
    (err) => {
      geoReason.value = err.code === 1 ? 'denied' : err.code === 3 ? 'timeout' : 'unavailable'
      status.value = 'denied'
    },
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 },
  )
}

async function startRegion() {
  ask.value = false
  if (!map) return
  status.value = 'locating'
  try {
    // 읍·면·동까지 고른 경우 선택 시 받아둔 좌표를 바로 사용, 아니면 시·군·구 주소를 지오코딩
    const d = store.shopDong
    center = d ? new kakao.maps.LatLng(d.lat, d.lng) : await geocodeAddress(kakao, regionAddresses(store.shopSido, store.shopRegion))
    search()
  } catch (e) {
    status.value = 'error'
  }
}

// 지도 화면 중심 기준으로 검색 (GPS 없이 사용 가능)
function searchHere() {
  ask.value = false
  if (!map) return
  store.shopConsent = 'map'
  center = map.getCenter()
  search()
}

/* ===== 검색 · 마커 ===== */
async function search() {
  if (!map || !center) return
  status.value = 'searching'
  selected.value = null
  moved.value = false
  sheetOpen.value = true
  try {
    const list = await searchRepairShops(kakao, center, store.shopRadius * 1000)
    shops.value = list.map((p, i) => ({
      n: i + 1, id: p.id, name: p.place_name, kind: shopKind(p), dist: formatDistance(p.distance),
      addr: p.road_address_name || p.address_name, tel: p.phone, url: p.place_url,
      latlng: new kakao.maps.LatLng(Number(p.y), Number(p.x)),
    }))
    render()
    status.value = shops.value.length ? 'ready' : 'empty'
  } catch (e) {
    status.value = 'error'
  }
}

function render() {
  clearOverlays()
  const bounds = new kakao.maps.LatLngBounds()
  bounds.extend(center)

  if (gps.value) {
    const el = document.createElement('div')
    el.className = 'khere'
    el.innerHTML = '<i></i><b></b>'
    hereOverlay = new kakao.maps.CustomOverlay({ position: center, content: el, yAnchor: 0.5, xAnchor: 0.5, zIndex: 1 })
    hereOverlay.setMap(map)
  }

  shops.value.forEach((s) => {
    const el = document.createElement('button')
    el.type = 'button'
    el.className = 'kpin' + (selected.value === s.id ? ' on' : '') // 번호는 고른 핀에만 — 나머지는 점만 찍힌 핀
    el.setAttribute('aria-label', s.name)
    pinEls.set(s.id, el)
    el.innerHTML = `<svg width="34" height="44" viewBox="0 0 34 44" fill="none" aria-hidden="true"><path d="M17 43.5C17 43.5 33 24.5 33 16.5A16 16 0 1 0 1 16.5C1 24.5 17 43.5 17 43.5Z" fill="#4E36E4"/></svg><b>${s.n}</b>`
    // 핀 클릭이 지도 click 으로 전파되어 시트가 접히지 않도록 차단
    el.addEventListener('click', (e) => { e.stopPropagation(); focus(s) })
    const ov = new kakao.maps.CustomOverlay({ position: s.latlng, content: el, yAnchor: 1, xAnchor: 0.5, zIndex: 2 })
    ov.setMap(map)
    overlays.push(ov)
    bounds.extend(s.latlng)
  })

  if (shops.value.length) map.setBounds(bounds, 80, 40, 300, 40)
  else map.setCenter(center)
  // setBounds/setCenter 로 인한 이동은 사용자 조작이 아니므로 플래그 초기화
  setTimeout(() => { moved.value = false }, 0)
}

/** 정비소를 고르면(목록 카드·지도 핀) 그 위치를 보이는 지도의 가운데로. 시트는 지금 상태를 그대로 둔다 */
function focus(s) {
  selected.value = s.id
  pinEls.forEach((el, id) => el.classList.toggle('on', id === s.id)) // 고른 핀에만 번호
  panToVisible(s.latlng)
  setTimeout(() => { moved.value = false }, 400)
  document.getElementById('shop-' + s.id)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}
/** 시트가 펼쳐져 있으면 시트에 가리지 않는 위쪽 지도의 가운데에, 접혀 있으면 지도 전체의 가운데에 오도록 옮긴다.
 *  지도 가운데는 화면 높이의 절반이고 보이는 영역의 가운데는 (높이 - 시트 높이)/2 이므로, 목표점을 시트 높이의 절반만큼 아래에 잡아 panTo 하면 된다 */
function panToVisible(latlng) {
  const hidden = sheetOpen.value ? (listEl.value?.offsetHeight || 0) : 0
  if (!hidden) { map.panTo(latlng); return }
  const proj = map.getProjection()
  const pt = proj.containerPointFromCoords(latlng)
  map.panTo(proj.coordsFromContainerPoint(new kakao.maps.Point(pt.x, pt.y + hidden / 2)))
}

/* ===== 결과 시트 접기/펼치기 ===== */
function measurePeek() { peek.value = headEl.value?.offsetHeight || 56 }
function toggleSheet() { measurePeek(); sheetOpen.value = !sheetOpen.value }
function collapseSheet() { if (!sheetOpen.value || !headEl.value) return; measurePeek(); sheetOpen.value = false }

function onSheetDown(e) {
  if (e.button !== 0) return
  measurePeek()
  y0 = e.clientY
  dragging.value = true
  e.currentTarget.setPointerCapture?.(e.pointerId)
}
function onSheetMove(e) {
  if (!dragging.value) return
  const dy = e.clientY - y0
  // 펼침 상태에서는 아래로, 접힘 상태에서는 위로만 따라오게 (반대 방향은 살짝만 허용)
  dragY.value = sheetOpen.value ? Math.max(dy, -12) : Math.min(dy, 12)
}
function onSheetUp() {
  if (!dragging.value) return
  const dy = dragY.value
  if (sheetOpen.value && dy > 60) collapseSheet()
  else if (!sheetOpen.value && (dy < -40 || Math.abs(dy) < 6)) sheetOpen.value = true // 위로 끌기 또는 탭
  dragY.value = 0
  dragging.value = false
}

/* ===== 칩 · 시트 ===== */
function onGpsChip() { gps.value && status.value === 'ready' ? map.panTo(center) : startGps() }
function clearRegion() { startGps() }
function pickRadius(km) { store.shopRadius = km; radiusSheet.value = false; if (center) search() }
function byRegion() { ask.value = false; router.push('/shops/region') }
function useMap() { ask.value = false; status.value = 'idle'; moved.value = true }
</script>

<template>
  <Screen>
    <AppHeader title="주변 정비소" back="/home" />

    <div class="mapwrap" :class="{ dimmed: ['denied', 'nokey', 'error'].includes(status) }">
      <div ref="mapEl" class="kmap"></div>

      <div class="chips">
        <button class="chip" :class="{ on: gps }" @click="onGpsChip">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="8" cy="8" r="4.2" stroke="currentColor" stroke-width="1.6"/><path d="M8 1v2.2M8 12.8V15M1 8h2.2M12.8 8H15" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
          현재 위치
        </button>
        <button v-if="!region" class="chip" @click="router.push('/shops/region')">지역 지정</button>
        <button v-else class="chip on" @click="clearRegion">
          {{ regionName }}
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" stroke="#FFFFFF" stroke-width="1.8" stroke-linecap="round"/></svg>
        </button>
        <button class="chip" @click="radiusSheet = true">
          반경 {{ store.shopRadius }}km
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 6.5L8 10.5l4-4" stroke="#8B95A1" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>

      <!-- 진행 상태 -->
      <div v-if="busy" class="loading">
        <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true" style="animation:dcspin 1s linear infinite"><circle cx="9" cy="9" r="7" stroke="#EEEBFD" stroke-width="2.5"/><circle cx="9" cy="9" r="7" stroke="#4E36E4" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="44" stroke-dashoffset="31"/></svg>
        {{ statusText }}
      </div>

      <!-- 지도를 움직였을 때: 이 위치에서 재검색 -->
      <button v-if="!busy && moved && !['denied', 'nokey', 'error'].includes(status)" class="here-btn" @click="searchHere">
        <svg width="14" height="14" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M16 9a6 6 0 0 0-10.5-3.2M4 11a6 6 0 0 0 10.5 3.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/><path d="M15.5 3.5v3h-3M4.5 16.5v-3h3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
        이 위치에서 검색
      </button>

      <!-- 위치 확보 실패 -->
      <div v-if="status === 'denied'" class="state">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 21.5c4.6-5.2 6.9-9 6.9-11.5a6.9 6.9 0 1 0-13.8 0c0 2.5 2.3 6.3 6.9 11.5Z" stroke="#D1D6DB" stroke-width="1.7" stroke-linejoin="round"/><path d="M4 3.5l16 16" stroke="#D1D6DB" stroke-width="1.7" stroke-linecap="round"/></svg>
        <h2>{{ geoMsg.t }}</h2>
        <p v-html="geoMsg.d"></p>
        <button class="btn" style="margin-top:24px;height:48px" @click="router.push('/shops/region')">지역 선택하기</button>
        <button class="btn outline muted" style="margin-top:10px;height:48px" @click="useMap">지도에서 직접 찾기</button>
        <button v-if="geoReason !== 'insecure' && geoReason !== 'unsupported'" class="tbtn" style="margin-top:12px" @click="startGps">위치 권한 다시 요청</button>
      </div>

      <!-- API 키 없음 / 로드 실패 -->
      <div v-if="status === 'nokey' || status === 'error'" class="state">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 21.5c4.6-5.2 6.9-9 6.9-11.5a6.9 6.9 0 1 0-13.8 0c0 2.5 2.3 6.3 6.9 11.5Z" stroke="#D1D6DB" stroke-width="1.7" stroke-linejoin="round"/><circle cx="12" cy="9.7" r="2.6" stroke="#D1D6DB" stroke-width="1.7"/></svg>
        <template v-if="status === 'nokey'">
          <h2>카카오 지도 키가 필요해요</h2>
          <p>프로젝트 루트의 <code>.env.local</code> 파일에<br><code>VITE_KAKAO_JS_KEY=발급받은_JavaScript_키</code><br>를 추가하고 서버를 다시 실행해 주세요</p>
        </template>
        <template v-else>
          <h2>지도를 불러오지 못했어요</h2>
          <p>카카오 개발자 콘솔의 JavaScript 키 &gt; JavaScript SDK 도메인에<br>현재 접속 주소가 등록되어 있는지 확인해 주세요</p>
          <button class="btn outline muted" style="margin-top:24px;height:48px" @click="router.go(0)">다시 시도</button>
        </template>
      </div>
    </div>

    <!-- 결과 목록 -->
    <div v-if="status === 'ready' || status === 'empty'" ref="listEl" class="list scroll" :class="{ closed: !sheetOpen }" :style="sheetStyle">
      <!-- 헤더를 아래로 끌면 시트가 접혀 지도만 보임 · 접힌 헤더는 탭 또는 위로 끌어 펼침 -->
      <div
        ref="headEl" class="head" role="button" tabindex="0"
        :aria-expanded="sheetOpen" :aria-label="sheetOpen ? '정비소 목록 접기' : '정비소 목록 펼치기'"
        @pointerdown="onSheetDown" @pointermove="onSheetMove" @pointerup="onSheetUp" @pointercancel="onSheetUp"
        @keydown.enter.prevent="toggleSheet" @keydown.space.prevent="toggleSheet"
      >
        <i class="grab"></i>
        <div class="row between" style="margin-top:16px">
          <span style="font-size:14px;font-weight:600;color:var(--text-3)">{{ listTitle }} · {{ shops.length }}곳</span>
          <span class="sub" style="font-size:12px">카카오맵 기준 · 반경 {{ store.shopRadius }}km</span>
        </div>
      </div>
      <p v-if="status === 'empty'" class="sub center" style="padding:20px 0 16px">반경 {{ store.shopRadius }}km 안에 정비소가 없어요.<br>반경을 넓히거나 지도를 움직여 다시 찾아보세요.</p>
      <div v-else>
        <div v-for="s in shops" :id="'shop-' + s.id" :key="s.id" class="shop" :class="{ on: selected === s.id }" @click="focus(s)">
          <span class="num">{{ s.n }}</span>
          <span class="flex1" style="display:flex;flex-direction:column;min-width:0">
            <span class="sec nowrap" style="overflow:hidden;text-overflow:ellipsis">{{ s.name }}</span>
            <span style="margin-top:3px;font-size:13px;color:var(--text-2)">{{ region ? s.kind : (s.dist ? s.dist + ' · ' : '') + s.kind }}</span>
            <span class="sub nowrap" style="margin-top:3px;font-size:12px;overflow:hidden;text-overflow:ellipsis">{{ s.addr }}</span>
          </span>
          <a class="call" :class="{ off: !s.tel }" :href="s.tel ? 'tel:' + s.tel.replace(/-/g, '') : undefined" :aria-label="s.tel ? '전화하기 ' + s.tel : '전화번호 없음'" @click.stop>
            <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M6.2 3.2h-2a1.2 1.2 0 0 0-1.2 1.3c.3 4.1 2.4 8 5.6 10.2 2.3 1.6 4.8 2.1 5.9 2.1a1.2 1.2 0 0 0 1.3-1.2v-2a1.2 1.2 0 0 0-1-1.2l-2-.4a1.2 1.2 0 0 0-1.2.5l-.6.9a11 11 0 0 1-4.1-4.1l.9-.6a1.2 1.2 0 0 0 .5-1.2l-.4-2a1.2 1.2 0 0 0-1.2-1z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>
          </a>
          <a class="call" :href="s.url" target="_blank" rel="noopener" aria-label="카카오맵에서 보기" @click.stop>
            <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 17s-5.5-4.6-5.5-9A5.5 5.5 0 0 1 15.5 8c0 4.4-5.5 9-5.5 9z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><circle cx="10" cy="8" r="1.9" stroke="currentColor" stroke-width="1.6"/></svg>
          </a>
        </div>
      </div>
    </div>

    <BottomSheet v-model="ask">
      <p class="st">위치 정보를 이용할까요?</p>
      <p class="sd">가까운 정비소를 찾기 위해 현재 위치(GPS)를 사용해요. 위치 정보는 검색에만 쓰이고 저장되지 않습니다.</p>
      <div class="acts">
        <button class="btn outline" @click="byRegion">지역으로 찾기</button>
        <button class="btn bold" @click="startGps">동의하고 사용</button>
      </div>
      <button class="tbtn" style="margin-top:12px" @click="useMap">지도를 움직여 직접 찾기</button>
    </BottomSheet>

    <BottomSheet v-model="radiusSheet">
      <p class="st">검색 반경</p>
      <div class="stack" style="margin-top:12px;gap:8px">
        <button v-for="km in [1, 2, 5, 10]" :key="km" class="ropt" :class="{ on: store.shopRadius === km }" @click="pickRadius(km)">
          <span class="radio" :class="{ on: store.shopRadius === km }"></span>반경 {{ km }}km
        </button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<!-- 지도 위 커스텀 오버레이는 컴포넌트 밖 DOM에 삽입되므로 전역 스타일로 정의 -->
<style>
.kpin { position: relative; width: 34px; height: 44px; display: block; padding: 0; border: 0; background: none; cursor: pointer; filter: drop-shadow(0 2px 4px rgba(25,31,40,.25)); }
.kpin:not(.on)::before { content: ""; position: absolute; left: 11px; top: 11px; width: 12px; height: 12px; border-radius: 50%; background: #fff; } /* 안 고른 핀은 번호 대신 점 */
.kpin.on { transform: scale(1.12); transform-origin: 50% 100%; } /* 고른 핀은 살짝 크게 */
.kpin b { position: absolute; left: 0; top: 0; width: 34px; height: 34px; display: flex; align-items: center; justify-content: center; font-size: 15px; font-weight: 700; color: #fff; font-family: var(--font); }
.kpin b { display: none; } /* 번호는 고른 핀(.on)에만 */
.kpin.on b { display: flex; }
.khere { position: relative; width: 72px; height: 72px; }
.khere i { position: absolute; inset: 0; border-radius: 36px; background: rgba(78,54,228,.15); }
.khere b { position: absolute; left: 24px; top: 24px; width: 24px; height: 24px; border-radius: 12px; background: #4E36E4; box-shadow: 0 0 0 3px #fff; }
</style>

<style scoped>
.mapwrap { position: relative; flex: 1 1 auto; min-height: 260px; overflow: hidden; background: var(--bg-map);
  background-image: repeating-linear-gradient(0deg, rgba(25,31,40,.05) 0 1px, transparent 1px 58px), repeating-linear-gradient(90deg, rgba(25,31,40,.05) 0 1px, transparent 1px 66px); }
.kmap { position: absolute; inset: 0; }
.mapwrap.dimmed::after { content: ""; position: absolute; inset: 0; background: rgba(255,255,255,.6); pointer-events: none; z-index: 2; }
.chips { position: absolute; left: 16px; right: 16px; top: 12px; display: flex; gap: 8px; overflow-x: auto; scrollbar-width: none; z-index: 4; }
.chips::-webkit-scrollbar { display: none; }
.chip { flex: 0 0 auto; height: 40px; padding: 0 14px; border: 1px solid var(--line); border-radius: 20px; background: var(--white); color: var(--text-2); display: flex; align-items: center; gap: 6px; font-size: 13px; white-space: nowrap; box-shadow: 0 2px 8px rgba(25,31,40,.08); }
.chip.on { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 500; box-shadow: 0 2px 8px rgba(25,31,40,.12); }
.loading, .here-btn { position: absolute; left: 50%; top: 64px; transform: translateX(-50%); z-index: 4; display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 14px; border-radius: 18px; background: var(--white); box-shadow: 0 2px 8px rgba(25,31,40,.12); font-size: 13px; color: var(--text-2); white-space: nowrap; }
.here-btn { color: var(--primary); font-weight: 600; border: 1px solid var(--primary-200); animation: fadein .2s ease-out; }
.here-btn:hover { background: var(--primary-50); }
.state { position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); display: flex; flex-direction: column; align-items: center; z-index: 3; background: var(--white); border-radius: 16px; padding: 24px 20px 20px; box-shadow: 0 8px 24px rgba(25,31,40,.14); }
.state h2 { margin-top: 16px; font-size: 17px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; text-align: center; }
.state p { margin-top: 8px; font-size: 13px; line-height: 1.6; color: var(--text-3); text-align: center; }
.state code, .state :deep(code) { font-family: ui-monospace, Consolas, monospace; font-size: 12px; background: var(--bg); padding: 1px 5px; border-radius: 4px; color: var(--text-2); }
.list { position: absolute; left: 0; right: 0; bottom: 0; background: var(--white); border-radius: 20px 20px 0 0; box-shadow: 0 -4px 16px rgba(25,31,40,.08); padding: 0 20px var(--safe-b); z-index: 5; max-height: 52%; overflow-y: auto; will-change: transform; }
.list.closed { overflow: hidden; }
.head { padding-bottom: 8px; touch-action: none; user-select: none; cursor: grab; outline: none; }
.head:active { cursor: grabbing; }
.head:focus-visible .grab { background: var(--primary); }
.grab { display: block; margin: 10px auto 0; width: 40px; height: 4px; border-radius: 2px; background: var(--line-2); }
.shop { min-height: 84px; padding: 12px 8px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid var(--line); cursor: pointer; margin: 0 -8px; border-radius: 10px; }
.shop:last-child { border-bottom: 0; }
.shop.on { background: var(--primary-soft); }
.num { flex: 0 0 28px; width: 28px; height: 28px; border-radius: 14px; background: var(--primary); color: #fff; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.call { flex: 0 0 40px; width: 40px; height: 40px; border-radius: 20px; background: var(--primary-100); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.call:hover { background: var(--primary-200); }
.call.off { background: var(--bg); color: var(--text-4); pointer-events: none; }
.ropt { width: 100%; height: 56px; padding: 0 16px; border: 1px solid var(--line); border-radius: 12px; display: flex; align-items: center; gap: 12px; font-size: 15px; background: var(--white); }
.ropt.on { border-color: var(--primary); font-weight: 600; }
</style>
