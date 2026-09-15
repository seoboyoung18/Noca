# 운영 DB 마이그레이션 적용 절차 (`S15P21A307-503`)

> 기준 `origin/develop` **`ec31829`** · 2026-09-15
> 마이그레이션 **9건** (`Docs/Erd/migrations/2026-*.sql`)

---

## 0. 왜 이 절차가 필요한가

이 저장소는 `spring.jpa.hibernate.ddl-auto=validate` 다.

**마이그레이션이 하나라도 빠지면 그 API 만 죽는 것이 아니라 앱 전체가 기동하지 않는다.**
엔티티가 요구하는 테이블·컬럼이 DB 에 없으면 Hibernate 가 컨텍스트 로딩 단계에서 멈춘다.

그래서 **적용 → 배포** 순서가 고정이다. 반대로 하면 앱이 안 뜬다.

---

## 1. 먼저 무엇이 빠졌는지 본다

```bash
psql "$DATABASE_URL" -f Docs/Erd/migrations/check-applied.sql
```

`psql` 이 PATH 에 없으면 컨테이너 안의 것을 쓴다.

```bash
docker exec -i a307-db psql -U "$DB_USERNAME" -d a307 < Docs/Erd/migrations/check-applied.sql
```

**SELECT 만 한다.** 아무것도 만들거나 고치지 않는다.

출력이 이렇게 나온다.

```
 # |            마이그레이션            |   종류   |        대상        | 적용
---+-----------------------------------+---------+-------------------+------
 1 | 2026-09-10-accident-image-...     | 컬럼     | accident_image... |  O
 ...
 9 | 2026-09-15-accident-review.sql    | 테이블 1 | accident_review   |  X
```

`X` 인 것만 적용하면 된다.

---

## 2. 적용 순서 — 파일명 날짜순

FK 의존이 있으므로 **반드시 날짜순**이다.

| # | 파일 | 만드는 것 | 재실행 |
| --- | --- | --- | --- |
| 1 | `2026-09-10-accident-image-angle-code.sql` | 컬럼 1 | 안전 |
| 2 | `2026-09-10-admin-master-and-rules.sql` | 테이블 3 + 컬럼 5 | 안전 |
| 3 | `2026-09-12-analysis-stage.sql` | 테이블 1 | **위험 — 아래 참조** |
| 4 | `2026-09-14-analysis-result-ingest.sql` | 컬럼 4 + NOT NULL 완화 | 안전 |
| 5 | `2026-09-14-estimate-notice.sql` | 테이블 1 + 시드 1행 | 안전 |
| 6 | `2026-09-14-repair-checklist.sql` | 테이블 3 + 시드 6행 | 안전 |
| 7 | `2026-09-15-accident-review.sql` | 테이블 1 | 안전 |
| 8 | `2026-09-15-guidance-notice.sql` | 시드 1행 | 안전 |
| 9 | `2026-09-15-repair-question.sql` | 테이블 2 | 안전 |

적용은 한 건씩 한다.

```bash
psql "$DATABASE_URL" -f Docs/Erd/migrations/2026-09-15-accident-review.sql
```

---

## 3. 🔴 `2026-09-12-analysis-stage.sql` 은 멱등이 아니다

이 파일만 `CREATE TABLE` 에 `IF NOT EXISTS` 가 없다.

```sql
CREATE TABLE analysis_stage (      -- IF NOT EXISTS 가 없다
```

이미 적용된 DB 에 다시 돌리면 이렇게 멈춘다.

```
ERROR: relation "analysis_stage" already exists
```

**§1 의 확인에서 `O` 로 나오면 이 파일은 건너뛴다.** 나머지 8건은 `IF NOT EXISTS` 와
`ON CONFLICT` 가 있어 여러 번 돌려도 안전하다.

---

## 4. 시드 행은 테이블 존재와 따로 본다

**테이블만 있고 행이 없는 경우가 실제로 있었다.** `check-applied.sql` 뒷부분이 이것을 따로 센다.

| 코드 | 없으면 |
| --- | --- |
| `LEGAL_NOTICE` | **리포트 전체가 500** (2026-09-14 에 실제로 났다) |
| `GUIDANCE_LIMIT_NOTICE` | 체크리스트 조회와 리포트의 고지 문구가 비어서 나간다 |

공통 확인 항목 6종(`repair_checklist_common_item`)도 마찬가지다. 테이블만 있고 시드가
없으면 체크리스트에 공통 항목이 하나도 붙지 않는다.

---

## 5. 적용 후 확인

```bash
# (1) 다시 확인 — 전부 O 여야 한다
psql "$DATABASE_URL" -f Docs/Erd/migrations/check-applied.sql

# (2) 앱 기동
#     Started BackendApplication 이 나오면 validate 를 통과한 것이다
```

기동이 실패하면 로그에서 이 줄을 찾는다.

```
Schema-validation: missing table [...]
Schema-validation: missing column [...]
```

그 이름으로 어느 마이그레이션이 만드는 것인지 §2 표에서 역추적한다.

---

## 6. 하지 말 것

- **`ls Docs/Erd/migrations/ | wc -l` 로 세지 않는다.** 그 폴더에는 마이그레이션이 아닌
  파일(`check-applied.sql`)이 섞여 있다. `2026-*.sql` 만 센다
- `docker compose down -v` 를 쓰지 않는다. **볼륨을 지워 데이터가 사라진다**
- 정본 DDL(`Docs/Erd/A307_ddl_final.sql`)을 운영 DB 에 통째로 돌리지 않는다.
  그것은 **신규 설치용**이다. 기존 DB 에는 마이그레이션만 얹는다
- 순서를 바꾸지 않는다. FK 의존이 있다

---

## 7. 지금 무엇이 걸려 있나 (2026-09-15)

| 마이그레이션 | 이것이 없으면 |
| --- | --- |
| `2026-09-14-estimate-notice.sql` | `estimate_notice` 가 없어 **기동 실패**. 견적 조회·리포트가 고지 문구를 여기서 읽는다 |
| `2026-09-14-analysis-result-ingest.sql` | `analysis_job.request_id` 등이 없어 **기동 실패** |
| `2026-09-15-accident-review.sql` | 지금은 엔티티가 없어 괜찮다. **`S15P21A307-350` 이 엔티티를 붙이는 순간 기동 실패** |
| `2026-09-15-guidance-notice.sql` | 시드 1행뿐이라 기동은 된다. 고지 문구가 비어서 나간다 |

**`-350` 착수 전에 `accident_review` 를 적용해 두는 것이 안전하다.**

---

## 8. 남은 일

- [ ] 운영 DB 에 `check-applied.sql` 실행 — 무엇이 빠졌는지 확인
- [ ] 빠진 마이그레이션 적용 (날짜순, `analysis-stage` 는 중복 적용 주의)
- [ ] 시드 행 확인 — `LEGAL_NOTICE` · `GUIDANCE_LIMIT_NOTICE` · 공통 항목 6종
- [ ] 앱 기동 확인
- [ ] 배포 스크립트화 — 지금은 사람이 순서를 기억해야 한다 (배포 준비 체크리스트 2번 항목)
