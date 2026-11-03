import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把 /api 代理到本地运行的插件（默认端口 8765），可用环境变量 LBR_API 覆盖
const target = process.env.LBR_API || 'http://127.0.0.1:8765'

export default defineConfig({
  // 相对路径：配合后端注入的 <base href>，支持任意 base-path 部署
  base: './',
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          icons: ['lucide-vue-next'],
          vue: ['vue', 'vue-router', 'pinia']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target, changeOrigin: true, ws: true },
      '/ext': { target, changeOrigin: true }
    }
  }
})
