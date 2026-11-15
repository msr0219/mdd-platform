import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import { router, syncRoutes } from './router'
import { useMetaStore } from './store'
import { auth, restoreSession, setUser } from './auth'

async function bootstrap() {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia).use(ElementPlus, { locale: zhCn })
  const metadata = useMetaStore(pinia)
  await restoreSession()
  try { if (auth.user && !auth.user.mustChangePassword) { await metadata.refresh(); syncRoutes(metadata.menus) } }
  catch { /* The app displays a retryable connection error. */ }
  app.use(router)
  await router.isReady()
  app.mount('#app')
  window.addEventListener('mdd:unauthorized', () => { setUser(null); syncRoutes([]); void router.replace('/login') })
}
void bootstrap()
