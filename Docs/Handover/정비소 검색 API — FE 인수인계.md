# 정비소 검색 API — FE 인수인계

> **독자** — 지도에서 "내 주변 정비소" 를 보여 줄 프론트엔드 담당자. **Vue + 순수 JavaScript** 기준으로 썼습니다.
> **작성 기준** — 2026-09-11 작성 (당시 워크트리 `S15P21A307-shop` 미커밋) · **같은 날 갱신** — `origin/develop` **`0ffb7c0`** 에 머지 완료(`920dfb8`). 더 이상 워크트리 전용이 아닙니다
> **이 문서의 JSON 은 실제 DTO(`RepairShopResponse`, `LocationPageResponse`)에서 옮긴 것입니다.**
> 관련 지라 — `S15P21A307-443`(스토리) · `-444`(카카오 연동) · `-445`(거리순 정비소 목록)
>
> ⚠️ **부분 검증됨.** 2026-09-11 에 REST API 키를 발급받아 **카카오 로컬 API 는 실제로 호출했고**(서울시청·부산역·안성죽산·역삼역 네 좌표에서 검색어 `자동차정비` 확정), 자동화 테스트는 쿼터를 태우지 않도록 전부 대역(Mock)입니다. **`/api/repair-shops` 자체를 서버를 띄워 끝에서 끝까지 부른 스모크는 아직 남아 있습니다** (7장).

---

## 0. 30초 요약

```http
GET /api/repair-shops?latitude=37.5665&longitude=126.9780
```

| FE 가 보내는 것 | 서버가 정하는 것 |
|---|---|
| **현재 위치(위도·경도)** — 필수 | 검색어(정비소), **정렬(항상 가까운 순)** |
| 반경(선택) · 페이지 · 크기 | 거리 계산(카카오 값이 비면 서버가 계산) |

### 먼저 알아야 할 다섯 가지

1. **검색어와 정렬을 보내지 마세요.** 범용 장소 검색(`/api/locations/places?query=자동차 정비&sort=DISTANCE`)을 조합하던 방식 대신 이 API 를 씁니다. `query` 를 보내도 무시됩니다.
2. **`latitude`(위도)가 먼저, `longitude`(경도)가 나중입니다.** 카카오 JS SDK 의 `new kakao.maps.LatLng(위도, 경도)` 와 순서가 같습니다. 바꿔 보내면 400 입니다.
3. **로그인이 필요합니다.** 비로그인 401. 카카오 쿼터가 걸린 자원이라 비인증에 열지 않았습니다.
4. **페이지는 0-based 입니다.** 첫 페이지가 `page=0`. 카카오 제약 때문에 최대 45건(3페이지 × 15)까지만 볼 수 있습니다.
5. **429 에 자동 재시도를 넣지 마세요.** 카카오 일일 쿼터가 바닥났다는 뜻이고, 재시도는 복구를 늦출 뿐입니다.

---

## 1. 요청

| 파라미터 | 필수 | 타입 | 규칙 |
|---|:---:|---|---|
| `latitude` | ✅ | number | 현재 위치 위도. 대한민국 범위 **33.0~39.0** 밖이면 400 |
| `longitude` | ✅ | number | 현재 위치 경도. **124.0~132.0** 밖이면 400 |
| `radius` | ⬜ | integer | 미터. 0~20000. **20000 초과는 20000 으로 잘립니다**(400 아님). 음수는 400. **생략하면 반경 제한 없이 가까운 순** — 결과 45건이 곧 가장 가까운 45곳입니다 |
| `page` | ⬜ | integer | 0-based. 기본 0. 음수는 0, 45페이지 초과는 잘립니다 |
| `size` | ⬜ | integer | 1~15. 기본 15. 초과는 15 로 잘립니다 |

- 좌표를 **둘 중 하나만** 보내도 400 입니다 — 조용히 무시하지 않습니다.
- 숫자가 아닌 값(`latitude=abc`)은 400 입니다.
- **거절은 전부 카카오를 부르기 전에 끝납니다.** 잘못된 요청은 쿼터를 쓰지 않습니다.

---

## 2. 응답

```jsonc
{
  "data": {
    "content": [
      {
        "placeId": "12345678",
        "placeName": "김씨카센터",
        "categoryName": "자동차 > 자동차정비",
        "phone": "02-123-4567",                    // null 가능
        "addressName": "서울 중구 태평로1가 31",
        "roadAddressName": "서울 중구 세종대로 110", // null 가능
        "latitude": 37.5665,
        "longitude": 126.9780,
        "placeUrl": "http://place.map.kakao.com/12345678",
        "distanceMeters": 120                      // 현재 위치로부터의 직선거리(m)
      }
    ],
    "page": 0,
    "size": 15,
    "totalElements": 132,
    "pageableCount": 45,
    "totalPages": 3,
    "hasNext": true
  }
}
```

| 필드 | nullable | 설명 |
|---|:---:|---|
| `placeId` | ✗ | 카카오 장소 ID. **우리 DB 의 키가 아닙니다** — 저장할 일이 생기면 별도 설계가 필요합니다 |
| `categoryName` | ✅ | 카카오 분류 경로(`>` 구분) |
| `phone` · `roadAddressName` | ✅ | 카카오가 없으면 빈 문자열을 주는데 서버가 `null` 로 접었습니다 |
| `latitude` · `longitude` | 드묾 | 카카오 응답 이상일 때만 `null` |
| `distanceMeters` | 드묾 | **직선거리**입니다. 도로를 따라가는 주행 거리가 아닙니다. 카카오 값을 쓰고, 비어 오면 서버가 계산합니다. 좌표까지 없는 이상 응답에서만 `null` 이고 그런 행은 맨 뒤에 옵니다 |
| `totalElements` | ✗ | 카카오가 찾은 전체 수 |
| `pageableCount` | ✗ | **실제로 열람 가능한 수 — 최대 45.** `totalElements` 가 132 여도 46번째는 볼 수 없습니다 |
| `totalPages` | ✗ | `pageableCount` 기준입니다 |

### 순서 보장 범위

- **한 페이지 안에서는 `distanceMeters` 오름차순이 보장됩니다.** 거리가 같으면 카카오가 준 순서를 지킵니다.
- 페이지 사이의 순서는 카카오 거리순 정렬이 정합니다.

---

## 3. 오류 — HTTP 상태와 `error.code` 를 함께 보세요

실패 본문은 `{ "error": { "code", "message" } }` 입니다. **단, 401 은 본문이 없습니다** — `status` 로만 판정하세요.

| HTTP | `error.code` | 언제 | 화면 처리 |
|:---:|---|---|---|
| 400 | `INVALID_REQUEST` | 좌표 누락·범위 밖·뒤바뀜, 음수 반경, 숫자 아닌 값 | `message` 노출 가능. 좌표가 뒤바뀌었다는 안내가 메시지에 들어 있습니다 |
| 401 | (본문 없음) | 비로그인·세션 만료 | 로그인 화면으로 |
| 403 | `SIGNUP_REQUIRED` | 소셜 인증만 끝나고 가입 전 | 가입 화면으로 |
| 403 | `FORBIDDEN` | 권한 없음 | 접근 차단 안내 |
| 429 | `TOO_MANY_REQUESTS` | **카카오 일일 쿼터 초과** | "잠시 후 다시 시도해 주세요". **자동 재시도 금지**, 사용자가 누르는 "다시 검색" 버튼만 |
| 503 | `SERVICE_UNAVAILABLE` | 카카오 점검·앱키 문제·서버에 키 미설정 | "일시적으로 사용할 수 없습니다". 재시도는 사용자 조작으로만 |
| 500 | `INTERNAL_ERROR` | 서버 버그(카카오 헤더 구성 오류 등) | 일반 오류 안내 |

> **2026-09-11 수정 사항** — 이전에는 카카오 쿼터 초과·앱키 오류가 **전부 500** 으로 나가고 있었습니다
> (위치 API 문서가 약속한 429·503 이 실제로는 오지 않았음). 이번에 서버가 제대로 429·503 을 내도록 고쳤습니다.
> 이미 500 을 가정한 분기가 있다면 위 표대로 바꿔 주세요.

⚠️ **카카오 원본 오류 문구와 REST API 키는 어떤 응답에도 없습니다.**

---

## 4. ⚠️ 키 두 개 — 헷갈리지 마세요

| 키 | 누가 | 어디에 | 용도 |
|---|---|---|---|
| **Maps JavaScript SDK 키** | **FE** | 브라우저. `.env` 의 `VITE_KAKAO_JS_KEY` 같은 이름으로 | 지도 그리기·마커·로드뷰. **카카오 개발자 콘솔에 사이트 도메인 등록 필수** (`http://localhost:5173` 등 개발 주소 포함) |
| **Local REST API 키** | **서버** | 서버 환경변수 `KAKAO_REST_API_KEY` | 장소 검색. **절대 FE 코드·번들·저장소에 넣지 마세요** — 번들에 들어가면 누구나 우리 쿼터를 씁니다 |

FE 가 카카오 REST API(`dapi.kakao.com/v2/local/...`)를 직접 부르지 않습니다. 장소 데이터는 전부 이 서버 API 로 받습니다.

---

## 5. Vue + JavaScript 예제 (Axios)

**모듈을 나눕니다.** 공통 HTTP 클라이언트(`http.js`) · 정비소 API(`repairShops.js`) · 화면 컴포넌트.
관리자 화면 모듈과 섞지 마세요 — 오류 처리 정책(특히 409)이 다릅니다.

### 5-1. `src/api/http.js` — 공통 클라이언트

```js
import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL, // 예: http://localhost:8080
  withCredentials: true,                        // 세션 쿠키 인증
  timeout: 10000,
})

/**
 * 서버 오류를 { status, code, message } 하나로 정규화한다.
 * - 401 은 본문이 없으므로 status 로만 판단한다.
 * - error.code 는 문자열로 받는다. 모르는 코드는 message 를 보여 준다.
 * - 여기서는 어떤 상태에도 자동 재시도를 하지 않는다. (특히 429)
 */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error) // 디바운스로 취소된 요청
    const status = error.response?.status ?? 0
    const body = error.response?.data?.error
    const code = body?.code ?? (status === 401 ? 'UNAUTHORIZED' : 'NETWORK_ERROR')
    const message = body?.message ?? '요청을 처리할 수 없습니다.'
    return Promise.reject(new ApiError(status, code, message))
  },
)
```

### 5-2. `src/api/repairShops.js` — 정비소 API

```js
import { http } from './http'

/**
 * 현재 위치 기준 가까운 순 정비소.
 * 검색어·정렬은 보내지 않는다 — 서버가 고정한다.
 *
 * params 는 axios 가 값마다 인코딩한다. URL 문자열을 직접 이어 붙이지 말 것.
 * (숫자뿐이라 인코딩 문제는 없지만, 같은 습관을 관리자 모듈의 rawName 에도 쓴다)
 */
export async function fetchNearbyRepairShops({ latitude, longitude, radius, page = 0, size = 15 }, { signal } = {}) {
  const { data } = await http.get('/api/repair-shops', {
    params: { latitude, longitude, radius, page, size }, // radius 가 undefined 면 axios 가 빼고 보낸다
    signal,
  })
  return data.data // { content, page, size, totalElements, pageableCount, totalPages, hasNext }
}
```

### 5-3. `src/utils/debounce.js`

```js
export function debounce(fn, waitMs) {
  let timer = null
  const debounced = (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), waitMs)
  }
  debounced.cancel = () => clearTimeout(timer)
  return debounced
}
```

### 5-4. `src/components/RepairShopMap.vue` — 지도 이동 검색

```vue
<template>
  <div>
    <div ref="mapEl" style="width: 100%; height: 480px" />

    <p v-if="notice" class="notice">{{ notice }}</p>
    <button v-if="canRetryManually" @click="searchAtCenter">이 위치에서 다시 검색</button>

    <ul>
      <li v-for="shop in shops" :key="shop.placeId">
        <strong>{{ shop.placeName }}</strong>
        <span v-if="shop.distanceMeters != null"> · {{ formatDistance(shop.distanceMeters) }}</span>
        <div>{{ shop.roadAddressName ?? shop.addressName }}</div>
        <a v-if="shop.phone" :href="`tel:${shop.phone}`">{{ shop.phone }}</a>
        <a :href="shop.placeUrl" target="_blank" rel="noopener">상세</a>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchNearbyRepairShops } from '@/api/repairShops'
import { debounce } from '@/utils/debounce'

const DEBOUNCE_MS = 400        // 권장 300~500ms. 지도를 끄는 동안에는 호출하지 않는다
const MIN_MOVE_METERS = 150    // 이만큼도 안 움직였으면 다시 부르지 않는다 — 쿼터 절약

const router = useRouter()
const mapEl = ref(null)
const shops = ref([])
const notice = ref('')
const canRetryManually = ref(false)

let map = null
let markers = []
let inFlight = null            // AbortController — 새 검색이 시작되면 이전 요청을 취소
let lastSearched = null        // { lat, lng }
let autoSearchPaused = false   // 429 를 받으면 자동 검색을 멈춘다

function loadKakaoSdk() {
  // JS SDK 키는 FE 몫이다. REST API 키를 여기에 넣지 말 것.
  return new Promise((resolve) => {
    if (window.kakao?.maps) return window.kakao.maps.load(resolve)
    const script = document.createElement('script')
    script.src = `//dapi.kakao.com/v2/maps/sdk.js?appkey=${import.meta.env.VITE_KAKAO_JS_KEY}&autoload=false`
    script.onload = () => window.kakao.maps.load(resolve)
    document.head.appendChild(script)
  })
}

async function searchAt(latitude, longitude) {
  inFlight?.abort()
  inFlight = new AbortController()
  notice.value = ''
  canRetryManually.value = false
  try {
    const page = await fetchNearbyRepairShops({ latitude, longitude, radius: 3000 }, { signal: inFlight.signal })
    lastSearched = { lat: latitude, lng: longitude }
    shops.value = page.content
    drawMarkers(page.content)
  } catch (e) {
    if (e?.name === 'CanceledError') return
    handleError(e)
  }
}

function handleError(e) {
  // HTTP 상태와 error.code 를 함께 본다. 메시지 문자열로 분기하지 않는다.
  switch (e.status) {
    case 401:
      router.push({ name: 'login' })
      return
    case 403:
      if (e.code === 'SIGNUP_REQUIRED') router.push({ name: 'signup' })
      else notice.value = '접근 권한이 없습니다.'
      return
    case 429:
      // 자동 재시도 금지. 지도 이동 자동 검색도 멈추고, 사용자가 버튼으로만 다시 부른다.
      autoSearchPaused = true
      notice.value = '검색 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.'
      canRetryManually.value = true
      return
    case 503:
      notice.value = '정비소 검색을 일시적으로 사용할 수 없습니다.'
      canRetryManually.value = true
      return
    case 400:
      notice.value = e.message // 좌표가 뒤바뀌었는지 등 서버 문구가 구체적이다
      return
    default:
      notice.value = '정비소를 불러오지 못했습니다.'
      canRetryManually.value = true
  }
}

function drawMarkers(list) {
  markers.forEach((m) => m.setMap(null))
  markers = list
    .filter((shop) => shop.latitude != null && shop.longitude != null)
    // ⚠️ LatLng(위도, 경도) — 응답의 latitude, longitude 순서 그대로
    .map((shop) => new window.kakao.maps.Marker({
      map,
      position: new window.kakao.maps.LatLng(shop.latitude, shop.longitude),
      title: shop.placeName,
    }))
}

function movedEnough(lat, lng) {
  if (!lastSearched) return true
  const rad = Math.PI / 180
  const dLat = (lat - lastSearched.lat) * rad
  const dLng = (lng - lastSearched.lng) * rad
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(lastSearched.lat * rad) * Math.cos(lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * 6371008.8 * Math.asin(Math.sqrt(a)) >= MIN_MOVE_METERS
}

function searchAtCenter() {
  autoSearchPaused = false
  const center = map.getCenter()
  searchAt(center.getLat(), center.getLng())
}

const onMapIdle = debounce(() => {
  if (autoSearchPaused) return
  const center = map.getCenter()
  if (!movedEnough(center.getLat(), center.getLng())) return
  searchAt(center.getLat(), center.getLng())
}, DEBOUNCE_MS)

onMounted(async () => {
  await loadKakaoSdk()
  map = new window.kakao.maps.Map(mapEl.value, {
    center: new window.kakao.maps.LatLng(37.5665, 126.9780),
    level: 5,
  })
  window.kakao.maps.event.addListener(map, 'idle', onMapIdle)

  // 현재 위치 — HTTPS(또는 localhost)에서만 동작한다
  navigator.geolocation?.getCurrentPosition(
    ({ coords }) => {
      map.setCenter(new window.kakao.maps.LatLng(coords.latitude, coords.longitude))
      searchAt(coords.latitude, coords.longitude)
    },
    () => { notice.value = '현재 위치를 가져오지 못했습니다. 지도를 움직여 검색해 주세요.' },
  )
})

onBeforeUnmount(() => {
  onMapIdle.cancel()
  inFlight?.abort()
})

function formatDistance(meters) {
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`
}
</script>
```

**이 예제가 지키는 것**

- 지도 `idle` 이벤트마다 부르지 않고 **400ms 디바운스** + **150m 미만 이동은 무시**.
- 새 검색이 시작되면 **이전 요청을 취소**(`AbortController`) — 늦게 온 옛 응답이 새 목록을 덮지 않게.
- **429 를 받으면 자동 검색을 멈추고** 버튼으로만 다시 부름. 인터셉터에도 재시도 로직이 없음.
- 마커는 `LatLng(shop.latitude, shop.longitude)` — 응답 필드 순서 그대로.

---

## 6. 범용 장소 검색과의 관계

| | `GET /api/locations/places` | `GET /api/repair-shops` |
|---|---|---|
| 검색어 | FE 가 보냄 | 서버 고정 |
| 정렬 | `sort` 선택(기본 정확도순) | 항상 거리순 |
| 좌표 | 선택 | **필수** |
| `distanceMeters` | 좌표를 줬을 때만 | 사실상 항상 |
| 페이지 안 순서 보장 | 카카오 그대로 | **서버가 오름차순 보장** |
| 오류·페이지 계약 | 같음 | 같음 |

정비소 화면은 **`/api/repair-shops` 만** 쓰세요. 범용 API 는 주소 입력·일반 장소 검색용입니다.

---

## 7. 아직 안 된 것 / 확인 필요

| 항목 | 상태 |
|---|---|
| 실제 카카오 호출 | ✅ **검증됨 (2026-09-11).** REST 키를 발급받아 서울시청·부산역·안성죽산·역삼역 네 좌표에서 `자동차정비`·`정비소`·`자동차 정비` 세 후보를 비교했고, 네 좌표 모두 결과가 나왔습니다. 검색어를 **`자동차정비`** 로 확정했습니다(`정비소` 는 보일러·자전거 정비를 끌어옵니다) |
| `/api/repair-shops` 종단 스모크 | **미검증.** 서버를 띄워 로그인 세션으로 직접 부른 적은 없습니다 |
| 결과에 정비 외 업종이 섞이는지 | 미확인. 섞이면 서버에서 분류 필터를 추가할 수 있으나 **실응답을 보기 전에는 넣지 않았습니다** (없는 분류 문자열을 지어내지 않기 위해) |
| 45건 제한 | 카카오 제약. 더 넓게 보려면 지도를 옮겨 다시 검색 |
| 캐시 | 없음. 호출 패턴을 본 뒤 결정 |
| 이 API 를 쓰는 화면 | FE 확정 필요 |
| 우리 서비스의 정비소 정보(평점·제휴 등) | 없음. 카카오 장소 데이터만 |
