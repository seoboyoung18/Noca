# 이미지 업로드 API — FE 인수인계

> **대상 백로그** `[FE] 다각도 이미지 업로드 화면 구성` · `[FE] 진행률 표시·재시도 화면 구성`
> **대응 BE** Story `S15P21A307-137`(다각도 이미지 업로드) · `S15P21A307-141`(진행률·재시도)
> **서버 상태** `develop` 머지 완료. **S3 어댑터도 들어갔다** — 남은 것은 배포 환경변수뿐
> **작성 기준** `origin/develop` + `feature/S15P21A307-137-be-accident-image-query`. 2026-09-10 갱신
> **근거** 요구사항 명세서 21·22행 · 정본 DDL `A307_ddl_final.sql` · 구현 코드와 테스트 777개
> **⚠️ 이 문서의 JSON 은 실제 record 에서 옮긴 것이며 실제 AWS 호출로 검증하지는 않았다** — 아래 0-1 을 먼저 읽을 것

---

## ⚠️ 시작하기 전에 — 503 은 **버킷 환경변수를 넣으면 사라진다**

이 문서의 예전 판은 "S3 어댑터가 없어서 항상 503" 이라고 적었습니다. **지금은 어댑터가 develop 에 있습니다.**
남은 것은 배포 환경에 버킷 두 개를 넣는 일뿐이고, 코드 변경은 필요 없습니다.

```
STORAGE_STAGING_BUCKET / STORAGE_SERVICE_BUCKET 이 비어 있으면
  POST /api/accidents/{id}/images/upload-urls   503
  POST /api/accidents/{id}/images               503
  DELETE /api/accidents/{id}/images/{imageId}   503
  GET  /api/accidents/{id}/images               200   ← 이것만은 저장소 없이도 동작합니다

두 값이 채워지면 네 개 모두 정상 동작합니다.
```

필요한 변수와 IAM 권한은 `backend/.env.example` 에 정리돼 있습니다.

**503 을 정상 분기로 처리해 두세요.** "이미지 기능 준비 중" 같은 문구면 충분하고, 버킷이 들어가는 순간 그 분기만 지나가지 않게 됩니다. **API 계약은 바뀌지 않습니다.**

---

## 0. 먼저 알아야 할 것 다섯 가지

### 0-1. 이 문서의 JSON 은 **코드와 테스트에서 옮긴 것**이다 — 실측이 아니다

차량·가이드 인수인계 문서는 실행 중인 서버에 curl 을 쳐서 받은 본문을 그대로 붙였다. **이 문서는 그럴 수 없다.** 위의 이유로 성공 경로가 실행되지 않고, 로컬 PostgreSQL·Redis 도 지금 떠 있지 않다.

대신 아래 JSON 은 **컨트롤러 계약 테스트(`AccidentImageControllerTest`)가 단언하는 필드 그대로**다. 필드 이름·타입·중첩은 테스트가 고정하고 있어 추측이 아니지만, `Content-Type` 헤더나 쿠키 같은 **전송 계층의 실측값은 이 문서에 없다.**

→ 어댑터가 붙어 실서버로 확인되면 이 문서를 실측본으로 갱신한다.

### 0-2. 바이트는 **서버를 거치지 않는다** — 업로드는 3단계다

```
1. POST .../images/upload-urls    서버가 제약을 검증하고 파일별 presigned PUT URL 을 준다   (201)
2. PUT  {uploadUrl}               브라우저 → S3 직접.  ★ BE 무관 ★
3. POST .../images                완료 통보. 서버가 실제 오브젝트를 재검증하고 전처리한다     (200)
```

`multipart/form-data` 로 서버에 파일을 올리는 API 는 **없다.** 20장 × 20MB = 400MB 가 서버를 통과하는 구조를 만들지 않는다는 결정이다(요구사항 21행 비고 "대용량은 Presigned URL 로 스토리지 직접 업로드 권장").

### 0-3. **진행률은 FE 가 계산한다** — 서버는 진행률을 주지 않는다

2단계에서 바이트는 브라우저 → S3 로 흐른다. **서버는 그 전송을 볼 수 없다.** 그래서 진행률 API 가 없고, 앞으로도 생기지 않는다.

```js
const xhr = new XMLHttpRequest();
xhr.upload.onprogress = (e) => {
  if (e.lengthComputable) setProgress(file.imageId, e.loaded / e.total);
};
```

⚠️ **`fetch()` 로는 업로드 진행률을 못 읽는다.** `fetch` 에 업로드 progress 이벤트가 없다. **2단계 PUT 만 `XMLHttpRequest` 로 짜야 한다.** 1·3단계는 `fetch` 로 해도 된다.

### 0-4. 서버가 아는 상태는 **두 개뿐**이다 — `PENDING` / `COMPLETED`

요구사항 22행은 대기·진행·완료·실패 **네 상태**를 말한다. 서버는 그 넷을 저장하지 않는다. 정본 `accident_image` 에 `upload_status` 류 컬럼이 없고, Story 141 이 "추가 기능·중요도 중" 이라 MVP 가 아닌 이유로 스키마를 건드리지 않았다.

| FE 가 보여줄 상태 | 누가 아는가 |
|---|---|
| 대기 | **FE** (내 큐에 있고 아직 PUT 안 함) |
| 진행 (%) | **FE** (`xhr.upload.onprogress`) |
| 실패 | **FE** (PUT 이 실패했거나, 완료 통보 응답의 `results[].status === "FAILED"`) |
| 완료 | **서버와 FE 둘 다** (완료 통보가 끝나면 `COMPLETED`) |

→ **네 상태는 FE 의 클라이언트 상태로 관리한다.** 서버의 `GET .../images` 는 **화면을 새로 열었을 때 "무엇이 아직 안 끝났는지" 복구하는 용도**다. 새로고침하면 진행률과 실패 사유는 사라지고 `PENDING` 만 남는다.

### 0-5. **각도는 이제 저장된다** ✅ (2026-09-10 변경)

예전에는 `angleCode` 를 검증만 하고 버려서 새로고침하면 각도 배지가 사라졌습니다. **`accident_image.angle_code` 컬럼이 생겨 이제 영구 저장됩니다.**

| | 예전 | 지금 |
|---|---|---|
| 발급 응답 `angleCode` | 요청값을 메아리로 되돌려줌 | **DB 에 저장된 값** |
| 완료 통보 응답 | 없음 | **`angleCode` 있음** |
| `GET .../images` | 없음 | **`angleCode` 있음** |
| 새로고침 후 | 각도 소실 | **유지됨** |

- **저장 시점은 발급(1단계)입니다.** 완료 통보 요청에는 각도 필드가 없고, 넣어도 받지 않습니다.
- 값은 `GET /api/guides/shooting` 의 `shots[].angleCode` 9종만 허용합니다 —
  `FRONT` · `REAR` · `LEFT` · `RIGHT` · `FRONT_LEFT` · `FRONT_RIGHT` · `REAR_LEFT` · `REAR_RIGHT` · `DAMAGE_CLOSE`.
  다른 값을 보내면 **400 `UNKNOWN_ANGLE_CODE`** 이고 행도 생기지 않습니다.
- **선택 필드입니다.** 안 보내면 `null` 이고, 빈 문자열도 `null` 로 접힙니다.
- **컬럼이 생기기 전에 올라간 이미지는 `null`** 입니다. 추측해서 채우지 않았습니다 — `null` 을 "각도 미지정" 으로 그려 주세요.
- **수정 API 는 없습니다.** 각도를 바꾸려면 지우고 다시 올리는 것이 현재 계약입니다.

---

## 1. API 4종

| # | 메서드 | 경로 | 인증 | 성공 | 지금 상태 |
|---|---|---|:---:|:---:|---|
| 1 | POST | `/api/accidents/{accidentId}/images/upload-urls` | 필요 | **201** | 버킷 설정 시 ✅ |
| 2 | (PUT) | `{uploadUrl}` — S3 직접 | (서명) | 200 | 버킷 설정 시 ✅ |
| 3 | POST | `/api/accidents/{accidentId}/images` | 필요 | **200** | 버킷 설정 시 ✅ |
| 4 | GET | `/api/accidents/{accidentId}/images` | 필요 | **200** | ✅ 항상 동작 |
| 5 | DELETE | `/api/accidents/{accidentId}/images/{imageId}` | 필요 | **204** | 버킷 설정 시 ✅ |

`{accidentId}` 는 **내 사고**여야 한다. 소유권은 `accident → vehicle.member_id` 로 확인하며, 남의 사고·없는 사고는 구분 없이 **404** 다(403 을 주면 그 사고가 존재한다는 사실이 새어 나간다).

---

### 1-1. POST `/api/accidents/{accidentId}/images/upload-urls` — URL 발급

**요청**

```json
{
  "files": [
    { "originalFilename": "front.jpg",  "contentType": "image/jpeg", "size": 1048576, "angleCode": "FRONT" },
    { "originalFilename": "left.png",   "contentType": "image/png",  "size": 2097152, "angleCode": "LEFT" },
    { "originalFilename": "damage.jpg", "contentType": "image/jpeg", "size": 3145728 }
  ]
}
```

| 필드 | 필수 | 설명 |
|---|:---:|---|
| `files` | ✅ | 비면 400. 상한은 `@Size` 가 아니라 **누적 장수**로 검사한다(1-1-2) |
| `files[].originalFilename` | ✅ | 255자 이하. 경로 구분자(`/` `\`)·제어문자 금지. 확장자 필수 |
| `files[].contentType` | ✅ | 확장자와 **일치해야 한다**. `image/jpeg; charset=x` 처럼 파라미터가 붙어도 되고 대소문자는 무시한다 |
| `files[].size` | ✅ | 바이트. 1 이상, 20,971,520(20MB) 이하 |
| `files[].angleCode` | ⬜ | 선택. 촬영 가이드 9종만 허용. **저장된다**(0-5). 틀리면 400 `UNKNOWN_ANGLE_CODE` |

> **`s3Key` 를 요청에 넣지 말 것.** 받지 않는 필드이며 넣어도 무시된다. 키는 서버가 만든다.

**201**

```json
{
  "data": {
    "issuedCount": 1,
    "maxCountPerAccident": 20,
    "remainingSlots": 19,
    "files": [
      {
        "imageId": 11,
        "originalFilename": "front.jpg",
        "angleCode": "FRONT",
        "s3Key": "accidents/7/images/11/original.jpg",
        "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/accidents/7/images/11/original.jpg?X-Amz-...",
        "uploadMethod": "PUT",
        "requiredHeaders": { "content-type": "image/jpeg", "content-length": "1048576" },
        "expiresAt": "2026-09-07T13:00:00Z"
      }
    ]
  }
}
```

| 필드 | 쓰임 |
|---|---|
| `imageId` | **이후 모든 단계의 키.** 완료 통보·재시도·삭제에 쓴다 |
| `s3Key` | 서버가 만든 오브젝트 경로. 규칙은 `accidents/{accidentId}/images/{imageId}/{variant}.{ext}` |
| `uploadUrl` | 2단계에서 이 URL 로 직접 PUT |
| `uploadMethod` | 항상 `"PUT"`. 값으로 내려주는 이유는 나중에 POST form 방식으로 바꿀 여지를 남기기 위해서다 — **하드코딩하지 말고 이 값을 쓸 것** |
| `requiredHeaders` | **PUT 에 그대로 실어야 한다.** 빠지면 서명이 맞지 않아 S3 가 403. ⚠️ **키 이름을 하드코딩하지 마세요** — 사고 이미지는 소문자(`content-type`)인데 프로필 이미지 API 는 대문자(`Content-Type`)입니다. 이 객체를 그대로 펼쳐 넣으세요 |
| `expiresAt` | 이 시각 이후에는 재발급이 필요하다(기본 10분) |
| `remainingSlots` | **이번 발급까지 반영한** 남은 장수. "N장 더 올릴 수 있습니다" 를 서버 상한과 어긋나지 않게 그릴 수 있다 |

**⚠️ 한 파일이라도 걸리면 아무것도 발급되지 않는다.** 서버가 검증을 **전부 먼저** 하고 나서 발급한다. "3장 중 2장만 성공" 같은 애매한 상태가 없다. 400 을 받으면 **그 요청 전체가 없던 일**이다 — `accident_image` 행도 생기지 않는다.

#### 1-1-2. 장수 상한은 **누적**이다

이미 등록된 장수 + 이번 요청 장수로 판정한다. 요청 하나만 보고 통과시키면 여러 번 나눠 보내 상한을 넘길 수 있기 때문이다.

```
이미 15장 등록된 사고에 6장 요청  →  400  (15 + 6 = 21 > 20)
이미 15장 등록된 사고에 5장 요청  →  201  (경계는 초과부터)
```

> **상한값은 20 이지만 확정이 아니다.** 요구사항 21행은 20장, `API 명세서 (바른견적 최신).md` 는 "사진 상한 10장 확정" 이라 두 정본이 어긋난다. 서버는 정본 우선순위에 따라 20 으로 두되 값을 프로퍼티(`app.accident-image.max-count-per-accident`)로 뺐다.
> → **FE 는 20 을 하드코딩하지 말고 응답의 `maxCountPerAccident` 를 쓸 것.** 기획이 10 으로 확정하면 서버 값만 바뀐다.

---

### 1-2. PUT `{uploadUrl}` — S3 직접 업로드 (BE 무관)

```js
function put(file, issued, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(issued.uploadMethod, issued.uploadUrl);          // 하드코딩 말고 uploadMethod 사용
    Object.entries(issued.requiredHeaders ?? {})              // ★ 빠지면 403
          .forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload  = () => (xhr.status >= 200 && xhr.status < 300) ? resolve() : reject(xhr.status);
    xhr.onerror = () => reject(new Error('network'));
    xhr.send(file);                                           // File 객체 그대로. FormData 아님
  });
}
```

지켜야 할 것 네 가지.

- **`credentials: 'include'` 를 쓰지 말 것.** 우리 서버가 아니라 S3 다. 세션 쿠키를 보내면 서명이 깨질 수 있다
- **`requiredHeaders` 를 빠짐없이 보낼 것.** 특히 `Content-Type`. 서명에 포함되어 있어 다르면 403 이다
- **`FormData` 로 감싸지 말 것.** 파일 바이트를 그대로 body 에 실는다
- **`File` 객체를 바꾸지 말 것.** 리사이즈·재인코딩하면 크기가 달라져 3단계 재검증에서 `SIZE_MISMATCH` 로 걸린다

**URL 이 만료되면(`expiresAt` 경과)** 1단계를 **같은 파일로 다시 호출**한다. 단 그러면 새 `imageId` 가 발급되어 **장수 슬롯을 하나 더 먹는다.** 만료 전에 올리는 것이 원칙이고, 만료가 잦으면 서버의 `app.accident-image.presigned-url-minutes` 를 늘려 달라고 요청할 것.

---

### 1-3. POST `/api/accidents/{accidentId}/images` — 완료 통보

**요청** — PUT 을 마친 것만 보낸다.

```json
{
  "images": [
    { "imageId": 11, "size": 1048576 },
    { "imageId": 12, "size": 2097152 },
    { "imageId": 13 }
  ]
}
```

| 필드 | 필수 | 설명 |
|---|:---:|---|
| `images[].imageId` | ✅ | 1단계에서 받은 값 |
| `images[].size` | ⬜ | **보내는 것을 권장한다.** 저장소의 실제 크기와 대조해 다르면 그 이미지를 실패로 처리하고 오브젝트를 지운다. 생략하면 상한과 매직바이트만 검사한다 |

> 서버는 발급 때 신고한 크기를 **보관하지 않는다**(담을 컬럼이 없다). 그래서 크기 대조를 하려면 FE 가 다시 보내야 한다.

**200 — 부분 실패도 200 이다**

```json
{
  "data": {
    "requested": 3,
    "succeeded": 2,
    "failed": 1,
    "results": [
      {
        "imageId": 11,
        "originalFilename": "front.jpg",
        "angleCode": "FRONT",
        "status": "COMPLETED",
        "qualityStatus": "PASS",
        "qualityReason": null,
        "failureCode": null,
        "failureMessage": null,
        "assets": [
          { "variant": "RESIZED",   "url": "https://<service-bucket>.s3.<region>.amazonaws.com/accidents/7/images/11/resized.jpg?X-Amz-...",   "expiresAt": "2026-09-10T09:40:00Z", "width": 1600, "height": 1200, "fileSize": 284133 },
          { "variant": "THUMBNAIL", "url": "https://<service-bucket>.s3.<region>.amazonaws.com/accidents/7/images/11/thumbnail.jpg?X-Amz-...", "expiresAt": "2026-09-10T09:40:00Z", "width":  320, "height":  240, "fileSize":  14802 }
        ]
      },
      {
        "imageId": 12,
        "originalFilename": "left.png",
        "angleCode": "LEFT",
        "status": "ALREADY_COMPLETED",
        "qualityStatus": "PASS",
        "qualityReason": null,
        "failureCode": null,
        "failureMessage": null,
        "assets": [ "...동일 형태 2행..." ]
      },
      {
        "imageId": 13,
        "originalFilename": "damage.jpg",
        "angleCode": null,
        "status": "FAILED",
        "qualityStatus": null,
        "qualityReason": null,
        "failureCode": "SIZE_MISMATCH",
        "failureMessage": "신고한 크기와 실제 업로드 크기가 다릅니다. (신고 3145728바이트, 실제 3145700바이트)",
        "assets": []
      }
    ]
  }
}
```

#### ★ 여기가 이 API 에서 가장 헷갈리는 지점이다

**개별 이미지의 검증 실패는 400 이 아니라 200 이다.** 요구사항 22행이 "실패한 파일만 개별 재시도" 를 요구하므로, 한 장이 실패했다고 전체를 뒤집으면 나머지 성공분까지 사용자가 다시 올려야 한다.

| 상황 | HTTP | 어디서 읽나 |
|---|:---:|---|
| 20장 중 3장 실패 | **200** | `results[].status === "FAILED"` |
| 전부 실패 | **200** | 같음. `failed === requested` |
| `images` 가 빈 배열 | 400 | `error.code` |
| 같은 `imageId` 를 두 번 보냄 | 400 | `error.code` |
| 남의/없는 `imageId` 가 섞임 | 404 | `error.code` — **하나라도 섞이면 전체가 404** 다 |
| 저장소 미구성 | 503 | `error.code` |

→ **`res.ok` 만 보고 성공 처리하면 안 된다.** 200 을 받아도 `results` 를 돌면서 장별로 판정할 것.

#### `status` 3종

| 값 | 뜻 | FE 동작 |
|---|---|---|
| `COMPLETED` | 전처리·저장·품질 판정까지 끝났다. `assets` 3행이 있다 | 완료 표시 |
| `ALREADY_COMPLETED` | 이미 끝난 이미지를 또 통보했다. **재처리하지 않았다**(다시 처리하면 `uk_aia` 위반) | 완료 표시. 오류 아님 |
| `FAILED` | 이 장만 실패. asset 은 하나도 저장되지 않았고 올라간 오브젝트는 정리됐다 | 재시도 버튼 |

#### `failureCode` — 재시도 여부 판단 기준

| 코드 | 뜻 | 재시도로 풀리나 |
|---|---|:---:|
| `MISSING_FILE` | 저장소에 오브젝트가 없다. PUT 이 실제로는 실패했다 | ✅ 다시 PUT |
| `SIZE_MISMATCH` | 신고 크기 ≠ 실제 크기 | ✅ 다시 PUT (파일을 가공하지 말 것) |
| `FILE_TOO_LARGE` | 실제 크기가 20MB 초과 | ❌ 다른 파일로 |
| `SIGNATURE_MISMATCH` | 매직바이트가 확장자와 다르다. 확장자만 바꾼 파일 | ❌ 다른 파일로 |
| `UNSUPPORTED_EXTENSION` | JPG·PNG·HEIC 가 아니다 | ❌ 다른 파일로 |
| `SERVER_CONVERSION_UNSUPPORTED` | HEIC. 서버가 못 연다 | ❌ **JPG 변환 안내**(3장) |
| `PROCESSING_ERROR` | 서버가 이미지를 처리하지 못했다 | ⚠️ 1회 재시도 후 실패 처리 |

---

### 1-4. GET `/api/accidents/{accidentId}/images` — 목록·조회 URL ✅ **지금 동작한다**

화면을 새로 열거나 세션이 끊겼다 돌아왔을 때 **무엇이 아직 안 끝났는지** 복구하고, **완료된 이미지를 화면에 띄우는** 용도입니다.

> **이 API 는 버킷 환경변수가 없어도 200 입니다.** 그때는 `assets[].url` 만 `null` 이고 나머지 메타는 그대로 옵니다 — 업로드 진행률 UI 가 저장소 설정에 발목 잡히지 않게 한 것입니다.

**200**

```json
{
  "data": {
    "total": 3,
    "completed": 2,
    "pending": 1,
    "maxCountPerAccident": 20,
    "remainingSlots": 17,
    "images": [
      {
        "imageId": 11,
        "originalFilename": "front.jpg",
        "angleCode": "FRONT",
        "uploadState": "COMPLETED",
        "qualityStatus": "PASS",
        "qualityReason": null,
        "createdAt": "2026-09-07T12:40:11Z",
        "assets": [
          { "variant": "RESIZED",   "url": "https://<service-bucket>.s3.<region>.amazonaws.com/accidents/7/images/11/resized.jpg?X-Amz-...",   "expiresAt": "2026-09-10T09:40:00Z", "width": 1600, "height": 1200, "fileSize": 284133 },
          { "variant": "THUMBNAIL", "url": "https://<service-bucket>.s3.<region>.amazonaws.com/accidents/7/images/11/thumbnail.jpg?X-Amz-...", "expiresAt": "2026-09-10T09:40:00Z", "width":  320, "height":  240, "fileSize":  14802 }
        ]
      },
      {
        "imageId": 13,
        "originalFilename": "damage.jpg",
        "angleCode": null,
        "uploadState": "PENDING",
        "qualityStatus": "PASS",
        "qualityReason": null,
        "createdAt": "2026-09-07T12:40:11Z",
        "assets": []
      }
    ]
  }
}
```

- `uploadState` 는 **`PENDING` / `COMPLETED` 둘뿐**입니다(0-4). `PENDING` 은 "URL 은 발급됐지만 완료 통보가 없다" 는 뜻이며, **FE 관점의 대기·진행·실패가 전부 여기로 뭉칩니다.**
- `assets` 가 빈 배열이면 아직 완료 전입니다. `uploadState` 와 항상 같은 이야기입니다
- ✅ **`angleCode` 가 옵니다**(0-5). 안 보냈던 이미지와 예전 데이터는 `null` 입니다
- ✅ **`assets[].url` 을 `<img src>` 에 그대로 넣으면 됩니다**(5-2)
- ⚠️ **`ORIGINAL` 은 오지 않습니다.** `RESIZED` · `THUMBNAIL` 두 개뿐이고, 원본은 조회 URL 자체가 발급되지 않습니다(5-2)
- ⚠️ **`s3Key` 는 더 이상 오지 않습니다.** 브라우저가 쓸 수 없는 값이라 `url` 로 대체했습니다
- ⚠️ **실패 사유가 없습니다.** 새로고침하면 왜 실패했는지 서버가 모릅니다. FE 가 보관해야 합니다

---

### 1-5. DELETE `/api/accidents/{accidentId}/images/{imageId}` — 삭제

**204**, 본문 없음. asset 전체와 저장소 오브젝트를 정리한 뒤 행을 지운다. 완료 통보 전(취소된) 업로드의 원본도 함께 지운다 — 키가 결정적이라 서버가 위치를 안다.

- 남의 이미지·없는 이미지 → **404**
- 사고를 지우면 이미지와 asset 은 FK `ON DELETE CASCADE` 로 함께 정리된다
- ⚠️ **"분석 진행 중이면 409" 는 아직 구현되지 않았다.** `analysis_job` 을 읽을 엔티티가 이 저장소에 없다(별도 Story). 지금은 분석 중이어도 지워진다
- ⚠️ **지금은 사실상 503 이다** — 저장소 오브젝트를 지우려 저장소를 부르는데 어댑터가 없다(2-6)

---

## 2. 공통 응답 봉투와 에러

### 2-1. 성공 — 항상 `data` 로 감싼다

```json
{ "data": { } }
```

`204`(삭제)만 본문이 없다.

### 2-2. 실패 — `error` 객체

```json
{ "error": { "code": "INVALID_REQUEST", "message": "이미지는 장당 20MB 이하여야 합니다. (신고 크기 22000000바이트)" } }
```

### 2-3. ★ 발급 실패의 `error.code` 는 **사유별로 다릅니다** (2026-09-10 변경)

예전에는 발급 400 이 전부 `INVALID_REQUEST` 라 **한글 메시지를 문자열 비교**해야 했습니다. 이제 사유가 코드로 나갑니다.

| 단계 | 사유 분류를 어디서 읽나 |
|---|---|
| **1단계 발급** (400) | **`error.code` 에 사유가 온다** — 아래 표 |
| **3단계 완료 통보** (200) | `results[].failureCode` 에 **같은 이름**이 온다 |

두 경로가 같은 이름을 쓰므로 문구 매핑을 한 벌만 만들면 됩니다.

| `error.code` | 언제 | 사용자 안내 |
|---|---|---|
| `TOO_MANY_IMAGES` | 누적 20장 초과 | "최대 20장까지 올릴 수 있어요" |
| `FILE_TOO_LARGE` | 장당 20MB 초과 | "20MB 이하로 줄여 주세요" |
| `UNSUPPORTED_EXTENSION` | JPG·PNG 가 아닌 확장자 | "JPG 또는 PNG 만 올릴 수 있어요" |
| `UNSUPPORTED_CONTENT_TYPE` | 확장자와 Content-Type 불일치 | "파일이 손상된 것 같아요" |
| `SERVER_CONVERSION_UNSUPPORTED` | **HEIC·HEIF** | "JPG 로 변환한 뒤 올려 주세요"(3-1) |
| `UNKNOWN_ANGLE_CODE` | 촬영 가이드에 없는 각도 | (FE 버그입니다. 사용자에게 노출하지 마세요) |
| `INVALID_FILE_NAME` | 파일명 255자 초과·경로 구분자·제어문자 | "파일 이름을 바꿔 주세요" |
| `MISSING_FILE` | 빈 목록이거나 0바이트 | "파일을 선택해 주세요" |
| `SIGNATURE_MISMATCH` | 매직바이트 불일치 (3단계) | "이미지 파일이 아닌 것 같아요" |
| `SIZE_MISMATCH` | 신고 크기 ≠ 실제 크기 (3단계) | "다시 올려 주세요" |

⚠️ **`error.code` 를 좁은 유니온 타입으로 잡지 마세요.** 이 값들은 공통 `ErrorCode` 열거형 밖의 이름이고(인증의 `SIGNUP_REQUIRED` 와 같은 방식), 사유가 늘어날 수 있습니다. `string` 으로 받고 알 수 없는 코드는 `error.message` 를 그대로 보여주세요.

→ `error.message` 에는 여전히 **위반한 제약과 실제 값**이 들어 있습니다(예: `"사고 1건에는 이미지를 최대 20장까지 등록할 수 있습니다. (등록 15장 + 요청 6장)"`). 그대로 노출해도 됩니다.

### 2-4. 상태 코드 표

| 코드 | 언제 | 봉투 |
|:---:|---|---|
| 201 | 발급 성공 | `data` |
| 200 | 완료 통보(부분 실패 포함) · 목록 | `data` |
| 204 | 삭제 성공 | 없음 |
| 400 | 제약 위반 · 빈 목록 · 중복 `imageId` · 경로 변수 타입 오류 | `error` (`INVALID_REQUEST`) |
| **401** | 비로그인 | ⚠️ **봉투가 다르다** (2-5) |
| 403 | 가입 대기(`SIGNUP_REQUIRED`) 또는 권한 없음(`FORBIDDEN`) | `error` |
| 404 | 남의 사고 · 없는 사고 · 남의 이미지 | `error` (`NOT_FOUND`) |
| **503** | **S3 어댑터 미구성 — 지금 대부분이 여기다** | `error` (`SERVICE_UNAVAILABLE`) |

### 2-5. 401 만 봉투가 다르다

차량 API 문서 2-3 과 같다. 시큐리티가 서블릿 기본 오류로 응답해 공통 예외 처리기를 거치지 않는다.

```json
{ "timestamp": "...", "status": 401, "error": "Unauthorized", "path": "/api/accidents/7/images" }
```

`error` 가 **객체가 아니라 문자열**이다. `err.error.code` 는 `undefined` 다.

→ **401 은 본문을 파싱하지 말고 `status === 401` 로만 분기할 것.**

**403 은 봉투가 정상이다.** 소셜 인증만 끝나고 약관 동의를 안 한 세션은 `403` + `{"error":{"code":"SIGNUP_REQUIRED", ...}}` 를 받는다 → 가입 화면으로 보낼 것.

### 2-6. ⚠️ 503 이 나오는 정확한 조건

```json
{ "error": { "code": "SERVICE_UNAVAILABLE", "message": "이미지 저장소 공급자가 구성되지 않았습니다." } }
```

| API | 지금 |
|---|---|
| 발급 | **항상 503.** `accident_image` 행도 생기지 않는다 |
| 완료 통보 | **항상 503.** 개별 이미지 실패로 뭉개지 않고 요청 전체가 503 이다 |
| 목록 | ✅ **200.** DB 만 읽는다 |
| 삭제 | **사실상 항상 503.** 지울 오브젝트 키가 하나라도 있으면 저장소를 부른다. 파일명에서 확장자를 읽을 수 있으면(=거의 언제나) 완료 통보 전이라도 원본 키가 후보에 들어간다. 확장자를 못 읽고 asset 도 없는 행만 204 로 지워진다 |

→ **FE 는 503 을 "서버 장애" 로 그리지 말 것.** "이미지 저장소 준비 중" 에 가깝다. 어댑터가 붙으면 사라진다.

### 2-7. 인증 방식과 CORS

차량 API 와 동일하다.

- **서버 세션.** JWT 아님. `SESSION` HttpOnly 쿠키 → **1·3·4·5번 API 에 `credentials: 'include'` 필수**
- **2단계 S3 PUT 에는 절대 붙이지 말 것**(1-2)
- CORS 는 `http://localhost:5173` 하나만 허용. FE 개발 서버를 5173 으로 띄울 것
- ⚠️ S3 버킷에 **CORS 설정이 아직 없다.** 버킷이 만들어질 때 `PUT` + `Content-Type` 헤더 허용이 함께 설정되어야 2단계가 브라우저에서 동작한다. `S15P21A307-217` 에 포함되어야 할 항목이며, 지금은 확인할 수 없다

---

## 3. 제약과 오류 메시지 — 화면 문구에 그대로 쓸 것

| 제약 | 값 | 위반 시 |
|---|---|---|
| 장수 | 사고당 **20장**(응답의 `maxCountPerAccident`). **누적** | 400 · `"사고 1건에는 이미지를 최대 20장까지 등록할 수 있습니다. (등록 N장 + 요청 M장)"` |
| 장당 크기 | **20MB** (20,971,520 B). 0바이트 불가 | 400 · `"이미지는 장당 20MB 이하여야 합니다. (신고 크기 N바이트)"` |
| 형식 | **JPG · PNG · HEIC** | 400 · `"JPG, PNG, HEIC 파일만 업로드할 수 있습니다."` |
| 확장자 ↔ Content-Type | 일치해야 함 | 400 · `"파일 Content-Type 과 확장자가 일치하지 않습니다. (확장자 기준 image/jpeg)"` |
| 파일명 | 255자 이하 · 경로 구분자 금지 · 제어문자 금지 · 확장자 필수 | 400 · `"원본 파일명이 올바르지 않습니다."` |
| 각도 코드 | 촬영 가이드 9종 | 400 · `"촬영 가이드에 없는 각도 코드입니다. (XXX)"` |
| 매직바이트 | 확장자와 실제 형식 일치 (3단계에서) | `failureCode: SIGNATURE_MISMATCH` |

**허용 확장자 ↔ Content-Type** — 대소문자는 무시한다.

| 확장자 | Content-Type | S3 키 확장자 |
|---|---|---|
| `jpg` · `jpeg` | `image/jpeg` | `jpg` (`jpeg` 도 `jpg` 로 통일) |
| `png` | `image/png` | `png` |
| `heic` | `image/heic` | `heic` |

**각도 코드 9종** — `shooting-guide.json` 이 정본이며 `GET /api/guides/shooting` 이 준다. 하드코딩하지 말고 그 API 에서 받을 것.

```
FRONT · FRONT_LEFT · FRONT_RIGHT · LEFT · RIGHT · REAR · REAR_LEFT · REAR_RIGHT · DAMAGE_CLOSE
```

### 3-1. ★ HEIC·HEIF — **서버 변환을 지원하지 않습니다**

```
현재 업로드 가능 : JPG, PNG
HEIC / HEIF     : 서버 변환 미지원으로 업로드 불가
```

`.heic` · `.heif` 어느 쪽이든, Content-Type 이 `image/heic` 이든 `image/heif` 이든 **같은 사유로 거절**됩니다.

```
발급 요청에 .heic 또는 .heif 를 넣으면  →  400
{ "error": { "code": "SERVER_CONVERSION_UNSUPPORTED",
             "message": "HEIC 형식은 서버 변환을 지원하지 않습니다. 업로드 전 JPG 로 변환해 주세요." } }
```

✅ **S3 URL 이 발급되기 전에 걸립니다.** 빈 오브젝트가 버킷에 남거나 `accident_image` 행이 생기지 않습니다.

⚠️ **확장자만 `.jpg` 로 바꿔서 올리는 것도 막힙니다.** 1단계는 통과하지만 완료 통보(3단계)에서 매직바이트를 보고 `SIGNATURE_MISMATCH` 로 실패합니다 — 그때는 이미 S3 에 오브젝트가 올라간 뒤라 서버가 지웁니다. **파일 선택 시점에 거르는 편이 훨씬 낫습니다.**

Java `ImageIO` 에 HEIF 디코더가 없고 순수 자바 라이브러리도 없으며, 이 저장소는 새 의존성 추가를 금지한다. **요구사항 21행의 확인·결정 사항이 이미 정해 둔 폴백**이다("미지원 시 업로드 전 클라이언트 변환 안내").

→ **FE 가 두 가지 중 하나를 해야 한다.**
1. **클라이언트에서 JPG 로 변환한 뒤 올린다** (권장). 브라우저 `heic2any` 류 또는 `<canvas>` 경유. iOS Safari 는 `<input type="file">` 로 고른 HEIC 을 `canvas` 에 그릴 수 있어 변환이 가능하다
2. 변환이 어려우면 **파일 선택 시점에 미리 걸러** "JPG 로 변환해 주세요" 를 안내한다. 발급 400 을 받고 나서 알리면 사용자가 20장을 고르고 나서야 튕긴다

⚠️ **`accept` 속성만으로는 못 막는다.** iOS 는 카메라 촬영본을 상황에 따라 HEIC 또는 JPEG 로 준다. **파일 확장자와 `file.type` 을 직접 확인할 것.**

> 이 방침은 서버 코드 구조상 되돌릴 수 있게 만들어져 있다(`ImageDecoderPort`). libheif 어댑터가 붙으면 **이 문서의 3-1 만 지워지고 API 계약은 그대로**다.

---

## 4. 재시도 — 요구사항 22행 "실패한 파일만 개별 재시도"

### 4-1. ★ `imageId` 는 재사용한다 — 다시 발급받지 말 것

가장 중요한 규칙이다.

```
실패했다 → 같은 imageId 의 같은 uploadUrl 로 다시 PUT → 같은 imageId 로 다시 완료 통보
```

**1단계를 다시 부르면 새 `imageId` 가 생기고 장수 슬롯을 하나 더 먹는다.** 20장을 다 채운 뒤 한 장이 실패하면 재발급이 아예 400 이 된다.

| 실패 지점 | 재시도 방법 |
|---|---|
| 2단계 PUT 실패 (네트워크·403) | 같은 `uploadUrl` 로 다시 PUT. `expiresAt` 이 지났으면 재발급 필요 |
| 3단계 `FAILED` (`MISSING_FILE`·`SIZE_MISMATCH`) | 같은 `uploadUrl` 로 다시 PUT → 같은 `imageId` 로 다시 통보 |
| 3단계 `FAILED` (형식·크기·시그니처) | 파일 자체가 문제다. **그 `imageId` 를 DELETE 하고** 다른 파일로 새로 발급 |
| `expiresAt` 만료 | 1단계 재호출. 슬롯을 먹으므로 **이전 `imageId` 를 DELETE 할 것** |

### 4-2. 완료 통보는 실패분만 다시 보내면 된다

```json
{ "images": [ { "imageId": 13, "size": 3145728 } ] }
```

이미 끝난 것을 섞어 보내도 안전하다 — `ALREADY_COMPLETED` 로 돌아오고 재처리하지 않는다. 다만 **불필요한 저장소 조회가 생기므로 실패분만 보내는 편이 낫다.**

### 4-3. 화면 이탈 경고는 FE 책임 (`S15P21A307-148`)

업로드 완료 전 이탈 시 경고는 서버가 관여하지 않는다. `beforeunload` 로 처리한다. 서버 기준의 "미완료" 는 `GET .../images` 의 `pending > 0` 이다.

---

## 5. 전처리 결과와 품질 판정 — 화면에 영향을 주는 것

### 5-1. 변형본 3종

| variant | 무엇 | 크기 | 형식 |
|---|---|---|---|
| `ORIGINAL` | 사용자가 올린 원본 그대로 | 원본 | 원본 형식 |
| `RESIZED` | 분석용 | 긴 변 **1600px** 이하 | **JPEG** |
| `THUMBNAIL` | 목록·미리보기용 | 긴 변 **320px** 이하 | **JPEG** |

- 종횡비를 유지하며 **긴 변 기준으로 축소**한다. **확대하지 않는다** — 원본이 320px 보다 작으면 썸네일이 원본과 같은 크기다
- 원본이 PNG 여도 변형본은 JPEG 다. 투명 PNG 는 **흰 배경에 합성**된다
- **EXIF 방향(Orientation 1~8)이 보정되어 있다.** 변형본은 이미 똑바로 서 있으므로 **FE 에서 CSS 로 회전 보정을 넣지 말 것** — 넣으면 두 번 돌아간다
- 변형본에는 **EXIF 가 통째로 없다** — GPS·기기 정보가 남지 않는다. `ORIGINAL` 에는 그대로 남아 있으니 **원본을 사용자에게 직접 노출하지 말 것**
- `width`·`height`·`fileSize` 는 **null 일 수 있다.** 32,767px 를 넘는 변은 저장하지 않는다(DB 가 SMALLINT). 없는 값을 0 으로 그리지 말 것
- `BLURRED` 는 **이 범위가 아니다.** 번호판·얼굴 블러는 `S15P21A307-226~228 · 386` 이다

### 5-2. ✅ **이미지를 화면에 띄울 수 있습니다** (2026-09-10 변경)

예전에는 `s3Key` 만 줘서 `<img src>` 를 만들 수 없었습니다. 이제 **조회용 presigned GET URL** 이 응답에 직접 들어옵니다.

```jsonc
"assets": [
  { "variant": "RESIZED",   "url": "https://...?X-Amz-...", "expiresAt": "2026-09-10T09:40:00Z", "width": 1600, "height": 1200, "fileSize": 284133 },
  { "variant": "THUMBNAIL", "url": "https://...?X-Amz-...", "expiresAt": "2026-09-10T09:40:00Z", "width":  320, "height":  240, "fileSize":  14802 }
]
```

```jsx
const thumb = image.assets.find(a => a.variant === 'THUMBNAIL');
{thumb?.url && <img src={thumb.url} width={thumb.width} height={thumb.height} />}
```

**규칙 다섯 가지**

1. **`ORIGINAL` 은 절대 오지 않습니다.** 원본에는 EXIF(GPS·기기 정보)가 남아 있고 staging 버킷에서 7일 뒤 사라집니다. 목록에서 빼는 것과 서명 자체를 거부하는 것, 두 겹으로 막혀 있습니다.
2. **URL 은 만료됩니다** (기본 10분, `expiresAt` 참조). **캐시하지 마세요.** 화면을 오래 열어 뒀다면 목록을 다시 부르면 새 URL 이 옵니다.
3. **URL 은 DB 에 저장되지 않습니다.** 응답을 만들 때마다 새로 서명하므로 같은 이미지라도 호출마다 URL 이 다릅니다 — **URL 을 키로 쓰지 마세요.** `imageId` + `variant` 를 쓰세요.
4. **`url` 이 `null` 일 수 있습니다.** 버킷 환경변수가 없거나 서명에 실패한 경우입니다. 그때도 `width`·`height`·`fileSize` 는 옵니다 — 자리만 비우고 나머지는 그리세요.
5. **오브젝트가 지워졌는지는 확인하지 않습니다.** 서명은 로컬 계산이라 오브젝트가 없어도 URL 은 나옵니다. 그 URL 을 열면 S3 가 404 를 줍니다 — `<img onError>` 로 폴백을 두세요.

→ **업로드 직후 미리보기는 여전히 `URL.createObjectURL(file)` 이 빠릅니다.** 완료 통보 응답에도 URL 이 오니 둘 중 편한 쪽을 쓰세요.

### 5-3. `qualityStatus` — 지금은 항상 `PASS`

| 값 | 뜻 |
|---|---|
| `PASS` | 판정 통과, **또는 판정을 수행하지 않음** |
| `WARN` | 해상도·블러 기준 미달. 사유가 `qualityReason` 에 온다(100자 이내) |

- **`WARN` 이어도 업로드는 성공이다.** 실패 상태 자체가 없다. 재촬영 권유는 **FE 책임**(`S15P21A307-136`)이며 `WARN` 을 보고 판단한다
- **현재 서버는 `app.image-quality.enabled=false` 라 판정을 수행하지 않는다.** 임계값이 실험 전 잠정값이라, 근거 없는 숫자로 재촬영을 권하지 않기 위한 결정이다 → **지금은 무엇을 올려도 `PASS`** 다
- 블러(흔들림) 판정은 AI 모듈(`S15P21A307-120`)이 붙어야 동작한다. 지금은 해상도만 본다
- → **재촬영 권유 UI 는 만들되, `WARN` 이 실제로 내려오는 것은 판정이 켜진 뒤다.** 지금 테스트하려면 서버에 `app.image-quality.enabled=true` 요청 필요

---

## 6. 미확정 · 서버 대기 — 재원님 확인 필요

| # | 항목 | 지금 | FE 영향 |
|---|---|---|---|
| 1 | ~~**S3 어댑터**~~ | ✅ **해결** — develop 에 있음 | 버킷 환경변수만 넣으면 동작 |
| 2 | ~~**이미지 조회 URL API**~~ | ✅ **해결** — 목록 응답에 URL 포함(5-2) | 재진입 화면에 이미지를 띄울 수 있음 |
| 3 | ~~**각도 태그 저장**~~ | ✅ **해결** — `angle_code` 컬럼 추가(0-5) | 새로고침해도 각도 유지 |
| 4 | ~~**발급 오류 코드 분류**~~ | ✅ **해결** — `error.code` 에 사유(2-3) | 문자열 비교 불필요 |
| 5 | **S3 버킷 CORS** | ⚠️ **미검증** | 설정 없으면 브라우저 PUT 자체가 막힌다. 실제 업로드 전 반드시 확인 |
| 6 | **버킷 환경변수 주입** | ⚠️ 배포 대기 | 없으면 발급·완료·삭제가 503 |
| 7 | **실제 AWS 호출 검증** | ⚠️ **미검증** | presigned 서명·`Content-Length` 강제가 실제 S3 에서 통하는지 확인 안 됨 |
| 8 | **장수 상한 10 vs 20** | 서버는 20 | `maxCountPerAccident` 를 쓰면 영향 없음 |
| 9 | **HEIC 서버 변환** | 미지원(방침 확정) | 클라이언트 변환 필요(3-1) |
| 10 | **품질 판정** | `enabled=false` | `WARN` 이 안 내려온다(5-3) |
| 11 | **분석 중 삭제 409** | 미구현 | 분석 중에도 삭제된다 |
| 12 | **업로드 4상태 저장** | `PENDING`/`COMPLETED` 둘뿐 | 새로고침하면 진행률·실패 사유 소실(0-4) |

---

## 7. 붙일 때 순서 (권장)

1. **`GET .../images` 부터 붙이세요** — 버킷 설정과 무관하게 항상 200 입니다. 목록·빈 상태·상한 표시·각도 배지·썸네일까지 여기서 끝납니다
2. 파일 선택 → **클라이언트 검증**(장수·20MB·확장자·HEIC 변환)을 서버 규칙과 같은 값으로 구현하세요. 서버 400 을 받기 전에 걸러야 UX 가 삽니다
3. `error.code` → 문구 매핑을 한 벌 만드세요(2-3). 발급 400 과 완료 통보 `failureCode` 가 같은 이름을 씁니다
4. 1단계·3단계 호출과 2단계 `XMLHttpRequest` PUT 을 연결하세요. 버킷이 아직이면 503 분기로 "준비 중" 을 띄우고 나머지 UI 를 완성하면 됩니다
5. 진행률·재시도·이탈 경고 완성
6. 버킷이 들어오면 실제 업로드를 **백엔드 담당과 같이 한 번** 돌려 보세요 — presigned 서명·CORS 는 실호출 검증 전입니다

---

## 문서 갱신 이력

| 날짜 | 내용 |
|---|---|
| 2026-09-08 | 최초 작성. HEAD `261d378` + 미커밋 작업본 기준. **JSON 은 계약 테스트에서 옮긴 것이며 실측 아님** |
| 2026-09-10 | `S15P21A307-137` 반영. **조회용 presigned GET URL**(5-2) · **`angleCode` 영구 저장**(0-5) · **`error.code` 사유 분류**(2-3) · **HEIC·HEIF 거절 명문화**(3-1) · S3 어댑터 존재 반영. `assets[].s3Key` 제거 → `url`·`expiresAt` 추가. 응답 JSON 은 실제 record 에서 옮겼고 **실제 AWS 호출 검증은 아직입니다** |
