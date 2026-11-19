<script setup>
import { computed, defineAsyncComponent, h, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useApp } from '../stores/app'
import { url } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { panelText } from '../utils/panel'
import TableSection from '../components/panel/TableSection.vue'
import FormSection from '../components/panel/FormSection.vue'
import StatsSection from '../components/panel/StatsSection.vue'
import WorkspaceTabs from '../components/panel/WorkspaceTabs.vue'
import { t } from '../i18n'

const route = useRoute()
const app = useApp()
const panel = computed(() => app.findPanel(route.params.ns, route.params.id))
const panelKey = computed(() => `${route.params.ns}/${route.params.id}`)
const activeGroup = ref('overview')
const menuLoading = ref(false)
const menuError = ref('')
const groupOf = section => ({ form: 'settings', table: 'records', text: 'help' })[section.type] || 'overview'
const groups = computed(() => [
  { id: 'overview', label: '运行概览', icon: 'activity' },
  { id: 'records', label: '数据记录', icon: 'table-2' },
  { id: 'settings', label: '设置', icon: 'sliders-horizontal' },
  { id: 'help', label: '说明', icon: 'book-open' }
].map(group => ({
  ...group,
  panelId: `module-content-${group.id}`,
  sections: (panel.value?.sections || []).filter(section => groupOf(section) === group.id)
})).filter(group => group.sections.length))
watch(panelKey, () => { activeGroup.value = groups.value[0]?.id || 'overview' }, { immediate: true })
watch(groups, available => {
  if (available.length && !available.some(group => group.id === activeGroup.value)) activeGroup.value = available[0].id
})

const loadingView = { render: () => h('div', { class: 'workspace-loading', role: 'status' }, t('正在加载模块界面…')) }
const errorView = {
  render: () => h('div', { class: 'workspace-load-error', role: 'alert' }, [
    h('p', t('模块界面加载失败，请刷新页面重试。')),
    h('button', { type: 'button', onClick: () => window.location.reload() }, t('刷新页面'))
  ])
}
const lazy = loader => defineAsyncComponent({ loader, loadingComponent: loadingView, errorComponent: errorView, delay: 120 })
const workspaces = {
  maintenance: lazy(() => import('../components/maintenance/MaintenanceWorkspace.vue')),
  antixray: lazy(() => import('../components/antixray/AntixrayWorkspace.vue')),
  multiverse: lazy(() => import('../components/multiverse/MultiverseWorkspace.vue')),
  terrain: lazy(() => import('../components/terrain/TerrainWorkspace.vue')),
  ai: lazy(() => import('../components/ai/AiChatWorkspace.vue')),
  qqauth: lazy(() => import('../components/qqauth/QQAuthWorkspace.vue')),
  moderation: lazy(() => import('../components/moderation/ModerationWorkspace.vue'))
}
const workspace = computed(() => {
  const namespace = route.params.ns, id = route.params.id
  if (namespace === 'motd' && id === 'motd') return workspaces.maintenance
  if (namespace === 'antixray') return workspaces.antixray
  if (namespace === 'multiverse' && id === 'worlds') return workspaces.multiverse
  if (namespace === 'terrain' && id === 'tectonic') return workspaces.terrain
  if (namespace === 'chat' && id === 'ai') return workspaces.ai
  if (namespace === 'qqauth' && id === 'accounts') return workspaces.qqauth
  if (['ban', 'anticheat'].includes(namespace) || ['ban', 'anticheat'].includes(id)) return workspaces.moderation
  return null
})
const sectionKey = (section, index) => `${panelKey.value}/${section.type}/${section.endpoint || section.loadEndpoint || section.title || index}/${index}`
const spanStyle = section => ({ gridColumn: `span ${section.type === 'stats' ? Math.min(24, Math.max(1, section.span || 24)) : 24}` })
function iframeSrc(value) {
  if (!value) return ''
  if (/^https?:\/\//i.test(value)) return value
  return url(value.startsWith('/') ? value : `/ext/${route.params.ns}/${value}`)
}
async function refreshMenu() {
  if (menuLoading.value) return
  menuLoading.value = true
  menuError.value = ''
  try { await app.loadPanels() }
  catch (error) { menuError.value = error.message }
  finally { menuLoading.value = false }
}
</script>

<template>
  <div class="page module-page">
    <template v-if="panel">
      <component :is="workspace" v-if="workspace" :key="panelKey" :panel="panel" :namespace="route.params.ns" :id="route.params.id" />
      <template v-else>
        <header class="page-header"><h1 class="page-title">{{ t(panel.title) }}</h1></header>
        <WorkspaceTabs v-if="groups.length > 1" v-model="activeGroup" :items="groups" label="模块内容分区" id="module-content" />
        <div v-for="group in groups" v-show="group.id === activeGroup" :id="group.panelId" :key="group.id"
          class="panel-sections" :role="groups.length > 1 ? 'tabpanel' : undefined"
          :aria-labelledby="groups.length > 1 ? `module-content-tab-${group.id}` : undefined">
          <section v-for="(section, index) in group.sections" :key="sectionKey(section, index)" :style="spanStyle(section)" class="panel-section">
            <TableSection v-if="section.type === 'table'" :section="section" :namespace="panel.namespace" :active="group.id === activeGroup" />
            <FormSection v-else-if="section.type === 'form'" :section="section" :namespace="panel.namespace" :active="group.id === activeGroup" />
            <StatsSection v-else-if="section.type === 'stats'" :section="section" :namespace="panel.namespace" :active="group.id === activeGroup" />
            <template v-else-if="section.type === 'iframe'">
              <h2 v-if="section.title" class="section-title">{{ t(section.title) }}</h2>
              <iframe v-if="group.id === activeGroup" :src="iframeSrc(section.url)" :title="t(section.title || '扩展视图')" :style="{ height: (section.height || 600) + 'px' }" class="frame" />
            </template>
            <template v-else-if="section.type === 'text'">
              <h2 v-if="section.title" class="section-title">{{ t(section.title) }}</h2>
              <div class="markdown" v-html="renderMarkdown(panelText(section.markdown, section.markdownKey))" />
            </template>
            <p v-else class="muted">{{ t('不支持的区块类型：{type}', { type: section.type }) }}</p>
          </section>
        </div>
        <el-empty v-if="!panel.sections?.length" :description="t('该面板没有内容')" />
      </template>
    </template>
    <el-result v-else icon="warning" :title="t('面板不存在')" :sub-title="t('对应模块可能已被禁用，或你没有查看该面板的权限。')">
      <template #extra><p v-if="menuError" role="alert">{{ menuError }}</p><el-button type="primary" :loading="menuLoading" @click="refreshMenu"><Icon name="refresh-cw" :size="15" />{{ t('刷新菜单') }}</el-button></template>
    </el-result>
  </div>
</template>

<style scoped>
.module-page { color:var(--lc-text); }
.panel-sections { display:grid; grid-template-columns:repeat(24,minmax(0,1fr)); gap:24px 8px; }
.panel-section { min-width:0; }
.section-title { margin:0 0 16px; font-size:15px; font-weight:600; }
.frame { width:100%; border:1px solid var(--el-border-color-light); border-radius:6px; background:var(--lc-surface); }
.markdown { max-width:88ch; }
:deep(.workspace-loading),:deep(.workspace-load-error) { min-height:180px; display:grid; place-content:center; justify-items:center; color:var(--lc-text-soft); font-size:13px; }
:deep(.workspace-load-error button) { border:1px solid var(--el-border-color); border-radius:5px; background:var(--lc-surface); color:var(--el-color-primary); padding:7px 12px; cursor:pointer; }
@media (max-width:760px) { .panel-sections { grid-template-columns:minmax(0,1fr); gap:20px; } .panel-section { grid-column:1/-1!important; } }
</style>
