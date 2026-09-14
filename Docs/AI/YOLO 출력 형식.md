부품 모델과 손상 모델은 동일한 JSON 구조로 추론 결과를 반환한다.

## 필수 조건

- 좌표는 resize된 모델 입력이 아닌 **원본 이미지 기준 픽셀 좌표**
- bbox 형식은 `xyxy`: `[x_min, y_min, x_max, y_max]`
- Segmentation 결과는 polygon 배열 허용
- Detection 모델이라면 `segmentation: null`
- 검출 결과가 없으면 `predictions: []`
- 모델 버전별 클래스 ID·이름 매핑표 함께 관리
- 부품·손상 매칭과 심각도 계산은 모델 외부에서 처리

## 반환할 핵심 값

```
model_version
image_id
original_width, original_height
class_id, class_name
confidence
bbox_xyxy
polygons
```
## 모델별 적용

| 모델 | `model.task` | `segmentation` |
| --- | --- | --- |
| 부품 모델 | `detect` | 반드시 `null` |
| 손상 모델 | `segment` | polygon 배열 필수 |

`task`와 `segmentation` 조합이 이 규칙에 맞지 않으면 AI 서버 adapter는
`422 INVALID_MODEL_OUTPUT`으로 처리한다.

## 출력 예시

### 부품 모델 — Detection

```json
{
  "model": {
    "name": "vehicle-part-detection",
    "version": "1.0.0",
    "task": "detect"
  },
  "image": {
    "image_id": "image-001",
    "original_width": 1280,
    "original_height": 960
  },
  "predictions": [
    {
      "detection_id": "part-001",
      "class_id": 0,
      "class_name": "Front bumper",
      "confidence": 0.9612,
      "bbox": {
        "format": "xyxy",
        "coordinates": [420, 280, 1230, 810]
      },
      "segmentation": null
    }
  ]
}
```

### 손상 모델 — Segmentation

부품 모델과 최상위 JSON 구조는 같고, 모델 정보·클래스와 `segmentation` 값이 다르다.

```json
{
  "model": {
    "name": "vehicle-damage-segmentation",
    "version": "1.0.0",
    "task": "segment"
  },
  "image": {
    "image_id": "image-001",
    "original_width": 1280,
    "original_height": 960
  },
  "predictions": [
    {
      "detection_id": "damage-001",
      "class_id": 0,
      "class_name": "Scratched",
      "confidence": 0.9321,
      "bbox": {
        "format": "xyxy",
        "coordinates": [520, 600, 1080, 760]
      },
      "segmentation": {
        "format": "polygon",
        "polygons": [
          [
            [520, 640],
            [800, 600],
            [1080, 690],
            [760, 760]
          ]
        ]
      }
    }
  ]
}
```
