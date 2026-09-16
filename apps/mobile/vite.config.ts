import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * uni-app 的 vite 配置。
 * H5 开发时把 /api 代理到本地 FastAPI（services/api），避免跨域。
 * App / 鸿蒙端不走代理，由 src/api/request.ts 里的 BASE_URL 决定。
 */
export default defineConfig({
  plugins: [uni()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true
      },
      '/static-uploads': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true
      }
    }
  }
})
