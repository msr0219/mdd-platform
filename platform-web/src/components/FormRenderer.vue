<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rpc, RpcError } from '../api'
import { mapParams, visible } from '../conditions'
import type { Action, Field, Model, Row, View, ViewField } from '../types'
import FieldWidget from './FieldWidget.vue'

const props = defineProps<{ view: View; model: Model; recordId?: string | number }>()
const emit = defineEmits<{ saved: []; close: [] }>()
const form = reactive<Row>({})
const errors = reactive<Record<string, string>>({})
const loading = ref(false)
const saving = ref('')
const loadError = ref('')
const requests = new Map<string, string>()
const fields = computed(() => (props.view.fields || props.model.fields.map((field): ViewField => ({ field: field.code }))).map(config => ({ config, field: props.model.fields.find(field => field.code === config.field) })).filter((item): item is { config: ViewField; field: Field } => Boolean(item.field && !item.field.hidden && visible(item.config.when, form, form))))
const buttons = computed(() => (props.view.buttons || [{ code: 'save', name: '保存', action: `service:${props.view.model}.create`, success: { close: true } }, { code: 'cancel', name: '取消', action: 'close' }]).filter(button => visible(button.when, form, form)))
const disabled = (field: Field) => field.editable === false || field.code === props.model.stateMachine?.field
async function initialize() {
  loading.value = true; loadError.value = ''
  try {
    for (const key of Object.keys(form)) delete form[key]
    for (const field of props.model.fields) if (field.defaultValue !== undefined) form[field.code] = field.defaultValue
    if (props.recordId != null) Object.assign(form, await rpc(`${props.view.model}.read`, { id: props.recordId }))
  } catch (error) { loadError.value = (error as Error).message }
  finally { loading.value = false }
}
onMounted(initialize)
function validate() {
  for (const key of Object.keys(errors)) delete errors[key]
  for (const { field } of fields.value) {
    if (disabled(field)) continue
    const value = form[field.code]
    if (value === undefined || value === null || value === '') {
      if (field.required) errors[field.code] = `${field.name}不能为空`
      continue
    }
    if (typeof value === 'string') {
      if (field.minLength != null && value.length < field.minLength) errors[field.code] = `至少输入 ${field.minLength} 个字符`
      const max = field.maxLength ?? field.length
      if (max != null && value.length > max) errors[field.code] = `不能超过 ${max} 个字符`
      if (field.pattern) {
        try { if (!new RegExp(field.pattern).test(value)) errors[field.code] = `${field.name}格式不正确` } catch { errors[field.code] = '字段校验规则无效，请联系建模人员' }
      }
    }
    if (typeof value === 'number') {
      if (field.min != null && value < field.min) errors[field.code] = `不能小于 ${field.min}`
      if (field.max != null && value > field.max) errors[field.code] = `不能大于 ${field.max}`
    }
  }
  return !Object.keys(errors).length
}
async function execute(button: Action) {
  if (saving.value) return
  if (button.action === 'close') { emit('close'); return }
  if (!button.action.startsWith('service:') || !validate()) return
  try {
    if (button.confirm) await ElMessageBox.confirm(button.confirm, '操作确认', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' })
    saving.value = button.code
    let method = button.action.slice(8)
    if (!method.includes('.')) method = `service.${method}`
    const editing = props.recordId != null
    if (editing && method === `${props.view.model}.create`) method = `${props.view.model}.update`
    let payload: Row = Object.fromEntries(props.model.fields.filter(field => !disabled(field) && form[field.code] !== undefined).map(field => [field.code, form[field.code] === '' && field.type !== 'string' && field.type !== 'text' ? null : form[field.code]]))
    if (editing) payload = { ...payload, id: form.id, version: form.version }
    const signature = `${method}:${JSON.stringify(form)}`
    if (!requests.has(signature)) requests.set(signature, crypto.randomUUID())
    if (button.map || button.params) payload = mapParams(button.map || button.params || {}, { form, row: form }, requests.get(signature)!)
    await rpc(method, payload)
    requests.delete(signature)
    ElMessage.success(button.success?.message || '保存成功')
    emit('saved')
    if (button.success?.clear) await initialize()
    if (button.success?.close !== false) emit('close')
  } catch (error) {
    if (error instanceof RpcError && Array.isArray(error.data?.fields)) error.data.fields.forEach(field => { errors[field.code] = field.message })
    if (error instanceof Error) ElMessage.error(error.message)
  } finally { saving.value = '' }
}
</script>
<template>
  <div v-loading="loading" class="dynamic-form">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon><template #default><el-button size="small" @click="initialize">重新加载</el-button></template></el-alert>
    <el-form v-else label-position="top" @submit.prevent>
      <el-row :gutter="24"><el-col v-for="{ config, field } in fields" :key="field.code" :xs="24" :sm="config.span || 12"><el-form-item :label="config.name || field.name" :required="field.required && !disabled(field)" :error="errors[field.code]"><FieldWidget v-model="form[field.code]" :field="field" :widget="config.widget" :disabled="disabled(field) || Boolean(saving)" :placeholder="config.placeholder" /></el-form-item></el-col></el-row>
      <div class="form-footer"><span v-if="recordId != null" class="muted mono">记录 #{{ form.id }} · 版本 {{ form.version }}</span><div><el-button v-for="button in buttons" :key="button.code" :type="button.action === 'close' ? 'default' : 'primary'" :disabled="Boolean(saving) && saving !== button.code" :loading="saving === button.code" @click="execute(button)">{{ button.name }}</el-button></div></div>
    </el-form>
  </div>
</template>
