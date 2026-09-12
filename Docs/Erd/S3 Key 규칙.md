# S3 Key 규칙

확정 규칙. 저장소 이미지·파일의 S3 Key 형식을 정한다.
관련 이슈: S15P21A307-139 · -94 · -217 · -342

S3 Key는 **이미지의 원천과 용도**에 따라 구분하며, 직접 문자열을 조합하지 않고 **전용 Key Builder를 사용한다.**

| 용도 | Key |
|---|---|
| 사고 이미지 | `accidents/{accidentId}/images/{imageId}/{variant}.{ext}` |
| AI-Hub 사례 이미지 | `repair-cases/{source}/{externalRef}/{sourceImageId}/{variant}.{ext}` |
| 프로필 | `profile/{memberId}` |
| 견적서 검증 | `validations/{validationId}` |
| PDF 리포트 | `reports/{reportId}.pdf` |

`variant`: `original` · `resized` · `thumbnail` · `blurred`

---

## 사고 이미지

`AccidentImageKeys.key()`를 사용한다.

```text
accidents/100/images/501/original.jpg
accidents/100/images/501/thumbnail.jpg
```

기존 `accidents/{imageId}/original.jpg` 규칙은 위 규칙으로 대체한다.

## AI-Hub 사례 이미지

원천 식별자로 조립한다. 생성 정본은 **파이프라인**이다 — `pipeline/standardization`의 key 생성 함수 하나가 만들고, 결과를 `repair_case_image.storage_key`에 기록한다. **백엔드는 그 컬럼을 읽고 key를 재계산하지 않는다.**

```text
repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg
repair-cases/AIHUB_SC/sc-192626/0621531/original.jpg
```

| 자리 | 값 | 출처 |
|---|---|---|
| `source` | `AIHUB_AS` · `AIHUB_SC` | `repair_case.source` |
| `externalRef` | `as-0000160` | `repair_case.external_ref` |
| `sourceImageId` | `0406472` | `source_image_ref` 파일명의 숫자 접두. 라벨 파일명 `<image_id>_<external_ref>.json`의 image_id |

`sourceImageId`는 **문자열로 다룬다.** `0406472`처럼 앞자리 0을 포함하므로 정수로 파싱하면 원본 파일과 어긋난다.

### DB 자동증가 PK를 쓰지 않는 이유 (2026-09-11 변경)

기존 규칙은 `repair-cases/{caseId}/images/{caseImageId}/{variant}.{ext}`였다. `case_id`·`case_image_id`가 `BIGSERIAL`이라 **테이블을 비우고 다시 적재할 때마다 번호가 새로 매겨진다.** 원본 사진은 그대로인데 key만 바뀌어, 같은 이미지를 세 번 다시 업로드했다.

원천 식별자는 몇 번을 재적재해도 바뀌지 않는다. 이제 재적재는 `storage_key` 컬럼을 다시 기록하는 것으로 끝나고 S3는 건드리지 않는다.

### 원천 정보를 어디까지 쓰는가

**데이터셋 폴더 구조는 Key에 넣지 않는다.** `1.Training/1.원천데이터/TS_damage_part/...` 같은 경로는 AI-Hub 배포본의 사정이라 바뀔 수 있다. Key에 쓰는 것은 **식별자 세 개**뿐이고, 전체 원본 경로는 `source_image_ref`에 그대로 보존한다.

Key를 만드는 값은 경로 구분자(`/` `\`)와 `..`를 포함할 수 없다. 포함되면 Key가 `repair-cases/` 밖으로 나갈 수 있으므로 생성 시점에 거부한다.

현재 `repair_case_image` 적재는 `original` 파일만 대상으로 한다. 추후 파생 이미지는 variant별 asset 관리 구조를 통해 추가한다.

## 실사용자 이미지의 사례 색인

실사용자가 등록한 사고 이미지가 이후 유사 사례 검색 대상으로 사용되더라도 `repair-cases/...`로 복사하지 않는다. 실사용자 사례 색인은 기존 사고 이미지의 `accident_image_id`를 참조하는 방식으로 처리한다.

```text
AI-Hub 사례       → repair-cases/... 사용
실사용자 사고      → accidents/... 사용
실사용자 사례 색인 → 기존 accidents/... Key 재사용
```

즉, 두 경로는 **동일 이미지를 중복 저장하기 위한 것이 아니라 이미지의 원천을 구분하기 위한 구조**다.

## 개발 환경

로컬 저장소를 사용하는 경우에도 동일한 Key를 상대 경로로 사용한다.

---

## 오버레이 Key — 필요 없어졌다 (2026-09-11)

이전 판에 "Key를 어떻게 만들지 정해야 한다"고 남겨둔 항목이다. **오버레이 이미지를 만들지 않기로 정해 Key 자체가 필요 없어졌다.**

「AI 연동 계약」 2026-09-11 수정 ①에 따라 AI는 오버레이 이미지를 보내지 않고 **좌표(JSON)만** 보낸다. 프론트가 원본 위에 직접 그린다. AI가 그려 보내면 프론트가 색·두께·부품별 토글을 조절할 수 없고 원본도 볼 수 없기 때문이다.

- `analysis_image_result.s3_key_overlay`는 **쓰지 않고 NULL로 둔다**
- 좌표는 `analysis_image_result.detections` JSONB에 보관한다
- 분석 중 S3 쓰기가 없어져 실패 시 보상 삭제도 불필요해졌다

`accident_image_asset`의 `ck_aia_var`가 4종만 허용하는 것도 그대로 두면 된다 — 오버레이는 asset이 아니다.

**PDF 리포트는 백엔드가 저장된 좌표로 직접 렌더링한다.** PDF는 정적 파일이라 캔버스로 그릴 수 없고, `estimate_report`가 큐라 PDF 워커는 분석과 다른 시점에 돈다. AI 응답을 들고 있지 않으므로 DB의 `detections`가 유일한 출처다.

재분석 시 이전 오버레이가 덮인다는 우려도 함께 사라졌다 — 덮을 객체가 없다.

상세는 같은 폴더의 `YOLO 출력 → 저장까지 넘어가는 정보.md` 4절·6절.
