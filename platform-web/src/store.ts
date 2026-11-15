import { defineStore } from 'pinia'
import { ref } from 'vue'
import { rpc } from './api'
import type { Menu, Model, Status, View } from './types'

export const useMetaStore = defineStore('metadata', () => {
  const menus = ref<Menu[]>([])
  const version = ref(0)
  const status = ref<Status | null>(null)
  const loading = ref(false)
  const error = ref('')
  const views = new Map<string, Promise<View>>()
  const models = new Map<string, Promise<Model>>()

  async function refresh() {
    loading.value = true
    try {
      const [menuResult, nextStatus] = await Promise.all([
        rpc<{ version: number; menus: Menu[] }>('meta.getMenus'), rpc<Status>('meta.status'),
      ])
      menus.value = menuResult.menus
      version.value = menuResult.version
      status.value = nextStatus
      views.clear(); models.clear(); error.value = ''
    } catch (err) { error.value = (err as Error).message; throw err }
    finally { loading.value = false }
  }
  async function getView(code: string): Promise<View> {
    if (!views.has(code)) views.set(code, rpc<{ views: View[] }>('meta.getViews', { code }).then(result => {
      const view = result.views.find(item => item.code === code)
      if (!view) throw new Error(`视图 ${code} 不存在`)
      return view
    }).catch(err => { views.delete(code); throw err }))
    return views.get(code)!
  }
  async function getModel(code: string): Promise<Model> {
    if (!models.has(code)) models.set(code, rpc<Model>('meta.getModel', { code }).catch(err => { models.delete(code); throw err }))
    return models.get(code)!
  }
  return { menus, version, status, loading, error, refresh, getView, getModel }
})
