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
const enabledCount = computed(() => items.value.filter((p) => p.enabled).length)

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
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('插件目录') }}</h1>
      </div>
      <div class="toolbar" style="margin: 0">
        <el-input v-model="q" :placeholder="t('搜索插件')" :aria-label="t('搜索插件')" clearable><template #prefix><Icon name="search" :size="14" /></template></el-input>
        <el-button :loading="loading" @click="load"><Icon name="refresh-cw" :size="14" />{{ t('刷新') }}</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="t('插件列表加载失败')" :description="error" type="error" show-icon :closable="false" />
    <div v-if="loaded" class="plugin-summary"><div><span>{{ t('已安装') }} <b>{{ items.length }}</b></span><span class="enabled-count"><i />{{ t('已启用') }} <b>{{ enabledCount }}</b></span></div><span>{{ t('当前显示 {count} 项', { count: shown.length }) }}</span></div>
    <div class="plugin-list">
      <el-table :data="shown" v-loading="loading" row-key="name" :empty-text="t('没有找到插件')">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div><b>{{ t('主类：') }}</b><span class="mono">{{ row.main }}</span></div>
              <div v-if="row.apiVersion"><b>{{ t('API 版本：') }}</b>{{ row.apiVersion }}</div>
              <div v-if="row.depend?.length"><b>{{ t('依赖：') }}</b>{{ row.depend.join(', ') }}</div>
              <div v-if="row.softDepend?.length"><b>{{ t('软依赖：') }}</b>{{ row.softDepend.join(', ') }}</div>
              <div v-if="row.website"><b>{{ t('网站：') }}</b><a v-if="safeWebsite(row.website)" :href="safeWebsite(row.website)" target="_blank" rel="noopener noreferrer">{{ row.website }}</a><span v-else>{{ row.website }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('名称')" prop="name" sortable min-width="160">
          <template #default="{ row }"><b>{{ row.name }}</b></template>
        </el-table-column>
        <el-table-column :label="t('版本')" prop="version" width="150" />
        <el-table-column :label="t('状态')" width="100" sortable :sort-by="(r) => (r.enabled ? 0 : 1)">
          <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'danger'" size="small">{{ row.enabled ? t('已启用') : t('未启用') }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('作者')" min-width="140"><template #default="{ row }">{{ (row.authors || []).join(', ') }}</template></el-table-column>
        <el-table-column :label="t('描述')" prop="description" min-width="260" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.plugins-page, .plugin-list { min-width: 0; }
.toolbar { margin: 0; gap: 8px; }
.toolbar .el-input { width: 230px; min-width: 0; }
.toolbar .el-button { gap: 7px; }
.el-alert { margin-bottom: 16px; }
.plugin-summary { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; padding-bottom: 16px; font-size: 12px; color: var(--lc-text-soft); }
.plugin-summary > div { display: flex; gap: 22px; }
.plugin-summary b { margin-left: 5px; color: var(--lc-text); font-weight: 600; }
.enabled-count { display: inline-flex; align-items: center; gap: 6px; }
.enabled-count i { width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-success); }
.plugin-list { overflow: hidden; border-top: 1px solid var(--el-border-color); }
.expand { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 26px; padding: 16px 32px; font-size: 12px; line-height: 1.8; background: var(--el-fill-color-extra-light); }
.expand > div { min-width: 0; overflow-wrap: anywhere; }
.expand b { font-weight: 500; color: var(--lc-text-soft); margin-right: 7px; }
@media (max-width: 700px) { .toolbar { width: 100%; flex-wrap: nowrap; }.toolbar .el-input { flex: 1; width: auto; }.expand { grid-template-columns: 1fr; padding: 14px 18px; }.plugin-summary > div { gap: 14px; } }
</style>
