# 로컬 AI 견적 산출 이미지 선별 가이드

이 문서는 후보 이미지 중 실제 AI 파이프라인에서 **견적 금액까지 생성되는 이미지**를 골라내기 위한 로컬 실행 절차다.

검증 기준은 AI 콜백 JSON의 다음 값이다.

```json
{
  "estimable": true,
  "totals": { "min": 249782, "median": 281115, "max": 312448 },
  "items": [{ "partCode": "FRONT_BUMPER", "itemTotal": 281115 }]
}
```

`estimable: false` 또는 `totals: null`이면 해당 이미지는 후보에서 제외한다. 특히 `INSUFFICIENT_CASES`는 검출에는 성공했지만 비용 정책을 통과한 유사 사례가 부족하다는 뜻이다.

> 이 가이드는 `develop`의 실제 경로인 `inference → search → estimate → callback`을 사용한다. `ANALYSIS_PROFILE=mock`은 YOLO 검출과 콜백만 확인하고 견적을 만들지 않으므로 이미지 선별에 사용하면 안 된다.

## 1. 준비물

- Docker Desktop: PostgreSQL·Redis 실행용
- Python 3.10 이상
- AI 가중치
  - `AI/models/damage/damage_best-60ep.pt`
  - `AI/models/part/damage_part_best-35ep.pt`
- 로컬 DB에 search corpus·embedding·repair case 비용 데이터가 적재되어 있어야 함

프로젝트 루트는 아래로 가정한다.

```text
develop/S15P21A307
```

## 2. 최신 실제 오케스트레이션 코드 확인

원격 `develop`에는 AI 오케스트레이션이 병합되어 있다. 시작 전에 최신 상태를 받아온다.

```bash
git fetch origin
git switch develop
git pull --ff-only origin develop
```

최소 유효 비용 사례 수를 2건으로 완화하는 변경은 별도 MR에 있다. 그 변경을 병합하기 전에는 기본 최소값이 3건이라, 같은 이미지가 `INSUFFICIENT_CASES`가 될 수 있다.

## 3. DB·Redis 실행

프로젝트 루트에서 실행한다.

```bash
docker compose up -d db redis
```

포트 확인:

```powershell
Test-NetConnection localhost -Port 5432
Test-NetConnection localhost -Port 6379
```

둘 다 `TcpTestSucceeded : True`여야 한다. 기본 로컬 DB 정보는 다음과 같다.

```text
DATABASE_URL=postgresql://a307:ssafy@127.0.0.1:5432/a307
```

## 4. 후보 이미지 정적 서버 실행

AI 서버는 이미지 파일 경로(`C:\...`)를 직접 읽지 않고 HTTP URL을 다운로드한다. 따라서 테스트 이미지는 HTTP로 노출해야 한다.

기본 예시 이미지 `frontend/public/assets/guide-good.jpg`를 쓰려면 새 터미널에서 실행한다.

```powershell
python -m http.server 5500 --bind 127.0.0.1 --directory frontend/public
```

이미지 URL은 다음 형식이다.

```text
http://localhost:5500/assets/guide-good.jpg
```

새 후보 이미지는 `frontend/public/assets/`에 넣고 같은 방식으로 URL을 만든다. 예: `candidate-01.jpg` → `http://localhost:5500/assets/candidate-01.jpg`.

## 5. AI 서버 실행 — 반드시 production

최초 1회라면 Python 가상환경과 의존성을 설치한다.

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r AI/requirements.txt -r AI/server/requirements.txt
```

새 PowerShell에서 다음을 실행한다.

```powershell
$env:AI_INTERNAL_TOKEN = "local-token"
$env:ANALYSIS_PROFILE = "production"
$env:FEATURE_PIPELINE_VERSION_ID = "1"
$env:DATABASE_URL = "postgresql://a307:ssafy@127.0.0.1:5432/a307"
.\.venv\Scripts\python -m uvicorn app.main:app --app-dir AI/server --host 127.0.0.1 --port 8000
```

정상 기동 확인:

```powershell
Invoke-RestMethod http://localhost:8000/health | ConvertTo-Json
```

필수 확인값:

```json
{
  "status": "ok",
  "configured": true,
  "analysisProfile": "production",
  "dbReachable": true
}
```

`analysisProfile`이 `mock`이면 견적은 나오지 않는다.

## 6. 이미지 선별용 로컬 콜백 수신기

후보 이미지를 빠르게 10장 정도 판별할 때는 **Spring Boot와 Java가 필요 없다**. 아래 수신기는 Postman의 요청 확인과 같은 용도로 AI 콜백을 받아 JSON 파일에 추가 기록하고 `200`을 응답한다.

프로젝트 루트에 `callback_receiver.py`를 만들고 아래 내용을 넣는다.

```python
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

LOG_PATH = Path("callback-results.log")

class CallbackReceiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", "replace")
        with LOG_PATH.open("a", encoding="utf-8") as log:
            log.write(f"[{datetime.now().isoformat(timespec='seconds')}] POST {self.path}\n")
            log.write(body + "\n\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"received":true}')

    def log_message(self, format, *args):
        return

HTTPServer(("127.0.0.1", 8080), CallbackReceiver).serve_forever()
```

새 터미널에서 실행한다.

```powershell
.\.venv\Scripts\python callback_receiver.py
```

그 다음 후보 하나당 아래 요청을 보낸다. `imageId`, `requestId`, 이미지 파일명은 매번 바꾼다.

```bash
curl -X POST http://localhost:8000/analyze \
  -H "X-Internal-Token: local-token" \
  -H "Content-Type: application/json" \
  -d '{"jobId":101,"requestId":"candidate-01","vehicle":{"modelId":1,"carClass":"Compact"},"images":[{"imageId":101,"url":"http://localhost:5500/assets/candidate-01.jpg"}],"callbackUrl":"http://localhost:8080/internal/analysis-jobs/101/result"}'
```

즉시 응답은 아래처럼 접수 확인만 준다.

```json
{"accepted":true,"jobId":101,"requestId":"candidate-01"}
```

분석이 끝난 뒤 `callback-results.log`의 해당 `requestId`를 확인한다.

| 결과 | 이미지 선별 판단 |
| --- | --- |
| `estimable: true`, `totals` 존재 | 채택 후보 |
| `NO_DAMAGE_DETECTED` | 손상 검출 실패, 제외 |
| `PART_NOT_RESOLVED` | 부품 매칭 실패, 제외 |
| `INSUFFICIENT_CASES` | 유사 사례 또는 유효 비용 사례 부족, 제외 |
| `error` 존재 | 서버·데이터 상태를 먼저 해결 |

## 7. 이미지 10장 선별 체크리스트

후보마다 다음 표를 채운다.

| 파일 | requestId | 검출 부품/손상 | estimable | 중앙값 | 참조 사례 수 | 비고 |
| --- | --- | --- | --- | ---: | ---: | --- |
| `candidate-01.jpg` | `candidate-01` | 예: FRONT_BUMPER / Scratched | true | 281,115원 | 2 | 채택 |
| `candidate-02.jpg` | `candidate-02` |  | false | - | 0 | `INSUFFICIENT_CASES` |

최종 전달물은 `estimable: true`이고 `totals.median`이 존재하는 이미지 약 10장과 이 표다. 콜백 원문도 함께 보관하면 검출 부위·손상·참조 사례를 나중에 재검증할 수 있다.

## 8. 자주 발생하는 문제

| 증상 | 원인 및 조치 |
| --- | --- |
| `PIPELINE_NOT_CONFIGURED` | AI 서버 기동 터미널에 `FEATURE_PIPELINE_VERSION_ID=1`을 설정 |
| `ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED` | `ANALYSIS_PROFILE=production` 및 최신 `develop` 코드 확인 |
| 이미지 다운로드 실패 | `5500` 서버 실행 여부와 이미지 URL 확인. Markdown 대괄호(`[]()`)를 URL에 넣지 않음 |
| `configured: false`, `dbReachable: false` | `DATABASE_URL`, Docker DB, corpus 적재 상태 확인 |
| `estimable: false / INSUFFICIENT_CASES` | 이미지 자체가 실패한 것은 아님. 검출 조건에 맞는 유사 사례·유효 비용 행이 최소 표본보다 부족한 경우이므로 다른 후보를 사용 |
