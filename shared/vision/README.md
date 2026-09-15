# 공용 Vision 도메인

`shared/vision`은 offline corpus batch와 online AI 서버가 동일한 판정을 내기 위해
공유하는 정본이다. 표준 부품·손상 코드, 정규화, 부품-손상 geometry 매칭, ROI 생성,
검색 메타데이터 계약, DINOv2 임베딩을 함께 둔다.

`pipeline/standardization`은 기존 batch 명령의 호환 import와 견적서·storage key 같은
파이프라인 전용 기능만 유지한다. AI 서버는 이 폴더를 거치지 않고 `shared.vision`을 직접 import한다.

## 공용 ROI 임베딩

`dinov2.py`는 corpus 배치 적재와 AI 서버 실시간 검색이 함께 쓰는 유일한 DINOv2 구현이다.
둘 모두 같은 모델 revision, `pooler_output`, ImageNet mean/std, L2 정규화를 사용해야
같은 `embedding_model_version` 벡터와 비교할 수 있다.

## 고정 계약

- 모델: `facebook/dinov2-base`
- revision: `f9e44c814b77203eaa57a6bdbbd535f21ede1415`
- 출력: `pooler_output`, 768차원, L2 정규화
- 입력: 같은 폴더의 `roi.py`가 만든 224×224 letterbox ROI
- 금지: Hugging Face `AutoImageProcessor` 재resize·center-crop

## 의존성

일반 데이터 적재에는 무거운 모델 런타임이 필요 없으므로,
`pipeline/requirements.txt`에 PyTorch·Transformers를 넣지 않는다. 임베딩 batch 또는
AI 서버를 실행할 때만 아래를 함께 설치한다.

```powershell
pip install -r AI/requirements.txt -r AI/server/requirements.txt
```

CUDA/CPU에 맞는 PyTorch 빌드는 실행 환경에서 선택한다. 모델·전처리·출력 차원이 바뀌면
기존 벡터와 섞지 않고 새 `embedding_model_version`으로 재임베딩해야 한다.
