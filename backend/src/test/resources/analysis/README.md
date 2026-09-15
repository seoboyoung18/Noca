# 분석 결과 적재 테스트 픽스처 — 출처

`sample-analysis-document.json` 의 출처를 남긴다. **실제 파이프라인 출력을 받아 적은 것이 아니다.**

## 왜 직접 만들었나

`S15P21A307-218` 작업 지시(prompt54 §3-2)는 인계 문서 §6 에 실린 샘플 본문을 픽스처로 쓰라고 했다.
**그 인계 문서가 저장소에 없다.** `sample_analysis.json`·`sample_yolo_part.json`·`sample_yolo_damage.json`
도 없다 (2026-09-11, `origin/develop` `0ffb7c0` 실측).

```bash
git ls-tree -r --name-only origin/develop | grep -iE "sample_(analysis|yolo)"   # 0건
git grep -rl "sample_analysis\|work_candidates" -- Docs/                        # 0건
```

**샘플을 지어내지 않기 위해**, 값은 저장소에 실재하는 두 파일에서만 가져왔다.

| 픽스처의 무엇 | 어디서 |
| --- | --- |
| 구조 · 필수 키 · 상수 · 값 범위 | `shared/vision/common_schema.json` (`inference-standardized-1.1.0`) |
| `part.code` · `name_en` · `name_ko` · `group` · `side` | `shared/vision/catalog.py` 의 `PARTS` |
| `damage.code` · `name_en` · `name_ko` | 같은 파일의 `DAMAGES` |
| `work_candidates` 조합 | 같은 파일의 `DEFAULT_WORK_BY_DAMAGE` |

좌표·넓이·신뢰도 숫자만 계약이 허용하는 범위 안에서 임의로 골랐다. 그 값들은 적재 결과에
영향을 주지 않는다 — 좌표는 저장되지 않고, 신뢰도는 "있다/없다" 와 대소만 쓰인다.

## 세 검출이 각각 무엇을 덮는가

| # | 부품 | 노리는 것 |
| --- | --- | --- |
| 1 | `FRONT_BUMPER` | **적재된다.** 후보 1개(`COATING`) · 신뢰도 둘 다 있음 → `confidence` 는 작은 쪽 `0.8700` |
| 2 | `BONNET` | **보류.** 후보 2개(`SHEET_METAL`·`EXCHANGE`) → `AMBIGUOUS_WORK_CANDIDATE`. 폴리곤이 2개인 경우도 함께 덮는다 |
| 3 | `HEAD_LIGHT_L` | **보류.** 후보는 1개지만 `confidence` 가 둘 다 `null` → `MISSING_CONFIDENCE` |

## 아직 확인되지 않은 것

**실제 파이프라인 출력으로 이 파서를 돌려본 적이 없다.** 계약 스키마를 옮겨 적은 것이 맞는지는
검증했지만, 파이프라인이 실제로 내보내는 문서가 계약을 그대로 지키는지는 이 픽스처로 알 수 없다.
파이프라인 담당에게 **실제 출력 샘플 1건의 커밋을 요청**해야 한다.
