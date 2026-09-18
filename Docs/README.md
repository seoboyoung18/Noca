# 문서 안내판

`Docs/` 에는 커밋된 문서가 **102개** 있다. 이 파일은 **무엇을 언제 읽어야 하는지**만 알려 준다.
내용 요약은 적지 않는다 — 요약은 원본보다 먼저 낡는다.

프로젝트 전체 소개와 실행 방법은 루트 [README.md](../README.md) 를 먼저 본다.

---

## 폴더 구성

| 폴더 | 무엇이 들어가나 | 누가 여나 |
| --- | --- | --- |
| [`Api/`](Api) | 백엔드 API 전수 명세 · 공통 규약 · AI 연동 계약 · 대조표 | 심사자·전체 |
| [`Handover/`](Handover) | 기능별 **FE 인수인계** — API 를 화면에 붙일 때 읽는다 | FE 담당 |
| [`Erd/`](Erd) | **정본 스키마**와 마이그레이션. 손대지 않는다 | DB·백엔드 |
| [`Architecture/`](Architecture) | 시스템 구성도 · 인프라 실행 · 배포·운영 DB 절차 | 배포하는 사람 |
| [`AI/`](AI) | AI 서버 API 명세 · YOLO 출력 · 견적 산출 형식 · 검색 corpus 결정 | AI·백엔드 |
| [`CostNotes/`](CostNotes) | AI-Hub 수리비 원천 데이터의 함정 노트 | 데이터·AI |
| [`Requirements/`](Requirements) | 요구사항 명세 (**정본 1순위**) | 전체 |
| [`Wireframe/`](Wireframe) | 화면 시안 20장 | 기획·FE |
| [`Archive/`](Archive) | 지난 자료. 지우지 않고 내려 둔 것 — **먼저 열 필요는 없다** | 근거를 되짚을 때 |

`Docs/Claude/` 와 `Docs/backend-db/` 는 git 추적 밖이거나 작업 중인 산출물이라 이 표에 없다.

---

## 처음 온 사람이 읽는 순서

| 순서 | 무엇을 | 어디를 |
| --- | --- | --- |
| 1 | 서비스가 무엇이고 어디까지 됐나 | 루트 [README.md](../README.md) |
| 2 | 시스템 구성 | [Architecture/system-architecture.svg](<Architecture/system-architecture.svg>) |
| 3 | 데이터 모델 (42 테이블) | [Erd/A307_ddl_final.sql](<Erd/A307_ddl_final.sql>) · 그림 [Erd/바른견적_ERD.png](<Erd/바른견적_ERD.png>) |
| 4 | 백엔드 API 전수 (93건) | [Api/API 명세서 (바른견적 2026-09-16 전수조사).md](<Api/API 명세서 (바른견적 2026-09-16 전수조사).md>) |
| 5 | 백엔드 ↔ AI 경계 | [Api/AI 연동 계약 (백엔드 ↔ AI 서버).md](<Api/AI 연동 계약 (백엔드 ↔ AI 서버).md>) |
| 6 | 누가 무엇을 맡았나 | [담당 범위.md](<담당 범위.md>) |

---

## 목적별 찾아가기

### 아키텍처 · 인프라

| 보려는 것 | 문서 |
| --- | --- |
| 시스템 구성도 | [Architecture/system-architecture.svg](<Architecture/system-architecture.svg>) |
| 서버·컨테이너를 실제로 띄우는 절차 | [Architecture/인프라 실행 교본.md](<Architecture/인프라 실행 교본.md>) |
| 배포 전 점검 항목 | [Architecture/배포 준비 체크리스트.md](<Architecture/배포 준비 체크리스트.md>) |
| 운영 DB 에 마이그레이션 적용하는 순서 | [Architecture/운영 DB 마이그레이션 적용 절차.md](<Architecture/운영 DB 마이그레이션 적용 절차.md>) |

### 데이터베이스

| 보려는 것 | 문서 |
| --- | --- |
| **정본 스키마** (이것이 기준이다) | [Erd/A307_ddl_final.sql](<Erd/A307_ddl_final.sql>) |
| 정본 이후의 변경 10건 | [Erd/migrations/](<Erd/migrations>) |
| 어느 마이그레이션이 적용됐나 확인 | [Erd/migrations/check-applied.sql](<Erd/migrations/check-applied.sql>) |
| 초기 데이터 | [Erd/vehicle_model_seed.sql](<Erd/vehicle_model_seed.sql>) · [Erd/A307_part_code_seed.sql](<Erd/A307_part_code_seed.sql>) · [Erd/A307_admin_code_seed.sql](<Erd/A307_admin_code_seed.sql>) · [Erd/A307_part_name_mapping_seed.sql](<Erd/A307_part_name_mapping_seed.sql>) |
| S3 오브젝트 키 규칙 | [Erd/S3 Key 규칙.md](<Erd/S3 Key 규칙.md>) |
| 수리비 사례 DB 인수인계 | [Erd/A307_COST_DB_HANDOVER.md](<Erd/A307_COST_DB_HANDOVER.md>) |
| 수리비 산정 정책 | [Erd/A307_COST_POLICY.md](<Erd/A307_COST_POLICY.md>) |
| 유사 사례 검색 스키마 | [Erd/A307_DAMAGE_SEARCH_SCHEMA.md](<Erd/A307_DAMAGE_SEARCH_SCHEMA.md>) · [Erd/A307_SEARCH_LOAD_SCOPE.md](<Erd/A307_SEARCH_LOAD_SCOPE.md>) |

### API

| 보려는 것 | 문서 |
| --- | --- |
| **백엔드 API 전수조사** (93건 · 차시·담당·비고) | [Api/API 명세서 (바른견적 2026-09-16 전수조사).md](<Api/API 명세서 (바른견적 2026-09-16 전수조사).md>) · [같은 내용 CSV](<Api/API 명세서 (바른견적 2026-09-16 전수조사).csv>) |
| 백엔드 ↔ AI 서버 계약 | [Api/AI 연동 계약 (백엔드 ↔ AI 서버).md](<Api/AI 연동 계약 (백엔드 ↔ AI 서버).md>) |
| AI 호출 실패·재시도 규칙 | [Api/AI 서버 오류·재시도 처리 명세.md](<Api/AI 서버 오류·재시도 처리 명세.md>) |
| 화면이 어느 API 를 쓰나 | [Api/화면-서버 대조표.md](<Api/화면-서버 대조표.md>) |
| 명세와 코드가 실제로 맞나 | [Api/BE 구현 증거 대조표.md](<Api/BE 구현 증거 대조표.md>) |

**기능별 FE 인수인계** — [`Handover/`](Handover) 폴더에 있다. 프런트에서 그 기능을 붙일 때 읽는다.

- [차량 API](<Handover/차량 API — FE 인수인계.md>)
- [사고 조회 API](<Handover/사고 조회 API — FE 인수인계.md>)
- [이미지 업로드 API](<Handover/이미지 업로드 API — FE 인수인계.md>)
- [견적서 검증 API](<Handover/견적서 검증 API — FE 인수인계.md>)
- [정비소 검색 API](<Handover/정비소 검색 API — FE 인수인계.md>)
- [위치 API (주소·좌표·장소 검색)](<Handover/위치 API (주소·좌표·장소 검색) — FE 인수인계.md>)
- [가이드 API](<Handover/가이드 API — FE 인수인계.md>)
- [관리자 마스터·규칙 관리 API](<Handover/관리자 마스터·규칙 관리 API — FE 인수인계.md>) · [Vue 연동 예제](<Handover/관리자 마스터·규칙 관리 — Vue 연동 예제.md>)
- [김재원 담당 백엔드 API 묶음](<Handover/김재원 담당 백엔드 API — FE 인수인계.md>)
- [사고 이미지 S3 스모크 테스트 체크리스트](<Api/사고 이미지 S3 스모크 테스트 체크리스트.md>)

### AI · 비전

| 보려는 것 | 문서 |
| --- | --- |
| AI 서버 API 명세 | [AI/AI 서버 API 명세.md](<AI/AI 서버 API 명세.md>) |
| YOLO 원본 출력 형식 | [AI/YOLO 출력 형식.md](<AI/YOLO 출력 형식.md>) |
| 견적 산출 입출력 | [AI/견적 산출 입출력 형식.md](<AI/견적 산출 입출력 형식.md>) → 개정 [_update](<AI/견적 산출 입출력 형식_update.md>) |
| 검색 corpus 결정 사항 | [AI/이미지 입력 및 검색 corpus 결정사항.md](<AI/이미지 입력 및 검색 corpus 결정사항.md>) |

코드 옆에 있는 문서도 함께 읽는다 — [AI/README.md](../AI/README.md) · [AI/server/README.md](../AI/server/README.md) ·
[pipeline/README.md](../pipeline/README.md) · [shared/README.md](../shared/README.md) · [shared/vision/README.md](../shared/vision/README.md)

### 수리비 데이터 (AI-Hub 원천 해석)

원천 데이터의 함정을 적어 둔 노트다. 수리비 숫자를 의심할 때 여기부터 본다.

- [CostNotes/DATA_NOTES.md](<CostNotes/DATA_NOTES.md>) — 원천 데이터 전반
- [CostNotes/DETACH_ONLY_NOTES.md](<CostNotes/DETACH_ONLY_NOTES.md>) — 탈착만 있는 행 (46만 건)
- [CostNotes/DUPLICATE_AND_MISSING_NOTES.md](<CostNotes/DUPLICATE_AND_MISSING_NOTES.md>) — 중복·결측
- [CostNotes/SC_ADJUSTMENT_NOTES.md](<CostNotes/SC_ADJUSTMENT_NOTES.md>) — 보정
- [CostNotes/WORK_COMBINATION_NOTES.md](<CostNotes/WORK_COMBINATION_NOTES.md>) — 작업 조합

### 기획 · 화면

| 보려는 것 | 문서 |
| --- | --- |
| 요구사항 명세 (정본 1순위) | [Requirements/A307 요구사항 명세.xlsx](<Requirements/A307 요구사항 명세.xlsx>) |
| 와이어프레임 20장 | [Wireframe/](<Wireframe>) |
| 사전 조사 기록 | [Archive/PreStudy/](<Archive/PreStudy>) |

---

## 읽을 때 주의할 것

- **정본이 무엇인지 문서마다 다르다.** 스키마는 `Erd/A307_ddl_final.sql`, 요구사항은
  `Requirements/A307 요구사항 명세.xlsx`, 판정 임계값은 **DB 테이블**(`estimate_validation_rule`)이
  정본이다. 서로 어긋나면 이 순서를 따른다.
- **`Api/` 에 "명세서" 가 둘처럼 보이지만 역할이 다르다.** API **목록**의 정본은
  [Api/API 명세서 (바른견적 2026-09-16 전수조사).md](<Api/API 명세서 (바른견적 2026-09-16 전수조사).md>)(93건)이고,
  공통 응답·에러·인증·비동기 **규약**의 정본은 [Api/공통 규약과 계약 결정.md](<Api/공통 규약과 계약 결정.md>) 다
  (API 표가 0행이다). 뒤 문서는 2026-09-17 까지 `API 명세서 (바른견적 최신).md` 라는 이름이어서
  목록 문서로 오해받았다.
- **문서의 수치는 적힌 날짜의 값이다.** 커밋이 계속 쌓이므로 숫자를 근거로 쓸 일이면 다시 센다.
- **제품 이름이 두 가지로 적혀 있다.** 화면 제목은 "노카", 문서 제목 다수는 "바른견적" 이다.
  같은 서비스다.
