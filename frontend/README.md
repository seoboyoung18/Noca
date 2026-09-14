# NOCA (노카) — 프론트엔드

사진 몇 장으로 차량 파손 부위와 예상 수리비를 확인하는 모바일 웹앱의 Vue 3 프론트엔드입니다.

- UI: `NOCA_UI/Noka Mockup.dc.html` (Claude Design) 기준
- 버튼 상호작용: `NOCA_Mockup.html` 프로토타입 기준
- 스택: Vite · Vue 3 · Vue Router · Pinia · Pretendard

## 실행

```bash
npm install
npm run dev
```

- PC 브라우저: http://localhost:5173 → 좌측 화면 목록(개발용) + 360×800 폰 프레임
- 모바일(같은 Wi-Fi): 터미널에 표시되는 `Network:` 주소로 접속 → 전체 화면 레이아웃

## 환경 변수

`.env.example` 을 `.env.local` 로 복사해 채웁니다.

| 키 | 용도 |
|---|---|
| `VITE_API_BASE_URL` | 백엔드 주소 (기본 `http://localhost:8080`). 소셜 로그인 진입과 모든 API 호출의 기준 |
| `VITE_KAKAO_JS_KEY` | 카카오 지도 JavaScript 키 (아래 지도 설정 참고) |
| `VITE_AUTH_GUARD` | `off` 로 두면 로그인 가드를 끄고 백엔드 없이 모든 화면을 볼 수 있음 (개발용) |

## 로그인 (카카오 · 구글)

토큰이 아니라 **서버 세션**입니다. 백엔드가 소셜 인증을 처리하고 `SESSION` HttpOnly 쿠키를 내려주며, 이후 모든 API 는 `withCredentials` 로 그 쿠키를 싣습니다 (`src/lib/api.js`).

```
로그인 버튼 → {API}/oauth2/authorization/kakao → 카카오 동의 → {API}/login/oauth2/code/kakao
  ├ 기존 회원  → FE /        (스플래시가 GET /api/auth/me 로 확인 후 홈으로)
  ├ 신규 회원  → FE /signup  (→ /terms 약관 동의 → POST /api/auth/signup → 홈)
  └ 실패       → FE /login?error=access_denied|withdrawn|invalid_response|server_error
```

- 가입 닉네임은 소셜 닉네임(12자 초과 시 절단)을 그대로 보내고, 서버가 거절(400)할 때만 약관 화면에 입력란이 나타납니다.
- 보호 화면은 라우터 가드(`meta.auth`)가 세션을 확인해 비로그인은 `/login`, 가입 대기는 `/terms` 로 보냅니다. 401 · 403 `SIGNUP_REQUIRED` 응답도 `src/main.js` 에서 같은 규칙으로 처리합니다.
- 로그아웃은 마이페이지에서 `POST /api/auth/logout` 을 호출합니다.

백엔드 준비 사항: Redis·PostgreSQL 실행, `KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET` 설정, 카카오 콘솔 Redirect URI 에 `http://localhost:8080/login/oauth2/code/kakao` 등록. `FRONTEND_BASE_URL` 기본값이 `http://localhost:5173` 이라 로컬 CORS 는 추가 설정이 없습니다.

## 카카오 지도 API 설정 (주변 정비소 화면)

1. https://developers.kakao.com → 내 애플리케이션 → 애플리케이션 추가
2. **앱 설정 > 플랫폼 > Web** 에 사이트 도메인 등록: `http://localhost:5173` (휴대폰 테스트 시 `http://<PC IP>:5173` 도 추가)
3. **앱 키 > JavaScript 키** 복사
4. 프로젝트 루트에 `.env.local` 파일 생성:

```bash
VITE_KAKAO_JS_KEY=여기에_JavaScript_키
```

5. `npm run dev` 재실행

키가 없으면 정비소 화면에 설정 안내가 표시됩니다.

### 현재 위치(GPS) 검색이 동작하는 조건

브라우저 Geolocation API는 **보안 컨텍스트(`https` 또는 `localhost`)** 에서만 동작합니다.

| 접속 방식 | 현재 위치 | 비고 |
|---|---|---|
| PC `http://localhost:5173` | 동작 | 브라우저 위치 권한 "허용" 필요 |
| 휴대폰 `http://<PC IP>:5173` | **차단됨** | 앱이 "https 접속이 필요해요" 안내 표시 |
| 휴대폰 `https://<PC IP>:5173` | 동작 | 아래 `dev:https` 로 실행 |

휴대폰에서 GPS를 쓰려면:

```bash
npm run dev:https
```

- 자체 서명 인증서라 휴대폰 브라우저에서 "안전하지 않음" 경고가 뜨면 "고급 → 계속 진행"을 선택합니다.
- 카카오 콘솔의 **JavaScript SDK 도메인**에 `https://localhost:5173`, `https://<PC IP>:5173` 도 추가해야 지도가 뜹니다.

GPS를 쓸 수 없을 때도 "지역 지정"(전국 17개 시·도 → 시·군·구 단위 검색, 목록은 `src/data/regions.js`)과 "지도에서 직접 찾기"(지도를 움직인 뒤 "이 위치에서 검색")로 검색할 수 있습니다. PC에서 위치 권한을 한 번 "차단"했다면 주소창 왼쪽 자물쇠 아이콘 → 위치 → 허용으로 바꾼 뒤 새로고침하세요.

## 구조

```
src/
  assets/styles.css      디자인 토큰(색상·타이포)과 공통 클래스
  components/            AppHeader, BottomSheet, Toast, CheckBox, CheckItem, Avatar, LogoMark, DevSidebar
  router/index.js        화면 목록(screens) — meta.group/code/name으로 사이드바 자동 구성
  stores/app.js          홈 상태, 차량, 업로드 슬롯, 사고 이력, 체크리스트 등 목데이터·액션
  views/                 화면별 컴포넌트 (S00 스플래시 ~ S14 체크리스트)
public/assets            디자인 에셋(PNG)
public/fonts             Pretendard Variable
```

## 화면 흐름

스플래시 → 랜딩 → 로그인 → 약관 동의 → 홈
홈 → 차량 선택 → 촬영 가이드 → 사진 업로드 → 분석 중(자동 진행) → 예상 견적 → 리포트 생성 중 → 리포트(끝까지 읽으면 PDF 활성화)
홈 → 주변 정비소(위치 동의 시트 / 지역 선택) · 정비 체크리스트(탭·체크·편집·추가·다시 생성)
홈 → 마이페이지 → 차량 관리 · 사고 이력(편집/삭제) · 알림 설정 · 계정 관리(회원 탈퇴)
