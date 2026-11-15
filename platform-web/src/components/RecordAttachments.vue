<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { attachmentRequest } from '../api'
import { auth, can } from '../auth'
import type { Model } from '../types'

interface Attachment {
  id: string; model: string; recordId: string; fileName: string; size: number;
  createdBy: string; createdAt: string; downloadUrl: string;
}
const props = defineProps<{ model: Model; recordId: string | number }>()
const files = ref<Attachment[]>([])
const loading = ref(false)
const uploading = ref(false)
const deleting = ref('')
const downloading = ref('')
const error = ref('')
const input = ref<HTMLInputElement>()
const busy = computed(() => loading.value || uploading.value || !!deleting.value || !!downloading.value)
const canRead = computed(() => can('data:read') || can(`${props.model.model.code}:read`) || can(`${props.model.model.code}:view`))
const canUpload = computed(() => canRead.value && (can('data:write') || can(`${props.model.model.code}:write`) || can(`${props.model.model.code}:attachments`) || can('data:maintain')))
const query = computed(() => `?${new URLSearchParams({ model: props.model.model.code, recordId: String(props.recordId) })}`)
const allowedExtensions = new Set(['png', 'jpg', 'jpeg', 'webp', 'pdf', 'txt', 'csv', 'docx', 'xlsx'])
const accept = '.png,.jpg,.jpeg,.webp,.pdf,.txt,.csv,.docx,.xlsx'
let loadSequence = 0

function sizeLabel(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
function timeLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function canDelete(file: Attachment) {
  return canUpload.value && (can('data:maintain') || auth.user?.username === file.createdBy)
}
async function load() {
  const sequence = ++loadSequence
  loading.value = true; error.value = ''
  try {
    const response = await attachmentRequest<{ records: Attachment[] }>(query.value)
    if (sequence === loadSequence) files.value = response.records
  } catch (err) { if (sequence === loadSequence) error.value = (err as Error).message }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function upload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file || busy.value || !canUpload.value) return
  error.value = ''
  if (files.value.length >= 20) { error.value = '每条记录最多保留20个附件，请先删除不需要的附件'; return }
  if (!file.size || file.size > 10 * 1024 * 1024) { error.value = '请选择非空文件，单个文件不能超过10 MB'; return }
  if (!allowedExtensions.has(file.name.split('.').pop()?.toLowerCase() || '')) { error.value = '请选择支持的图片、PDF、文本或Office文件'; return }
  uploading.value = true
  try {
    const data = new FormData(); data.append('file', file)
    await attachmentRequest(query.value, 'POST', data)
    ElMessage.success('附件上传成功')
    await load()
  } catch (err) { error.value = (err as Error).message }
  finally { uploading.value = false }
}
async function remove(file: Attachment) {
  if (busy.value || !canDelete(file)) return
  try {
    await ElMessageBox.confirm(`确认删除附件“${file.fileName}”？`, '删除附件', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  deleting.value = file.id; error.value = ''
  try {
    await attachmentRequest(`/${encodeURIComponent(file.id)}`, 'DELETE')
    ElMessage.success('附件已删除')
    await load()
  } catch (err) { error.value = (err as Error).message }
  finally { deleting.value = '' }
}
async function download(file: Attachment) {
  if (busy.value) return
  downloading.value = file.id; error.value = ''
  let url = ''
  try {
    const blob = await attachmentRequest<Blob>(`/${encodeURIComponent(file.id)}/download`, 'GET', undefined, 'blob')
    url = URL.createObjectURL(blob)
    const link = document.createElement('a'); link.href = url; link.download = file.fileName
    document.body.appendChild(link); link.click(); link.remove()
  } catch (err) { error.value = (err as Error).message }
  finally {
    if (url) window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    downloading.value = ''
  }
}
watch(() => [props.model.model.code, props.recordId], () => { files.value = []; void load() }, { immediate: true })
</script>

<template>
  <section class="record-attachments">
    <div class="attachment-toolbar">
      <div>
        <strong>附件（{{ files.length }}/20）</strong>
        <p class="muted attachment-hint">单个文件最大10 MB，支持 PNG、JPG、WEBP、PDF、TXT、CSV、DOCX、XLSX。</p>
      </div>
      <div class="attachment-actions">
        <el-button :disabled="busy" @click="load">刷新</el-button>
        <el-button v-if="canUpload" type="primary" :loading="uploading" :disabled="busy || files.length >= 20" @click="input?.click()">上传附件</el-button>
        <input ref="input" type="file" :accept="accept" hidden aria-label="选择上传附件" @change="upload" />
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="attachment-error" />
    <el-table v-loading="loading" :data="files" empty-text="暂无附件" class="attachment-table">
      <el-table-column prop="fileName" label="文件名称" min-width="220" show-overflow-tooltip />
      <el-table-column label="大小" width="100"><template #default="{ row }">{{ sizeLabel(row.size) }}</template></el-table-column>
      <el-table-column prop="createdBy" label="上传人" min-width="100" show-overflow-tooltip />
      <el-table-column label="上传时间" min-width="180"><template #default="{ row }">{{ timeLabel(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="160" fixed="right"><template #default="{ row }">
        <el-button link type="primary" :disabled="busy" :loading="downloading === row.id" @click="download(row)">下载</el-button>
        <el-button v-if="canDelete(row)" link type="danger" :disabled="busy" :loading="deleting === row.id" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.attachment-toolbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin: 8px 0 16px; }
.attachment-hint { font-size: 14px; margin: 8px 0 0; line-height: 1.7; }
.attachment-actions { display: flex; flex-shrink: 0; align-items: center; }
.attachment-error { margin-bottom: 12px; }
.attachment-table { width: 100%; }
@media (max-width: 640px) { .attachment-toolbar { flex-direction: column; } }
</style>
