import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/rpc': 'http://127.0.0.1:8080', '/api': 'http://127.0.0.1:8080' } },
  build: {
    chunkSizeWarningLimit: 1100,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          return /element-plus|floating-ui|lodash|dayjs|async-validator|normalize-wheel|memoize-one/.test(id) ? 'ui-vendor' : 'vendor'
        },
      },
    },
  },
})
