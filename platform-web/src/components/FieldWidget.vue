<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { rpc } from '../api'
import { useMetaStore } from '../store'
import type { Field, Row } from '../types'

const props = defineProps<{ field: Field; widget?: string; modelValue: any; disabled?: boolean; placeholder?: string; searching?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: any] }>()
const value = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const metadata = useMetaStore()
const references = ref<Row[]>([])
const refLoading = ref(false)
const refError = ref('')
const refLabelField = ref('name')
const refTemplate = ref('')
const refFields = ref<Field[]>([])
function referenceLabel(row: Row) {
  if (!refTemplate.value) return String(row[refLabelField.value] ?? row.id)
  return refTemplate.value.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_, code: string) => {
    const field = refFields.value.find(field => field.code === code), value = row[code]
    if (value == null) return '—'
    return field?.type === 'enum' ? String(field.options?.find(option => option.value === value)?.label || value) : typeof value === 'boolean' ? value ? '是' : '否' : String(value)
  })
}
const kind = computed(() => props.widget || ({ text: 'textarea', integer: 'number', decimal: 'number', enum: 'select', ref: 'ref', boolean: 'switch', date: 'date', datetime: 'datetime' } as Record<string, string>)[props.field.type] || 'input')
const options = computed(() => props.field.options || [])
async function loadReferences(query = '') {
  if (!props.field.ref) return
  refLoading.value = true; refError.value = ''
  try {
    const model = await metadata.getModel(props.field.ref)
    refTemplate.value = typeof model.model.displayTemplate === 'string' ? model.model.displayTemplate : ''
    refFields.value = model.fields
    const field = model.fields.find(item => item.code === 'name') || model.fields.find(item => item.type === 'string')
    refLabelField.value = field?.code || 'id'
    const filters = [...(props.field.refFilters || []), ...(query && field?.searchable ? [{ field: field.code, op: 'like', value: query }] : [])]
    const result = await rpc<{ records: Row[] }>(`${props.field.ref}.list`, { page: 1, pageSize: 100, filters })
    references.value = result.records
    if (!query && value.value != null && !references.value.some(row => row.id === value.value)) {
      try { references.value.push(await rpc(`${props.field.ref}.read`, { id: value.value })) } catch { /* Preserve selected ID when record is unavailable. */ }
    }
  } catch (error) { refError.value = (error as Error).message }
  finally { refLoading.value = false }
}
watch(() => props.field.ref, () => { if (props.field.type === 'ref') void loadReferences() }, { immediate: true })
</script>
<template>
  <div v-if="field.type === 'ref'" class="widget-full">
    <el-select v-model="value" filterable remote :remote-method="loadReferences" :loading="refLoading" clearable :disabled="disabled" :placeholder="placeholder || '请选择或搜索'" class="widget-full">
      <el-option v-for="row in references" :key="row.id" :value="row.id" :label="referenceLabel(row)" />
    </el-select><small v-if="refError" class="field-help error-text">{{ refError }}</small>
  </div>
  <el-select v-else-if="kind === 'select'" v-model="value" :disabled="disabled" clearable :placeholder="placeholder || '请选择'" class="widget-full"><el-option v-for="option in options" :key="String(option.value)" :value="option.value" :label="option.label" /></el-select>
  <el-radio-group v-else-if="kind === 'radio'" v-model="value" :disabled="disabled"><el-radio v-for="option in options" :key="String(option.value)" :value="option.value">{{ option.label }}</el-radio></el-radio-group>
  <el-select v-else-if="kind === 'switch' && searching" v-model="value" clearable placeholder="全部" class="widget-full"><el-option :value="true" label="是" /><el-option :value="false" label="否" /></el-select>
  <el-switch v-else-if="kind === 'switch'" v-model="value" :disabled="disabled" />
  <el-input-number v-else-if="kind === 'number'" v-model="value" :disabled="disabled" :min="field.min" :max="field.max" :precision="field.type === 'integer' ? 0 : undefined" :step="field.type === 'integer' ? 1 : 0.1" controls-position="right" class="widget-full" />
  <el-date-picker v-else-if="kind === 'date' || kind === 'datetime'" v-model="value" :disabled="disabled" :type="kind" :value-format="kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'" :placeholder="placeholder || '请选择日期'" class="widget-full" />
  <el-input v-else v-model="value" :disabled="disabled" :type="kind === 'textarea' ? 'textarea' : 'text'" :rows="3" :maxlength="field.maxLength || field.length" :show-word-limit="kind === 'textarea'" :placeholder="placeholder || `请输入${field.name}`" clearable @keyup.enter="searching && $emit('update:modelValue', value)" />
</template>
