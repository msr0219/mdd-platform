<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { rpc } from '../api'
import type { ChartDefinition, Row } from '../types'
const props = defineProps<{ model: string; definition: ChartDefinition; filters?: unknown[]; refreshKey?: number }>()
const rows = ref<Row[]>([]), loading = ref(false), error = ref(''), truncated = ref(false)
const colors = ['#117d75', '#3f79ae', '#bd7826', '#985b8c']
const labels = computed(() => rows.value.map(row => props.definition.groupBy.map(key => String(row[key] ?? '未填写')).join(' / ')))
const value = (row: Row, key: string) => {
  const ratio = props.definition.ratios?.find(item => item.as === key)
  if (!ratio) return Number(row[key] ?? 0)
  const denominator = Number(row[ratio.denominator] ?? 0)
  return denominator ? (Number(row[ratio.numerator] ?? 0) - Number(ratio.subtract ? row[ratio.subtract] ?? 0 : 0)) / denominator * 100 : null
}
const series = computed(() => props.definition.series.map(item => ({ ...item, values: rows.value.map(row => value(row, item.field)) })))
const maximum = computed(() => Math.max(1, ...series.value.flatMap(item => item.values.map(v => v ?? 0))))
const width = computed(() => Math.max(640, rows.value.length * 110 + 90))
const x = (index: number) => 60 + (index + .5) * (width.value - 90) / Math.max(1, rows.value.length)
const y = (v: number) => 220 - v / maximum.value * 180
const points = (values: (number | null)[]) => values.map((v, index) => v == null ? '' : `${x(index)},${y(v)}`).filter(Boolean).join(' ')
const format = (v: number | null, percent = false) => v == null ? '—' : `${v.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${percent ? '%' : ''}`
const totals = computed(() => {
  const total: Row = {}
  for (const metric of props.definition.metrics) total[metric.as] = rows.value.reduce((sum, row) => sum + Number(row[metric.as] ?? 0), 0)
  return props.definition.metrics.every(metric => metric.op !== 'avg') ? props.definition.series.map(item => ({ ...item, value: value(total, item.field) })) : []
})
let sequence = 0
async function load() {
  const current = ++sequence; loading.value = true; error.value = ''
  try {
    const result = await rpc<{ records: Row[]; truncated?: boolean }>(`${props.model}.aggregate`, { groupBy: props.definition.groupBy, metrics: props.definition.metrics, filters: props.filters ?? [], ...(props.definition.firstBy ? { firstBy: props.definition.firstBy } : {}) })
    if (current === sequence) { rows.value = result.records; truncated.value = Boolean(result.truncated) }
  } catch (e) { if (current === sequence) { error.value = (e as Error).message; rows.value = [] } }
  finally { if (current === sequence) loading.value = false }
}
watch(() => JSON.stringify([props.model, props.definition, props.filters, props.refreshKey]), load, { immediate: true })
</script>
<template>
  <section v-loading="loading" class="panel metadata-chart">
    <div class="panel-heading"><div><h2>{{ definition.title }}</h2><p v-if="definition.description">{{ definition.description }}</p></div><el-button @click="load">刷新图表</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="truncated" title="分组超过 1000 项，仅展示前 1000 项。请缩小筛选范围；汇总仅涵盖展示分组。" type="warning" :closable="false" />
    <template v-if="rows.length">
      <div class="chart-summary"><div v-for="item in totals" :key="item.field"><span>{{ item.name }}</span><strong>{{ format(item.value, item.percent) }}</strong></div></div>
      <div class="chart-legend"><span v-for="(item, index) in series" :key="item.field"><i :style="{background: colors[index % colors.length]}" />{{ item.name }}</span></div>
      <div class="chart-scroll"><svg :viewBox="`0 0 ${width} 285`" :style="{minWidth: `${width}px`}" role="img" :aria-label="definition.title">
        <g v-for="tick in [0, 1, 2, 3, 4]" :key="tick"><line x1="52" :x2="width - 20" :y1="220 - tick * 45" :y2="220 - tick * 45" stroke="#dfe8eb" /><text x="45" :y="224 - tick * 45" text-anchor="end">{{ format(maximum * tick / 4) }}</text></g>
        <g v-for="(item, s) in series" :key="item.field" :fill="colors[s % colors.length]">
          <polyline v-if="definition.type === 'line'" :points="points(item.values)" fill="none" :stroke="colors[s % colors.length]" stroke-width="2" />
          <template v-for="(v, index) in item.values" :key="index"><template v-if="v != null">
            <rect v-if="definition.type === 'bar'" :x="x(index) - series.length * 12 + s * 24" :y="y(v)" width="20" :height="Math.max(0, 220 - y(v))" rx="3"><title>{{ labels[index] }} · {{ item.name }}：{{ format(v, item.percent) }}</title></rect>
            <circle v-else :cx="x(index)" :cy="y(v)" r="4"><title>{{ labels[index] }} · {{ item.name }}：{{ format(v, item.percent) }}</title></circle>
          </template></template>
        </g>
        <text v-for="(label, index) in labels" :key="index" :x="x(index)" y="246" text-anchor="middle">{{ definition.categoryPrefix }}{{ label.length > 14 ? `${label.slice(0, 14)}…` : label }}<title>{{ label }}</title></text>
      </svg></div>
      <el-table :data="rows" max-height="300"><el-table-column v-for="(key, index) in definition.groupBy" :key="key" :prop="key" :label="definition.groupNames?.[index] || key" min-width="140" /><el-table-column v-for="item in definition.series" :key="item.field" :label="item.name" min-width="135"><template #default="{row}">{{ format(value(row, item.field), item.percent) }}</template></el-table-column></el-table>
    </template><el-empty v-else-if="!loading && !error" description="暂无符合条件的统计数据" :image-size="70" />
  </section>
</template>
<style scoped>
.metadata-chart{margin-bottom:20px;padding:22px}.panel-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.panel-heading h2{margin:0 0 8px;font-size:19px}.panel-heading p{line-height:1.7;color:#627d87;margin:0 0 16px;max-width:900px}.chart-summary{display:flex;gap:28px;flex-wrap:wrap;margin:18px 0}.chart-summary div{display:flex;flex-direction:column;gap:7px}.chart-summary span{color:#627d87;font-size:14px}.chart-summary strong{font-size:25px}.chart-legend{display:flex;gap:22px;margin:12px 0}.chart-legend span{display:flex;align-items:center;gap:8px}.chart-legend i{width:12px;height:12px;border-radius:3px}.chart-scroll{overflow-x:auto;margin:10px 0 20px}.chart-scroll svg{width:100%;height:285px}.chart-scroll text{font-size:12px;fill:#526e79}
</style>
