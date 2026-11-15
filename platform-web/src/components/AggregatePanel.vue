<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { rpc } from '../api'
import { useMetaStore } from '../store'
import type { Model, Row } from '../types'
const metadata = useMetaStore()
const models = ref<Model[]>([])
const selectedCode = ref('')
const groupField = ref('')
const valueField = ref('')
const rows = ref<Row[]>([])
const loading = ref(false)
const error = ref('')
const current = computed(() => models.value.find(model => model.model.code === selectedCode.value))
const groupFields = computed(() => current.value?.fields.filter(field => field.type === 'enum') || [])
const valueFields = computed(() => current.value?.fields.filter(field => ['integer', 'decimal'].includes(field.type)) || [])
const groupDef = computed(() => groupFields.value.find(field => field.code === groupField.value))
const total = computed(() => rows.value.reduce((sum, row) => sum + Number(row.metric ?? row.METRIC ?? 0), 0))
const bars = computed(() => rows.value.map((row, index) => {
  const key = row[groupField.value] ?? row[groupField.value.toUpperCase()]
  const value = Number(row.metric ?? row.METRIC ?? 0)
  return { key: String(key ?? '未分类'), label: groupDef.value?.options?.find(option => option.value === key)?.label || String(key ?? '未分类'), value, percentage: total.value ? Math.round(value / total.value * 100) : 0, color: ['#539b88', '#bba06b', '#c5847e', '#8ba1b9', '#9686af'][index % 5] }
}))
async function load() {
  if (!selectedCode.value || !groupField.value) return
  loading.value = true; error.value = ''
  try {
    rows.value = (await rpc<{ records: Row[] }>(`${selectedCode.value}.aggregate`, { groupBy: [groupField.value], metrics: [{ op: valueField.value ? 'sum' : 'count', ...(valueField.value ? { field: valueField.value } : {}), as: 'metric' }] })).records
  } catch (err) { error.value = (err as Error).message }
  finally { loading.value = false }
}
function modelChanged() {
  groupField.value = groupFields.value.find(field => field.code === 'qualityStatus')?.code || groupFields.value[0]?.code || ''
  valueField.value = valueFields.value.find(field => field.code === 'quantity')?.code || ''
  void load()
}
onMounted(async () => {
  loading.value = true
  const results = await Promise.allSettled((metadata.status?.models || []).map(code => metadata.getModel(code)))
  models.value = results.flatMap(result => result.status === 'fulfilled' && result.value.fields.some(field => field.type === 'enum') ? [result.value] : [])
  selectedCode.value = models.value.find(model => model.fields.some(field => field.code === 'qualityStatus'))?.model.code || models.value[0]?.model.code || ''
  modelChanged(); if (!selectedCode.value) loading.value = false
})
</script>
<template>
  <section class="panel aggregate-panel" v-loading="loading">
    <div class="panel-heading"><div><h2>数据分布</h2><p>按模型字段实时聚合，查看业务数据分账</p></div><el-button :icon="Refresh" circle aria-label="刷新聚合数据" :disabled="!selectedCode" @click="load" /></div>
    <div v-if="models.length" class="aggregate-filters"><el-select v-model="selectedCode" @change="modelChanged"><el-option v-for="model in models" :key="model.model.code" :value="model.model.code" :label="model.model.name" /></el-select><el-select v-model="groupField" @change="load"><el-option v-for="field in groupFields" :key="field.code" :value="field.code" :label="`按${field.name}分组`" /></el-select><el-select v-model="valueField" @change="load"><el-option value="" label="统计记录数" /><el-option v-for="field in valueFields" :key="field.code" :value="field.code" :label="`汇总${field.name}`" /></el-select></div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="aggregate-error" />
    <div v-else-if="bars.length" class="aggregate-chart"><div class="aggregate-total"><strong>{{ total.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }}</strong><span>{{ valueFields.find(field => field.code === valueField)?.name || '记录总数' }}</span></div><div class="aggregate-bars"><div v-for="bar in bars" :key="bar.key" class="aggregate-bar"><span>{{ bar.label }}</span><div class="bar-track"><div :style="{ width: `${bar.percentage}%`, background: bar.color }" /></div><strong>{{ bar.value.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }}</strong><small>{{ bar.percentage }}%</small></div></div></div>
    <el-empty v-else description="暂无可聚合的数据" :image-size="70" />
  </section>
</template>
