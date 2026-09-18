# 운영 DB 마이그레이션 적용 절차 (`S15P21A307-503`)

> 기준 `origin/develop` **`9116ff6`** · 2026-09-15
> 마이그레이션 **9건** (`Docs/Erd/migrations/2026-*.sql`) · 정본 테이블 **42개**

---

## 0. 왜 이 절차가 필요한가

이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.

**마이그레이션이 하나라도 빠지면 그 API 만 죽는 것이 아니라 서버 전체가 기동하지 않는다.**
엔티티가 요구하는 테이블·컬럼이 DB 에 없으면 Hibernate 가 컨텍스트 로딩 단계에서 멈춘다.

그리고 증상이 **"서버가 안 뜬다" 하나로 뭉쳐 나온다.** 환경변수가 빠진 것인지 스키마가
어긋난 것인지 겉으로는 구분되지 않는다. 그래서 서버를 올리기 전에 여기부터 닫는다.

---

## 1. 🔴 운영 DB 는 원격이다 — 로컬과 헷갈리지 않는다

| | 어디 | |
| --- | --- | --- |
| **운영** | **AI 서버의 PostgreSQL** | 2026-09-14 결정 · 배포 준비 체크리스트 6-1 |
| 로컬 | 개발자 PC 의 `localhost:5432` (`a307-db` 컨테이너) | **운영이 아니다** |

로컬에 적용해도 운영은 그대로다. **둘을 헷갈리면 "적용했는데 서버가 안 뜬다" 가 된다.**

---

## 2. 개수를 이 문서에서 읽지 않는다

머리말의 9건도 **적는 순간 낡는다.** 스키마 작업이 머지될 때마다 늘어난다.

```bash
git fetch origin
git log --oneline -1 origin/develop
git ls-tree --name-only origin/develop Docs/Erd/migrations/ | grep '2026-' | wc -l
grep -c '^CREATE TABLE' Docs/Erd/A307_ddl_final.sql
```

⚠️ **작업 트리가 develop 최신인지 먼저 확인한다.** 2026-09-15 에 실제로 메인 트리가 다른
브랜치에 있어 develop 보다 뒤처졌고 폴더에 6건만 보였다. **뒤처진 트리에서 세면 마지막
마이그레이션을 통째로 빠뜨린다.**

`ls Docs/Erd/migrations/ | wc -l` 로 세지 않는다 — 그 폴더에는 `check-applied.sql` 이 섞여 있다.

---

## 3. 순서 — 백업 → 확인 → 적용 → 기동 → 헬스

사람이 기억해서 하면 반드시 한 번 어긋난다. **배포 스크립트에 이 순서를 박는다.**

### 3-1. 백업부터

**마이그레이션을 되돌리는 스크립트는 없다.**

```bash
pg_dump "$DATABASE_URL" -Fc -f backup_$(date +%Y%m%d_%H%M).dump
ls -la backup_*.dump          # 크기가 0이 아닌지 확인한다
```

### 3-2. 무엇이 빠졌는지 본다

```bash
psql "$DATABASE_URL" -f Docs/Erd/migrations/check-applied.sql
```

`psql` 이 PATH 에 없으면 컨테이너 안의 것을 쓴다.

```bash
docker exec -i <db컨테이너> psql -U "$DB_USERNAME" -d a307 < Docs/Erd/migrations/check-applied.sql
```

**SELECT 만 한다.** 아무것도 만들거나 고치지 않는다. 출력은 이렇다.

```
 # | 적용 |              파일               |   만드는 것   |      판정 근거
---+------+--------------------------------+--------------+-------------------
 1 | O    | 2026-09-10-accident-image-...  | 컬럼          | accident_image...
 9 | X    | 2026-09-15-accident-review.sql | 테이블 1      | accident_review
```

`X` 인 것만 적용한다.

### 3-3. 적용 — 두 옵션을 반드시 붙인다

```bash
PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 "$DATABASE_URL" \
  -f Docs/Erd/migrations/2026-09-15-accident-review.sql
```

| 옵션 | 없으면 |
| --- | --- |
| `ON_ERROR_STOP=1` | 중간에 실패해도 계속 가서 **반쯤 적용된 DB** 가 남는다 |
| `PGCLIENTENCODING=UTF8` | 시드의 한글이 `?` 로 들어간다. **에러가 안 나서 모른다** |

**한 건씩** 적용하고, 파일명 **날짜순**을 지킨다 — FK 의존이 있다.

### 3-4. 적용 후 확인

```bash
psql "$DATABASE_URL" -f Docs/Erd/migrations/check-applied.sql   # 전부 O
```

테이블 수가 정본과 맞는지도 본다. AI-Hub 스테이징 테이블(`aihub_*` 5개)은 파이프라인이
만드는 것이라 정본보다 많은 것이 정상이다.

### 3-5. 서버 기동과 헬스

```bash
curl -s localhost:8080/actuator/health     # {"status":"UP"}
```

기동이 실패하면 로그에서 이 줄을 찾는다.

```
Schema-validation: missing table [...]
Schema-validation: missing column [...]
```

그 이름으로 어느 마이그레이션이 만드는 것인지 §4 표에서 역추적한다.

---

## 4. 파일마다 성격이 다르다

| 파일 | 만드는 것 | 재실행 |
| --- | --- | --- |
| `2026-09-10-accident-image-angle-code.sql` | 컬럼 1 | 안전 |
| `2026-09-10-admin-master-and-rules.sql` | 테이블 3 + 컬럼 5 | 안전 |
| `2026-09-12-analysis-stage.sql` | 테이블 1 | **아래 참조** |
| `2026-09-14-analysis-result-ingest.sql` | 컬럼 4 + NOT NULL 완화 | 안전 (전부 ALTER) |
| `2026-09-14-estimate-notice.sql` | 테이블 1 + 시드 1행 | 안전 |
| `2026-09-14-repair-checklist.sql` | 테이블 3 + 시드 6행 | 안전 |
| `2026-09-15-guidance-notice.sql` | 시드 1행 | 안전 |
| `2026-09-15-repair-question.sql` | 테이블 2 | 안전 · 끝에 제약 10개 확인 블록 |
| `2026-09-15-accident-review.sql` | 테이블 1 | 안전 |

### 🔴 `2026-09-12-analysis-stage.sql` 만 다르다

`CREATE TABLE` 에 `IF NOT EXISTS` 를 **일부러 붙이지 않았다.** 이미 있으면 에러로 알려주는
편이 낫다는 판단이다.

```
ERROR: relation "analysis_stage" already exists
```

**이것은 고장이 아니라 "이미 적용됨" 신호다.** §3-2 에서 `O` 로 나오면 건너뛴다.

### `2026-09-15-repair-question.sql` 의 확인 블록

끝에 제약 10개를 이름으로 확인하는 블록이 있다. 하나라도 빠지면 `RAISE EXCEPTION` 으로
멈춘다. **멈추면 앞의 적용이 잘못된 것이므로 로그를 본다.**

---

## 5. 시드 행은 테이블 존재와 따로 본다

**테이블만 있고 행이 없는 경우가 실제로 있었다.** `check-applied.sql` 뒷부분이 따로 센다.

| 코드 | 없으면 |
| --- | --- |
| `LEGAL_NOTICE` | **리포트 전체가 500** (2026-09-14 에 실제로 났다) |
| `GUIDANCE_LIMIT_NOTICE` | 체크리스트 조회와 리포트의 고지 문구가 비어서 나간다 |

공통 확인 항목 6종(`repair_checklist_common_item`)도 같다. 테이블만 있고 시드가 없으면
체크리스트에 공통 항목이 하나도 붙지 않는다.

---

## 6. 하지 말 것

- **`docker system prune -a --volumes` 를 DB 가 있는 박스에서 치지 않는다.**
  `repair_case` 125,006건이 사라진다
- **`docker compose down -v` 도 같은 이유로 치지 않는다**
- DB 접속 정보를 문서·로그·커밋 어디에도 적지 않는다. **환경변수로만 쓴다**
- `Docs/Erd/A307_ddl_final.sql` 을 수정하지 않는다
- 정본 DDL 을 운영 DB 에 통째로 돌리지 않는다. 그것은 **신규 설치용**이다
- 적용 순서를 바꾸지 않는다. FK 의존이 있다
- `ON_ERROR_STOP` 없이 적용하지 않는다

---

## 7. 지금 무엇이 걸려 있나 (2026-09-15 · develop `9116ff6`)

| 마이그레이션 | 이것이 없으면 |
| --- | --- |
| `2026-09-14-estimate-notice.sql` | `estimate_notice` 가 없어 **기동 실패**. 견적 조회·리포트가 고지 문구를 여기서 읽는다 |
| `2026-09-14-analysis-result-ingest.sql` | `analysis_job.request_id` 등이 없어 **기동 실패** |
| `2026-09-15-accident-review.sql` | **기동 실패.** `S15P21A307-350` 이 엔티티를 붙였다 |
| `2026-09-15-repair-question.sql` | `S15P21A307-477` 이 쓴다 |
| `2026-09-15-guidance-notice.sql` | 시드 1행뿐이라 기동은 된다. 고지 문구가 비어서 나간다 |

---

## 8. 진행 상태

- [x] 마이그레이션 9건 실측 확인 (2026-09-15 · develop `9116ff6`)
- [x] `check-applied.sql` 작성 — **로컬 DB 에서 정상 동작 확인** (9건 전부 `O`)
- [x] 이 절차 문서 작성
- [ ] **운영 DB 백업** — 접속 정보가 이 환경에 없어 미수행
- [ ] **운영 DB 확인** (`check-applied.sql`)
- [ ] **미적용분 적용** (`ON_ERROR_STOP=1` · `PGCLIENTENCODING=UTF8`)
- [ ] 적용 후 테이블 수 대조
- [ ] 서버 기동 및 `/actuator/health` UP
- [ ] 배포 스크립트화 — 지금은 사람이 순서를 기억해야 한다 (배포 준비 체크리스트 2번)

> 로컬 `a307-db` 는 9건 전부 적용돼 있고 시드 3종(`LEGAL_NOTICE` · `GUIDANCE_LIMIT_NOTICE` ·
> 공통 항목 6종)도 들어 있다. **운영은 별개이며 아직 확인되지 않았다.**
