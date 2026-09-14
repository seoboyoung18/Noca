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
