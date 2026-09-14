import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { onAuthError } from './lib/api'
import { useAuthStore } from './stores/auth'
import './assets/styles.css'

const app = createApp(App).use(createPinia()).use(router)

// 보호 API 가 401(세션 만료·비로그인) 또는 403 SIGNUP_REQUIRED(가입 미완료)를 돌려주면 화면을 옮긴다.
// 공개 화면(스플래시 등)에서 세션을 확인하다 난 401 은 정상 흐름이므로 옮기지 않는다.
onAuthError((err) => {
  const auth = useAuthStore()
  const route = router.currentRoute.value
  if (err.status === 401) {
    auth.clear()
    if (route.meta.auth) { auth.rememberNext(route.fullPath); router.replace('/login') }
  } else {
    auth.setPending()
    if (route.path !== '/terms') router.replace('/terms')
  }
})

app.mount('#app')
