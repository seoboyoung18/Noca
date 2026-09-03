# API 명세서 (바른견적/최신)

# A307 백엔드 API 명세서

범위 **MVP 51개** + **SUB 15개**(여유되면) · 내부 연동 2개 포함

기준 스키마 — `A307_ddl_final.sql` · **26 테이블** · FK 30

| 회차 | 기준 | 건수 |
| --- | --- | --- |
| 1차 |  |  |
| 2차 |  |  |
| 3차 |  |  |

**뷰** — `도메인별` (도메인끼리 묶음) · `MVP / SUB 구분`

담당·회차는 팀에서 채우면 됩니다.

**공통 규약**

- 응답 성공 `{ "data": { ... } }` / 실패 `{ "error": { "code": "...", "message": "..." } }`
- 공통 에러 — 400 `INVALID_REQUEST` · 401 `UNAUTHORIZED` · 403 `FORBIDDEN` · 404 `NOT_FOUND` · 409 `CONFLICT` · 429 `TOO_MANY_REQUESTS` · 500 `INTERNAL_ERROR`
- 에러를 HTTP 200으로 반환하지 않음
- 소유자 검사 필요 — `/api/accidents/**` `/api/analysis-jobs/**` `/api/estimates/**` `/api/estimate-validations/**` `/api/vehicles/**`

**인증 방식** — 소셜 로그인 + **서버 세션**입니다. 자체 JWT를 발급하지 않으므로 토큰 재발급 API가 없고 `refresh_token` 테이블도 없습니다. 세션 ID는 HttpOnly Cookie로 전달합니다.

**인증 없는 API 5개** — 나머지는 전부 세션이 필요합니다.

`/oauth2/authorization/{provider}` · `/login/oauth2/code/{provider}` · `/api/auth/signup` · `/api/guides/checklist` · `/api/guides/shooting`

앞의 세 개는 로그인 진입이라 세션이 없는 상태에서 호출됩니다. 뒤의 두 개는 사고 직후 로그인 없이 보여야 하는 안내입니다.

**SUB 공개 API 4개**는 여기에 포함되지 않습니다. 착수 시 예외 목록을 다시 정리해야 합니다 — 정비소가 가입 없이 응답해야 해서 인증을 걸 수 없습니다.

**비동기 API 3종** — 전부 202 Accepted 후 상태 폴링입니다.

AI 분석 `analysis_job.status` + 단계별 `analysis_stage` · 견적 PDF `estimate_report.status` · 견적서 검증 `estimate_validation.status`

폴링이 일 16,200회로 전체 트래픽의 62%입니다. 개별 쿼리는 [실측] 0.03ms라 스키마를 바꿀 일이 아니고, **Redis 캐시**로 DB 호출을 120회로 줄이는 게 맞습니다.

**26테이블 반영 사항**

- `estimate.pdf_*` → **`estimate_report`** 분리. 견적과 생명주기가 달라 재생성·재시도가 가능하고 `report_no`를 새로 발급합니다
- `estimate_validation` → **3개로 분리**. OCR 항목이 JSONB였을 때 항목별 비교표를 못 만들었습니다
- **`analysis_stage`** 신규. `status` 하나로는 화면의 4단계 체크리스트를 못 그립니다
- **`accident_image_asset`** 신규. 번호판·얼굴 **블러본**이 없어 화면에 원본이 나가고 있었습니다
- **`audit_log`** 신규 → 관리자 감사 로그 조회 API 1개 추가
- `accident.member_id` 제거. `accident → vehicle → member` 로 도달 가능한 이행적 종속이었습니다
- `vehicle.deleted_at` 추가. 폐차·매각 시 **소프트 삭제** — 사고 이력의 차종은 그대로 보입니다
- HQ 체계 도입. 공임 = 표준 정비시간 × 단가 — 원천 견적서 22건 전부 일치 확인
- **사진 상한 10장 확정.** 촬영 가이드가 8방향 + 근접 2장을 권장하므로 그 기준과 맞춤니다. 장당 20MB

[A307 백엔드 API 명세서 (MVP / SUB 구분으로 봐주세요)](A307%20%EB%B0%B1%EC%97%94%EB%93%9C%20API%20%EB%AA%85%EC%84%B8%EC%84%9C%20(MVP%20SUB%20%EA%B5%AC%EB%B6%84%EC%9C%BC%EB%A1%9C%20%EB%B4%90%EC%A3%BC%EC%84%B8%EC%9A%94)%206d5b2f1a7ebe82879fa1016d57526854.csv)