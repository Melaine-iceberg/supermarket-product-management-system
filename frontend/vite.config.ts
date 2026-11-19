import { fileURLToPath, URL } from 'node:url'
import ui from '@nuxt/ui/vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { defineConfig } from 'vite'
import { DYNAMIC_ICON_NAMES } from './src/utils/icons.ts'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Nuxt UI：Vue（Vite）版接入，自动注册 U* 组件与组合式函数
    ui({
      // 主题切换由 src/composables/useColorScheme.ts 统一管理（跟随系统 + 用户覆盖）
      colorMode: false,
      ui: {
        colors: {
          primary: 'blue',
          neutral: 'slate',
        },
      },
      icon: {
        // 构建期扫描源码中的 i-lucide-* 并打包，离线/PWA 下图标仍可用
        clientBundle: { scan: true, icons: DYNAMIC_ICON_NAMES },
      },
    }),
    // PWA：预缓存构建产物（应用外壳），接口请求始终走网络
    VitePWA({
      // prompt：新版本就绪后由应用内 UI 询问用户是否立即刷新
      registerType: 'prompt',
      injectRegister: 'auto',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        id: '/',
        name: '超市商品管理系统',
        short_name: '超市管理',
        description: '超市商品、库存、采购、会员与报表一体化管理系统',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'any',
        // 与 Nuxt UI 主色 / 页面底色保持一致，避免启动时闪白
        theme_color: '#2563eb',
        background_color: '#f8fafc',
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          {
            src: '/pwa-maskable-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}'],
        // echarts 等依赖体积较大，放宽单文件预缓存上限
        maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
        cleanupOutdatedCaches: true,
        // SPA history 路由：离线时回退到 index.html
        navigateFallback: '/index.html',
        // 接口不能回退到 index.html，也不做缓存，避免展示过期业务数据
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [
          {
            urlPattern: ({ url }: { url: URL }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkOnly',
          },
        ],
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
