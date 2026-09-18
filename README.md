# NOCA

차량 사고 데이터 기반 예상 수리 견적 조회 서비스

배포 URL: <https://j15a307.p.ssafy.io>

---

## 서비스 소개

차량 외관 손상 사진을 올리면 정비소 방문 전에 예상 수리비를 확인할 수 있다.

사진에서 손상 부위와 유형을 인식하고, 수리 사례 DB 에서 유사 사례를 찾아 예상 비용 구간을
낸다. 분석 결과를 근거로 정비소에서 확인할 항목을 체크리스트로 만들고, 현재 위치 주변
정비소를 함께 보여 준다.

## 핵심 기능

- 사고 사진 업로드와 품질 점검
- 손상 부위·유형 인식 (YOLO 2모델)
- 유사 수리 사례 검색 (ROI 임베딩 + pgvector)
- 예상 수리비 구간 산정과 근거 표시
- 정비소 확인 체크리스트 생성
- 견적·리포트 PDF
- 정비소 견적서 업로드 후 항목 검증
- 주변 정비소 검색

각 기능의 구현·연동·활성 상태는 [현재 구현 범위](#현재-구현-범위)에 구분해 적었다.

## 기술 아키텍처

[![NOCA 기술 아키텍처](Docs/Architecture/technology-architecture.png)](Docs/Architecture/기술%20아키텍처.md)

구성요소·통신 방식·저장소 경계와 코드 검증 결과는
[기술 아키텍처](Docs/Architecture/기술%20아키텍처.md) 에 있다.

## 기술 스택

| 영역 | 스택 |
| --- | --- |
| Frontend | Vue 3.5 · Vite 6 · Pinia 3 · Vue Router 4 · axios. 모바일 웹 SPA (네이티브 앱 없음) |
| Backend | Java 21 · Spring Boot 4.1.1 · Spring Security (OAuth2 카카오·구글) · Spring Data JPA · Redis 세션 |
| AI | Python · FastAPI 0.115 · Ultralytics YOLO (부품 detection · 손상 segmentation) · 768차원 ROI 임베딩 |
| Database | PostgreSQL 16 · pgvector |
| Data | Python 오프라인 파이프라인 — AI-Hub 차량파손 데이터셋·견적 원천을 표준 코드로 정규화해 수리 사례 DB 적재 |
| Infra | Docker · Nginx · GitLab CI/CD · Amazon S3 (presigned URL 업로드). LLM 은 SSAFY GMS 프록시 경유 |

## 저장소 구조

| 폴더 | 내용 |
| --- | --- |
| `backend/` | Spring Boot 서버. `src/main/java` 519파일 · 컨트롤러 33개 · API 매핑 97개 |
| `frontend/` | Vue SPA. 화면 25개 · 라우트 31개 · 스토어 5개 |
| `AI/` | FastAPI 추론 서버(`server/`) · 학습 스크립트(`training/`) · 모델 가중치(`models/`) |
| `pipeline/` | 오프라인 데이터 파이프라인 (정규화 · 검증 · 적재) |
| `shared/` | 파이프라인과 AI 서버가 같은 결과를 내야 하는 공용 파이썬 모듈 (ROI · 임베딩 · 정규화) |
| `Docs/` | 설계·명세·데이터 노트. 입구는 [Docs/README.md](Docs/README.md) |
| `docker-compose.yml` | 로컬 개발용 PostgreSQL(pgvector) + Redis. **애플리케이션은 들어 있지 않다** |

## 로컬 실행

### 1. 데이터베이스 · Redis

```bash
docker compose up -d          # a307-db(127.0.0.1:5432) · a307-redis(127.0.0.1:6379)
```

`Docs/Erd/A307_ddl_final.sql` 과 시드가 `docker-entrypoint-initdb.d` 로 마운트돼 있어
**볼륨이 비어 있는 첫 기동에만** 자동 적용된다 (43 테이블).

> [!WARNING]
> 이미 볼륨이 있고 스키마가 낡았으면 백엔드가 기동하지 않는다. `ddl-auto=validate` 라
> `Schema validation: missing column …` 로 멈춘다. 이때
> [`Docs/Erd/migrations/`](Docs/Erd/migrations) 의 빠진 SQL 을 적용한다. 무엇이 빠졌는지는
> [`check-applied.sql`](Docs/Erd/migrations/check-applied.sql) 이 알려 준다.
> `docker compose down -v` 는 볼륨을 지워 처음부터 다시 만들지만 로컬 데이터가 사라진다.

### 2. 백엔드

```bash
cd backend
DB_USERNAME=a307 DB_PASSWORD=ssafy ./gradlew bootRun     # http://localhost:8080
curl http://localhost:8080/actuator/health               # {"status":"UP"}
```

`DB_USERNAME` · `DB_PASSWORD` 만 필수다 (기본값을 두지 않았다). 값은 `docker-compose.yml` 의
기본 계정이다. 나머지 연동 키는 없어도 기동하고 해당 기능만 503 이 된다. 채울 목록은
[`backend/.env.example`](backend/.env.example) 에 있다. **키를 저장소에 커밋하지 않는다.**

### 3. 프론트엔드

```bash
cd frontend
cp .env.example .env.local    # VITE_API_BASE_URL · 카카오 지도 JS 키
npm ci
npm run dev                   # http://localhost:5173
```

백엔드 없이 화면만 둘러보려면 `.env.local` 에 `VITE_AUTH_GUARD=off` 를 넣는다.

### 4. AI 서버

```bash
pip install -r AI/requirements.txt -r AI/server/requirements.txt
uvicorn app.main:app --app-dir AI/server --port 8000
```

`torch` 를 포함한 무거운 의존성이라 **이 절차는 검증하지 못했다.** 컨테이너 정의는
[`AI/server/Dockerfile`](AI/server/Dockerfile), 환경변수는
[`AI/server/README.md`](AI/server/README.md) 에 있다.

## 테스트

```bash
cd backend && ./gradlew build     # 1657건 · 실패 0 (2026-09-18 측정)
cd frontend && npm run build      # 통과
```

> [!NOTE]
> 백엔드 테스트 중 40건은 `@EnabledIf("localPostgresRunning")` 이다.
> **Docker 가 꺼져 있으면 조용히 건너뛰고**(`1617 · 건너뜀 40`), 켜져 있으면 개발자의 로컬
> `a307` DB 를 직접 쓴다. DB 가 마이그레이션 한 건이라도 뒤처져 있으면 그 40건이 통째로
> 실패한다. **테스트가 붉으면 먼저 DB 최신 여부를 본다.**

프론트엔드에는 `lint` · `typecheck` 스크립트가 없다 (`dev` · `dev:https` · `build` ·
`preview` 뿐).

## 현재 구현 범위

**"구현했다" 와 "돌고 있다" 를 구분해 적는다.** 기능 플래그 8개는 전부 기본 `false` 다
(`grep -E '^app\..*enabled=' backend/src/main/resources/application.properties`).
**배포 파이프라인이 켜는 것은 체크리스트·질문 워커 둘뿐이고**, 같은 자리에서 LLM 벤더를
OpenAI `gpt-5.4` 로 바꾼다.

| 기능 | 상태 | 근거 · 스위치 |
| --- | --- | --- |
| 소셜 로그인 · 회원 · 약관 | 구현 · 화면 연동 | 카카오/구글 OAuth2. 세션은 Redis |
| 차량 등록 · 목록 | 구현 · 화면 연동 | 차량 모델 마스터 기반 |
| 사고 접수 · 사진 업로드 | 구현 · 화면 연동 | S3 presigned. 버킷 미설정이면 503 |
| 사진 품질 판정 | 구현 · **꺼짐** | `app.image-quality.enabled=false` |
| 분석 결과 · 견적 조회 · 리포트 | 구현 · 화면 연동 | `EstimateView` · `ReportView` |
| AI 분석 요청 · 콜백 수신 | 구현 · **워커 꺼짐** | `ANALYSIS_REQUEST_WORKER_ENABLED` |
| AI 손상 인식 (`POST /inference`) | 구현 | YOLO 2모델 → 표준화 결과 |
| AI 유사 사례 검색 (`POST /search`) | 구현 | ROI 임베딩 + pgvector. `FEATURE_PIPELINE_VERSION_ID` 없으면 503 |
| AI 수리비 산정 (`POST /estimate`) | 구현 | 수리 사례 DB 조회. 데이터 없으면 503 |
| AI 분석 오케스트레이션 (`POST /analyze`) | **목 프로파일까지** | `ANALYSIS_PROFILE=mock` 에서만 202. 목은 YOLO 추론은 실제로 하지만 `estimable: false` 로 답한다 |
| 견적서 검증 (OCR → 판정 → 리포트) | 구현 · **워커 꺼짐 · 화면 없음** | `ESTIMATE_WORKER_ENABLED`. FE 에 호출 코드가 없다 |
| 검증 결과 PDF · 견적 PDF | 구현 · **꺼짐** | `VALIDATION_PDF_ENABLED` · `ESTIMATE_PDF_ENABLED` |
| LLM 요약 · 체크리스트 · 정비소 질문 | 구현 · **기본 꺼짐** | `ESTIMATE_SUMMARY_ENABLED` · `REPAIR_CHECKLIST_WORKER_ENABLED` · `REPAIR_QUESTION_WORKER_ENABLED`. `GMS_KEY` 없으면 503 |
| 체크리스트 화면 | 구현 · 화면 연동 | `stores/checklist.js` 를 세 화면이 쓴다 |
| 정비소 검색 | 구현 · **화면은 백엔드를 안 쓴다** | BE `GET /api/repair-shops` 는 있으나 `ShopsView` 가 카카오 지도 SDK 를 브라우저에서 직접 호출한다 |
| 정비소 질문 화면 | 구현 · **화면 없음** | BE API 는 있으나 `lib/api.js` 에 호출 함수가 없다 |
| 관리자 마스터 · 규칙 관리 (34 API) | 구현 · **화면 없음** | API·문서만 있고 관리자 UI 는 만들지 않았다 |

## 기술문서

| 문서 | 내용 |
| --- | --- |
| [Docs/README.md](Docs/README.md) | 전체 문서 인덱스 |
| [기술 아키텍처](Docs/Architecture/기술%20아키텍처.md) | 구성요소 · 통신 · 저장소 경계 |
| [문서-코드 정합성 감사](Docs/audit/문서-코드-정합성.md) | 수치 근거 · 문서 분류 · 불일치 목록 |
| [A307_ddl_final.sql](Docs/Erd/A307_ddl_final.sql) | 스키마 정본 (43 테이블) |
| [AI 연동 계약](<Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md>) | BE ↔ AI 요청·콜백 계약 |
| [담당 범위](Docs/담당%20범위.md) | 팀 분담 |

## 알려진 제한사항

- `POST /analyze` 오케스트레이션은 목 프로파일까지만 확인됐다. 실제 추론 프로파일의 종단
  동작은 검증하지 못했다.
- 관리자 API 34개와 정비소 질문 API 는 화면이 없다.
- 정비소 검색 화면은 백엔드 API 를 쓰지 않고 카카오 지도 SDK 를 직접 호출한다.
- 기능 플래그 대부분이 배포에서 꺼져 있다. 위 표의 스위치를 참고한다.
- 스키마 변경은 사람이 적용한다. 순서를 틀리면 앱 전체가 기동하지 않는다.
- 배포 서버 종류(EC2/Lightsail)에 대해 문서끼리 서술이 다르다. 저장소에 IaC 가 없어
  확인하지 못했다 — [감사 §3-3](Docs/audit/문서-코드-정합성.md) 참고.
