<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search, Box, Document, Connection } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useMetaStore } from '../store'
const metadata = useMetaStore()
const tab = ref('models')
const query = ref('')
const detail = ref('')
const detailName = ref('')
const detailOpen = ref(false)
const rows = computed(() => ((metadata.status?.[tab.value] ?? []) as string[]).filter(code => code.toLowerCase().includes(query.value.toLowerCase())).map(code => ({ code })))
async function inspect(code: string) {
  try {
    const result = tab.value === 'models' ? await metadata.getModel(code) : await metadata.getView(code)
    detail.value = JSON.stringify(result, null, 2); detailName.value = code; detailOpen.value = true
  } catch (error) { ElMessage.error((error as Error).message) }
}
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">METADATA REGISTRY</p><h1>模型管理</h1><p class="page-description">查看已加载的元模型，修改配置文件后通过“重载元模型”发布变更。</p></div><span class="subtle-tag">快照版本 {{ metadata.version }}</span></div>
  <section class="panel"><div class="registry-header"><el-tabs v-model="tab" class="registry-tabs"><el-tab-pane name="models"><template #label><span><el-icon><Box /></el-icon> 数据模型 <b>{{ metadata.status?.models.length || 0 }}</b></span></template></el-tab-pane><el-tab-pane name="views"><template #label><span><el-icon><Document /></el-icon> 视图 <b>{{ metadata.status?.views.length || 0 }}</b></span></template></el-tab-pane><el-tab-pane name="services"><template #label><span><el-icon><Connection /></el-icon> 服务 <b>{{ metadata.status?.services.length || 0 }}</b></span></template></el-tab-pane></el-tabs><el-input v-model="query" :prefix-icon="Search" placeholder="搜索编码" clearable class="registry-search" /></div>
    <el-table :data="rows" empty-text="没有匹配的元模型" class="registry-table"><el-table-column label="元模型编码" min-width="220"><template #default="{ row }"><span class="code-label">{{ row.code }}</span></template></el-table-column><el-table-column label="类型" width="160"><template #default>{{ tab === 'models' ? '数据模型' : tab === 'views' ? '动态视图' : '事务服务' }}</template></el-table-column><el-table-column label="注册状态" width="150"><template #default><span class="inline-status"><i />已加载</span></template></el-table-column><el-table-column label="配置目录" min-width="200"><template #default><span class="mono muted">meta/{{ tab }}/</span></template></el-table-column><el-table-column label="操作" width="120"><template #default="{ row }"><el-button v-if="tab !== 'services'" link type="primary" @click="inspect(row.code)">查看定义</el-button><span v-else class="muted">JSON 配置</span></template></el-table-column></el-table>
    <div class="panel-bottom">元模型校验失败时保留旧快照；表结构自动同步只执行建表和追加字段。</div>
  </section>
  <el-dialog v-model="detailOpen" :title="`元模型定义 · ${detailName}`" width="min(760px, 94vw)"><pre class="json-preview">{{ detail }}</pre></el-dialog>
</template>
