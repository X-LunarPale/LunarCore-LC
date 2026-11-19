<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../api'
import CodeEditor from '../components/CodeEditor.vue'
import { useAuth } from '../stores/auth'
import { t } from '../i18n'
import { requireSuccess } from '../utils/panel'

const PaperWorkspace = defineAsyncComponent(() => import('../components/paper/PaperWorkspace.vue'))
const BrandingSettings = defineAsyncComponent(() => import('../components/branding/BrandingSettings.vue'))

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
const validating = ref(false)
const validation = ref(null)
const loadError = ref(null)
const loaded = ref(false)
let loadRevision = 0

const dirty = computed(() => content.value !== original.value)

async function load() {
  if (!canEditCore.value || saving.value || loading.value || validating.value) return
  const revision = ++loadRevision
  loading.value = true
  try {
    if (dirty.value) {
      const discard = await ElMessageBox.confirm(t('重新加载会丢弃未保存修改，确定继续吗？'), t('重新加载'), { type: 'warning' }).then(() => true).catch(() => false)
      if (!discard) return
    }
    const r = requireSuccess(await api.get('/api/config'))
    if (typeof r?.content !== 'string') throw new Error(t('配置返回格式错误'))
    if (revision !== loadRevision) return
    loaded.value = true
    loadError.value = null
    content.value = r.content
    original.value = r.content
    path.value = r.path
    validation.value = null
  } catch (e) {
    if (revision === loadRevision) {
      loadError.value = e.message
      loaded.value = false
      ElMessage.error(e.message)
    }
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}

async function validate() {
  if (!canEditCore.value || validating.value || saving.value || loading.value || !loaded.value) return
  const submitted = content.value
  validating.value = true
  try {
    const r = await api.post('/api/config/validate', { content: submitted })
    if (content.value !== submitted) return
    validation.value = r
    if (r.ok) ElMessage.success(t('YAML 格式正确'))
  } catch (e) {
    ElMessage.error(e.message)
  } finally { validating.value = false }
}

async function save() {
  if (!canEditCore.value || saving.value || loading.value || validating.value || !loaded.value) return
  saving.value = true
  if (!dirty.value) { saving.value = false; return ElMessage.info(t('没有修改')) }
  const ok = await ElMessageBox.confirm(t('保存并重载核心配置？'), t('保存配置'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) { saving.value = false; return }
  const submitted = content.value
  try {
    const r = requireSuccess(await api.post('/api/config', { content: submitted }))
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
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
watch(activeTab, value => { if (value === 'core' && !loaded.value) load() })
watch(content, () => { validation.value = null })
</script>

<template>
  <div class="page config-page">
    <header class="page-header"><h1 class="page-title">{{ t('服务器配置') }}</h1></header>
    <el-tabs v-model="activeTab">
      <el-tab-pane name="paper" lazy><template #label>{{ t('Paper 设置') }}<el-tag v-if="paperDirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template><PaperWorkspace @dirty-change="paperDirty = $event" @busy-change="paperBusy = $event" /></el-tab-pane>
      <el-tab-pane name="branding" lazy><template #label>{{ t('品牌设置') }}<el-tag v-if="brandDirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template><BrandingSettings @dirty-change="brandDirty = $event" @busy-change="brandBusy = $event" /></el-tab-pane>
      <el-tab-pane v-if="canEditCore" name="core" lazy><template #label>{{ t('核心配置') }}<el-tag v-if="dirty" class="tab-state" size="small" type="warning">{{ t('未保存') }}</el-tag></template>
    <el-alert v-if="loadError" class="config-alert" type="error" :title="t('配置加载失败')" :description="loadError" show-icon :closable="false" />
    <el-alert v-if="validation && !validation.ok" class="config-alert" type="error" :title="t('YAML 校验未通过')" :description="validation.error" show-icon />
    <section class="editor-panel" v-loading="loading">
      <div class="editor-toolbar">
        <div class="file-info" :title="path"><Icon name="file-cog" :size="17" /><strong>config.yml</strong></div>
        <div class="editor-actions">
          <el-tooltip :content="t('重新加载')"><el-button class="icon-button" :aria-label="t('重新加载')" :disabled="loading || saving || validating" @click="load"><Icon name="rotate-ccw" :size="15" /></el-button></el-tooltip>
          <el-tooltip :content="t('校验')"><el-button class="icon-button" :aria-label="t('校验')" :loading="validating" :disabled="!loaded || loading || saving" @click="validate"><Icon name="check-check" :size="15" /></el-button></el-tooltip>
          <el-button type="primary" :loading="saving" :disabled="!dirty || !loaded || loading || validating" @click="save"><Icon name="save" :size="14" />{{ t('保存并重载') }}</el-button>
        </div>
      </div>
      <div class="editor-body"><CodeEditor v-model="content" :readonly="loading || saving || !loaded" height="clamp(360px, calc(100dvh - 310px), 860px)" @save="save" /></div>
      <div v-if="path" class="editor-status"><code>{{ path }}</code></div>
    </section>
    <details class="config-notes"><summary>{{ t('配置说明') }}</summary><p>{{ t('密码与密钥以') }} <code>******</code> {{ t('显示，保留占位符即可保留原值。') }}</p><p>{{ t('修改') }} <code>web</code> {{ t('配置后，需执行') }} <code>/lc web reload</code> {{ t('生效。') }}</p></details>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tab-state { margin-left: 8px; }
.editor-panel { min-width: 0; overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 7px; background: var(--lc-surface); }
.editor-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-light); }
.file-info { display: flex; align-items: center; gap: 8px; min-width: 0; }
.file-info > .lc-icon { color: var(--lc-text-soft); }
.file-info strong { font-size: 13px; font-weight: 600; }
.editor-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.editor-actions > .el-button { margin-left: 0; }
.editor-actions .icon-button { width: 34px; padding: 0; }
.editor-body { padding: 4px; }
.editor-status { padding: 8px 12px; font-size: 11px; color: var(--lc-text-soft); border-top: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.editor-status code { padding: 0; background: none; }
.config-alert { margin-bottom: 12px; }
.config-notes { padding: 14px 2px 0; color: var(--lc-text-soft); font-size: 12px; }
.config-notes summary { cursor: pointer; width: fit-content; }
.config-notes p { margin: 10px 0 0; line-height: 1.8; }
code { background: var(--el-fill-color-light); border-radius: 3px; padding: 2px 4px; overflow-wrap: anywhere; }
@media (max-width: 580px) { .file-info { width: 100%; } }
</style>
