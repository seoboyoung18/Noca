import { createRouter, createWebHistory } from 'vue-router'

const v = (name) => () => import(`../views/${name}.vue`)

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
]

const router = createRouter({
  history: createWebHistory(),
  routes: [...screens, { path: '/:pathMatch(.*)*', redirect: '/' }],
})

export default router
