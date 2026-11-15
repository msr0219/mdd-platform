<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Grid, Refresh, Setting, ArrowRight, Connection, Menu as MenuIcon } from '@element-plus/icons-vue'
import { useMetaStore } from './store'
import { router, syncRoutes } from './router'
import { rpc } from './api'
import MenuTree from './components/MenuTree.vue'
import AccessPanel from './components/AccessPanel.vue'
import { auth, can, logout } from './auth'

const route = useRoute()
const metadata = useMetaStore()
const reloading = ref(false)
const collapsed = ref(false)
const passwordOpen = ref(false)
async function enter() { try { await metadata.refresh(); syncRoutes(metadata.menus); await router.replace('/') } catch (error) { ElMessage.error((error as Error).message) } }
watch(() => auth.user && !auth.user.mustChangePassword ? auth.user.id : null, id => { if (id) void enter() })
async function exit() { await logout(); syncRoutes([]); await router.replace('/login') }
const title = computed(() => route.meta.title || '工作台')
async function reconnect() {
  try {
    await metadata.refresh(); syncRoutes(metadata.menus)
    await router.replace(route.fullPath)
    ElMessage.success('服务已连接')
  } catch (error) { ElMessage.error((error as Error).message) }
}
async function reload() {
  try {
    await ElMessageBox.confirm('将重新读取并校验模型配置，成功后同步菜单和页面。是否继续？', '重载元模型', { confirmButtonText: '开始重载', cancelButtonText: '取消', customClass: 'metadata-reload-confirm' })
    reloading.value = true
    await rpc('meta.reload')
    await metadata.refresh()
    syncRoutes(metadata.menus)
    const resolved = router.resolve(route.fullPath)
    await router.replace(resolved.name === 'not-found' ? '/' : route.fullPath)
    ElMessage.success('元模型已更新，菜单与视图已同步')
  } catch (error) { if (error instanceof Error) ElMessage.error(error.message) }
  finally { reloading.value = false }
}
</script>
<template>
  <AccessPanel v-if="!auth.user || auth.user.mustChangePassword" />
  <div v-else class="app-shell" :class="{ 'sidebar-hidden': collapsed }">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"><span class="brand-symbol">M</span><span>元构<span class="brand-sub">MODEL DRIVEN</span></span></RouterLink>
      <div class="workspace-label">开发工作空间 <span>LOCAL</span></div>
      <el-menu :default-active="route.path" router background-color="transparent" text-color="#a9bec5" active-text-color="#fff" class="nav-menu">
        <el-menu-item index="/"><el-icon><Grid /></el-icon><span>工作台</span></el-menu-item>
        <div class="nav-label">业务应用</div>
        <MenuTree :menus="metadata.menus" />
        <div class="nav-label">平台管理</div>
        <el-menu-item v-if="can('meta:inspect')" index="/platform/models"><el-icon><Setting /></el-icon><span>模型管理</span></el-menu-item>
        <el-menu-item v-if="can('users:manage')" index="/platform/users"><el-icon><Setting /></el-icon><span>用户与角色</span></el-menu-item>
        <el-menu-item v-if="can('users:manage')" index="/platform/service-logs"><el-icon><Connection /></el-icon><span>服务调用日志</span></el-menu-item>
      </el-menu>
      <div class="sidebar-note"><el-icon><Connection /></el-icon><span>配置驱动 · 即时生效<small>本地开发演示环境</small></span></div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumbs"><button class="menu-toggle" aria-label="切换侧栏" @click="collapsed = !collapsed"><el-icon><MenuIcon /></el-icon></button><span>工作空间</span><el-icon><ArrowRight /></el-icon><strong>{{ title }}</strong></div>
        <div class="topbar-actions"><span class="environment"><i />{{ auth.user.name }}</span><el-button v-if="can('meta:reload')" :icon="Refresh" :loading="reloading" @click="reload">重载元模型</el-button><el-button @click="passwordOpen=true">修改密码</el-button><el-button @click="exit">退出</el-button></div>
      </header>
      <main class="page-content">
        <el-alert v-if="metadata.error" :title="metadata.error" type="error" :closable="false" show-icon class="connection-error"><template #default><el-button size="small" @click="reconnect">重新连接</el-button></template></el-alert>
        <RouterView :key="`${route.fullPath}:${metadata.version}`" />
      </main>
      <footer class="page-footer"><span>元构 MDD Platform</span><span>Java 17 / Vue 3 · JSONRPC 2.0</span></footer>
    </div>
  </div>
  <el-dialog v-model="passwordOpen" title="修改密码" width="500px" destroy-on-close><AccessPanel change-only @changed="passwordOpen=false;syncRoutes([]);router.replace('/login')" /></el-dialog>
</template>
