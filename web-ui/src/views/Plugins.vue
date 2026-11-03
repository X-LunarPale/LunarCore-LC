<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { t } from '../i18n'

const items = ref([])
const loading = ref(false)
const q = ref('')

async function load() {
  loading.value = true
  try {
    items.value = (await api.get('/api/plugins')).items
  } catch (e) {
    ElMessage.error(e.message)
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
        <p class="eyebrow">{{ t('功能与服务') }}</p><h1 class="page-title">{{ t('插件目录') }}</h1>
        <div class="page-sub">{{ t('查看已安装插件的状态与版本，展开列表查看依赖及详细信息。') }}</div>
      </div>
      <div class="toolbar" style="margin: 0">
        <el-input v-model="q" :placeholder="t('搜索插件')" clearable style="width: 220px"><template #prefix><Icon name="search" :size="14" /></template></el-input>
        <el-button @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
      </div>
    </div>
    <div class="plugin-summary"><div><span>{{ t('已安装') }} <b>{{ items.length }}</b></span><span class="enabled-count"><i />{{ t('已启用') }} <b>{{ enabledCount }}</b></span></div><span>{{ t('当前显示 {count} 项', { count: shown.length }) }}</span></div>
    <div class="card plugin-list">
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
.plugins-page{color:var(--lbr-text,#19283b)}.eyebrow{font-size:12px;color:var(--lbr-text-soft,#718096);margin:0 0 8px}.page-title{font-size:25px;font-weight:650;line-height:1.3;letter-spacing:-.5px}.page-sub{font-size:13px;line-height:1.8;margin-top:10px}.page-header{margin-bottom:28px;gap:24px}.toolbar{gap:10px;flex-wrap:wrap}.plugin-summary{display:flex;justify-content:space-between;gap:16px;padding:0 2px 14px;font-size:12px;color:var(--lbr-text-soft,#718096)}.plugin-summary>div{display:flex;gap:24px}.plugin-summary b{font-weight:550;color:var(--lbr-text,#19283b);margin-left:6px}.enabled-count{display:inline-flex;align-items:center;gap:5px}.enabled-count i{width:6px;height:6px;border-radius:50%;background:#10b981}.plugin-list{padding:0;border-radius:10px;overflow:hidden}.plugin-list :deep(.el-table){font-size:13px}.plugin-list :deep(.el-table__cell){padding:18px 8px}.plugin-list :deep(th.el-table__cell){background:var(--el-fill-color-light,#f8fafc);font-weight:500;padding:14px 8px}.plugin-list :deep(.el-table__expand-icon){color:#168cce}.plugin-list :deep(.el-tag){font-size:11px}.expand{padding:18px 48px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px 32px;font-size:13px;line-height:1.8;background:var(--el-fill-color-light,#f8fafc)}.expand>div{overflow-wrap:anywhere;min-width:0}.expand b{font-weight:500;color:var(--lbr-text-soft,#718096)}.expand a{color:#168cce}.expand a:focus-visible{outline:2px solid #168cce;outline-offset:3px}@media(max-width:760px){.page-header{align-items:flex-start;flex-direction:column;gap:18px}.toolbar{width:100%}.toolbar :deep(.el-input){flex:1;min-width:160px}.page-title{font-size:26px}.expand{grid-template-columns:1fr;padding:20px 24px}.plugin-summary{gap:12px}.plugin-summary>div{gap:16px}}
.plugins-page{min-width:0}.eyebrow{font-size:11px;margin-bottom:5px;letter-spacing:.08em}.page-sub{margin-top:6px}.page-header{margin-bottom:20px;gap:16px}.plugin-list{border-radius:8px}.plugin-list :deep(.el-table__cell){padding:10px 0}.plugin-list :deep(th.el-table__cell){padding:8px 0}.plugin-list :deep(.el-table__expand-icon),.expand a{color:var(--el-color-primary)}.expand a:focus-visible{outline-color:var(--el-color-primary)}.expand{padding:14px 24px;gap:8px 24px;font-size:12px}.plugin-summary{font-size:12px;flex-wrap:wrap;gap:8px;padding-bottom:12px}.enabled-count i{background:var(--el-color-success)}.toolbar :deep(.el-input){min-width:0;max-width:100%}@media(max-width:600px){.page-title{font-size:23px}.page-header{gap:14px}.toolbar{width:100%;flex-wrap:nowrap;gap:8px}.toolbar :deep(.el-input){width:auto!important;flex:1}.plugin-summary>div{gap:14px}.expand{padding:12px 16px}.plugin-list{padding:0}}
</style>
