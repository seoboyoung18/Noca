# A307 AI 서버 이미지
#
# 빌드 컨텍스트는 **저장소 루트**다. backend/Dockerfile 과 같은 규칙이다.
#   docker build -t a307-ai -f AI/server/Dockerfile .
#
# ── 경로를 그대로 옮기는 이유 ──────────────────────────────────
#   app/core/bootstrap.py 가 REPO_ROOT = parents[4] 로 저장소 루트를 찾아 sys.path 에
#   넣는다(shared.vision 임포트용). app/core/config.py 는 AI_ROOT = parents[3] 로
#   AI/ 를 찾아 모델 가중치 기본 경로를 만든다.
#   그래서 컨테이너 안에서도 <루트>/AI/server/app/... 과 <루트>/shared/... 구조를
#   유지해야 한다. 평평하게 복사하면 둘 다 깨진다.
#
# ── torch 를 CPU 판으로 바꾼 이유 ─────────────────────────────
#   AI/requirements.txt 는 torch==2.6.0+cu124 · torchvision==0.21.0+cu124 다.
#   배포 대상 Lightsail 인스턴스에는 GPU 가 없다. CUDA 휠을 넣으면 이미지가 6GB 를
#   넘는데 실제로는 CPU 로 돈다 — dinov2.py 가
#     self._device = "cuda" if torch.cuda.is_available() else "cpu"
#   로 알아서 내려간다. 그래서 같은 버전의 CPU 휠을 쓴다. 결과는 같고 느릴 뿐이다.
#   ※ GPU 인스턴스로 옮기면 이 블록을 되돌리고 --index-url 을 cu124 로 바꾼다.

# ─────────────────────────────────────────────────────────────
# 1단계 — 의존성 설치
# ─────────────────────────────────────────────────────────────
FROM python:3.12-slim AS deps

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# opencv-python 이 libGL 과 glib 를 링크한다. 없으면 import 에서 죽는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 요구사항 파일만 먼저 넣는다. 코드가 바뀌어도 이 레이어는 캐시된다.
COPY AI/requirements.txt        /tmp/ai-requirements.txt
COPY AI/server/requirements.txt /tmp/server-requirements.txt

# torch·torchvision 은 CPU 판으로 따로 깐다(위 주석). 나머지에서 두 줄을 빼고 설치한다.
RUN pip install --index-url https://download.pytorch.org/whl/cpu \
        torch==2.6.0 torchvision==0.21.0 \
 && grep -vE '^(torch|torchvision)==' /tmp/ai-requirements.txt > /tmp/ai-rest.txt \
 && pip install -r /tmp/ai-rest.txt -r /tmp/server-requirements.txt

# ─────────────────────────────────────────────────────────────
# 2단계 — DINOv2 가중치를 이미지에 굽는다
# ─────────────────────────────────────────────────────────────
# 런타임에 Hugging Face 에서 받게 두면 첫 요청이 느리고, 네트워크가 막히면 아예 죽는다.
# config.py 의 기본 revision 과 같은 값을 박는다. 바꾸려면 두 곳을 같이 고쳐야 한다.
FROM deps AS models
ENV PATH="/opt/venv/bin:$PATH" \
    HF_HOME=/opt/hf
RUN python -c "\
from transformers import AutoModel; \
AutoModel.from_pretrained('facebook/dinov2-base', revision='f9e44c814b77203eaa57a6bdbbd535f21ede1415')"

# ─────────────────────────────────────────────────────────────
# 3단계 — 런타임
# ─────────────────────────────────────────────────────────────
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 curl \
 && rm -rf /var/lib/apt/lists/*

# 백엔드 이미지와 같은 규칙 — 루트로 돌지 않는다.
RUN useradd --system --uid 1001 --create-home a307

COPY --from=deps   /opt/venv /opt/venv
COPY --from=models /opt/hf   /opt/hf

ENV PATH="/opt/venv/bin:$PATH" \
    HF_HOME=/opt/hf \
    HF_HUB_OFFLINE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# 변하는 빈도가 낮은 것부터 COPY 한다. 위 레이어가 캐시된다.
COPY --chown=a307:a307 AI/models/damage/ ./AI/models/damage/
COPY --chown=a307:a307 AI/models/part/   ./AI/models/part/
COPY --chown=a307:a307 shared/           ./shared/
COPY --chown=a307:a307 AI/server/app/    ./AI/server/app/

USER a307
EXPOSE 8000

# /health 는 토큰 없이 열려 있다. 컨테이너가 살아 있는지만 본다 —
# modelsLoaded 는 첫 추론 전까지 false 이므로 기동 판정에 쓰면 안 된다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf http://127.0.0.1:8000/health || exit 1

# --reload 를 쓰지 않는다. README 의 개발용 명령과 다른 점이다.
# 워커를 늘리지 않는 이유 — 모델이 프로세스마다 메모리에 올라가고
# MAX_INFERENCE_CONCURRENCY 기본값이 1 이다.
CMD ["uvicorn", "app.main:app", "--app-dir", "AI/server", "--host", "0.0.0.0", "--port", "8000"]
