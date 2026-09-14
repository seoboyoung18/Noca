# 차량 파손 AI

손상 유형 모델, 부품 모델, 학습 도구와 AI 서버를 한 디렉터리에서 관리한다. 외부 HTTP
경계는 `server/`뿐이며, 서버 내부의 추론·검색·견적 모듈은 HTTP로 서로 호출하지 않는다.

```text
AI/
├─ models/                         학습 완료·초기 가중치
│  ├─ damage/damage_best-60ep.pt   손상 segmentation 모델
│  ├─ part/damage_part_best-35ep.pt 기존 부품 segmentation 가중치
│  └─ base/yolo26n-seg.pt          학습 초기 가중치
├─ training/                       데이터셋 준비·학습 스크립트
├─ tools/                          서버와 독립적인 로컬 추론·raw JSON 확인 도구
├─ server/                         FastAPI AI 서버
│  └─ app/
│     ├─ api/                      /analyze · /inference · /search · /estimate · /health
│     ├─ services/                 추론 → 검색 → 견적의 일반 함수
│     ├─ adapters/                 Ultralytics → raw JSON → canonical inference adapter
│     ├─ infrastructure/           이미지 다운로드·DB/벡터 gateway
│     ├─ schemas/                  HTTP DTO
│     └─ core/                     설정·인증·공유 도메인 규칙 import
├─ artifacts/                      학습·로컬 추론 산출물 (Git 제외)
└─ samples/                        로컬 smoke test 입력 이미지 (Git 제외)
```

## 모델 역할

| 모델 | 서버 계약 | 현재 가중치 상태 |
| --- | --- | --- |
| damage | segmentation | `damage_best-60ep.pt` 사용 가능 |
| part | detection | 현재 `damage_part_best-35ep.pt`는 segmentation 가중치. 서버는 bbox만 읽어 로컬 smoke test에서는 detect 형식으로 adapter에 전달하지만, 운영 전 detection 모델로 교체한다. |

두 모델의 raw 출력은 [YOLO 출력 형식](../Docs/AI/YOLO%20출력%20형식.md), FastAPI 외부
계약은 [AI 서버 API 명세](../Docs/AI/AI%20서버%20API%20명세.md)를 기준으로 한다.

## 학습·로컬 추론

```powershell
# 학습 데이터 생성
python AI/training/prepare_dataset.py --model damage
python AI/training/prepare_dataset.py --model damage_part

# 학습
python AI/training/train.py --model damage

# 서버와 무관한 시각 확인
python AI/tools/infer_visual.py --source AI/samples/input

# 두 모델 raw JSON 확인
python AI/tools/export_yolo_json.py
```

학습 데이터는 `datasets/`, 결과물은 `artifacts/`, smoke test 이미지는 `samples/`에 두며
모두 Git에 올리지 않는다.

## AI 서버

설치·환경 변수·현재 구현 범위는 [server/README.md](server/README.md)를 따른다.
`/inference`는 YOLO 실행과 서버 소유 `app/adapters/yolo_adapter.py`까지 연결한다.
adapter는 batch 적재와 온라인 추론의 판정을 일치시키기 위해 공용 표준 코드·ROI 매칭 규칙을 참조한다.
`/search`는 공용 `shared/vision/dinov2.py`의 DINOv2 ROI 임베딩과 pgvector gateway까지 연결한다. 비용 통계·비동기 callback이
필요한 `/estimate`·`/analyze`는 gateway를 연결하기 전까지 명시적으로 `501`을 반환한다.
