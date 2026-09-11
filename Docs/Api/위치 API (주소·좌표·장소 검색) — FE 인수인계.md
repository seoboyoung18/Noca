# 위치 API (주소·좌표·장소 검색) — FE 인수인계

> **독자** — 지도 화면을 만드는 프론트엔드 담당자. **백엔드 코드를 열지 않고** 이 문서만으로 붙일 수 있게 썼습니다.
> **작성 기준** — `develop` `25dccdd` 위의 로컬 작업본. 2026-09-10 · **2026-09-11 갱신** (develop `4a0836d` 머지 반영, 정비소 전용 API·오류 번역 수정)
> **이 문서의 JSON 은 실제 DTO 에서 옮긴 것입니다.** 추측한 필드가 없습니다.
>
> 관련 지라 — `S15P21A307-443`(주변 정비소 지도 검색) · `-444`(카카오 로컬 연동) · `-445`(거리순 정비소 목록).
> ⚠️ 이 API 를 쓰는 화면은 아직 정해지지 않았습니다 (11장).

---

## 0. 30초 요약

| 하고 싶은 것 | 엔드포인트 |
|---|---|
| 주소 문자열 → 좌표 | `GET /api/locations/geocode?query=` |
| 지도에서 찍은 좌표 → 주소 | `GET /api/locations/reverse-geocode?latitude=&longitude=` |
| 장소·업체명으로 검색 | `GET /api/locations/places?query=` |
| 카테고리로 주변 검색 | `GET /api/locations/places/category?categoryGroupCode=` |
| 카테고리 코드 목록 | `GET /api/locations/category-groups` |
| **현재 위치 기준 가까운 정비소** | **`GET /api/repair-shops?latitude=&longitude=`** — 별도 문서 `정비소 검색 API — FE 인수인계.md` |

### 먼저 알아야 할 다섯 가지

1. **⚠️ `latitude`(위도)가 먼저, `longitude`(경도)가 나중입니다.** 카카오 JS SDK 도 `LatLng(위도, 경도)` 순서라 그대로 넘기면 됩니다. 다만 **카카오 REST 문서의 `x`·`y` 와는 순서가 반대**입니다 — 이 API 에는 `x`/`y` 라는 이름이 아예 없습니다 (2장).
2. **전부 로그인이 필요합니다.** 비로그인은 401. 카카오 쿼터가 걸린 자원이라 비인증에 열지 않았습니다.
3. **페이지는 0-based 입니다.** 카카오는 1-based 지만 서버가 변환합니다. 프로젝트의 다른 조회 API 와 같습니다 (4장).
4. **`roadAddress` 는 `null` 일 수 있습니다.** 카카오가 좌표에 따라 도로명 주소를 주지 않습니다. `landLotAddress` 로 fallback 하세요.
5. **지도 렌더링은 FE 몫입니다.** 서버는 좌표·장소 **데이터만** 줍니다. 지도 이미지·로드뷰·길찾기는 제공하지 않습니다 (10장).

---

## 1. 공통 규약

### 1-1. 응답 봉투

성공은 항상 `{ "data": ... }`, 실패는 항상 `{ "error": { "code", "message" } }` 입니다. 프로젝트 공통 규약과 같습니다.

### 1-2. 인증

`SecurityConfig` 의 `anyRequest().hasAnyRole("USER","ADMIN")` 이 그대로 적용됩니다.

| 상황 | 결과 |
|---|---|
| 비로그인 | 401 |
| 가입 대기(`ROLE_SIGNUP_PENDING`) | 403 `SIGNUP_REQUIRED` |
| 로그인 `USER`·`ADMIN` | 정상 |

### 1-3. 오류 코드

**`error.code` 로 분기하세요.** 한글 메시지는 문구가 바뀝니다.

| `error.code` | HTTP | 언제 | 화면이 할 일 |
|---|:---:|---|---|
| `INVALID_REQUEST` | 400 | 좌표 범위 밖, 파라미터 조합 오류, 숫자 아닌 값, 정의되지 않은 카테고리 코드 | `message` 를 그대로 노출 가능 |
| `TOO_MANY_REQUESTS` | 429 | **카카오 일일 쿼터 초과** | "잠시 후 다시 시도" 안내. **자동 재시도 금지** (5장) |
| `SERVICE_UNAVAILABLE` | 503 | 카카오 점검·앱키 문제·서버에 키 미설정 | "일시적으로 사용할 수 없습니다". 운영자 확인 필요 |
| `UNAUTHORIZED` | 401 | 비로그인 | 로그인 유도 |

⚠️ **카카오 원본 오류 메시지는 절대 내려가지 않습니다.** 서버가 프로젝트 문구로 바꿉니다. REST API 키도 응답·로그 어디에도 없습니다.

> **2026-09-11 수정** — `develop` `4a0836d` 까지는 위 표와 달리 카카오 실패(쿼터 초과·앱키 오류·점검)가
> **전부 500 `INTERNAL_ERROR`** 로 나가고 있었습니다. 전송 계층의 예외가 공통 오류 처리에 연결되지 않았기
> 때문입니다. 이제 표대로 429·503·400 이 나갑니다 (`LocationKakaoFailureMappingTest`).

---

## 2. ⚠️ 축 순서 — 이 연동에서 가장 사고가 잘 나는 지점

### 세 가지 표기가 섞여 있습니다

| 주체 | 표기 |
|---|---|
| **이 API** | `latitude`(위도), `longitude`(경도) — 이름으로 명시 |
| 카카오 **JS SDK** | `new kakao.maps.LatLng(위도, 경도)` — **이 API 와 같은 순서** |
| 카카오 **REST** 문서 | `x` = 경도, `y` = 위도 — **반대**. 서버 내부에서만 씁니다 |

### 그대로 넘기면 됩니다

```js
const { data } = await api.get('/api/locations/geocode', { params: { query: '서울시청' } });
const first = data.content[0];

// ✅ 순서가 이 API 와 같습니다
const position = new kakao.maps.LatLng(first.latitude, first.longitude);
new kakao.maps.Marker({ map, position });
```

### 왜 이렇게까지 강조하나

**바꿔 넣어도 에러가 나지 않습니다.** 서울은 위도 37.5·경도 127.0 인데 바꿔 넣으면 위도 127 이 되고, 그 좌표는 **인도양 한가운데**입니다. 카카오는 그것을 오류로 보지 않고 "결과 없음" 을 정상 응답으로 줍니다 — **버그가 빈 결과로 위장됩니다.**

그래서 서버가 **대한민국 범위를 벗어난 좌표를 카카오 호출 전에 400 으로 거절**합니다.

| 축 | 허용 범위 |
|---|---|
| `latitude` | 33.0 ~ 39.0 |
| `longitude` | 124.0 ~ 132.0 |

마라도·독도·백령도를 포함하는 넉넉한 사각형입니다. **국경 정밀 판정이 아니라 축 뒤집힘 탐지가 목적**입니다.

```jsonc
// 400 — 위도·경도를 바꿔 보낸 경우
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "좌표가 대한민국 범위를 벗어났습니다. 위도는 33.0~39.0, 경도는 124.0~132.0 입니다. 위도와 경도를 바꿔 보내지 않았는지 확인해 주세요 (latitude=127.1086228, longitude=37.4012191)."
  }
}
```

---

## 3. 주소 → 좌표 (지오코딩)

```http
GET /api/locations/geocode?query={주소}&page=0&size=10&exact=false
```

| 파라미터 | 필수 | 설명 |
|---|:---:|---|
| `query` | O | 검색할 주소 문자열 |
| `page` | X | 0-based. 기본 0. **상한 44 초과는 절단** |
| `size` | X | 1~**30**. 기본 10. **초과는 절단** |
| `exact` | X | `true` 면 정확한 주소만. 기본 `false`(유사 주소 포함) |

**응답**

```jsonc
{
  "data": {
    "content": [
      {
        "addressName": "전북 익산시 부송동 100",
        "addressType": "REGION_ADDR",
        "latitude": 37.40128143786497,
        "longitude": 127.10860415615309,
        "landLotAddress": {
          "addressName": "전북 익산시 부송동 100",
          "region1DepthName": "전북",
          "region2DepthName": "익산시",
          "region3DepthName": "부송동",
          "mainAddressNo": "100",
          "subAddressNo": null
        },
        "roadAddress": {
          "addressName": "전북 익산시 망산길 11-17",
          "region1DepthName": "전북",
          "region2DepthName": "익산시",
          "region3DepthName": "부송동",
          "roadName": "망산길",
          "mainBuildingNo": "11",
          "subBuildingNo": "17",
          "buildingName": "한국전력공사",
          "zoneNo": "54547"
        }
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 4,
    "pageableCount": 4,
    "totalPages": 1,
    "hasNext": false
  }
}
```

| `addressType` | 뜻 |
|---|---|
| `REGION` | 지명 |
| `ROAD` | 도로명 |
| `REGION_ADDR` | 지번 주소 |
| `ROAD_ADDR` | 도로명 주소 |

- **`zoneNo` 가 우편번호입니다** (5자리). 카카오의 6자리 `zip_code` 는 Deprecated 라 제공하지 않습니다.
- **`subAddressNo`·`subBuildingNo`·`buildingName` 은 없으면 `null`** 입니다. 카카오는 빈 문자열(`""`)을 주지만 서버가 `null` 로 접었습니다 — `""` 를 "값이 있다" 로 오해하지 않게.

---

## 4. 페이지네이션 규약

### 0-based 입니다

카카오는 `page=1` 이 첫 페이지지만, **이 API 는 `page=0` 이 첫 페이지**입니다. 프로젝트의 다른 조회 API(`AdminPageResponse`, 사고 조회)와 같은 규약을 골랐습니다 — 화면마다 규약이 다르면 언젠가 한 페이지 밀린 목록을 보게 되고, 그 버그는 **2페이지부터만** 나타나서 찾기 어렵습니다.

### ⚠️ `totalElements` 와 `pageableCount` 는 다릅니다

| 필드 | 뜻 |
|---|---|
| `totalElements` | 검색된 **전체** 문서 수 (카카오 `total_count`) |
| `pageableCount` | **실제로 열어볼 수 있는** 문서 수 (카카오 `pageable_count`). **장소 검색은 최대 45로 잘립니다** |
| `totalPages` | **`pageableCount` 기준** 쪽수 |
| `hasNext` | 다음 페이지가 있는가 (카카오 `is_end` 의 반대) |

**예시** — 장소 검색에서 `totalElements: 1000`, `pageableCount: 45`, `size: 15` 인 경우:

- `totalPages` 는 **3** 입니다 (45 ÷ 15).
- `totalElements` 로 계산하면 67쪽이 되지만 **4쪽부터는 빈 결과**입니다.
- 화면에 "총 1,000건" 은 띄울 수 있지만 **페이지네이션은 `totalPages` 를 써야 합니다.**

### 상한 초과는 거절하지 않고 절단합니다

`S15P21A307-225` 정책과 같습니다.

| 입력 | 결과 |
|---|---|
| `page=999` | 45번째 페이지로 절단 (400 아님) |
| `page=-5` | 첫 페이지로 |
| `size=9999` | 주소 30 / 장소 15 로 절단 |
| `size=0` · 미전송 | 기본값 (주소 10 / 장소 15) |
| `page=abc` | **400** — 숫자가 아닌 값만 거절 |

---

## 5. 좌표 → 주소 (역지오코딩)

```http
GET /api/locations/reverse-geocode?latitude=37.4012191&longitude=127.1086228
```

지도에서 사용자가 클릭·핀 이동으로 고른 지점을 저장 가능한 주소로 바꿉니다.

**응답 — 배열입니다. 0건 또는 1건.**

```jsonc
{
  "data": [
    {
      "addressName": "경기도 안성시 죽산면 죽산초교길 69-4",
      "addressType": null,
      "latitude": null,
      "longitude": null,
      "landLotAddress": {
        "addressName": "경기도 안성시 죽산면 죽산리 120-3",
        "region1DepthName": "경기",
        "region2DepthName": "안성시",
        "region3DepthName": "죽산면 죽산리",
        "mainAddressNo": "120",
        "subAddressNo": "3"
      },
      "roadAddress": {
        "addressName": "경기도 안성시 죽산면 죽산초교길 69-4",
        "roadName": "죽산초교길",
        "mainBuildingNo": "69",
        "subBuildingNo": "4",
        "buildingName": "무지개",
        "zoneNo": "17519",
        "region1DepthName": "경기",
        "region2DepthName": "안성시",
        "region3DepthName": "죽산면"
      }
    }
  ]
}
```

### 주의할 세 가지

1. **`data` 가 빈 배열일 수 있습니다.** 바다나 미등록 구역을 찍으면 결과가 없습니다. **404 가 아니라 200 + `[]`** 입니다 — 지도에서 아무 곳이나 찍을 수 있으므로 "주소 없음" 은 정상 흐름입니다. "이 위치의 주소를 찾을 수 없습니다" 를 정상 화면으로 그리세요.

2. **`roadAddress` 가 `null` 일 수 있습니다.** 카카오가 좌표에 따라 도로명 주소를 주지 않습니다.

```js
// ✅ fallback 을 항상 두세요
const display = item.roadAddress?.addressName ?? item.landLotAddress?.addressName ?? '주소 없음';
```

3. **`addressType`·`latitude`·`longitude` 가 `null` 입니다.** 이 엔드포인트는 좌표를 우리가 준 것이라 카카오가 되돌려주지 않습니다. **없는 값을 지어내지 않았습니다** — 좌표가 필요하면 요청에 보낸 값을 그대로 쓰세요.

---

## 6. 키워드 장소 검색

```http
GET /api/locations/places?query={질의어}&latitude=&longitude=&radius=&sort=&categoryGroupCode=&page=0&size=15
```

| 파라미터 | 필수 | 설명 |
|---|:---:|---|
| `query` | O | 장소명·업체명 |
| `latitude`·`longitude` | X | 중심 좌표. **둘 다 보내거나 둘 다 안 보내야 합니다.** 하나만 보내면 400 |
| `radius` | X | 0~**20000** 미터. **좌표와 함께만** 유효. 좌표 없이 보내면 400. 20000 초과는 절단, 음수는 400 |
| `sort` | X | `ACCURACY`(기본) · `DISTANCE`. **`DISTANCE` 는 좌표가 필수** — 없으면 400 |
| `categoryGroupCode` | X | 카테고리로 추가 필터 (7장) |
| `page` | X | 0-based |
| `size` | X | 1~**15**. 기본 15 |

**응답**

```jsonc
{
  "data": {
    "content": [
      {
        "placeId": "26338954",
        "placeName": "카카오프렌즈 반포한강공원점",
        "categoryName": "가정,생활 > 문구,사무용품 > 팬시점",
        "categoryGroupCode": null,
        "phone": "02-543-5548",
        "addressName": "서울 서초구 반포동 245-5",
        "roadAddressName": "서울 서초구 신반포로11길 40",
        "latitude": 37.51269873333228,
        "longitude": 127.00380043029785,
        "placeUrl": "http://place.map.kakao.com/26338954",
        "distanceMeters": 1273
      }
    ],
    "page": 0, "size": 15, "totalElements": 1,
    "pageableCount": 1, "totalPages": 1, "hasNext": false
  }
}
```

- **`distanceMeters` 는 중심 좌표를 넘겼을 때만 채워집니다.** 안 넘겼으면 `null` 입니다. 카카오는 빈 문자열을 주지만 서버가 `null` 로 접었습니다 — `""` 를 0m 로 오해하지 않게.
- **`phone`·`roadAddressName`·`categoryGroupCode` 도 `null` 일 수 있습니다.**
- **`placeUrl`** 은 카카오맵 장소 상세 페이지입니다. 새 창으로 그대로 열면 됩니다.
- **`placeId` 는 카카오의 ID 이고 우리 DB 의 키가 아닙니다.** 저장이 필요하면 별도 설계가 필요합니다 (11장).

### 조합 오류 예시

```jsonc
// 400 — sort=DISTANCE 인데 좌표가 없다
{ "error": { "code": "INVALID_REQUEST", "message": "거리순 정렬에는 중심 좌표(latitude·longitude)가 필요합니다." } }

// 400 — radius 만 보냈다
{ "error": { "code": "INVALID_REQUEST", "message": "radius 는 중심 좌표(latitude·longitude)와 함께 보내야 합니다." } }

// 400 — 좌표를 하나만 보냈다
{ "error": { "code": "INVALID_REQUEST", "message": "latitude 와 longitude 는 함께 보내야 합니다." } }
```

> 이 셋을 서버가 **거절**하는 이유는, 카카오가 이런 요청에 오류를 내지 않고 **다른 결과를 조용히 돌려주기** 때문입니다. 좌표 없이 `DISTANCE` 를 요청하면 카카오는 정확도순 결과를 줍니다 — 사용자는 "가까운 순" 을 눌렀는데 아무 순서인 목록을 보게 됩니다.

---

## 7. 카테고리 장소 검색

```http
GET /api/locations/places/category?categoryGroupCode=PM9&latitude=37.5&longitude=127.0&radius=1000&page=0&size=15
```

| 파라미터 | 필수 | 설명 |
|---|:---:|---|
| `categoryGroupCode` | O | 아래 18종 중 하나. 정의되지 않은 값은 **400** |
| `latitude`·`longitude` | **O** | 카카오가 중심 좌표 또는 사각 영역을 요구합니다. 이 API 는 중심 좌표만 지원 |
| `radius` | X | 0~20000 미터 |
| `sort`·`page`·`size` | X | 키워드 검색과 같음 |

응답은 키워드 검색과 **같은 모양**입니다.

### CategoryGroupCode 18종

`GET /api/locations/category-groups` 로도 받을 수 있습니다 (코드 + 한글 이름). **하드코딩하지 말고 이 API 를 쓰세요.**

| 코드 | 이름 | 코드 | 이름 | 코드 | 이름 |
|---|---|---|---|---|---|
| `MT1` | 대형마트 | `PK6` | 주차장 | `AG2` | 중개업소 |
| `CS2` | 편의점 | `OL7` | 주유소·충전소 | `PO3` | 공공기관 |
| `PS3` | 어린이집·유치원 | `SW8` | 지하철역 | `AT4` | 관광명소 |
| `SC4` | 학교 | `BK9` | 은행 | `AD5` | 숙박 |
| `AC5` | 학원 | `CT1` | 문화시설 | `FD6` | 음식점 |
| | | | | `CE7` | 카페 |
| | | | | `HP8` | 병원 |
| | | | | `PM9` | 약국 |

### ⚠️ 정비소·카센터 코드가 없습니다

18종을 훑어보면 **자동차 정비 업종이 하나도 없습니다.** 이 서비스의 성격상 "주변 정비소" 를 찾을 일이 있을 텐데, **카테고리 검색으로는 불가능합니다.**

> **2026-09-11 변경 — 정비소 전용 API 가 생겼습니다.** 검색어·거리순을 FE 가 조합하지 말고
> **`GET /api/repair-shops?latitude=&longitude=`** 를 쓰세요. 서버가 검색어와 가까운 순 정렬을 고정하고
> 거리를 항상 채워 줍니다. 계약과 Vue 예제는 **`정비소 검색 API — FE 인수인계.md`** 에 있습니다.

```js
// ✅ 정비소는 전용 API 로 — 검색어·정렬을 보내지 않는다
await http.get('/api/repair-shops', { params: { latitude, longitude, radius: 3000 } });

// ⚠️ 예전 방식 — 동작은 하지만 쓰지 마세요. 검색어가 FE 에 흩어집니다
await http.get('/api/locations/places', {
  params: { query: '자동차 정비', latitude, longitude, radius: 3000, sort: 'DISTANCE' }
});

// ❌ 이런 코드는 없습니다. 400 이 납니다
await http.get('/api/locations/places/category', { params: { categoryGroupCode: 'CAR9' } });
```

`AG2`(중개업소)나 `OL7`(주유소·충전소)를 정비소 대용으로 쓰지 마세요 — 다른 업종이고, 결과가 섞이면 사용자가 잘못된 곳으로 차를 가져갑니다.

---

## 8. 쿼터 — 지도 이동마다 호출하지 마세요

카카오 로컬 API 는 **일일 허용 회수(쿼터)** 가 있고, 초과하면 **서비스 전체가 멈춥니다.** 초과 시 429 `TOO_MANY_REQUESTS` 가 나가고, 그때는 로그인한 모든 사용자의 조회가 함께 실패합니다.

### 권고

| 상황 | 권고 |
|---|---|
| 지도 이동·확대 중 역지오코딩 | **디바운스 300~500ms.** 이동이 멈춘 뒤 한 번만 |
| 최소 이동거리 | **이전 호출 지점에서 50m 이상 움직였을 때만** 다시 호출 |
| 주소 입력창 | 타이핑마다 호출하지 말고 **디바운스 300ms** 또는 검색 버튼 |
| 같은 좌표 재조회 | **FE 에서 짧게 캐시하세요** (같은 지점을 다시 찍는 경우가 흔합니다) |
| 429 를 받았을 때 | **자동 재시도 금지.** 쿼터가 이미 바닥났으므로 재시도는 상황만 악화시킵니다 |
| 503 을 받았을 때 | 재시도해도 되지만 간격을 두세요. 서버가 이미 내부 재시도를 했습니다 |

> **서버 쪽 캐시는 아직 없습니다.** 이 API 를 쓰는 화면이 정해지지 않아 호출 패턴(중복률·이동 빈도)을 모르는 상태에서 TTL 과 좌표 반올림 정밀도를 정하면 근거 없는 숫자가 굳습니다. 실제 호출량을 보고 결정할 후속 항목입니다 (11장).

---

## 9. FE 가 별도로 해야 하는 일

이 API 는 **데이터만** 줍니다. 지도를 그리는 것은 FE 몫이고, 그쪽은 **다른 키**를 씁니다.

| 구분 | 제품 | 키 | 담당 |
|---|---|---|---|
| 지도 렌더링·마커·로드뷰 | Maps **JavaScript SDK** | **JavaScript 키** + 도메인 등록 | **FE** |
| 주소·좌표·장소 데이터 | **Local REST API** | REST API 키 (서버 보관) | 백엔드 (이 문서) |

### 체크리스트

1. **카카오 개발자 콘솔에서 JavaScript 키 발급**
2. **플랫폼 > Web 에 도메인 등록** — 개발용 `http://localhost:5173`(Vite), `http://localhost:8080` 을 함께 넣으세요. 등록하지 않은 도메인에서는 SDK 가 지도를 그리지 않습니다.
3. SDK 스크립트 로드 후 지도 초기화
4. 서버에서 받은 `latitude`·`longitude` 로 마커 생성

> **JavaScript 키를 서버에 주지 마세요.** 백엔드는 그것을 보관하지도, 응답으로 내보내지도 않습니다 — 키가 새는 통로를 늘리지 않으려는 의도입니다.

---

## 10. 백엔드가 제공하지 않는 것

| 항목 | 이유 |
|---|---|
| **지도 이미지 (정적 지도)** | 카카오에 **서버용 정적 지도 REST API 가 없습니다.** `kakao.maps.StaticMap` 은 JS SDK 클래스라 브라우저에서만 씁니다 |
| **로드뷰** | JS SDK 기능 |
| **길찾기·경로 계산** | 카카오모빌리티 Directions 는 별개 제품이고 이번 범위 밖입니다 |
| **좌표 → 행정구역 코드** (`coord2regioncode`) | 필요하다는 근거가 코드·요구사항에 없어 만들지 않았습니다. 필요해지면 말씀해 주세요 |
| **좌표계 변환** (`transcoord`) | 위와 같음. 이 API 는 WGS84 만 다룹니다 |
| **장소·주소 저장** | 이 API 는 **조회 프록시**이고 DB 를 쓰지 않습니다. 사고 위치를 저장해야 하면 스키마 설계가 선행입니다 (11장) |

---

## 11. 아직 정해지지 않은 것 / 후속

| 항목 | 상태 |
|---|---|
| **이 API 를 쓰는 화면** | ❓ **미확정.** 지라 백로그에 지도·위치 관련 이슈가 없습니다. 이 작업은 이슈 선행 세팅입니다 |
| **지라 이슈** | ❌ 없음. 커밋 컨벤션상 이슈 키가 필요해 먼저 등록해야 합니다 |
| **`KAKAO_REST_API_KEY` 환경변수** | ⚠️ **미설정 상태입니다.** 설정 전에는 이 API 전체가 503 입니다 (애플리케이션 기동은 정상) |
| **쿼터 실제 한도** | ❓ 카카오 콘솔에서 확인이 필요합니다 |
| **서버 캐시** | ⚠️ 없음. 소비처가 붙은 뒤 호출량을 보고 결정 (8장) |
| **조회 결과 영속화** | ❌ 없음. `placeId`·좌표를 우리 DB 에 저장해야 하면 별도 스키마 작업 |
| **실제 카카오 호출 검증** | ⚠️ **미검증.** 모든 테스트는 `MockRestServiceServer` 로 카카오를 대역화했습니다. 키를 설정한 환경에서 한 번 실제 호출로 확인이 필요합니다 |

---

*작성 2026-09-10 · `develop` `25dccdd` 위 로컬 작업본 기준 · 커밋 전*
