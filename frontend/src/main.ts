import './assets/main.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import router from './router'
import { useColorScheme } from './composables/useColorScheme'
import { vLoading } from './directives/loading'

// 初始化主题（跟随系统 + 用户覆盖），并挂载跨标签页同步监听
useColorScheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
// Nuxt UI：注册组件、指令与 Toast/Tooltip/Overlay 等全局能力
app.use(ui)
app.directive('loading', vLoading)

app.mount('#app')
