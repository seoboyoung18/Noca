# 차량 파손 세그멘테이션 (YOLO26-seg)

차량 손상 유형(damage)과 손상 부위(damage_part)를 각각 인식하는 두 개의 YOLO26 세그멘테이션 모델을 학습·추론하는 프로젝트.

## 1. 실행 순서 (터미널)

```bash
# 0. 가상환경 활성화 + 의존성 설치
venv\Scripts\activate
pip install -r requirements.txt

# 1. 데이터셋 생성 (모델별로 각각 실행)
python scripts/prepare_dataset.py --model damage
python scripts/prepare_dataset.py --model damage_part

# 2. 학습 (모델별로 각각 실행)
python scripts/train.py --model damage
python scripts/train.py --model damage_part

# 3-a. 추론 - 시각화 이미지만 필요할 때
python scripts/infer.py

# 3-b. 추론 - 표준 JSON 결과가 필요할 때 (damage, damage_part 순차 실행)
python scripts/infer_json.py
```

- `prepare_dataset.py`는 `train.py`보다 먼저 실행해야 한다 (학습에 필요한 `datasets/<model>/` 생성).
- `infer.py`, `infer_json.py`는 각각 독립 실행 가능하며 학습된 가중치(`.pt`)가 이미 있어야 한다.

## 2. 데이터 인풋/아웃풋

| 스크립트 | 인풋 | 아웃풋 |
|---|---|---|
| `prepare_dataset.py` | `160. 차량파손 이미지 데이터/01.데이터/` 아래 원천 이미지 zip + 라벨링 JSON zip | `datasets/<model>/images,labels/{train,val}` (YOLO seg txt 라벨) + `datasets/<model>/<model>.yaml` |
| `train.py` | `datasets/<model>/<model>.yaml` + 초기 가중치(`yolo26s-seg.pt`) | `runs/<model>/train/weights/best.pt` 등 학습 산출물 (가중치, 로그, plot) |
| `infer.py` | `추론이미지/*.jpg` + `best(30ep).pt` | `추론결과/<타임스탬프>/` 시각화 이미지 |
| `infer_json.py` | `추론이미지/*.jpg` + `damage_best(60ep).pt`, `damage_part_best(35ep).pt` | `추론결과/<타임스탬프>/{damage,damage_part}/<image_id>.json` (bbox + polygon) + `classes.json` |

- `damage` 클래스(4개): `datasets/damage/damage.yaml` 참고 (Breakage, Crushed, Scratched, Separated)
- `damage_part` 클래스(32개): `datasets/damage_part/damage_part.yaml` 참고 (차량 부위별 명칭)

## 3. 아키텍처

```
160. 차량파손 이미지 데이터 (원천 zip + 라벨 JSON zip)
        │  scripts/prepare_dataset.py
        ▼
datasets/damage/, datasets/damage_part/  (YOLO seg 포맷)
        │  scripts/train.py (ultralytics YOLO26-seg)
        ▼
runs/damage/train/weights/best.pt, runs/damage_part/train/weights/best.pt
        │
        ├─ scripts/infer.py       → 추론결과/<타임스탬프>/ (시각화 이미지)
        └─ scripts/infer_json.py  → 추론결과/<타임스탬프>/{damage,damage_part}/*.json (표준 JSON)
```

- 손상 유형(damage)과 손상 부위(damage_part)는 서로 다른 클래스 체계를 가진 별개 모델로 분리 학습·추론한다.
- `infer_json.py`는 두 모델을 순차 실행해 이미지 1장당 모델별 JSON 결과(픽셀 좌표 bbox/polygon)를 남긴다.
