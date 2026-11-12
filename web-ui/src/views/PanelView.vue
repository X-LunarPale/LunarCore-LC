<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useApp } from '../stores/app'
import { url } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { panelText } from '../utils/panel'
import { navigationGroup } from '../utils/navigation'
import TableSection from '../components/panel/TableSection.vue'
import FormSection from '../components/panel/FormSection.vue'
import StatsSection from '../components/panel/StatsSection.vue'
import ModerationWorkspace from '../components/moderation/ModerationWorkspace.vue'
import AntixrayWorkspace from '../components/antixray/AntixrayWorkspace.vue'
import MaintenanceWorkspace from '../components/maintenance/MaintenanceWorkspace.vue'
import MultiverseWorkspace from '../components/multiverse/MultiverseWorkspace.vue'
import TerrainWorkspace from '../components/terrain/TerrainWorkspace.vue'
import AiChatWorkspace from '../components/ai/AiChatWorkspace.vue'
import QQAuthWorkspace from '../components/qqauth/QQAuthWorkspace.vue'
import { t } from '../i18n'

const route = useRoute()
const app = useApp()
const panel = computed(() => app.findPanel(route.params.ns, route.params.id))
const activeGroup = ref('records')
const groupOf = section => section.type === 'form' ? 'settings' : section.type === 'text' ? 'help' : 'records'
const groups = computed(() => [
  { id: 'records', label: t('概览与记录') }, { id: 'settings', label: t('设置') }, { id: 'help', label: t('说明') }
].filter(group => panel.value?.sections?.some(s => groupOf(s) === group.id)))
// 面板定义会由 MainLayout 定时刷新；只在真正切换面板时重置分区，避免后台刷新把用户从“设置”带回“概览与记录”。
watch(() => `${route.params.ns}/${route.params.id}`, () => { activeGroup.value = groups.value[0]?.id || 'records' }, { immediate: true })
// 首次菜单恢复或分区变更时，不能把用户留在已不存在的分区。
watch(groups, (available) => {
  if (available.length && !available.some(group => group.id === activeGroup.value)) activeGroup.value = available[0].id
})

const spanStyle = (s) => ({ gridColumn: `span ${Math.min(24, Math.max(1, s.span || 24))}` })
const sectionLabels = { table: '数据列表', form: '配置设置', stats: '运行概览', text: '使用说明', iframe: '扩展视图' }
function goSection(index) {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  document.getElementById(`panel-section-${index}`)?.scrollIntoView({ behavior: reduced ? 'instant' : 'smooth', block: 'start' })
}

function iframeSrc(u) {
  if (!u) return ''
  if (/^https?:\/\//i.test(u)) return u
  return url(u.startsWith('/') ? u : `/ext/${route.params.ns}/${u}`)
}
</script>

<template>
  <div class="page">
    <template v-if="panel">
      <MaintenanceWorkspace v-if="route.params.ns === 'motd' && route.params.id === 'motd'" :panel="panel" />
      <AntixrayWorkspace v-else-if="route.params.ns === 'antixray'" :panel="panel" />
      <MultiverseWorkspace v-else-if="route.params.ns === 'multiverse' && route.params.id === 'worlds'" />
      <TerrainWorkspace v-else-if="route.params.ns === 'terrain' && route.params.id === 'tectonic'" />
      <AiChatWorkspace v-else-if="route.params.ns === 'chat' && route.params.id === 'ai'" />
      <QQAuthWorkspace v-else-if="route.params.ns === 'qqauth' && route.params.id === 'accounts'" :panel="panel" />
      <ModerationWorkspace v-else-if="route.params.ns === 'ban' || route.params.ns === 'anticheat' || route.params.id === 'ban' || route.params.id === 'anticheat'" :panel="panel" :namespace="route.params.ns" :id="route.params.id" />
      <template v-else>
      <div class="page-header">
        <div>
          <h2 class="page-title"><Icon :name="panel.icon || 'box'" :size="22" />{{ t(panel.title) }}</h2>
          <div class="page-sub">{{ t(navigationGroup(panel).title) }}<span class="sub-divider">/</span>{{ t('配置、记录与运行状态') }}</div>
        </div>
      </div>
      <nav v-if="groups.length > 1" class="section-index" :aria-label="t('模块内容分区')" role="tablist"><button v-for="group in groups" :key="group.id" type="button" role="tab" :aria-selected="activeGroup === group.id" :class="{selected: activeGroup === group.id}" @click="activeGroup = group.id">{{ group.label }}</button></nav>
      <div class="section-grid">
        <Transition v-for="(s, i) in panel.sections" :key="`${panel.namespace}/${panel.id}/${i}`" name="section-reveal" appear>
        <div v-show="groupOf(s) === activeGroup" :id="`panel-section-${i}`" :style="{ ...spanStyle(s), '--section-order': i % 4 }" class="section-cell">
          <TableSection v-if="s.type === 'table'" :section="s" :namespace="panel.namespace" :active="groupOf(s) === activeGroup" />
          <FormSection v-else-if="s.type === 'form'" :section="s" :namespace="panel.namespace" :active="groupOf(s) === activeGroup" />
          <StatsSection v-else-if="s.type === 'stats'" :section="s" :namespace="panel.namespace" :active="groupOf(s) === activeGroup" />
          <div v-else-if="s.type === 'iframe'" class="card">
            <div class="card-title" v-if="s.title"><Icon name="app-window" :size="16" />{{ t(s.title) }}</div>
            <iframe :src="iframeSrc(s.url)" :style="{ height: (s.height || 600) + 'px' }" class="frame" />
          </div>
          <div v-else-if="s.type === 'text'" class="card">
            <div class="card-title" v-if="s.title"><Icon name="file-text" :size="16" />{{ t(s.title) }}</div>
            <div class="markdown" v-html="renderMarkdown(panelText(s.markdown, s.markdownKey))" />
          </div>
          <div v-else class="card muted">{{ t('不支持的区块类型：{type}', { type: s.type }) }}</div>
        </div>
        </Transition>
      </div>
      <el-empty v-if="!panel.sections || !panel.sections.length" :description="t('该面板没有内容')" />
      </template>
    </template>
    <el-result v-else icon="warning" :title="t('面板不存在')" :sub-title="t('对应模块可能已被禁用，或你没有查看该面板的权限。')">
      <template #extra><el-button type="primary" @click="app.loadPanels()">{{ t('刷新菜单') }}</el-button></template>
    </el-result>
  </div>
</template>

<style scoped>
.page { color: var(--lc-text); }
.sub-divider { margin: 0 10px; color: var(--el-border-color); }
.section-grid { display: grid; grid-template-columns: repeat(24, minmax(0, 1fr)); gap: 24px; }
.section-index { display: flex; gap: 6px; align-items: center; overflow-x: auto; position: sticky; top: 0; z-index: 8; padding: 8px 0 12px; margin: 0 0 20px; background: var(--lc-bg); border-bottom: 1px solid var(--el-border-color-light); scrollbar-width: thin; }
.index-label { flex: 0 0 auto; color: var(--lc-text-soft); font-size: 11px; padding: 0 12px; border-right: 1px solid var(--el-border-color-light); margin-right: 4px; }
.section-index button { border: 0; border-radius: 6px; background: transparent; color: var(--el-text-color-regular); white-space: nowrap; padding: 8px 12px; cursor: pointer; font-size: 12px; transition: color 180ms, background-color 180ms, transform 360ms var(--motion-spring, cubic-bezier(.2,1.3,.35,1)); }
.section-index button:active { background: var(--el-color-primary-light-8); transform: scale(.95); }
.section-index button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 1px; }
.section-index button.selected { background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; animation: tab-select 420ms var(--motion-spring, cubic-bezier(.2,1.3,.35,1)); }
.section-cell { min-width: 0; scroll-margin-top: 90px; }
.section-reveal-enter-active { transition: opacity 200ms ease, transform 460ms var(--motion-spring, cubic-bezier(.2,1.3,.35,1)); transition-delay: calc(var(--section-order) * 35ms); }
.section-reveal-enter-from { opacity: 0; transform: translateY(10px) scale(.985); }
@keyframes tab-select { from { transform: scale(.94); } to { transform: scale(1); } }
@media (hover: hover) { .section-index button:hover { color: var(--el-color-primary); background: var(--el-color-primary-light-9); transform: translateY(-1px); } }
@media (max-width: 760px) { .section-grid { grid-template-columns: minmax(0, 1fr); gap: 18px; } .section-cell { grid-column: 1 / -1 !important; } .index-label { display: none; } .section-index { margin-bottom: 18px; } }
@media (prefers-reduced-motion: reduce) { .section-reveal-enter-active, .section-index button { transition: none; transition-delay: 0ms; } .section-index button.selected { animation: none; } }
.section-cell :deep(.card + .card) { margin-top: 0; }
.frame { width: 100%; border: none; border-radius: 8px; background: var(--el-fill-color-blank); }
</style>
