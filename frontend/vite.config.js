import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import basicSsl from '@vitejs/plugin-basic-ssl'
import { fileURLToPath, URL } from 'node:url'

// `npm run dev:https` (mode=https) 로 실행하면 자체 서명 인증서로 https 개발 서버를 띄운다.
// 휴대폰에서 LAN IP 로 접속할 때 현재 위치(Geolocation)를 쓰려면 https 가 필요하다.
export default defineConfig(({ mode }) => ({
  plugins: [vue(), ...(mode === 'https' ? [basicSsl()] : [])],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: { port: 5173 },
}))
