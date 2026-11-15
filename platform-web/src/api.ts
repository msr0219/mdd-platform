let nextId = 0
let csrfToken = ''
export function setCsrfToken(value: string) { csrfToken = value }
export async function authRequest<T = any>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/api/auth${path}`, { method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const data = await response.json()
  if (!response.ok) { if (response.status === 401 && path !== '/login') window.dispatchEvent(new Event('mdd:unauthorized')); throw new Error(data.message || `请求失败（${response.status}）`) }
  return data
}
export async function attachmentRequest<T = any>(path = '', method = 'GET', body?: FormData, responseType: 'json' | 'blob' = 'json'): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 60000)
  try {
    const response = await fetch(`/api/attachments${path}`, {
      method, credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
      ...(body ? { body } : {}), signal: controller.signal,
    })
    if (!response.ok) {
      if (response.status === 401) window.dispatchEvent(new Event('mdd:unauthorized'))
      const detail = await response.json().catch(() => ({}))
      throw new Error(detail.message || (response.status === 413 ? '文件超过上传大小限制' : `附件请求失败（HTTP ${response.status}）`))
    }
    return (responseType === 'blob' ? await response.blob() : await response.json()) as T
  } catch (error) {
    if (error instanceof TypeError) throw new Error('无法连接附件服务，请稍后重试')
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('附件请求超时，请稍后重试')
    throw error
  } finally { window.clearTimeout(timer) }
}
export class RpcError extends Error {
  constructor(public code: number, message: string, public data?: { fields?: { code: string; message: string }[]; [key: string]: unknown }) { super(message) }
}
export async function rpc<T = any>(method: string, params: unknown = {}): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 30000)
  try {
    const response = await fetch('/rpc', {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++nextId, method, params }), signal: controller.signal,
    })
    if (!response.ok) { if (response.status === 401) window.dispatchEvent(new Event('mdd:unauthorized')); const detail = await response.json().catch(() => ({})); throw new Error(detail.message || `服务请求失败（HTTP ${response.status}）`) }
    const result = await response.json()
    if (result.error) throw new RpcError(result.error.code, result.error.message, result.error.data)
    return result.result as T
  } catch (error) {
    if (error instanceof TypeError) throw new Error('无法连接后端服务，请确认后端已在 8080 端口启动')
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('请求超时，请稍后重试')
    throw error
  } finally { window.clearTimeout(timer) }
}
