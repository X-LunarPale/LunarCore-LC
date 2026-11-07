<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from './api.js'
import { t } from './i18n.js'
import { Castle as Icon } from 'lucide-vue-next'
import { commonFlags, families, inheritWorlds, parseConfig, sameRules, setFlags } from './structures.js'

const environments = { NORMAL: '主世界', NETHER: '下界', THE_END: '末地' }
const status = ref(null)
const draft = ref(null)
const baseline = ref(null)
const scopes = ref([])
const scopeOpen = ref(false)
const error = ref('')
const absent = ref(false)
const loading = ref(false)
const saving = ref(false)
let timer, disposed = false
const dirty = computed(() => draft.value && !sameRules(draft.value, baseline.value))
const inherited = computed(() => scopes.value.length > 0 && scopes.value.every(world => !Object.keys(draft.value?.worlds[world] || {}).length))
const selected = computed(() => draft.value ? commonFlags(draft.value, scopes.value) : {})
const active = computed(() => status.value ? commonFlags(status.value.active, scopes.value) : {})
const scopeWorlds = computed(() => {
  const worlds = new Map((status.value?.worlds || []).map(world => [world.key, world]))
  Object.keys(draft.value?.worlds || {}).forEach(key => { if (!worlds.has(key)) worlds.set(key, { key, name: key }) })
  return [...worlds.values()]
})
const scopeSummary = computed(() => {
  if (!scopes.value.length) return t('全服默认')
  const names = scopes.value.map(key => scopeWorlds.value.find(world => world.key === key)?.name || key)
  return names.length === 1 ? names[0] : t('已选 {count} 个世界：{names}', { count: names.length, names: names.join('、') })
})
const mode = value => value === null ? t('不一致') : value ? 'YUNG’s' : t('原版')

function toggleWorld(key, checked) {
  scopes.value = checked ? [...scopes.value, key] : scopes.value.filter(world => world !== key)
}

async function load(reset = false) {
  if (loading.value || saving.value || disposed) return
  loading.value = true
  try {
    const result = await api.get('/api/ext/structures/status')
    if (disposed) return
    if (result.available === false) {
      status.value = draft.value = baseline.value = null
      absent.value = true
      error.value = t(result.message)
      return
    }
    result.active = parseConfig(result.active)
    result.saved = parseConfig(result.saved)
    if (!Array.isArray(result.worlds) || typeof result.restartRequired !== 'boolean') throw new Error(t('结构状态格式无效'))
    if (disposed) return
    status.value = result
    if (reset || !dirty.value) {
      baseline.value = structuredClone(result.saved)
      draft.value = structuredClone(result.saved)
    }
    absent.value = false
    error.value = ''
  } catch (e) {
    if (disposed) return
    absent.value = e.status === 404 || e.status === 503
    error.value = t(e.message)
    if ([401, 403, 404, 503].includes(e.status)) status.value = draft.value = baseline.value = null
  } finally { loading.value = false }
}

async function save() {
  if (!dirty.value || saving.value || error.value) return
  let pending = false
  saving.value = true
  try {
    const result = await api.post('/api/ext/structures/config', parseConfig(draft.value))
    const saved = parseConfig(result.saved)
    if (disposed) return
    draft.value = structuredClone(saved)
    baseline.value = structuredClone(saved)
    status.value.saved = saved
    status.value.restartRequired = result.restartRequired
    pending = result.restartRequired
    if (!pending) ElMessage.success(t('结构设置已保存'))
  } catch (e) {
    ElMessage.error(t(e.message))
    if ([401, 403, 503].includes(e.status)) { error.value = t(e.message); status.value = draft.value = baseline.value = null }
  }
  finally { saving.value = false }
  if (pending) await offerRestart()
}

async function offerRestart() {
  const canRestart = status.value.canRestart
  const confirmed = await ElMessageBox.confirm(
    canRestart ? t('结构设置已保存，需要完整重启服务器才能生效。重启期间在线玩家会断开连接。')
      : t('结构设置已保存，需要完整重启服务器才能生效。服务器没有配置 restart-script，确认后只会停止，需要手动启动。'),
    t('需要重启'),
    { type: 'warning', confirmButtonText: canRestart ? t('确认重启') : t('确认停止'), cancelButtonText: t('取消'), confirmButtonType: 'danger', closeOnClickModal: false }
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  try {
    await api.post('/api/ext/structures/restart')
    ElMessage.warning(canRestart ? t('服务器正在重启') : t('服务器正在停止'))
  } catch (e) { ElMessage.error(t(e.message)) }
}

onMounted(() => { load(); timer = setInterval(() => load(), 10000) })
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <section class="card structures-workspace" v-loading="loading && !status">
    <div class="card-title"><Icon name="castle" :size="16" />{{ t('结构美化') }}
      <el-tag v-if="status?.restartRequired" type="warning" size="small">{{ t('等待重启') }}</el-tag>
    </div>
    <p class="section-description">{{ t('为每类结构选择原版或 YUNG’s 生成方式，只在新生成的区块和新建世界中生效，保存后完整重启服务器。') }}</p>
    <el-alert v-if="absent" :closable="false" type="info" :title="t('结构生成插件不可用')"
      :description="`${error}。${t('从独立插件仓库下载 LunarCoreStructures.jar，放入 plugins 文件夹后完整重启。')}`" />
    <el-alert v-if="error && !absent" :closable="false" type="error" :title="error" />
    <template v-if="draft">
      <div class="structure-toolbar">
        <button type="button" class="scope-toggle" :aria-expanded="scopeOpen" aria-controls="structure-scope-list" @click="scopeOpen = !scopeOpen">
          <svg class="scope-caret" :class="{ open: scopeOpen }" viewBox="0 0 10 10" aria-hidden="true"><path d="M2 0.5 9 5 2 9.5z" /></svg>
          <span>{{ t('设置范围') }}</span><strong>{{ scopeSummary }}</strong>
        </button>
        <el-switch v-if="scopes.length" :model-value="inherited" :disabled="saving" :active-text="t('继承全服设置')"
          :aria-label="t('继承全服设置')" @change="value => inheritWorlds(draft, scopes, value)" />
      </div>
      <div v-show="scopeOpen" id="structure-scope-list" class="scope-list" role="group" :aria-label="t('设置范围')">
        <el-checkbox :model-value="!scopes.length" :disabled="saving" @change="scopes = []">{{ t('全服默认') }}</el-checkbox>
        <div v-for="world in scopeWorlds" :key="world.key" class="scope-item">
          <el-checkbox :model-value="scopes.includes(world.key)" :disabled="saving || world.supported === false"
            @change="value => toggleWorld(world.key, value)">
            {{ world.name }}<small v-if="environments[world.environment]">{{ t(environments[world.environment]) }}</small>
          </el-checkbox>
          <el-tooltip v-if="world.supported === false" placement="top"
            :content="t('{world} 不是原生噪声世界，结构设置不生效，继续使用原有生成方式。', { world: world.name })">
            <button type="button" class="scope-info" :aria-label="t('{world} 不是原生噪声世界，结构设置不生效，继续使用原有生成方式。', { world: world.name })">ⓘ</button>
          </el-tooltip>
        </div>
      </div>
      <div class="structure-grid">
        <div v-for="family in families" :key="family.id" class="structure-option">
          <div><strong>{{ t(family.label) }}</strong><small>{{ t('当前：{mode}', { mode: mode(active[family.id]) }) }}</small>
            <small v-if="selected[family.id] === null">{{ t('所选世界设置不同，切换后统一') }}</small></div>
          <el-switch :model-value="selected[family.id] === true" :disabled="saving || inherited || !!error || absent"
            :active-text="'YUNG’s'" :inactive-text="t('原版')" inline-prompt :aria-label="t(family.label)"
            @change="value => setFlags(draft, scopes, family.id, value)" />
        </div>
      </div>
      <el-alert v-for="world in status.worlds.filter(w => w.error)" :key="world.key" :closable="false" type="error" :title="`${world.name}: ${world.error}`" />
      <div class="structure-actions">
        <span class="muted">{{ dirty ? t('有未保存的结构设置') : t('已生成的建筑保留，关闭开关仅改变后续生成。') }}</span>
        <el-button :disabled="saving" @click="load(true)">{{ t('重新读取') }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="!dirty || !!error || absent" @click="save">{{ t('保存结构设置') }}</el-button>
      </div>
    </template>
    <el-button v-else-if="error || absent" class="retry" @click="load(true)">{{ t('重新读取') }}</el-button>
  </section>
</template>

<style scoped>
.structures-workspace{margin-top:16px}.structure-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:18px 0}.structure-toolbar .el-select{width:240px;max-width:100%}.structure-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.structure-option{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px;border:1px solid var(--lbr-border);border-radius:8px}.structure-option strong{font-size:13px}.structure-option small{display:block;margin-top:5px;color:var(--lbr-text-soft)}.section-description{font-size:12px;line-height:1.8;color:var(--lbr-text-soft);margin:8px 0}.structure-actions{display:flex;align-items:center;gap:10px;justify-content:flex-end;margin-top:18px;flex-wrap:wrap}.structure-actions .muted{margin-right:auto;font-size:12px}.retry{margin-top:12px}.el-alert{margin-top:10px}@media(max-width:1100px){.structure-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.structure-grid{grid-template-columns:1fr}.structure-actions .muted{width:100%}.structure-toolbar .el-select{width:100%}}
</style>

<style scoped>
.scope-toggle{display:inline-flex;align-items:center;gap:8px;max-width:100%;padding:6px 8px 6px 0;border:0;background:none;color:inherit;font:inherit;cursor:pointer;text-align:left}.scope-toggle strong{font-weight:500;color:var(--lbr-text-soft);overflow-wrap:anywhere}.scope-toggle:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px;border-radius:4px}.scope-caret{flex:none;width:10px;height:10px;fill:currentColor;transition:transform .15s}.scope-caret.open{transform:rotate(90deg)}.scope-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%, 200px), 1fr));gap:2px 16px;margin:-6px 0 18px;padding:8px 14px;border:1px solid var(--lbr-border);border-radius:8px}.scope-list small{margin-left:8px;color:var(--lbr-text-soft)}.scope-item{display:flex;align-items:center;gap:6px;min-width:0}.scope-info{padding:0;border:0;background:none;color:var(--lbr-text-soft);font:inherit;line-height:1;cursor:help}.scope-info:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px;border-radius:50%}

.scope-list :deep(.el-checkbox) {
  min-width: 0;
  height: auto;
  min-height: var(--el-checkbox-height, 32px);
  margin-right: 0;
}
.scope-list :deep(.el-checkbox__input),
.scope-info {
  flex: none;
}
.scope-list :deep(.el-checkbox__label) {
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.4;
}
</style>
