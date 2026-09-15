# Shared domain modules

오프라인 데이터 파이프라인과 온라인 AI 서버가 **동일한 결과를 내야 하는 코드만** 둔다.
이 폴더는 별도 서비스·컨테이너가 아니라 두 실행 단위가 의존하는 Python 라이브러리다.

```text
pipeline/  ─┐
            ├─> shared/vision/
AI/server/ ─┘
```

현재는 DINOv2 ROI 임베딩을 공유한다. 이후 ROI geometry·부품-손상 매칭·부품/손상
코드처럼 온라인과 batch가 실제로 함께 써야 하는 규칙만 단계적으로 옮긴다. 원천 적재,
SQL, manifest, FastAPI route, YOLO 실행 adapter처럼 한쪽 실행 주체의 책임은 옮기지 않는다.
