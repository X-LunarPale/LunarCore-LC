<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAutoRefresh } from '../utils/autoRefresh'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { t } from '../i18n'
import ModuleMarket from './ModuleMarket.vue'

const auth = useAuth()
const app = useApp()
const items = ref([])
const loading = ref(false)
const busy = ref('')
const q = ref('')
const tab = ref('installed')

async function load() {
  loading.value = true
  try {
    items.value = (await api.get('/api/modules')).items
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  if (!s) return items.value
  return items.value.filter((m) => [m.id, m.name, m.description].some((x) => String(x || '').toLowerCase().includes(s)))
})

async function op(m, action) {
  if (action === 'disable') {
    const ok = await ElMessageBox.confirm(t('确定禁用模块「{name}」吗？依赖它的模块也会被禁用。', { name: m.name }), t('禁用模块'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
  }
  busy.value = m.id + action
  try {
    const r = await api.post(`/api/modules/${encodeURIComponent(m.id)}/${action}`)
    ElMessage.success(r.message || t({ enable: '已启用', disable: '已禁用', reload: '已重载' }[action]))
    await load()
    app.loadPanels()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = ''
  }
}

useAutoRefresh(() => { if (!loading.value && !busy.value) return load() }, { immediate: true, active: () => tab.value === 'installed' })
</script>

<template>
  <div class="page modules-page">
    <div class="page-header">
      <div>
        <p class="eyebrow">{{ t('功能与服务') }}</p><h1 class="page-title">{{ t('模块管理') }}</h1>
        <div class="page-sub">{{ t('查看模块状态、依赖关系及运行配置。') }}</div>
      </div>
      <div v-if="tab === 'installed'" class="toolbar" style="margin: 0">
        <el-input v-model="q" :placeholder="t('搜索模块')" clearable style="width: 200px"><template #prefix><Icon name="search" :size="14" /></template></el-input>
        <el-button @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
      </div>
    </div>
    <el-tabs v-model="tab"><el-tab-pane :label="t('已安装')" name="installed" /><el-tab-pane :label="t('模块市场')" name="market" /></el-tabs>
    <ModuleMarket v-if="tab === 'market'" />
    <div v-if="tab === 'installed'" class="list-summary"><span>{{ t('模块目录') }} <b>{{ items.length }}</b></span><span>{{ t('当前显示 {count} 项', { count: shown.length }) }}</span></div>
    <div v-if="tab === 'installed'" v-loading="loading" class="grid">
      <div v-for="m in shown" :key="m.id" class="card mod" :class="{ off: !m.enabled }">
        <div class="mod-head">
          <div class="mod-icon"><Icon name="package" :size="22" /></div>
          <div class="mod-name">
            <div>{{ m.name }} <span class="muted ver">v{{ m.version }}</span></div>
            <div class="muted mono id">{{ m.id }}</div>
          </div>
          <el-tag :type="m.enabled ? 'success' : 'info'" effect="light" round>{{ t(m.enabled ? '已启用' : m.loaded ? '已加载' : '未启用') }}</el-tag>
        </div>
        <div class="details"><div class="desc">{{ m.description || t('暂无描述') }}</div>
        <div class="meta">
          <span v-if="m.authors?.length"><Icon name="user" :size="12" /> {{ m.authors.join(', ') }}</span>
          <span v-if="m.depends?.length"><Icon name="link" :size="12" /> {{ t('依赖') }} {{ m.depends.join(', ') }}</span>
          <span v-if="m.softDepends?.length" class="muted">{{ t('可选') }} {{ m.softDepends.join(', ') }}</span>
        </div>
        </div>
        <div v-if="auth.can('core.modules.manage')" class="ops">
          <el-button v-if="!m.enabled" size="small" type="primary" :loading="busy === m.id + 'enable'" @click="op(m, 'enable')">{{ t('启用') }}</el-button>
          <template v-else>
            <el-button size="small" :loading="busy === m.id + 'reload'" @click="op(m, 'reload')"><Icon name="rotate-cw" :size="13" />&nbsp;{{ t('重载') }}</el-button>
            <el-button size="small" type="danger" plain :loading="busy === m.id + 'disable'" @click="op(m, 'disable')">{{ t('禁用') }}</el-button>
          </template>
        </div>
      </div>
      <el-empty v-if="!loading && !shown.length" :description="t('没有找到模块')" />
    </div>
  </div>
</template>

<style scoped>
.modules-page{color:var(--lc-text,#19283b)}.eyebrow{font-size:12px;color:var(--lc-text-soft,#718096);margin:0 0 8px}.page-title{font-size:25px;line-height:1.3;font-weight:650;letter-spacing:-.5px}.page-sub{font-size:13px;line-height:1.8;margin-top:10px}.page-header{margin-bottom:28px;gap:24px}.toolbar{flex-wrap:wrap;gap:10px}.list-summary{display:flex;justify-content:space-between;gap:16px;align-items:center;font-size:12px;color:var(--lc-text-soft,#718096);padding:0 2px 12px}.list-summary b{font-weight:500;color:var(--lc-text,#19283b);margin-left:8px}.grid{display:flex;flex-direction:column;min-height:120px;background:var(--lc-surface,#fff);border:1px solid var(--lc-sidebar-border,#e3e8ef);border-radius:10px;overflow:hidden}.grid .card+.card{margin-top:0}.mod{display:grid;grid-template-columns:minmax(250px,.85fr) minmax(220px,1.2fr) auto;align-items:center;gap:28px;padding:24px;border:0;border-radius:0;border-bottom:1px solid var(--lc-sidebar-border,#e3e8ef);box-shadow:none;transition:background-color 180ms ease}.mod:last-of-type{border-bottom:0}.mod-head{display:flex;align-items:center;gap:12px;min-width:0}.mod-icon{width:36px;height:36px;flex-shrink:0;border-radius:8px;display:flex;align-items:center;justify-content:center;background:var(--el-fill-color-light,#f4f6f9);color:#168cce}.mod.off .mod-icon{color:var(--lc-text-soft,#718096)}.mod-name{flex:1;font-weight:600;font-size:14px;overflow-wrap:anywhere}.ver{font-weight:400;font-size:12px;white-space:nowrap}.id{font-size:12px;font-weight:400;margin-top:6px}.mod-head :deep(.el-tag){font-size:11px;flex-shrink:0}.desc{color:var(--el-text-color-regular,#4d5f74);font-size:13px;line-height:1.8;overflow-wrap:anywhere}.meta{display:flex;flex-wrap:wrap;gap:6px 14px;margin-top:8px;font-size:12px;line-height:1.7;color:var(--lc-text-soft,#718096)}.meta span{display:inline-flex;align-items:center;gap:4px;overflow-wrap:anywhere}.ops{display:flex;gap:8px;justify-content:flex-end;min-width:142px}.ops :deep(.el-button+.el-button){margin-left:0}.ops :deep(.el-button){min-height:32px}.details{min-width:0}@media(hover:hover) and (pointer:fine){.mod:hover{background:var(--el-fill-color-light,#f8fafc)}}@media(max-width:1100px){.mod{grid-template-columns:minmax(220px,1fr) minmax(200px,1fr);gap:16px}.ops{grid-column:2;justify-content:flex-end}.details{grid-column:2;grid-row:1/3}.mod-head{flex-wrap:wrap}.ops{grid-column:1;justify-content:flex-start}}@media(max-width:680px){.page-header{align-items:flex-start;flex-direction:column;gap:18px}.toolbar{width:100%}.toolbar :deep(.el-input){flex:1;min-width:160px}.page-title{font-size:26px}.mod{grid-template-columns:1fr;padding:20px;gap:14px}.details,.ops{grid-column:auto;grid-row:auto}.ops{justify-content:flex-start}.mod-head{flex-wrap:nowrap}.mod-name{min-width:0}}@media(prefers-reduced-motion:reduce){.mod{transition:none}}
.modules-page{min-width:0}.eyebrow{font-size:11px;margin-bottom:5px;letter-spacing:.08em}.page-sub{margin-top:6px}.page-header{margin-bottom:20px;gap:16px}.grid{border-radius:8px}.mod{grid-template-columns:minmax(230px,.9fr) minmax(180px,1.2fr) auto;padding:16px 18px;gap:18px}.mod-icon{width:30px;height:30px;border-radius:6px;color:var(--el-color-primary)}.mod-icon :deep(svg){width:17px;height:17px}.mod-name{font-size:13px}.id{margin-top:3px;font-size:11px}.desc{font-size:12px;line-height:1.7}.meta{margin-top:5px;font-size:11px}.mod-head :deep(.el-tag){border-radius:4px}.toolbar :deep(.el-input){min-width:0;max-width:100%}.details,.meta,.meta span{min-width:0;overflow-wrap:anywhere}.meta span{display:inline;}.list-summary{flex-wrap:wrap;gap:8px}@media(max-width:1100px){.mod{grid-template-columns:minmax(0,1fr) auto;gap:10px 16px}.details{grid-column:1/-1;grid-row:2}.ops{grid-column:2;grid-row:1}.mod-head{min-width:0}.mod-head :deep(.el-tag){margin-left:auto}}@media(max-width:600px){.page-title{font-size:23px}.page-header{gap:14px}.toolbar{width:100%;gap:8px;flex-wrap:nowrap}.toolbar :deep(.el-input){flex:1;width:auto!important}.mod{grid-template-columns:minmax(0,1fr);padding:14px;gap:10px}.mod-head{grid-column:1;grid-row:1}.details{grid-column:1;grid-row:2}.ops{grid-column:1;grid-row:3;justify-content:flex-start;min-width:0}.mod-head :deep(.el-tag){margin-left:0}.mod-icon{display:none}}
</style>
