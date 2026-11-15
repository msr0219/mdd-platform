<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { authRequest } from '../api'
import type { Row } from '../types'
const search = reactive({ service: '', actor: '', outcome: '', root: '', requestKey: '' })
const rows = ref<Row[]>([]), total = ref(0), page = ref(1), loading = ref(false), error = ref(''), selected = ref<Row>(), detailOpen = ref(false)
const outcomes: Record<string, string> = { SUCCESS: '成功', FAILED: '失败', ROLLED_BACK: '随事务回滚', REPLAYED: '幂等结果重放' }
function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false }) }
let sequence = 0
async function load() {
  const current = ++sequence; loading.value = true; error.value = ''
  try {
    const result = await authRequest(`/service-logs?${new URLSearchParams({ ...search, page: String(page.value), pageSize: '20' })}`)
    if (current === sequence) { rows.value = result.records; total.value = result.total }
  } catch (e) { if (current === sequence) error.value = (e as Error).message }
  finally { if (current === sequence) loading.value = false }
}
function query() { page.value = 1; void load() }
function reset() { Object.assign(search, { service: '', actor: '', outcome: '', root: '', requestKey: '' }); query() }
function chain(row: Row) { search.root = row.root_trace_id || row.trace_id; search.service = ''; search.actor = ''; search.outcome = ''; search.requestKey = ''; query() }
onMounted(load)
</script>
<template>
  <div><header class="page-heading"><div><span class="eyebrow">SERVICE AUDIT</span><h1>服务调用日志</h1><p>查询操作人、调用链、幂等请求及事务执行结果。</p></div></header>
    <section class="panel log-filters"><el-form inline @submit.prevent="query"><el-form-item label="服务编码"><el-input v-model="search.service" clearable placeholder="精确匹配" /></el-form-item><el-form-item label="操作人"><el-input v-model="search.actor" clearable /></el-form-item><el-form-item label="执行结果"><el-select v-model="search.outcome" clearable style="width:170px"><el-option v-for="(name, code) in outcomes" :key="code" :label="name" :value="code" /></el-select></el-form-item><el-form-item label="调用链"><el-input v-model="search.root" clearable /></el-form-item><el-form-item label="幂等键"><el-input v-model="search.requestKey" clearable /></el-form-item><el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item></el-form></section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="panel log-table"><el-table v-loading="loading" :data="rows" empty-text="暂无匹配日志"><el-table-column label="记录时间" min-width="190"><template #default="{row}">{{ formatTime(row.created_at) }}</template></el-table-column><el-table-column prop="service_code" label="服务编码" min-width="190" /><el-table-column prop="actor" label="操作人" min-width="110" /><el-table-column label="执行结果" min-width="150"><template #default="{row}"><el-tag :type="row.outcome === 'FAILED' ? 'danger' : row.outcome === 'ROLLED_BACK' ? 'warning' : 'success'">{{ outcomes[row.outcome] || row.outcome }}</el-tag></template></el-table-column><el-table-column prop="cost_ms" label="耗时（ms）" width="120" /><el-table-column prop="error_code" label="错误码" width="110" /><el-table-column label="操作" width="180" fixed="right"><template #default="{row}"><el-button link type="primary" @click="selected=row;detailOpen=true">详情</el-button><el-button link type="primary" @click="chain(row)">查看调用链</el-button></template></el-table-column></el-table><div class="log-pagination"><span>共 {{total}} 条</span><el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" /></div></section>
    <el-dialog v-model="detailOpen" title="调用详情" width="min(760px, 94vw)"><el-descriptions v-if="selected" :column="1" border><el-descriptions-item v-for="(label, key) in {trace_id:'调用编号',root_trace_id:'根调用编号',parent_trace_id:'上级调用编号',transaction_id:'逻辑事务编号',service_code:'服务编码',actor:'操作人',request_key:'幂等键',param_names:'参数名称',params_hash:'参数摘要（SHA-256）',cost_ms:'耗时（ms）',error_code:'错误码'}" :key="key" :label="label"><span class="log-value">{{ selected[key] ?? '—' }}</span></el-descriptions-item></el-descriptions><p class="muted">日志保留参数名称和摘要，不保存参数原文。逻辑事务编号用于关联平台内共享事务的调用。</p></el-dialog>
  </div>
</template>
<style scoped>
.log-filters,.log-table{padding:22px;margin-bottom:20px}.log-filters .el-form-item{margin-bottom:14px}.log-pagination{display:flex;justify-content:space-between;align-items:center;margin-top:18px}.log-value{overflow-wrap:anywhere}.muted{line-height:1.7}
</style>
