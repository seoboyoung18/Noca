> AI 연동의 실패 callback, HTTP 응답 코드, 재시도 및 중복 방지에 대한 운영 명세입니다.
> 성공 callback JSON의 필드 구조는 [AI 연동 계약](AI%20연동%20계약%20(백엔드%20%E2%86%94%20AI%20서버).md)을 따릅니다.

## 적용 범위

- AI 서버 `POST /analyze` 접수 응답과 오류 응답
- AI 서버가 백엔드 callback을 호출할 때의 실패 본문
- callback 재시도와 `requestId` 멱등성

## `/analyze` 응답 코드

| 상태 | 의미 | 처리 |
| --- | --- | --- |
| `202` | 분석 요청 접수 | AI 서버가 비동기 분석을 시작하고 callback을 예약 |
| `400` | `INVALID_PAYLOAD` | 필수 필드 누락·형식 위반 |
| `401` | `UNAUTHORIZED` | `X-Internal-Token` 불일치 |
| `404` | `IMAGE_FETCH_FAILED` | presigned URL 만료·접근 불가 |
| `422` | `INVALID_MODEL_OUTPUT` | 모델 출력 구조가 raw·정규화 계약을 위반 |
| `500` | `MODEL_ERROR`·`SEARCH_ERROR`·`INTERNAL` | 추론·검색·그 밖의 서버 오류 |

### 접수 응답 — `202`

```json
{
  "accepted": true,
  "jobId": 12,
  "requestId": "a1b2c3d4e5f6"
}
```

`202` 응답에는 분석 결과가 포함되지 않습니다. 결과는 분석 완료 후 callback으로
전달합니다.

### 오류 응답

```json
{
  "code": "IMAGE_FETCH_FAILED",
  "message": "presigned URL이 만료되었습니다",
  "requestId": "a1b2c3d4e5f6"
}
```

검출 없음은 HTTP 오류가 아닙니다. 차량 유효하지만 손상이 없으면
`NO_DAMAGE_DETECTED` 사유와 `detections: []`를 담은 정상 callback으로 처리합니다.
차량 자체가 검출되지 않은 이미지는 이미지 단위로 `excluded: true`,
`exclusionReason: NOT_VEHICLE`을 담습니다.

## 실패 callback

`202`로 접수한 뒤 이미지 다운로드·모델 실행·정규화에 실패하면 성공 callback 대신
같은 `callbackUrl`로 아래 본문을 보냅니다.

```json
{
  "requestId": "a1b2c3d4e5f6",
  "jobId": 12,
  "modelVersion": "a307-ai-pipeline-20260912-v1",
  "pipelineVersionId": 3,
  "estimable": false,
  "nonEstimableReason": null,
  "error": {
    "code": "MODEL_ERROR",
    "message": "damage 모델을 실행할 수 없습니다",
    "retryable": true
  }
}
```

callback 요청에는 다음 헤더를 함께 보냅니다.

```text
Content-Type: application/json
X-Internal-Token: ...
X-Request-Id: a1b2c3d4e5f6
```

`X-Request-Id` 헤더와 본문의 `requestId`는 반드시 같은 값이어야 합니다.

`error.code`는 다음 중 하나입니다.

- `IMAGE_FETCH_FAILED`
- `INVALID_MODEL_OUTPUT`
- `MODEL_ERROR`
- `INTERNAL`

백엔드는 `error`가 있는 callback을 받으면 해당 `analysis_job`을 `FAILED`로
처리합니다.

## 재시도

AI 서버는 callback 전송이 실패하면 다음 간격으로 최대 3회 재시도합니다.

```text
1초 → 5초 → 20초
```

재시도할 때 다음 값을 변경하지 않습니다.

- `X-Request-Id`
- 본문의 `requestId`
- `jobId`
- `callbackUrl`

`retryable=false`인 오류는 재시도하지 않습니다. 오류 코드별 재시도 가능 여부는
AI 서버 설정으로 관리합니다.

## 중복 방지

같은 `requestId`로 callback이 다시 도착하면 백엔드는 새 견적 버전을 만들지 않고
기존 처리 결과를 유지한 채 `200`을 반환합니다.

다음 검증을 함께 수행합니다.

1. Header의 `X-Request-Id`와 본문 `requestId`가 같은지 확인
2. 본문 `jobId`가 해당 `requestId`에 연결된 분석 작업인지 확인
3. 이미 성공·실패 처리된 요청이면 저장 작업을 생략하고 `200` 반환

`requestId`가 다르면 새로운 요청으로 간주될 수 있으므로, AI 서버는 같은 분석의
재시도에 새로운 값을 생성하면 안 됩니다.
