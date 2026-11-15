<script setup lang="ts">
import type { Menu } from '../types'
import { menuPath } from '../router'
defineProps<{ menus: Menu[]; parent?: string }>()
</script>
<template>
  <template v-for="menu in [...menus].sort((a, b) => (a.order ?? 0) - (b.order ?? 0))" :key="menu.code">
    <el-sub-menu v-if="menu.children?.length" :index="menuPath(menu, parent)">
      <template #title><span class="menu-dot" /><span>{{ menu.name }}</span></template>
      <MenuTree :menus="menu.children" :parent="menuPath(menu, parent)" />
    </el-sub-menu>
    <el-menu-item v-else :index="menuPath(menu, parent)"><span class="menu-dot" /><span>{{ menu.name }}</span></el-menu-item>
  </template>
</template>
