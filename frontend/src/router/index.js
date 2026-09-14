import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const v = (name) => () => import(`../views/${name}.vue`)

// 로그인 없이 볼 수 있는 화면. 그 밖의 모든 화면은 서버 세션(ROLE_USER)이 필요하다 —
// 가이드 2종을 제외한 모든 백엔드 API 가 로그인 필수이기 때문이다.
const PUBLIC_PATHS = new Set(['/', '/landing', '/login', '/terms'])

// 백엔드 없이 화면만 볼 때(프로토타입 확인) .env.local 에 VITE_AUTH_GUARD=off 를 두면 가드를 건너뛴다
export const AUTH_GUARD_OFF = import.meta.env.VITE_AUTH_GUARD === 'off'

export const screens = [
  { path: '/',                     component: v('SplashView'),              meta: { group: '진입',      code: 'S00',  name: '스플래시' } },
  { path: '/landing',              component: v('LandingView'),             meta: { group: '진입',      code: 'S01',  name: '랜딩' } },
  { path: '/login',                component: v('LoginView'),               meta: { group: '진입',      code: 'S02',  name: '로그인' } },
  { path: '/terms',                component: v('TermsView'),               meta: { group: '진입',      code: 'S02a', name: '약관 동의' } },
  { path: '/home',                 component: v('HomeView'),                meta: { group: '홈·차량',   code: 'S03',  name: '홈' } },
  { path: '/claim/vehicle',        component: v('ClaimVehicleView'),        meta: { group: '홈·차량',   code: 'S04',  name: '차량 선택' } },
  { path: '/vehicles/new',         component: v('VehicleNewView'),          meta: { group: '홈·차량',   code: 'S04a', name: '차량 등록' } },
  { path: '/claim/guide',          component: v('GuideView'),               meta: { group: '사고 접수', code: 'S05',  name: '촬영 가이드' } },
  { path: '/claim/upload',         component: v('UploadView'),              meta: { group: '사고 접수', code: 'S06',  name: '사진 업로드' } },
  { path: '/claim/analyzing',      component: v('AnalyzingView'),           meta: { group: '사고 접수', code: 'S07',  name: '분석 중' } },
  { path: '/estimate',             component: v('EstimateView'),            meta: { group: '결과',      code: 'S07b', name: '예상 견적' } },
  { path: '/report/generating',    component: v('ReportGeneratingView'),    meta: { group: '결과',      code: 'S08a', name: '리포트 생성 중' } },
  { path: '/report',               component: v('ReportView'),              meta: { group: '결과',      code: 'S08',  name: '리포트' } },
  { path: '/shops',                component: v('ShopsView'),               meta: { group: '정비 준비', code: 'S13',  name: '주변 정비소' } },
  { path: '/shops/region',         component: v('RegionView'),              meta: { group: '정비 준비', code: 'S13b', name: '지역 선택' } },
  { path: '/checklist/generating', component: v('ChecklistGeneratingView'), meta: { group: '정비 준비', code: 'S14a', name: '체크리스트 생성 중' } },
  { path: '/checklist',            component: v('ChecklistView'),           meta: { group: '정비 준비', code: 'S14b', name: '정비 체크리스트' } },
  { path: '/checklists',           component: v('ChecklistsView'),          meta: { group: '정비 준비', code: 'S14c', name: '나의 체크리스트' } },
  { path: '/history',              component: v('HistoryView'),             meta: { group: '마이',      code: 'S12d', name: '사고 이력' } },
  { path: '/my',                   component: v('MyPageView'),              meta: { group: '마이',      code: 'S12',  name: '마이페이지' } },
  { path: '/my/vehicles',          component: v('VehiclesView'),            meta: { group: '마이',      code: 'S12a', name: '차량 관리' } },
  { path: '/my/notifications',     component: v('NotificationsView'),       meta: { group: '마이',      code: 'S12b', name: '알림 설정' } },
  { path: '/my/account',           component: v('AccountView'),             meta: { group: '마이',      code: 'S12e', name: '계정 관리' } },
].map((s) => ({ ...s, meta: { ...s.meta, auth: !PUBLIC_PATHS.has(s.path) } }))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...screens,
    // 서버(OAuth2SuccessHandler)가 신규 회원을 FE '/signup' 으로 보낸다. 화면은 기존 약관 동의(S02a)를 그대로 쓴다
    { path: '/signup', redirect: '/terms' },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (AUTH_GUARD_OFF) return true
  const auth = useAuthStore()

  if (to.meta.auth) {
    const status = await auth.ensure()
    if (status === 'member') return true
    if (status === 'pending') return { path: '/terms' }
    auth.rememberNext(to.fullPath)
    return { path: '/login' }
  }

  // 이미 로그인된 세션이 로그인·약관 화면으로 오면 홈으로
  if (to.path === '/login' || to.path === '/terms') {
    if ((await auth.ensure()) === 'member') return { path: '/home' }
  }
  return true
})

export default router
