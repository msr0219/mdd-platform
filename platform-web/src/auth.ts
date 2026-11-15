import { reactive } from 'vue'
import { authRequest, setCsrfToken } from './api'
export interface User { id: number; username: string; name: string; roles: string[]; permissions: string[]; effectivePermissions?: string[]; mustChangePassword: boolean; csrfToken: string }
export const auth = reactive({ user: null as User | null })
export function can(permission?: string): boolean {
  if (!permission) return true
  const list = auth.user?.effectivePermissions || auth.user?.permissions || []
  return list.includes('*') || list.includes(permission) || list.includes(`${permission.split(':')[0]}:*`)
}
export function setUser(user: User | null) { auth.user = user; setCsrfToken(user?.csrfToken || '') }
export async function restoreSession() { try { setUser(await authRequest<User>('/me')) } catch { setUser(null) } }
export async function login(username: string, password: string) { setUser(await authRequest<User>('/login', 'POST', { username, password })) }
export async function logout() { try { await authRequest('/logout', 'POST', {}) } finally { setUser(null) } }
