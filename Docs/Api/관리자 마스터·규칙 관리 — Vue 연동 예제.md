# 관리자 마스터·규칙 관리 — Vue 연동 예제 (JavaScript)

> **독자** — 관리자 화면을 **Vue + 순수 JavaScript** 로 붙이는 프론트엔드 담당자.
> **계약 자체는 `관리자 마스터·규칙 관리 API — FE 인수인계.md` 가 정본입니다.** 이 문서는 그 계약을
> 화면 코드로 옮기는 방법만 다룹니다. 필드·오류 코드 전체 목록은 그 문서를 보세요.
> **작성 2026-09-11** (prompt53) · 저장소에 FE 프로젝트가 없어 화면은 만들지 않고 예제만 둡니다.
> 관련 지라 — `S15P21A307-362`·`-363`·`-364`·`-365`

---

## 0. 모듈 구성 — 정비소 검색과 섞지 마세요

```
src/
  api/
    http.js              ← 공통 클라이언트 (정비소 검색 문서의 것과 같은 파일)
    admin/
      vehicleModels.js
      partCodes.js
      partNameMappings.js
      rules.js           ← 수리 방식 규칙 · 이상 탐지 임계값 · 이력
  composables/
    useVersionedForm.js  ← version / baseVersion 을 폼 상태로 들고 다니는 도우미
  views/admin/…
```

관리자 모듈은 **409 를 사용자에게 설명해야** 하고, 정비소 검색은 **429 를 조용히 멈춰야** 합니다.
오류 처리 정책이 달라 모듈을 나눕니다. 공통 클라이언트는 **재시도를 전혀 하지 않습니다.**

---

## 1. `src/api/http.js` — 공통 클라이언트

```js
import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  withCredentials: true,   // 세션 쿠키. 관리자 ID 는 절대 요청에 싣지 않는다 — 서버가 세션에서 꺼낸다
  timeout: 10000,
})

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code        // 문자열로 받는다. 관리자 오류 코드는 계속 늘 수 있다
  }
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const status = error.response?.status ?? 0
    const body = error.response?.data?.error          // 401 은 본문이 없다
    const code = body?.code ?? (status === 401 ? 'UNAUTHORIZED' : 'NETWORK_ERROR')
    return Promise.reject(new ApiError(status, code, body?.message ?? '요청을 처리할 수 없습니다.'))
  },
)
```

---

## 2. HTTP 상태 **와** `error.code` 를 함께 처리하기

상태 코드만 보면 **409 세 종류를 구분할 수 없습니다** — 중복은 입력을 고치고, 버전 충돌은 다시 읽고,
참조 중은 다른 것을 먼저 바꿔야 합니다. 반대로 `error.code` 만 보면 401(본문 없음)을 놓칩니다.

```js
// src/api/admin/handleAdminError.js
export function describeAdminError(e) {
  switch (e.status) {
    case 401: return { action: 'login' }
    case 403: return e.code === 'SIGNUP_REQUIRED'
      ? { action: 'signup' }
      : { action: 'toast', text: '관리자만 사용할 수 있습니다.' }
    case 404: return { action: 'toast', text: '없는 항목입니다. 목록을 새로 고칩니다.', reload: true }
    case 409:
      switch (e.code) {
        case 'VERSION_CONFLICT':
        case 'CONFLICT':                  // 분류되지 않은 경쟁 조건 — 같은 처리
          return { action: 'reloadAndReview', text: '다른 관리자가 먼저 변경했습니다. 최신 값을 확인한 뒤 다시 저장해 주세요.' }
        case 'DUPLICATE_VEHICLE_MODEL':
        case 'DUPLICATE_PART_CODE':
        case 'DUPLICATE_PART_NAME_MAPPING':
          return { action: 'fieldError', text: e.message }
        case 'NORMALIZED_NAME_CONFLICT':  // 메시지에 충돌 상대 원문·부품이 들어 있다
        case 'OVERLAPPING_RULE_RANGE':    // 메시지에 겹치는 규칙 ID
        case 'REFERENCED_BY_ACTIVE_RULE':
        case 'LAST_ACTIVE_RULE':
        case 'CODE_IN_USE':
          return { action: 'dialog', text: e.message }
        default:
          return { action: 'dialog', text: e.message }
      }
    case 400: return { action: 'fieldError', text: e.message } // INVALID_REQUEST·INACTIVE_CODE·UNKNOWN_CODE 등
    case 429: return { action: 'toast', text: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.' } // 자동 재시도 없음
    case 503: return { action: 'toast', text: '일시적으로 사용할 수 없습니다.' }             // 판정 규칙 시드 누락 등
    default:  return { action: 'toast', text: '처리하지 못했습니다.' }
  }
}
```

| 상태 | 화면 | 비고 |
|:---:|---|---|
| 401 | 로그인 화면으로 | 본문 없음 |
| 403 | `SIGNUP_REQUIRED` → 가입, 그 외 → 접근 차단 | |
| 409 | **`error.code` 로 세 갈래** — 입력 수정 / 다시 읽고 재검토 / 먼저 다른 것 변경 | 자동 재시도·자동 덮어쓰기 금지 |
| 429 | 안내만 | 관리자 API 에 레이트 리밋은 없지만 공통 정책으로 **자동 재시도 금지** |
| 503 | 안내 | `GET /api/admin/estimate-validation-rules/current` 는 판정 규칙 시드가 없으면 503 |

---

## 3. `version` / `baseVersion` 을 폼 상태에 그대로 들고 다니기

**규칙 하나 — 서버가 준 `version` 을 사용자가 못 보는 필드로 폼에 넣어 두고, 저장할 때 그대로 보낸다.**
화면에서 계산하거나 +1 하지 않습니다. 저장이 성공하면 **응답의 `version` 으로 교체**합니다.

```js
// src/composables/useVersionedForm.js
import { reactive, ref } from 'vue'

/**
 * @param load  () => Promise<서버 응답 data>
 * @param save  (form) => Promise<서버 응답 data>
 * @param pick  서버 응답 → 폼 필드 (version 포함)
 */
export function useVersionedForm({ load, save, pick }) {
  const form = reactive({})
  const saving = ref(false)
  const conflict = ref(null)   // 409 VERSION_CONFLICT 때 서버 최신값

  async function reload() {
    Object.assign(form, pick(await load()))
    conflict.value = null
  }

  async function submit() {
    saving.value = true
    try {
      const saved = await save({ ...form })   // version 이 그대로 실려 간다
      Object.assign(form, pick(saved))         // 새 version 으로 교체
      return saved
    } catch (e) {
      if (e.status === 409 && (e.code === 'VERSION_CONFLICT' || e.code === 'CONFLICT')) {
        // 자동으로 다시 저장하지 않는다 — 남의 변경을 덮는다. 최신값을 보여 주고 사용자가 고른다.
        conflict.value = pick(await load())
      }
      throw e
    } finally {
      saving.value = false
    }
  }

  function takeServerVersion() {
    Object.assign(form, conflict.value)
    conflict.value = null
  }

  return { form, saving, conflict, reload, submit, takeServerVersion }
}
```

### 3-1. 차량 모델 수정 화면

```js
// src/api/admin/vehicleModels.js
import { http } from '../http'

export const vehicleModels = {
  // vehicleType: SEDAN·SUV·VAN·TRUCK / carClass: 응답과 같은 'CityCar'·'Compact'·'Mid-size'·'Full-size'
  search: (q) => http.get('/api/admin/vehicle-models', { params: q }).then((r) => r.data.data),
  get: (id) => http.get(`/api/admin/vehicle-models/${id}`).then((r) => r.data.data),
  create: (body) => http.post('/api/admin/vehicle-models', body).then((r) => r.data.data),
  update: (id, body) => http.patch(`/api/admin/vehicle-models/${id}`, body).then((r) => r.data.data),
  changeStatus: (id, active, version) =>
    http.patch(`/api/admin/vehicle-models/${id}/status`, { active, version }).then((r) => r.data.data),
}
```

```vue
<!-- src/views/admin/VehicleModelEdit.vue -->
<script setup>
import { onMounted } from 'vue'
import { vehicleModels } from '@/api/admin/vehicleModels'
import { useVersionedForm } from '@/composables/useVersionedForm'
import { describeAdminError } from '@/api/admin/handleAdminError'

const props = defineProps({ modelId: { type: Number, required: true } })

const { form, saving, conflict, reload, submit, takeServerVersion } = useVersionedForm({
  load: () => vehicleModels.get(props.modelId),
  save: (f) => vehicleModels.update(props.modelId, {
    manufacturer: f.manufacturer, modelName: f.modelName,
    vehicleType: f.vehicleType, carClass: f.carClass,
    version: f.version,                      // ← 조회 때 받은 값 그대로
  }),
  pick: (d) => ({ manufacturer: d.manufacturer, modelName: d.modelName,
                  vehicleType: d.vehicleType, carClass: d.carClass, version: d.version }),
})

onMounted(reload)

async function onSave() {
  try { await submit() } catch (e) { showError(describeAdminError(e)) }
}
function showError(r) { /* 토스트·다이얼로그·필드 오류로 분기 */ }
</script>

<template>
  <form @submit.prevent="onSave">
    <input v-model.trim="form.manufacturer" maxlength="50" />
    <input v-model.trim="form.modelName" maxlength="100" />
    <select v-model="form.vehicleType"><option>SEDAN</option><option>SUV</option><option>VAN</option><option>TRUCK</option></select>
    <select v-model="form.carClass"><option>CityCar</option><option>Compact</option><option>Mid-size</option><option>Full-size</option></select>
    <!-- version 은 화면에 보이지 않지만 form 안에 있다 -->
    <button :disabled="saving">저장</button>

    <div v-if="conflict" class="conflict">
      다른 관리자가 먼저 바꿨습니다. 최신 값: {{ conflict.manufacturer }} {{ conflict.modelName }}
      <button type="button" @click="takeServerVersion">최신 값으로 다시 편집</button>
    </div>
  </form>
</template>
```

### 3-2. 이상 탐지 임계값 — `baseVersion`

`PATCH /api/admin/estimate-validation-rules/current` 는 **새 버전을 만듭니다.** 이름이 `version` 이 아니라
`baseVersion`(= 조회한 `ruleVersion`)입니다. 값이 그대로면 새 버전이 생기지 않고 현재 규칙이 그대로 옵니다.

```js
// src/api/admin/rules.js
import { http } from '../http'

export const rules = {
  overview: () => http.get('/api/admin/rules/overview').then((r) => r.data.data),
  currentThresholds: () => http.get('/api/admin/estimate-validation-rules/current').then((r) => r.data.data),
  saveThresholds: (form) => http.patch('/api/admin/estimate-validation-rules/current', {
    baseVersion: form.ruleVersion,             // ← 조회한 ruleVersion 을 그대로
    severeOverP75Multiplier: form.severeOverP75Multiplier,
    cautionTotalDifferenceRatio: form.cautionTotalDifferenceRatio,
    needsReviewTotalDifferenceRatio: form.needsReviewTotalDifferenceRatio,
    needsReviewItemCount: form.needsReviewItemCount,
    changeNote: form.changeNote,
  }).then((r) => r.data.data),
  // 수리 방식 규칙 — 상태 변경도 version 필수. 다시 켤 때 코드가 꺼져 있으면 400 INACTIVE_CODE
  changeRuleStatus: (ruleId, active, version) =>
    http.patch(`/api/admin/repair-method-rules/${ruleId}/status`, { active, version }).then((r) => r.data.data),
  history: (q) => http.get('/api/admin/rules/history', { params: q }).then((r) => r.data.data),
}
```

```js
const thresholds = useVersionedForm({
  load: rules.currentThresholds,
  save: rules.saveThresholds,
  pick: (d) => ({ ...d }),                    // ruleVersion 이 곧 baseVersion
})
```

---

## 4. 부품명 매핑 — 한글 `rawName` 과 `encodeURIComponent`

`rawName` 은 **경로가 아니라 쿼리 파라미터**이고, 실제 시드 원문에 `/`·`%`·`#`·`&`·`+`·괄호·공백이 들어 있습니다.

**원칙 — axios 의 `params` 에 원문을 그대로 넣는다.** axios 가 값마다 인코딩합니다.
**여기에 `encodeURIComponent` 를 한 번 더 쓰면 이중 인코딩**되어 `%2F` 가 `%252F` 로 가고 404 가 납니다.

```js
// src/api/admin/partNameMappings.js
import { http } from '../http'

export const partNameMappings = {
  // scope: 'AI_LABEL' 이면 AI 핵심 32종 대상 매핑만. 응답의 partCodeScope 로도 구분된다
  search: (q) => http.get('/api/admin/part-name-mappings', { params: q }).then((r) => r.data.data),

  create: ({ rawName, partCode, changeReason }) =>
    http.post('/api/admin/part-name-mappings', { rawName, partCode, changeReason }).then((r) => r.data.data),

  // ✅ rawName 은 params 로. 인코딩은 axios 가 한다
  retarget: (rawName, { partCode, changeReason }) =>
    http.patch('/api/admin/part-name-mappings', { partCode, changeReason }, { params: { rawName } })
      .then((r) => r.data.data),

  // 삭제에는 본문이 없어 changeReason 도 쿼리로 간다
  remove: (rawName, changeReason) =>
    http.delete('/api/admin/part-name-mappings', { params: { rawName, changeReason } }),
}
```

**`encodeURIComponent` 가 필요한 곳은 URL 문자열을 손으로 만들 때뿐입니다.**

```js
// 링크·라우터 쿼리를 문자열로 만들 때
const href = `/admin/mappings?rawName=${encodeURIComponent(row.rawName)}`

// ❌ 이렇게 하지 마세요 — '&' 에서 값이 잘리고 '+' 는 공백이 됩니다
http.patch(`/api/admin/part-name-mappings?rawName=${row.rawName}`, body)
// ❌ 이것도 — params 와 섞으면 이중 인코딩
http.patch('/api/admin/part-name-mappings', body, { params: { rawName: encodeURIComponent(row.rawName) } })
```

| 원문 문자 | 손으로 이어 붙이면 | `params` / `encodeURIComponent` 를 쓰면 |
|---|---|---|
| `/` | 경로로 해석될 위험 | `%2F` |
| `%` | 잘못된 이스케이프로 400 | `%25` |
| `#` | 뒤가 잘려 서버에 안 옴 | `%23` |
| `&` | 다음 파라미터로 잘림 | `%26` |
| `+` | **공백으로 디코딩돼 다른 원문이 조회됨** | `%2B` |
| 한글·공백·괄호 | 브라우저마다 다름 | UTF-8 퍼센트 인코딩 |

화면 목록에서는 **`inDictionary: false` 행에 경고 표시**를 해 주세요 — 등록은 됐지만 런타임 사전에 들어가지 않는 행입니다.

---

## 5. 목록 필터 — 새로 생긴 파라미터 (2026-09-11)

```js
vehicleModels.search({ keyword: '기아', vehicleType: 'SUV', carClass: 'Mid-size', active: true, page: 0, size: 20 })
partNameMappings.search({ keyword: '범퍼', scope: 'AI_LABEL', page: 0, size: 50 })
```

- `carClass` 는 **응답과 같은 표기**입니다. 목록에서 받은 값을 필터 드롭다운에 그대로 쓰세요. `MID_SIZE` 는 400 입니다.
- `scope` 는 `AI_LABEL`·`EXTENDED` 둘뿐입니다. 다른 값은 400.
- 페이지는 0-based, `size` 상한 200 (넘기면 깎임).
