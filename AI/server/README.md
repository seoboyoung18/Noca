# AI 서버

`Docs/AI/AI 서버 API 명세.md`의 구현 위치다. FastAPI는 외부 HTTP 경계만 맡고,
YOLO 호출·검색·견적은 `app/services/`의 일반 함수로 연결한다.

```text
app/api/          FastAPI route 및 인증 경계
app/services/     inference → search → estimate orchestration
app/adapters/     Ultralytics 결과 → raw YOLO → canonical inference adapter
app/infrastructure/ 이미지 다운로드·DB/벡터 gateway
app/schemas/      HTTP 요청·응답 DTO
```

## 현재 구현 범위

- `GET /health`: 설정·모델 파일 준비 상태 확인
- `POST /inference`: presigned GET URL → 두 YOLO 모델 → raw adapter → 표준화 결과
- `POST /search`: 원본 URL + 표준화 detection → damage ROI → DINOv2 768d → pgvector Top-K.
  `STRICT`는 부품·손상유형, `VECTOR_ONLY`는 손상유형으로 필터한 뒤 현재 DEV corpus의
  `CAR_CLASS → ALL` 완화 단계를 적용한다.
- `POST /estimate`, `/analyze`: 비용 통계·callback receiver가 아직 연결되지 않아 `501`을 반환한다.

`app/adapters/yolo_adapter.py`는 AI 서버 소유다. batch 적재와 실시간 분석에서 같은
판정을 보장하기 위해 부품/손상 코드·ROI 매칭·정규화 규칙만
`shared/vision`의 공용 도메인 기준을 참조한다.

`damage_part_best-35ep.pt`는 현재 **segmentation** 가중치다. 서버 adapter는 bbox만
읽어 API에는 `detect` 결과로 내보내지만, 운영 전에는 명세대로 학습한 part detection
가중치로 교체해야 한다. 이 호환 처리는 모델의 출력 계약을 바꾸는 것이 아니라 기존
가중치를 로컬 smoke test에만 사용할 수 있게 하는 임시 경계다.

## 실행

```powershell
pip install -r AI/requirements.txt -r AI/server/requirements.txt
$env:AI_INTERNAL_TOKEN = "<백엔드와 합의한 내부 토큰>"
$env:FEATURE_PIPELINE_VERSION_ID = "<활성 feature_pipeline_version ID>"
$env:DATABASE_URL = "postgresql://<AI서버 읽기전용 계정>:<password>@<host>:5432/<db>"
uvicorn app.main:app --app-dir AI/server --host 0.0.0.0 --port 8000 --reload
```

검색은 corpus batch와 공용으로 쓰는 `shared/vision/dinov2.py`를 통해 `facebook/dinov2-base` revision
`f9e44c814b77203eaa57a6bdbbd535f21ede1415`의 `pooler_output`(768차원)을 쓴다.
ROI는 공용 `roi.py`의 20% padding·224px letterbox 결과이며, ImageNet mean/std 적용 뒤
L2 정규화한다. 활성 `embedding_model_version`이 이 모델명·버전과 일치하지 않으면 검색을 거부한다.

모델 파일 위치는 기본적으로 다음과 같다.

```text
AI/models/damage/damage_best-60ep.pt
AI/models/part/damage_part_best-35ep.pt
```
