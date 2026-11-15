<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Download, ArrowLeft } from '@element-plus/icons-vue'
import { useMetaStore } from '../store'
import { rpc } from '../api'
import { mapParams, visible } from '../conditions'
import type { Action, Field, Model, Row, View } from '../types'
import FieldWidget from '../components/FieldWidget.vue'
import FormRenderer from '../components/FormRenderer.vue'
import ServiceForm from '../components/ServiceForm.vue'
import AnalyticsPanel from '../components/AnalyticsPanel.vue'
import MetadataChart from '../components/MetadataChart.vue'
import RecordDetail from '../components/RecordDetail.vue'

const route = useRoute()
const router = useRouter()
const metadata = useMetaStore()
const view = ref<View>()
const model = ref<Model>()
const rows = ref<Row[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const sort = ref<{ sortField?: string; order?: string }>({})
const search = ref<Row>({})
const loading = ref(false)
const pageLoading = ref(true)
const error = ref('')
const chartRefresh = ref(0), chartFilters = ref<unknown[]>([])
const busy = ref('')
const formOpen = ref(false)
const serviceOpen = ref(false), serviceCode = ref(''), serviceInitial = ref<Row>({})
const formView = ref<View>()
const formModel = ref<Model>()
const editingId = ref<string | number>()
const detail = ref<Row>()
const requests = new Map<string, string>()
const detailId = computed(() => typeof route.params.id === 'string' ? route.params.id : undefined)
const buttons = computed(() => view.value?.table?.buttons || [])
const toolbarButtons = computed(() => buttons.value.filter(button => !button.rowSelected && visible(button.when)))
const rowButtons = computed(() => buttons.value.filter(button => button.rowSelected))
const columns = computed<{ field: string; title?: string; width?: number }[]>(() => view.value?.table?.columns || model.value?.fields.filter(field => field.listable !== false && !field.hidden).map(field => ({ field: field.code, title: field.name })) || [])
const fieldFor = (code: string): Field => model.value?.fields.find(field => field.code === code) || { code, name: code, type: 'string' }
const activeSearchCount = computed(() => Object.values(search.value).filter(value => value !== '' && value !== null && value !== undefined).length)
const filters = () => (view.value?.search?.fields || []).filter(config => search.value[config.field] !== undefined && search.value[config.field] !== null && search.value[config.field] !== '').map(config => ({ field: config.field, op: config.op || 'eq', value: search.value[config.field] }))

async function initialize() {
  pageLoading.value = true; error.value = ''
  try {
    view.value = await metadata.getView(String(route.meta.viewCode))
    model.value = await metadata.getModel(view.value.model)
    pageSize.value = view.value.table?.pagination?.pageSize || 20
    if (detailId.value) {
      const id = model.value.model.idType === 'uuid' ? detailId.value : Number(detailId.value)
      if (typeof id === 'number' && (!Number.isSafeInteger(id) || id <= 0)) throw new Error('记录编号必须是正整数')
      detail.value = await rpc(`${view.value.model}.read`, { id })
    }
    else if (view.value.type === 'list') await load()
  } catch (err) { error.value = (err as Error).message }
  finally { pageLoading.value = false }
}
onMounted(initialize)
async function load() {
  if (!view.value) return
  loading.value = true; error.value = ''
  try {
    const result = await rpc<{ total: number; records: Row[] }>(`${view.value.model}.list`, { page: page.value, pageSize: pageSize.value, ...sort.value, filters: filters() })
    rows.value = result.records; total.value = result.total
    chartFilters.value = filters(); chartRefresh.value++
  } catch (err) { error.value = (err as Error).message }
  finally { loading.value = false }
}
function query() { page.value = 1; void load() }
function reset() { search.value = {}; query() }
function sortChange({ prop, order }: { prop: string; order: string | null }) {
  sort.value = order ? { sortField: prop, order: order === 'ascending' ? 'asc' : 'desc' } : {}
  query()
}
function format(value: unknown, field: Field) {
  if (value === null || value === undefined || value === '') return '—'
  if (field.type === 'enum') return field.options?.find(option => option.value === value)?.label || String(value)
  if (field.type === 'boolean') return value ? '是' : '否'
  if (field.type === 'datetime') return String(value).replace('T', ' ').slice(0, 19)
  if (field.type === 'date') return String(value).slice(0, 10)
  return String(value)
}
function tagType(value: unknown): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  const text = String(value).toUpperCase()
  if (['GOOD', 'PASS', 'CLOSED', 'COMPLETED', 'DONE', 'APPROVED'].includes(text)) return 'success'
  if (['BAD', 'DEFECTIVE', 'FAIL', 'SCRAP', 'REJECTED'].includes(text)) return 'danger'
  if (['PENDING', 'QUARANTINE', 'REWORK', 'WAITING', 'RECHECK'].includes(text)) return 'warning'
  return 'info'
}
async function execute(button: Action, row: Row = {}) {
  if (busy.value) return
  const key = `${button.code}:${row.id || 'toolbar'}`
  try {
    if (button.action.startsWith('openForm:')) {
      busy.value = key
      formView.value = await metadata.getView(button.action.slice(9))
      formModel.value = await metadata.getModel(formView.value.model)
      editingId.value = button.rowSelected ? row.id : undefined
      formOpen.value = true
      return
    }
    if (button.action === 'refresh') { await load(); return }
    if (!button.action.startsWith('service:')) throw new Error(`暂不支持动作 ${button.action}`)
    if (button.parameters) {
      serviceCode.value = button.action.slice(8).replace(/^service\./,'')
      serviceInitial.value = mapParams(button.map || {}, { row, search:search.value, form:{} }, crypto.randomUUID())
      serviceOpen.value = true; return
    }
    const destructive = button.action.endsWith('.delete') || button.action.endsWith('.batchDelete')
    if (button.confirm || destructive) await ElMessageBox.confirm(button.confirm || '确定删除此记录吗？', '操作确认', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' })
    busy.value = key
    let method = button.action.slice(8)
    if (!method.includes('.')) method = `service.${method}`
    const signature = `${method}:${JSON.stringify(row)}:${JSON.stringify(search.value)}`
    if (!requests.has(signature)) requests.set(signature, crypto.randomUUID())
    const payload = button.map || button.params ? mapParams(button.map || button.params || {}, { row, search: search.value, form: {} }, requests.get(signature)!) : button.rowSelected ? { id: row.id, version: row.version } : {}
    const result = await rpc(method, payload)
    requests.delete(signature)
    if (method.endsWith('.related') || method.endsWith('.history') || method.endsWith('.aggregate')) {
      inspectResult.value = result; inspectOpen.value = true; inspectTitle.value = button.name
    } else ElMessage.success(button.success?.message || '操作成功')
    if (button.success?.refresh !== false) await load()
  } catch (err) { if (err instanceof Error) ElMessage.error(err.message) }
  finally { busy.value = '' }
}
const inspectResult = ref<any>()
const inspectOpen = ref(false)
const inspectTitle = ref('查询结果')
function openDetail(row: Row) { void router.push(`${route.path}/${encodeURIComponent(String(row.id))}`) }
const resultRows = computed<Row[]>(() => Array.isArray(inspectResult.value) ? inspectResult.value : inspectResult.value?.records || [])
const resultKeys = computed(() => resultRows.value.length ? Object.keys(resultRows.value[0]!) : [])
function exportPage() {
  if (!rows.value.length) return
  const escape = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`
  const text = '\ufeff' + [columns.value.map(column => escape(column.title || fieldFor(column.field).name)).join(','), ...rows.value.map(row => columns.value.map(column => {
    let value = format(row[column.field], fieldFor(column.field))
    if (/^[=+\-@]/.test(value)) value = `'${value}`
    return escape(value)
  }).join(','))].join('\r\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${view.value?.name || '数据'}-当前页.csv`; anchor.click(); URL.revokeObjectURL(url)
}
</script>
<template>
  <div v-loading="pageLoading" class="renderer-root">
    <div v-if="pageLoading" class="loading-space" />
    <template v-if="view && model">
      <div class="page-heading"><div><p class="eyebrow">{{ view.model.replaceAll('_', ' ').toUpperCase() }}</p><h1>{{ view.name }}<span v-if="detailId" class="detail-id">#{{ detailId }}</span></h1><p class="page-description">{{ view.description || model.model.description || `${model.model.name}数据与业务操作` }}</p></div><span class="subtle-tag">配置驱动视图</span></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="connection-error"><template #default><el-button size="small" @click="initialize">重新加载</el-button></template></el-alert>
      <section v-if="detailId && detail" class="panel detail-panel"><el-button :icon="ArrowLeft" @click="$router.push(route.path.replace(/\/[^/]+$/, ''))">返回列表</el-button><RecordDetail :model="model" :record="detail" /></section>
      <section v-else-if="view.type === 'form'" class="panel standalone-form"><FormRenderer :view="view" :model="model" @close="$router.push('/')" /></section>
      <template v-else>
        <AnalyticsPanel v-if="view.analytics" :model="view.model" :definition="view.analytics" :filters="chartFilters" :refresh-key="chartRefresh" />
        <MetadataChart v-for="chart in view.chart || []" :key="chart.code" :model="view.model" :definition="chart" :filters="chartFilters" :refresh-key="chartRefresh" />
        <section v-if="view.search?.fields.length" class="panel search-panel"><div class="section-label">筛选条件<span v-if="activeSearchCount">{{ activeSearchCount }} 项生效</span></div><el-form label-position="top" class="search-form" @submit.prevent="query"><el-form-item v-for="config in view.search.fields" :key="config.field" :label="config.name || fieldFor(config.field).name"><FieldWidget v-model="search[config.field]" :field="fieldFor(config.field)" :widget="config.widget" searching /></el-form-item><div class="search-actions"><el-button type="primary" :icon="Search" :loading="loading" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></div></el-form></section>
        <section class="panel data-panel"><div class="table-toolbar"><div class="table-title"><h2>数据列表</h2><span>{{ total }} 条记录</span></div><div class="table-actions"><el-button v-for="button in toolbarButtons" :key="button.code" :type="button.code === 'create' ? 'primary' : 'default'" :icon="button.code === 'create' ? Plus : undefined" :loading="busy === `${button.code}:toolbar`" :disabled="Boolean(busy)" @click="execute(button)">{{ button.name }}</el-button><el-tooltip content="导出当前页"><el-button :icon="Download" circle aria-label="导出当前页" :disabled="!rows.length" @click="exportPage" /></el-tooltip><el-tooltip content="刷新"><el-button :icon="Refresh" circle aria-label="刷新列表" :loading="loading" @click="load" /></el-tooltip></div></div>
          <el-table v-loading="loading" :data="rows" row-key="id" @sort-change="sortChange" empty-text="暂无数据，请新增记录或调整筛选条件" class="business-table"><el-table-column v-for="column in columns" :key="column.field" :prop="column.field" :label="column.title || fieldFor(column.field).name" :min-width="column.width || 160" :sortable="fieldFor(column.field).listable !== false ? 'custom' : false" show-overflow-tooltip><template #default="{ row }"><el-tag v-if="fieldFor(column.field).type === 'enum'" :type="tagType(row[column.field])" effect="light" size="small">{{ format(row[column.field], fieldFor(column.field)) }}</el-tag><span v-else :class="{ 'mono': /No$|Code$|^sn$|^id$/.test(column.field) }">{{ format(row[column.field], fieldFor(column.field)) }}</span></template></el-table-column><el-table-column label="操作" :min-width="Math.min(560, rowButtons.length * 90 + 110)" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">详情</el-button><template v-for="button in rowButtons" :key="button.code"><el-button v-if="visible(button.when, row)" link :type="button.action.endsWith('.delete') ? 'danger' : 'primary'" :loading="busy === `${button.code}:${row.id}`" :disabled="Boolean(busy)" @click="execute(button, row)">{{ button.name }}</el-button></template></template></el-table-column></el-table>
          <div class="pagination-row"><span class="muted">共 {{ total }} 条记录</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="total" layout="sizes, prev, pager, next" background @size-change="query" @current-change="load" /></div>
        </section>
      </template>
    </template>
    <el-result v-else-if="error" icon="error" title="页面加载失败" :sub-title="error"><template #extra><el-button type="primary" @click="initialize">重新加载</el-button></template></el-result>
    <el-dialog v-model="formOpen" :title="`${editingId != null ? '编辑' : '新增'} · ${formModel?.model.name || ''}`" width="min(720px, 94vw)" destroy-on-close :close-on-click-modal="false"><FormRenderer v-if="formView && formModel" :view="formView" :model="formModel" :record-id="editingId" @close="formOpen = false" @saved="load" /></el-dialog>
    <el-dialog v-model="serviceOpen" title="业务操作" width="min(720px, 94vw)" destroy-on-close :close-on-click-modal="false"><ServiceForm :code="serviceCode" :initial="serviceInitial" @saved="load" @close="serviceOpen=false" /></el-dialog>
    <el-dialog v-model="inspectOpen" :title="inspectTitle" width="min(1000px, 94vw)"><el-table v-if="resultRows.length" :data="resultRows" max-height="480"><el-table-column v-for="key in resultKeys" :key="key" :prop="key" :label="key" min-width="140" show-overflow-tooltip /></el-table><el-empty v-else description="暂无匹配记录" :image-size="80" /><details class="raw-result"><summary>查看原始响应</summary><pre class="json-preview">{{ JSON.stringify(inspectResult, null, 2) }}</pre></details></el-dialog>
  </div>
</template>

