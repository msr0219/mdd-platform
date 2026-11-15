<script setup lang="ts">
import { ref } from 'vue'
import { auth, login, setUser } from '../auth'
import { authRequest } from '../api'
const props = defineProps<{ changeOnly?: boolean }>()
const emit = defineEmits<{ ready: []; changed: [] }>()
const username = ref('admin'), password = ref(''), nextPassword = ref(''), confirm = ref(''), error = ref(''), busy = ref(false)
async function submit() {
  if (busy.value) return
  error.value = ''; busy.value = true
  try {
    if (props.changeOnly || auth.user?.mustChangePassword) {
      if (nextPassword.value !== confirm.value) throw new Error('两次输入的新密码不一致')
      await authRequest('/password', 'POST', { oldPassword: password.value, newPassword: nextPassword.value })
      setUser(null); error.value = '密码已修改，请使用新密码重新登录'; password.value = ''; nextPassword.value = ''; confirm.value = ''; emit('changed')
    } else { await login(username.value, password.value); password.value = ''; if (!auth.user?.mustChangePassword) emit('ready') }
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
</script>
<template>
  <div class="access-wrap" :class="{ 'access-inline': changeOnly }"><section class="access-card">
    <div class="brand access-brand"><span class="brand-symbol">M</span><span>元构<span class="brand-sub">MODEL DRIVEN</span></span></div>
    <h1>{{ changeOnly || auth.user?.mustChangePassword ? '修改密码' : '登录工作空间' }}</h1>
    <p class="muted">{{ auth.user?.mustChangePassword ? '首次登录或密码被重置，请先设置自己的密码。' : '使用管理员分配的账号访问模型与业务。' }}</p>
    <el-alert v-if="error" :title="error" type="info" :closable="false" show-icon />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item v-if="!changeOnly && !auth.user?.mustChangePassword" label="用户名"><el-input v-model="username" autocomplete="username" maxlength="50" /></el-form-item>
      <el-form-item :label="changeOnly || auth.user?.mustChangePassword ? '原密码' : '密码'"><el-input v-model="password" type="password" show-password autocomplete="current-password" maxlength="128" /></el-form-item>
      <template v-if="changeOnly || auth.user?.mustChangePassword"><el-form-item label="新密码"><el-input v-model="nextPassword" type="password" show-password autocomplete="new-password" placeholder="6~128位，包含字母和数字或符号" maxlength="128" /></el-form-item><el-form-item label="确认新密码"><el-input v-model="confirm" type="password" autocomplete="new-password" maxlength="128" /></el-form-item></template>
      <el-button type="primary" native-type="submit" :loading="busy" class="widget-full">{{ changeOnly || auth.user?.mustChangePassword ? '修改密码并重新登录' : '登录' }}</el-button>
    </el-form>
    <p v-if="!changeOnly && !auth.user" class="field-help">首次启动的管理员账号为 admin。初始密码保存在项目 .logs/admin-initial-password.txt；如已由环境变量配置，请使用配置的密码。</p>
  </section></div>
</template>
