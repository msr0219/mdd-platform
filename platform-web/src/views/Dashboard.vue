<script setup lang="ts">
import { computed } from 'vue'
import { Box, Connection, Document, ArrowRight, CircleCheck, FolderOpened } from '@element-plus/icons-vue'
import { useMetaStore } from '../store'
import type { Menu } from '../types'
import AggregatePanel from '../components/AggregatePanel.vue'
const metadata = useMetaStore()
const apps = computed(() => {
  const rows: { name: string; path: string; group: string }[] = []
  const walk = (menus: Menu[], parent = '', group = '') => menus.forEach(menu => {
    const path = `${parent}/${menu.code}`
    if (menu.view) rows.push({ name: menu.name, path, group: group || '业务应用' })
    if (menu.children) walk(menu.children, path, menu.name)
  })
  walk(metadata.menus)
  return rows
})
const stats = computed(() => [
  { label: '已注册模型', value: metadata.status?.models.length ?? '—', icon: Box, hint: '数据结构与约束', color: 'teal' },
  { label: '动态视图', value: metadata.status?.views.length ?? '—', icon: Document, hint: '列表、表单自动渲染', color: 'blue' },
  { label: '自定义服务', value: metadata.status?.services.length ?? '—', icon: Connection, hint: '事务与业务步骤编排', color: 'amber' },
  { label: '业务入口', value: apps.value.length, icon: FolderOpened, hint: '菜单自动生成路由', color: 'purple' },
])
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">WORKSPACE OVERVIEW</p><h1>让业务，从模型开始。</h1><p class="page-description">用一份配置连接数据、服务与界面，专注于业务本身。</p></div><span class="status-badge"><i />{{ metadata.error ? '等待服务连接' : '平台运行中' }}</span></div>
  <section class="stat-grid"><article v-for="stat in stats" :key="stat.label" class="stat-card"><div class="stat-top"><span>{{ stat.label }}</span><span class="stat-icon" :class="stat.color"><el-icon><component :is="stat.icon" /></el-icon></span></div><strong>{{ stat.value }}</strong><small>{{ stat.hint }}</small></article></section>
  <section class="dashboard-grid">
    <div class="panel application-panel"><div class="panel-heading"><div><h2>业务应用</h2><p>由菜单元模型动态装配</p></div><span class="muted">{{ apps.length }} 个入口</span></div><div class="app-grid"><RouterLink v-for="(app, index) in apps" :key="app.path" :to="app.path" class="app-card"><span class="app-card-icon" :class="['teal', 'blue', 'amber', 'purple'][index % 4]"><el-icon><Box /></el-icon></span><div><strong>{{ app.name }}</strong><small>{{ app.group }}</small></div><el-icon class="app-arrow"><ArrowRight /></el-icon></RouterLink></div><el-empty v-if="!apps.length" description="暂无业务入口，请先配置菜单并重载" :image-size="80" /></div>
    <div class="panel runtime-panel"><div class="panel-heading"><div><h2>运行信息</h2><p>当前工作空间状态</p></div><el-icon class="success-text"><CircleCheck /></el-icon></div><dl class="runtime-list"><div><dt>运行模式</dt><dd>单体 · 本地演示</dd></div><div><dt>接口协议</dt><dd>JSONRPC 2.0</dd></div><div><dt>元模型版本</dt><dd class="mono">{{ metadata.version || '—' }}</dd></div><div><dt>重载策略</dt><dd>原子快照切换</dd></div></dl><RouterLink class="text-link" to="/platform/models">查看运行配置 <el-icon><ArrowRight /></el-icon></RouterLink></div>
  </section>
  <section class="panel workflow-panel"><div class="panel-heading"><div><h2>从配置到应用</h2><p>业务变化，通过元模型表达</p></div><span class="subtle-tag">MODEL DRIVEN DEVELOPMENT</span></div><div class="workflow"><div v-for="(step, index) in [{ name: '定义模型', text: '描述字段、约束与关联' }, { name: '配置视图', text: '声明列表、搜索和表单' }, { name: '编排服务', text: '组合动作与事务步骤' }, { name: '重载生效', text: '即时更新接口和页面' }]" :key="step.name" class="workflow-step"><span>{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ step.name }}</strong><p>{{ step.text }}</p></div><el-icon v-if="index < 3"><ArrowRight /></el-icon></div></div></section>
  <AggregatePanel />
  <div class="development-note">当前为本地开发演示，已启用账号与角色权限。管理员负责分配账号；对外部署仍需 HTTPS 和数据库备份。</div>
</template>
