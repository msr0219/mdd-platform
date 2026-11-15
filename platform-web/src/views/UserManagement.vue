<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authRequest } from '../api'
import { auth } from '../auth'
import ApplicationPermissions from '../components/ApplicationPermissions.vue'
import type { Application } from '../components/ApplicationPermissions.vue'
import type { Menu } from '../types'
const users = ref<any[]>([]), roles = ref<any[]>([]), loading = ref(false), error = ref(''), active = ref('users')
const applications = ref<Application[]>([]), menus = ref<Menu[]>([]), existingRole = ref(false)
const showUser = ref(false), showRole = ref(false), showReset = ref(false), busy = ref(false)
const deletingRole = ref('')
const deletingUser = ref(0)
const builtinRoles = new Set(['admin','modeler','operator','viewer'])
const form = reactive<any>({}), role = reactive<any>({}), reset = reactive({ id: 0, username: '', password: '' })
const systemPermissions = [{ code:'meta:inspect', name:'查看模型配置' }, { code:'meta:reload', name:'重载模型配置' }, { code:'users:manage', name:'管理用户与角色' }]
const visibleApplications = (permissions: string[] = []) => applications.value.filter(app => permissions.includes('*') || permissions.includes(`app_${app.code}:view`))
const roleApplications = computed(() => visibleApplications(role.permissions))
const userPermissions = computed<string[]>(() => [...new Set((form.roles || []).flatMap((code: string) => roles.value.find(r => r.code === code)?.permissions || []))] as string[])
const userApplications = computed(() => visibleApplications(userPermissions.value))
const isAdministrator = (user: any) => user.username === 'admin' || user.roles?.includes('admin')
const allRows = (permissions: string[] = []) => permissions.some(p => ['*','data:scopeAll'].includes(p))
const roleScope = computed({
  get: () => allRows(role.permissions) ? 'ALL' : 'OWN',
  set: (value: string) => { role.permissions = (role.permissions || []).filter((p: string) => p !== 'data:scopeAll'); if (value === 'ALL') role.permissions.push('data:scopeAll') },
})
async function load() { loading.value = true; error.value = ''; try { const [nextUsers,nextRoles,catalog] = await Promise.all([authRequest('/users'),authRequest('/roles'),authRequest('/applications')]); users.value=nextUsers;roles.value=nextRoles;applications.value=catalog.applications;menus.value=catalog.menus;users.value.forEach(u => { u.roles = JSON.parse(u.roles_json) }); roles.value.forEach(r => { r.permissions = JSON.parse(r.permissions_json) }) } catch (e) { error.value = (e as Error).message } finally { loading.value = false } }
function edit(user?: any) { if(user && isAdministrator(user))return; Object.keys(form).forEach(k => delete form[k]); Object.assign(form, user ? { id:user.id, username:user.username, name:user.display_name, roles:[...user.roles], enabled:user.enabled, version:user.version } : { username:'', name:'', password:'', roles:['operator'], enabled:true }); showUser.value = true }
function editPassword(user: any) { if(isAdministrator(user))return; Object.assign(reset,{id:user.id,username:user.username,password:''});showReset.value=true }
async function removeUser(user: any) {
  if(deletingUser.value || isAdministrator(user) || user.id===auth.user?.id)return
  deletingUser.value=user.id
  try {
    await ElMessageBox.confirm(`确定删除用户“${user.display_name}”（${user.username}）吗？该账号将无法登录，已有登录会话将失效；业务记录保留，用户名不能再次注册。`, '删除用户', { type:'warning', confirmButtonText:'删除', cancelButtonText:'取消', confirmButtonClass:'el-button--danger', closeOnClickModal:false })
    await authRequest(`/users/${user.id}?version=${user.version}`, 'DELETE')
    ElMessage.success('用户已删除，业务记录已保留')
    await load()
  } catch(e) { if(e instanceof Error){ElMessage.error(e.message);await load()} }
  finally { deletingUser.value=0 }
}
function editRole(value?: any) { Object.keys(role).forEach(k => delete role[k]); Object.assign(role, value ? { code:value.code, name:value.name, permissions:value.permissions.filter((p: string)=>p!=='apps:configured') } : { code:'', name:'', permissions:[] }); existingRole.value=Boolean(value);showRole.value = true }
const roleUsage = (code: string) => users.value.filter(user => user.roles?.includes(code)).length
const roleDeleteHint = (code: string) => builtinRoles.has(code) ? '内置角色不可删除' : roleUsage(code) ? `已分配给${roleUsage(code)}个用户，请先在用户账号中解除分配（含停用账号）` : ''
async function removeRole(value: any) {
  if(deletingRole.value || builtinRoles.has(value.code) || roleUsage(value.code))return
  deletingRole.value=value.code
  try {
    await ElMessageBox.confirm(`确定删除角色“${value.name}”（${value.code}）吗？删除后将从角色列表中移除。`, '删除角色', { type:'warning', confirmButtonText:'删除', cancelButtonText:'取消', confirmButtonClass:'el-button--danger', closeOnClickModal:false })
    await authRequest(`/roles/${encodeURIComponent(value.code)}`, 'DELETE')
    ElMessage.success('角色已删除')
    await load()
  } catch(e) { if(e instanceof Error){ElMessage.error(e.message);await load()} }
  finally { deletingRole.value='' }
}
async function save(kind: 'user'|'role'|'reset') { busy.value = true; try { await authRequest(kind === 'user' ? '/users' : kind === 'role' ? '/roles' : `/users/${reset.id}/password`, 'POST', kind === 'user' ? form : kind === 'role' ? role : { password:reset.password }); showUser.value = false; showRole.value = false; showReset.value = false; reset.password = ''; ElMessage.success(kind==='role'?'角色权限已保存，后续请求立即生效；已登录用户刷新页面更新菜单':'已保存，账号变更会使旧会话失效'); await load() } catch (e) { ElMessage.error((e as Error).message) } finally { busy.value = false } }
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">ACCESS MANAGEMENT</p><h1>用户与角色</h1><p class="page-description">分配账号与角色，按应用控制查看、编辑和业务操作权限。</p></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel user-management-panel" v-loading="loading">
    <el-tabs v-model="active" class="management-tabs">
      <el-tab-pane label="用户账号" name="users">
        <div class="management-toolbar"><el-button type="primary" :disabled="Boolean(deletingUser)" @click="edit()">新增用户</el-button></div>
        <el-table :data="users" class="management-table">
          <el-table-column prop="username" label="账号" min-width="150" />
          <el-table-column prop="display_name" label="姓名" min-width="140" />
          <el-table-column label="角色" min-width="180"><template #default="{row}"><div class="role-tags"><el-tag v-for="code in row.roles" :key="code">{{ roles.find(r => r.code === code)?.name || code }}</el-tag></div></template></el-table-column>
          <el-table-column label="状态" min-width="160"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}{{ row.must_change ? ' · 待修改密码' : '' }}</template></el-table-column>
          <el-table-column label="操作" width="220" align="left" fixed="right"><template #default="{row}"><div class="management-row-actions"><el-button link :disabled="isAdministrator(row) || Boolean(deletingUser)" @click="edit(row)">编辑</el-button><el-button link :disabled="isAdministrator(row) || Boolean(deletingUser)" @click="editPassword(row)">重置密码</el-button><el-button link type="danger" :aria-label="`删除用户${row.username}`" :disabled="isAdministrator(row) || row.id===auth.user?.id || Boolean(deletingUser)" :loading="deletingUser===row.id" @click="removeUser(row)">删除</el-button></div></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="角色与权限" name="roles">
        <div class="management-toolbar"><el-button type="primary" :disabled="Boolean(deletingRole)" @click="editRole()">新增角色</el-button></div>
        <el-table :data="roles" class="management-table">
          <el-table-column prop="code" label="编码" min-width="130" />
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column label="数据范围" width="180"><template #default="{row}">{{ allRows(row.permissions) ? '全部数据' : '本人及所属记录' }}</template></el-table-column>
          <el-table-column label="可访问应用" min-width="260"><template #default="{row}"><span v-if="!visibleApplications(row.permissions).length" class="muted">未授权业务应用</span><div v-else class="application-tags"><el-tag v-for="app in visibleApplications(row.permissions)" :key="app.code">{{ app.name }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" width="150" align="left" fixed="right"><template #default="{row}"><div class="management-row-actions"><el-button link :disabled="row.code==='admin' || Boolean(deletingRole)" @click="editRole(row)">编辑</el-button><el-tooltip :content="roleDeleteHint(row.code)" :disabled="!roleDeleteHint(row.code)"><span class="role-delete-target"><el-button link type="danger" :aria-label="`删除角色${row.name}`" :disabled="builtinRoles.has(row.code) || roleUsage(row.code)>0 || Boolean(deletingRole)" :loading="deletingRole===row.code" @click="removeRole(row)">删除</el-button></span></el-tooltip></div></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
  <el-dialog v-model="showUser" :title="form.id ? '编辑用户' : '新增用户'" width="600px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="账号"><el-input v-model="form.username" :disabled="Boolean(form.id)" maxlength="50" /></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.name" maxlength="80" /></el-form-item>
      <el-form-item v-if="!form.id" label="初始密码（首次登录必须修改）"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少6位，包含字母和数字或符号" /></el-form-item>
      <el-form-item label="角色"><el-select v-model="form.roles" multiple class="widget-full"><el-option v-for="r in roles" :key="r.code" :value="r.code" :label="r.name" /></el-select><p class="muted">用户可访问所有角色授权应用的并集。需要单独限制某个用户时，可为他建立专用角色。</p></el-form-item>
      <el-form-item label="该用户可访问的应用"><div><el-tag v-for="app in userApplications" :key="app.code">{{ app.name }}</el-tag><span v-if="!userApplications.length" class="muted">暂未授权业务应用</span></div></el-form-item>
      <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="showUser=false">取消</el-button><el-button type="primary" :loading="busy" @click="save('user')">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="showRole" title="角色权限与可访问应用" width="850px">
    <el-form label-position="top">
      <el-form-item label="角色编码"><el-input v-model="role.code" :disabled="existingRole" /></el-form-item>
      <el-form-item label="角色名称"><el-input v-model="role.name" /></el-form-item>
      <el-form-item label="按应用授权"><ApplicationPermissions v-model="role.permissions" :menus="menus" :applications="applications" /></el-form-item>
      <el-form-item label="可访问应用"><div class="widget-full"><el-tag v-for="app in roleApplications" :key="app.code">{{ app.name }}</el-tag><span v-if="!roleApplications.length" class="muted">未授权业务应用</span></div></el-form-item>
      <el-form-item label="平台管理权限"><el-checkbox-group v-model="role.permissions"><el-checkbox v-for="p in systemPermissions" :key="p.code" :value="p.code">{{ p.name }}</el-checkbox></el-checkbox-group><p class="muted">用户与角色管理权限可以重新分配授权，仅授予可信管理员。</p></el-form-item>
      <el-form-item label="数据范围"><el-select v-model="roleScope" class="widget-full"><el-option value="OWN" label="本人及所属记录" /><el-option value="ALL" label="全部数据" /></el-select><p class="muted">决定应用内能看哪些记录，不增加应用入口。适用于启用数据范围的模型；批次、BOM和质量等共享数据仍按应用授权访问。多个角色合并授权，任一角色允许全部数据即生效。</p></el-form-item>
    </el-form>
    <template #footer><el-button @click="showRole=false">取消</el-button><el-button type="primary" :loading="busy" @click="save('role')">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="showReset" :title="`重置 ${reset.username} 的密码`" width="480px"><el-input v-model="reset.password" type="password" show-password autocomplete="new-password" placeholder="至少6位，包含字母和数字或符号" /><p class="muted">旧会话将失效，下次登录必须修改密码。</p><template #footer><el-button @click="showReset=false">取消</el-button><el-button type="primary" :loading="busy" @click="save('reset')">保存</el-button></template></el-dialog>
</template>
<style scoped>
.el-tag { margin: 3px 6px 3px 0; }
.user-management-panel { padding: 0 24px 24px; }
.management-tabs { min-width: 0; }
.management-tabs :deep(.el-tabs__header) { margin: 0 0 20px; }
.management-tabs :deep(.el-tabs__item) { height: 56px; }
.management-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.management-table { width: 100%; }
.management-row-actions { display: flex; align-items: center; justify-content: flex-start; gap: 12px; white-space: nowrap; }
.management-row-actions .el-button.is-link { padding-left: 0; padding-right: 0; }
.management-row-actions .el-button + .el-button { margin-left: 0; }
.role-delete-target { display: inline-flex; }
.role-tags, .application-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 6px 0; }
.role-tags .el-tag, .application-tags .el-tag { margin: 0; }
@media (max-width: 900px) {
  .user-management-panel { padding: 0 16px 16px; }
  .management-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
  .management-toolbar { margin-bottom: 16px; }
}
</style>
