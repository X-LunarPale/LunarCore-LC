<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api.js'
import { t } from '../../i18n.js'
import { effective, families, inheritWorld, jobPhases, parseConfig, sameRules, setFlag, structureMessage } from './structures.js'

const status = ref(null)
const draft = ref(null)
const baseline = ref(null)
const selection = ref('__defaults__')
const scope = computed(() => selection.value === '__defaults__' ? '' : selection.value)
const error = ref('')
const absent = ref(false)
const loading = ref(false)
const saving = ref(false)
const cancelling = ref('')
let timer, disposed = false
const message = value => structureMessage(value, t)
const dirty = computed(() => draft.value && !sameRules(draft.value, baseline.value))
const inherited = computed(() => !!scope.value && !Object.keys(draft.value?.worlds[scope.value] || {}).length)
const selected = computed(() => draft.value ? effective(draft.value, scope.value) : {})
const unsupported = computed(() => (status.value?.worlds || []).filter(world => world.supported === false))
const active = computed(() => !status.value || unsupported.value.some(world => world.key === scope.value) ? {} : effective(status.value.active, scope.value))
const scopeWorlds = computed(() => {
  const worlds = new Map((status.value?.worlds || []).map(world => [world.key, world.name]))
  Object.keys(draft.value?.worlds || {}).forEach(key => { if (!worlds.has(key)) worlds.set(key, key) })
  return [...worlds].map(([key, name]) => ({ key, name }))
})

async function load(reset = false) {
  if (loading.value || saving.value || disposed) return
  loading.value = true
  try {
    const result = await api.get('/api/ext/structures/status')
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
    absent.value = e.status === 404
    error.value = absent.value ? '' : message(e.message)
  } finally { loading.value = false }
}

async function save() {
  if (!dirty.value || saving.value || error.value) return
  saving.value = true
  try {
    const result = await api.post('/api/ext/structures/config', parseConfig(draft.value))
    const saved = parseConfig(result.saved)
    if (disposed) return
    draft.value = structuredClone(saved)
    baseline.value = structuredClone(saved)
    status.value.saved = saved
    status.value.restartRequired = result.restartRequired
    ElMessage.success(t('结构设置已保存，完整重启后生效'))
  } catch (e) { ElMessage.error(message(e.message)) }
  finally { saving.value = false }
}

async function cancelJob(id) {
  cancelling.value = id
  try {
    const result = await api.post(`/api/ext/structures/jobs/${encodeURIComponent(id)}/cancel`)
    ElMessage.success(message(result.message))
    await load()
  } catch (e) { ElMessage.error(message(e.message)) }
  finally { cancelling.value = '' }
}

onMounted(() => { load(); timer = setInterval(() => load(), 10000) })
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <section class="card structures-workspace" v-loading="loading && !status">
    <div class="card-title"><Icon name="castle" :size="16" />{{ t('结构美化') }}
      <el-tag v-if="status?.restartRequired" type="warning" size="small">{{ t('等待重启') }}</el-tag>
    </div>
    <p class="section-description">{{ t('为每类结构选择原版或 YUNG’s 生成方式，保存后完整重启服务器生效。') }}</p>
    <el-alert v-if="absent" :closable="false" type="info" :title="t('尚未安装结构生成插件')"
      :description="t('将发行包中的 LunarCoreStructures.jar 放入服务端 plugins 文件夹后完整重启。')" />
    <el-alert v-if="error" :closable="false" type="error" :title="error" />
    <template v-if="draft">
      <div class="structure-toolbar">
        <label for="structure-scope">{{ t('设置范围') }}</label>
        <el-select id="structure-scope" v-model="selection" :disabled="saving" :aria-label="t('设置范围')">
          <el-option :label="t('全服默认')" value="__defaults__" />
          <el-option v-for="world in scopeWorlds" :key="world.key" :label="world.name" :value="world.key" />
        </el-select>
        <el-switch v-if="scope" :model-value="inherited" :disabled="saving" :active-text="t('继承全服设置')"
          :aria-label="t('继承全服设置')" @change="value => inheritWorld(draft, scope, value)" />
      </div>
      <div class="structure-grid">
        <div v-for="family in families" :key="family.id" class="structure-option">
          <div><strong>{{ t(family.label) }}</strong><small>{{ t('当前：{mode}', { mode: active[family.id] ? 'YUNG’s' : t('原版') }) }}</small></div>
          <el-switch :model-value="selected[family.id]" :disabled="saving || inherited || !!error || absent"
            :active-text="'YUNG’s'" :inactive-text="t('原版')" inline-prompt :aria-label="t(family.label)"
            @change="value => setFlag(draft, scope, family.id, value)" />
        </div>
      </div>
      <div class="structure-overwrite">
        <el-switch :model-value="selected.overwriteExisting" :disabled="saving || inherited || !!error || absent"
          :active-text="t('覆盖原有结构')" :aria-label="t('覆盖原有结构')"
          @change="value => setFlag(draft, scope, 'overwriteExisting', value)" />
        <p>{{ t('只覆盖已开启美化且能确认未改动的原版结构，结构内的野生生物和原版生成的实体会一并替换；玩家改动、玩家实体、领地冲突或无法判断的区域会跳过。处理前自动备份，期间全服暂停新玩家登录。') }}</p>
      </div>
      <el-alert v-for="world in status.worlds.filter(w => w.error)" :key="world.key" :closable="false" type="error" :title="`${world.name}: ${world.error}`" />
      <el-alert v-for="world in unsupported" :key="world.key" :closable="false" type="warning" :title="t('{world} 不是原生噪声世界，结构设置不生效，继续使用原有生成方式。', { world: world.name })" />
      <div class="structure-actions">
        <span class="muted">{{ dirty ? t('有未保存的结构设置') : t('已生成的建筑保留，关闭开关仅改变后续生成。') }}</span>
        <el-button :disabled="saving" @click="load(true)">{{ t('重新读取') }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="!dirty || !!error || absent" @click="save">{{ t('保存结构设置') }}</el-button>
      </div>
      <div v-if="status.jobs?.length" class="structure-jobs">
        <h3>{{ t('旧结构处理进度') }}</h3>
        <div v-for="job in status.jobs" :key="job.id" class="structure-job">
          <div class="structure-toolbar">
            <strong>{{ job.name }}</strong><el-tag size="small">{{ t(jobPhases[job.phase] || job.phase) }}</el-tag>
            <span class="muted">{{ t('已扫描 {chunks} 个区块，覆盖 {done} 个，跳过 {skipped} 个', { chunks: job.scanned, done: job.completed, skipped: job.skipped }) }}</span>
            <el-button v-if="!['completed', 'cancelled', 'failed', 'recovery_required'].includes(job.phase)" size="small"
              :loading="cancelling === job.id" :disabled="!!cancelling" @click="cancelJob(job.id)">{{ t('取消处理') }}</el-button>
          </div>
          <el-alert v-if="job.error" type="error" :closable="false" :title="message(job.error)" />
          <details v-if="job.details?.length"><summary>{{ t('查看跳过原因') }}</summary>
            <ul><li v-for="(detail, index) in job.details" :key="index"><span class="mono">{{ detail.candidate }}</span> — {{ message(detail.reason) }}</li></ul>
          </details>
        </div>
      </div>
    </template>
    <el-button v-else-if="error || absent" class="retry" @click="load(true)">{{ t('重新读取') }}</el-button>
  </section>
</template>

<style scoped>
.structures-workspace{margin-top:16px}.structure-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:18px 0}.structure-toolbar .el-select{width:240px;max-width:100%}.structure-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.structure-option{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px;border:1px solid var(--lbr-border);border-radius:8px}.structure-option strong{font-size:13px}.structure-option small{display:block;margin-top:5px;color:var(--lbr-text-soft)}.structure-overwrite{margin-top:18px;padding-top:14px;border-top:1px solid var(--lbr-border)}.structure-overwrite p,.section-description{font-size:12px;line-height:1.8;color:var(--lbr-text-soft);margin:8px 0}.structure-actions{display:flex;align-items:center;gap:10px;justify-content:flex-end;margin-top:18px;flex-wrap:wrap}.structure-actions .muted{margin-right:auto;font-size:12px}.retry{margin-top:12px}.el-alert{margin-top:10px}@media(max-width:1100px){.structure-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.structure-grid{grid-template-columns:1fr}.structure-actions .muted{width:100%}.structure-toolbar .el-select{width:100%}}
</style>

<style scoped>
.structure-jobs{margin-top:20px;border-top:1px solid var(--lbr-border);padding-top:14px}.structure-jobs h3{font-size:14px}.structure-job{padding:8px 0}.structure-job .structure-toolbar{margin:4px 0 10px;font-size:12px}.structure-job details{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.structure-job summary{cursor:pointer}.structure-job li+li{margin-top:6px}
</style>
