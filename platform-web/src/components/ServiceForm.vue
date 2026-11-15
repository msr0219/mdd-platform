<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { rpc, RpcError } from '../api'
import type { Field, Row } from '../types'
import FieldWidget from './FieldWidget.vue'
const props = defineProps<{ code: string; initial: Row }>()
const emit = defineEmits<{ saved: []; close: [] }>()
const definition = ref<any>(), data = reactive<Row>({}), errors = reactive<Record<string,string>>({}), busy = ref(false), error = ref('')
const requestId = crypto.randomUUID()
const fields = computed<Field[]>(() => (definition.value?.params || []).filter((p: any) => p.name !== definition.value.idempotencyKey).map((p: any) => ({ ...p, code:p.name, name:p.label || p.name })))
const result = ref<Row>()
const resultFields = computed<{ field: string; label: string }[]>(() => definition.value?.resultFields || [])
watch(data, () => { result.value = undefined }, { deep: true, flush: 'sync' })
onMounted(async () => { try { definition.value = await rpc('meta.getService',{code:props.code}); Object.assign(data,props.initial); for (const p of definition.value.params) if(data[p.name] === undefined && p.defaultValue !== undefined) data[p.name] = p.defaultValue } catch(e) { error.value = (e as Error).message } })
async function submit() {
  if(busy.value) return
  Object.keys(errors).forEach(k => delete errors[k]); error.value = ''; result.value = undefined
  for(const f of fields.value) if(f.required && (data[f.code] == null || String(data[f.code]).trim() === '')) errors[f.code] = `${f.name}不能为空`
  if(Object.keys(errors).length) return
  busy.value = true
  try {
    const payload = Object.fromEntries(definition.value.params.filter((p:any) => data[p.name] !== undefined).map((p:any) => [p.name,data[p.name]]))
    if(definition.value.idempotencyKey) payload[definition.value.idempotencyKey] = requestId
    const response = await rpc<Row>(`service.${props.code}`,payload)
    ElMessage.success(`${definition.value.name}成功`)
    if (resultFields.value.length) result.value = response
    else { emit('saved'); emit('close') }
  } catch(e) { error.value = (e as Error).message; if(e instanceof RpcError && Array.isArray(e.data?.fields)) e.data.fields.forEach(f => errors[f.code] = f.message) } finally { busy.value = false }
}
</script>
<template>
  <div v-loading="!definition && !error">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-form v-if="definition" label-position="top" @submit.prevent="submit">
      <p v-if="definition.description" class="muted service-description">{{ definition.description }}</p>
      <el-row :gutter="20">
        <el-col v-for="field in fields" :key="field.code" :xs="24" :sm="field.type==='text'?24:12">
          <el-form-item :label="field.name" :required="field.required" :error="errors[field.code]">
            <FieldWidget v-model="data[field.code]" :field="field" :disabled="busy" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-descriptions v-if="result" title="查询结果" :column="1" border class="service-result" aria-live="polite">
        <el-descriptions-item v-for="field in resultFields" :key="field.field" :label="field.label">{{ result[field.field] ?? '—' }}</el-descriptions-item>
      </el-descriptions>
      <p v-if="definition.idempotencyKey" class="muted">提交失败后可重试；同一份表单保留请求编号，防止重复执行。</p>
      <div class="form-footer">
        <el-button :disabled="busy" @click="emit('close')">{{ resultFields.length ? '关闭' : '取消' }}</el-button>
        <el-button type="primary" native-type="submit" :loading="busy">{{ definition.submitLabel || '确认提交' }}</el-button>
      </div>
    </el-form>
  </div>
</template>

<style scoped>
.service-description { margin-bottom: 20px; line-height: 1.8; }
.service-result { margin-bottom: 20px; }
</style>
