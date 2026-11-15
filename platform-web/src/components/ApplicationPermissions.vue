<script setup lang="ts">
import { computed } from 'vue'
import type { Menu } from '../types'
export interface Application {
  code: string; name: string; view: string; model: string; modelName: string; canEdit: boolean; canOperate: boolean;
  readModels: string[]; operationReadModels: string[]; services: Record<string, string>
}
const props = defineProps<{ menus: Menu[]; applications: Application[]; modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const catalog = computed(() => new Map(props.applications.map(app => [app.code, app])))
const grant = (code: string, action: string) => `app_${code}:${action}`
const has = (code: string, action = 'view') => props.modelValue.includes(grant(code, action))
const invalidGrants = computed(() => props.modelValue.filter(permission => {
  if (!permission.startsWith('app_')) return false
  const [resource, action] = permission.split(':'), app = catalog.value.get(resource!.slice(4))
  return !app || !['view', 'edit', 'operate'].includes(action!) || action === 'edit' && !app.canEdit || action === 'operate' && !app.canOperate
}))
function clearInvalid() { emit('update:modelValue', props.modelValue.filter(permission => !invalidGrants.value.includes(permission))) }
function leaves(node: Menu): string[] { return node.children?.length ? node.children.flatMap(leaves) : catalog.value.has(node.code) ? [node.code] : [] }
const selected = (node: Menu) => leaves(node).length > 0 && leaves(node).every(code => has(code))
const partial = (node: Menu) => !selected(node) && leaves(node).some(code => has(code))
function toggle(node: Menu, checked: boolean) {
  const codes = leaves(node), next = new Set(props.modelValue)
  for (const code of codes) {
    if (checked) next.add(grant(code, 'view'))
    else for (const action of ['view', 'edit', 'operate']) next.delete(grant(code, action))
  }
  emit('update:modelValue', [...next])
}
function capability(code: string, action: string, checked: boolean) {
  const next = new Set(props.modelValue)
  if (checked) next.add(grant(code, action)); else next.delete(grant(code, action))
  emit('update:modelValue', [...next])
}
function details(app: Application) {
  const references = new Set([...app.readModels, ...(has(app.code, 'operate') ? app.operationReadModels : [])])
  references.delete(app.model)
  return `${references.size ? `关联数据只读：${[...references].join('、')}。` : ''}${Object.keys(app.services).length ? `业务操作：${Object.values(app.services).join('、')}。` : ''}` || (app.canEdit ? '此应用支持查看及普通数据维护。' : app.canOperate ? '可查看记录及管理其附件。' : '此应用仅提供查看。')
}
</script>
<template>
  <div class="application-permissions">
    <el-alert v-if="invalidGrants.length" title="元模型已调整，部分旧授权已失效。请移除后保存。" type="warning" :closable="false"><template #default>{{ invalidGrants.join('、') }} <el-button link type="primary" @click="clearInvalid">移除失效授权</el-button></template></el-alert>
    <div class="application-columns"><span>允许访问（含查看）</span><span>新增 / 编辑 / 删除</span><span>业务操作 / 附件</span></div>
    <el-tree :data="menus" node-key="code" :props="{ label: 'name', children: 'children' }" default-expand-all :expand-on-click-node="false" :indent="20">
      <template #default="{ data }">
        <div class="application-node" @click.stop>
          <el-checkbox :model-value="selected(data)" :indeterminate="partial(data)" @change="toggle(data, Boolean($event))">{{ data.name }}</el-checkbox>
          <template v-if="catalog.has(data.code)">
            <el-tooltip :content="details(catalog.get(data.code)!)" placement="top" :show-after="300"><span class="application-info" tabindex="0" aria-label="查看关联数据与业务操作说明">ⓘ</span></el-tooltip>
            <el-checkbox class="application-capability" :aria-label="`${data.name}新增编辑删除`" :model-value="has(data.code, 'edit')" :disabled="!has(data.code) || !catalog.get(data.code)!.canEdit" @change="capability(data.code, 'edit', Boolean($event))" />
            <el-checkbox class="application-capability" :aria-label="`${data.name}业务操作及附件`" :model-value="has(data.code, 'operate')" :disabled="!has(data.code) || !catalog.get(data.code)!.canOperate" @change="capability(data.code, 'operate', Boolean($event))" />
          </template>
        </div>
      </template>
    </el-tree>
    <p class="muted">业务权限以应用勾选为准。工单、库存等通过业务操作修改；关联下拉框和应用内查询数据只读开放，不增加对应菜单。ⓘ 可查看关联模型与操作清单。多个角色的应用授权取并集。</p>
  </div>
</template>
<style scoped>
.application-permissions { width: 100%; }
.application-columns { display: grid; grid-template-columns: 1fr 150px 150px; padding: 10px 0 10px 28px; background: #f2f6f7; font-weight: 600; }
.application-columns span:not(:first-child) { text-align: center; }
.application-permissions :deep(.el-tree-node__content) { height: 44px; }
.application-node { display: flex; align-items: center; width: 100%; min-width: 0; }
.application-node > .el-checkbox:first-child { flex: 1; min-width: 0; }
.application-info { color: #45877a; margin-right: 12px; cursor: help; }
.application-capability { width: 150px; flex: 0 0 150px; justify-content: center; margin: 0; }
</style>
