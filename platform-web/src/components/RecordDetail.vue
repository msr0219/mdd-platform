<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { rpc } from '../api'
import { useMetaStore } from '../store'
import RecordAttachments from './RecordAttachments.vue'
import type { Field, Model, Row, RelationHop } from '../types'
const props = defineProps<{ model: Model; record: Row }>()
const metadata = useMetaStore()
const tab = ref('detail')
const events = ref<Row[]>([])
const eventError = ref('')
const eventLoading = ref(false)
const relations = ref<{ key: string; label: string; model: Model; field?: Field; paths?: RelationHop[][] }[]>([])
const relationKey = ref('')
const related = ref<Row[]>([])
const relatedTotal = ref(0)
const relatedPage = ref(1)
const relatedLoading = ref(false)
const relatedError = ref('')
const selectedRelation = computed(() => relations.value.find(item => item.key === relationKey.value))
const relatedColumns = computed(() => selectedRelation.value?.model.fields.filter(field => !field.hidden && field.listable !== false) || [])
const fieldLabel = (value: unknown, field: Field) => value == null ? '—' : field.type === 'enum' ? field.options?.find(option => option.value === value)?.label || String(value) : field.type === 'boolean' ? value ? '是' : '否' : String(value)
const eventValue = (row: Row, key: string) => row[key] ?? row[key.toUpperCase()]
const eventTime = (value: unknown) => {
  if (value == null) return ''
  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}
async function loadEvents() {
  eventLoading.value = true; eventError.value = ''
  try { events.value = (await rpc<{ records: Row[] }>('meta.getEvents', { model: props.model.model.code, id: props.record.id })).records }
  catch (error) { eventError.value = (error as Error).message }
  finally { eventLoading.value = false }
}
async function findRelations() {
  const results = await Promise.allSettled((metadata.status?.models || []).map(code => metadata.getModel(code)))
  const models = results.flatMap(result => result.status === 'fulfilled' ? [result.value] : [])
  const available = new Map(models.map(model => [model.model.code, model]))
  const named = (props.model.relations || []).flatMap(relation => {
    const target = available.get(relation.target)
    return target && relation.paths.every(path => path.every(hop => available.has(hop.model))) ? [{ key: `declared:${relation.code}`, label: relation.name, model: target, paths: relation.paths }] : []
  })
  const reverse = models.flatMap(model => model.fields.filter(field => field.type === 'ref' && field.ref === props.model.model.code).map(field => ({ key: `${model.model.code}:${field.code}`, label: `${model.model.name} · ${field.name}`, model, field })))
  relations.value = [...named, ...reverse]
  if (relations.value.length) { relationKey.value = relations.value[0]!.key; await loadRelated() }
}
async function loadRelated() {
  const relation = selectedRelation.value
  if (!relation) return
  relatedLoading.value = true; relatedError.value = ''
  try {
    const result = await rpc<{ total: number; records: Row[] }>(`${props.model.model.code}.related`, { id: props.record.id, model: relation.model.model.code, ...(relation.paths ? { paths: relation.paths } : { field: relation.field!.code }), page: relatedPage.value, pageSize: 10 })
    related.value = result.records; relatedTotal.value = result.total
  } catch (error) { relatedError.value = (error as Error).message }
  finally { relatedLoading.value = false }
}
function changeRelation() { relatedPage.value = 1; void loadRelated() }
onMounted(() => { void loadEvents(); void findRelations() })
</script>
<template>
  <div class="record-detail">
    <el-tabs v-model="tab">
      <el-tab-pane label="基本信息" name="detail"><el-descriptions :column="2" border><el-descriptions-item v-for="field in model.fields.filter(item => !item.hidden)" :key="field.code" :label="field.name">{{ fieldLabel(record[field.code], field) }}</el-descriptions-item><el-descriptions-item label="记录编号"><span class="mono">{{ record.id }}</span></el-descriptions-item><el-descriptions-item label="数据版本"><span class="mono">{{ record.version }}</span></el-descriptions-item></el-descriptions></el-tab-pane>
      <el-tab-pane :label="`状态历史（${events.length}）`" name="events"><div v-loading="eventLoading" class="event-history"><el-alert v-if="eventError" :title="eventError" type="error" :closable="false" show-icon /><el-timeline v-else-if="events.length"><el-timeline-item v-for="event in events" :key="eventValue(event, 'id')" :timestamp="eventTime(eventValue(event, 'created_at'))" placement="top" color="#41947f"><div class="event-state"><el-tag size="small" type="info">{{ eventValue(event, 'from_state') }}</el-tag><span>→</span><el-tag size="small" type="success">{{ eventValue(event, 'to_state') }}</el-tag></div><p class="event-reason">{{ eventValue(event, 'reason') || '状态变更' }}</p><small class="muted">操作人：{{ eventValue(event, 'actor') || '—' }}</small></el-timeline-item></el-timeline><el-empty v-else description="暂无状态流转记录" :image-size="75" /></div></el-tab-pane>
      <el-tab-pane :label="`关联反查（${relations.length}）`" name="relations"><template v-if="relations.length"><div class="related-toolbar"><span class="muted">从当前记录追溯关联数据</span><el-select v-model="relationKey" class="related-select" @change="changeRelation"><el-option v-for="relation in relations" :key="relation.key" :value="relation.key" :label="relation.label" /></el-select></div><el-alert v-if="relatedError" :title="relatedError" type="error" :closable="false" show-icon /><el-table v-loading="relatedLoading" :data="related" empty-text="暂无关联记录" max-height="350"><el-table-column prop="id" label="ID" width="80" /><el-table-column v-for="field in relatedColumns" :key="field.code" :label="field.name" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ fieldLabel(row[field.code], field) }}</template></el-table-column></el-table><div class="pagination-row"><span class="muted">共 {{ relatedTotal }} 条关联记录</span><el-pagination v-model:current-page="relatedPage" :page-size="10" :total="relatedTotal" layout="prev, pager, next" @current-change="loadRelated" /></div></template><el-empty v-else description="没有其他模型引用当前模型" :image-size="75" /></el-tab-pane>
      <el-tab-pane v-if="model.model.attachments === true" label="附件" name="attachments" lazy><RecordAttachments :model="model" :record-id="record.id" /></el-tab-pane>
    </el-tabs>
  </div>
</template>
