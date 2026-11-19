<script setup>
import { computed, ref } from 'vue'
import { api } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { t } from '../i18n'
import { requireSuccess } from '../utils/panel'

const items = ref([])
const loading = ref(false)
const q = ref('')
const loaded = ref(false)
const error = ref('')

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const response = requireSuccess(await api.get('/api/plugins'))
    if (!Array.isArray(response?.items)) throw new Error(t('插件列表返回格式错误'))
    items.value = response.items
    loaded.value = true
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return s ? items.value.filter((p) => [p.name, p.description, (p.authors || []).join(',')].some((x) => String(x || '').toLowerCase().includes(s))) : items.value
})
function safeWebsite(value) {
  try {
    const target = new URL(value)
    return ['http:', 'https:'].includes(target.protocol) ? target.href : null
  } catch {
    return null
  }
}

useAutoRefresh(() => { if (!loading.value) return load() }, { immediate: true })
</script>

<template>
  <div class="page plugins-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('插件目录') }}</h1>
      <div class="toolbar">
        <el-input v-model="q" :placeholder="t('搜索插件')" :aria-label="t('搜索插件')" clearable><template #prefix><Icon name="search" :size="14" /></template></el-input>
        <el-button :loading="loading" :title="t('刷新')" :aria-label="t('刷新')" @click="load"><Icon name="refresh-cw" :size="15" /></el-button>
      </div>
    </header>
    <el-alert v-if="error" :title="t('插件列表加载失败')" :description="error" type="error" show-icon :closable="false" />
    <div v-if="loaded || loading" class="plugin-list" :aria-busy="loading">
      <el-table :data="shown" v-loading="loading && !loaded" row-key="name" :empty-text="t('没有找到插件')">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <p v-if="row.description" class="plugin-description">{{ row.description }}</p>
              <div v-if="row.authors?.length"><b>{{ t('作者') }}:</b>{{ row.authors.join(', ') }}</div>
              <div><b>{{ t('主类：') }}</b><span class="mono">{{ row.main }}</span></div>
              <div v-if="row.apiVersion"><b>{{ t('API 版本：') }}</b>{{ row.apiVersion }}</div>
              <div v-if="row.depend?.length"><b>{{ t('依赖：') }}</b>{{ row.depend.join(', ') }}</div>
              <div v-if="row.softDepend?.length"><b>{{ t('软依赖：') }}</b>{{ row.softDepend.join(', ') }}</div>
              <div v-if="row.website"><b>{{ t('网站：') }}</b><a v-if="safeWebsite(row.website)" :href="safeWebsite(row.website)" target="_blank" rel="noopener noreferrer">{{ row.website }}</a><span v-else>{{ row.website }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('名称')" prop="name" sortable min-width="114">
          <template #default="{ row }"><b>{{ row.name }}</b></template>
        </el-table-column>
        <el-table-column :label="t('版本')" prop="version" min-width="74" />
        <el-table-column :label="t('状态')" min-width="90" sortable :sort-by="(r) => (r.enabled ? 0 : 1)">
          <template #default="{ row }"><span class="plugin-state" :class="{ enabled: row.enabled }">{{ t(row.enabled ? '已启用' : '未启用') }}</span></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.plugins-page, .plugin-list { min-width: 0; }
.toolbar { margin: 0; }
.toolbar .el-input { width: 230px; min-width: 0; }
.el-alert { margin-bottom: 16px; }
.plugin-list { border-top: 1px solid var(--lc-line); }
.plugin-state { display: inline-flex; align-items: center; gap: 6px; color: var(--lc-text-soft); }
.plugin-state::before { content: ""; flex: 0 0 6px; height: 6px; border-radius: 50%; background: currentColor; }
.plugin-state.enabled::before { color: var(--el-color-success); }
.expand { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr)); gap: 8px 24px; padding: 14px 24px; font-size: 12px; line-height: 1.7; background: var(--el-fill-color-extra-light); }
.expand > div { min-width: 0; overflow-wrap: anywhere; }
.expand b { font-weight: 500; color: var(--lc-text-soft); margin-inline-end: 6px; }
.plugin-description { grid-column: 1 / -1; margin: 0; overflow-wrap: anywhere; }
.plugin-list :deep(.cell) { overflow-wrap: anywhere; }
@media (max-width: 700px) { .toolbar { width: 100%; flex-wrap: nowrap; } .toolbar .el-input { flex: 1; width: auto; } }
</style>
