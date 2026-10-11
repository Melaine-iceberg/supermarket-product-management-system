import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite-plus'

/**
 * 前端单测配置（Vitest）。
 * 仅覆盖不依赖真实浏览器的纯逻辑（如路由守卫判定），不启动 Vue 应用。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    globals: false,
  },
})
