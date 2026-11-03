<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api'
import CodeEditor from '../components/CodeEditor.vue'
import PaperWorkspace from '../components/paper/PaperWorkspace.vue'
import BrandingSettings from '../components/branding/BrandingSettings.vue'
import { useAuth } from '../stores/auth'
import { t } from '../i18n'

const auth = useAuth()
const activeTab = ref('paper')
const paperDirty = ref(false)
const paperBusy = ref(false)
const brandDirty = ref(false)
const brandBusy = ref(false)
const canEditCore = computed(() => auth.can('core.config.edit'))

const content = ref('')
const original = ref('')
const path = ref('')
const loading = ref(false)
const saving = ref(false)
const validation = ref(null)
const loadError = ref(null)
const loaded = ref(false)
let loadRevision = 0

const dirty = computed(() => content.value !== original.value)

async function load() {
  if (!canEditCore.value || saving.value || loading.value) return
  if (dirty.value) {
    const discard = await ElMessageBox.confirm(t('重新加载会丢弃未保存修改，确定继续吗？'), t('重新加载'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!discard) return
  }
  const revision = ++loadRevision
  loading.value = true
  try {
    const r = await api.get('/api/config')
    if (revision !== loadRevision) return
    loaded.value = true
    loadError.value = null
    content.value = r.content
    original.value = r.content
    path.value = r.path
    validation.value = null
  } catch (e) {
    loadError.value = e.message
    loaded.value = false
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function validate() {
  if (!canEditCore.value) return
  try {
    const r = await api.post('/api/config/validate', { content: content.value })
    validation.value = r
    if (r.ok) ElMessage.success(t('YAML 格式正确'))
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function save() {
  if (!canEditCore.value || saving.value || loading.value || !loaded.value) return
  saving.value = true
  if (!dirty.value) { saving.value = false; return ElMessage.info(t('没有修改')) }
  const ok = await ElMessageBox.confirm(t('保存后将立即重载核心配置（会自动备份为 config.yml.bak）。确定保存吗？'), t('保存配置'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) { saving.value = false; return }
  const submitted = content.value
  try {
    const r = await api.post('/api/config', { content: submitted })
    original.value = submitted
    validation.value = null
    ElMessage({ type: r.webChanged ? 'warning' : 'success', message: r.message, duration: r.webChanged ? 8000 : 3000 })
  } catch (e) {
    validation.value = { ok: false, error: e.message }
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

function beforeUnload(e) {
  if (dirty.value || paperDirty.value || brandDirty.value || saving.value || paperBusy.value || brandBusy.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onBeforeRouteLeave(async () => {
  if (saving.value || paperBusy.value || brandBusy.value) { ElMessage.warning(t('配置正在保存，请等待操作完成。')); return false }
  if (!dirty.value && !paperDirty.value && !brandDirty.value) return true
  return ElMessageBox.confirm(t('配置尚未保存，确定离开吗？'), t('未保存'), { type: 'warning' }).then(() => true).catch(() => false)
})

onMounted(() => {
  load()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<template>
  <div class="page config-page">
    <div class="page-header">
      <div>
        <h2 class="page-title"><Icon name="file-cog" :size="22" />{{ t('服务器配置') }}</h2>
        <div class="page-sub">{{ t('管理 Paper 服务器参数与核心配置，查看修改状态及生效方式。') }}</div>
      </div>
    </div>
    <el-tabs v-model="activeTab" class="config-tabs">
      <el-tab-pane name="paper"><template #label>{{ t('Paper 设置') }}<el-tag v-if="paperDirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template><PaperWorkspace @dirty-change="paperDirty = $event" @busy-change="paperBusy = $event" /></el-tab-pane>
      <el-tab-pane name="branding"><template #label>{{ t('品牌设置') }}<el-tag v-if="brandDirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template><BrandingSettings @dirty-change="brandDirty = $event" @busy-change="brandBusy = $event" /></el-tab-pane>
      <el-tab-pane v-if="canEditCore" name="core"><template #label>{{ t('核心配置') }}<el-tag v-if="dirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template>
    <p class="core-description">{{ t('编辑核心 YAML 配置，保存后立即重载并自动备份。') }}</p>
    <el-alert v-if="loadError" class="config-alert" type="error" :title="t('配置加载失败')" :description="loadError" show-icon :closable="false" />
    <el-alert v-if="validation && !validation.ok" class="config-alert" type="error" :title="t('YAML 校验未通过')" :description="validation.error" show-icon />
    <section class="editor-panel" v-loading="loading">
      <div class="editor-toolbar">
        <div class="file-info"><Icon name="file-cog" :size="17" /><div><strong>config.yml</strong><span :title="path">{{ path || t('核心配置文件') }}</span></div><el-tag v-if="dirty" type="warning" size="small" effect="plain">{{ t('未保存') }}</el-tag><el-tag v-else-if="loaded" type="info" size="small" effect="plain">{{ t('已同步') }}</el-tag></div>
        <div class="editor-actions">
          <el-button @click="load" :disabled="saving"><Icon name="rotate-ccw" :size="14" />&nbsp;{{ t('重新加载') }}</el-button>
          <el-button :disabled="!loaded || loading || saving" @click="validate"><Icon name="check-check" :size="14" />&nbsp;{{ t('校验') }}</el-button>
          <el-button type="primary" :loading="saving" :disabled="!dirty || !loaded || loading" @click="save"><Icon name="save" :size="14" />&nbsp;{{ t('保存并重载') }}</el-button>
        </div>
      </div>
      <div class="editor-body"><CodeEditor v-model="content" height="clamp(360px, calc(100dvh - 340px), 860px)" @save="save" /></div>
      <div class="editor-status"><span><span class="status-dot" :class="{ modified: dirty }"></span>{{ t(dirty ? '存在未保存的修改' : loaded ? '配置已与服务器同步' : '等待加载配置') }}</span><span>YAML <span class="status-separator">·</span> Ctrl + S {{ t('保存') }}</span></div>
    </section>
    <div class="config-notes"><div><Icon name="shield" :size="15" /><p>{{ t('密码与密钥以') }} <code>******</code> {{ t('显示，保留占位符即可保留原值。') }}</p></div><div><Icon name="info" :size="15" /><p>{{ t('修改') }} <code>web</code> {{ t('配置后，需执行') }} <code>/lc web reload</code> {{ t('生效。') }}</p></div></div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tab-state { margin-left: 8px; }
.core-description { color: var(--lbr-text-soft); font-size: 13px; margin: 5px 0 16px; }
.editor-panel { min-width: 0; overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 10px; background: var(--lbr-surface); }
.editor-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px; border-bottom: 1px solid var(--el-border-color-light); }
.file-info { display: flex; align-items: center; gap: 12px; min-width: 0; }
.file-info > .lbr-icon { color: var(--lbr-text-soft); }
.file-info > div { min-width: 0; }
.file-info strong { font-size: 13px; font-weight: 600; display: block; }
.file-info div > span { display: block; max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--lbr-text-soft); font-size: 11px; margin-top: 2px; }
.editor-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.editor-actions > .el-button { margin-left: 0; }
.editor-body { padding: 8px; background: var(--el-fill-color-extra-light); }
.editor-status { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 9px 18px; font-size: 11px; color: var(--lbr-text-soft); border-top: 1px solid var(--el-border-color-light); }
.editor-status > span { display: inline-flex; align-items: center; gap: 7px; }
.status-dot { width: 6px; height: 6px; background: var(--el-text-color-secondary); border-radius: 50%; }
.status-dot.modified { background: var(--el-color-warning); }
.status-separator { opacity: .5; }
.config-alert { margin-bottom: 12px; }
.config-notes { display: flex; flex-wrap: wrap; gap: 10px 28px; padding: 16px 2px 0; color: var(--lbr-text-soft); font-size: 12px; }
.config-notes > div { display: flex; align-items: flex-start; gap: 8px; }
.config-notes .lbr-icon { margin-top: 3px; }
.config-notes p { margin: 0; line-height: 1.8; }
code { background: var(--el-fill-color-light); border-radius: 3px; padding: 2px 4px; overflow-wrap: anywhere; }
@media (max-width: 640px) { .editor-toolbar { padding: 14px 12px; gap: 14px; } .file-info { width: 100%; } .file-info div > span { max-width: 190px; } .editor-actions { width: 100%; } .editor-actions > .el-button { flex: 1; padding-inline: 8px; } .editor-status { padding-inline: 12px; } }
</style>
