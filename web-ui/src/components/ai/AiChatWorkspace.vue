<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { localeTag, t } from '../../i18n'
import { requireSuccess } from '../../utils/panel'
import { useAutoRefresh } from '../../utils/autoRefresh'
import WorkspaceTabs from '../panel/WorkspaceTabs.vue'

const endpoint = '/api/ext/chat/ai/settings'
const form = reactive({
  enabled: false,
  name: '小月',
  persona: '',
  baseUrl: '',
  model: '',
  providerType: 'custom',
  fallbackModel: '',
  bots: [],
  replyMode: 'mention',
  proactiveProbability: 100,
  cooldownSeconds: 20,
  memoryEnabled: false,
  embeddingBaseUrl: 'https://api.openai.com/v1',
  embeddingModel: '',
  replyOnError: false,
  errorMessage: '暂时无法回复，请稍后再试。'
})
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const keyConfigured = ref(false)
const savedBaseUrl = ref('')
const apiKey = ref('')
const clearApiKey = ref(false)
const embeddingApiKey = ref('')
const embeddingKeyConfigured = ref(false)
const clearEmbeddingApiKey = ref(false)
const savedEmbeddingBaseUrl = ref('')
const savedBotUrls = ref({})
const models = ref({})
const balances = ref({})
const upstreamBusy = ref('')
const liveBalance = ref(false)
const memoryPlayer = ref('')
const baseline = ref('')
const error = ref('')
const notice = ref('')
const uncertain = ref(false)
const tab = ref('identity')
const savedIdentity = ref({ enabled: false, name: '', model: '' })
const memoryClearing = ref(false)
const tabs = [
  { id: 'identity', label: '角色设置', icon: 'bot', panelId: 'ai-identity' },
  { id: 'connection', label: '模型连接', icon: 'link', panelId: 'ai-connection' },
  { id: 'bots', label: '附加角色', icon: 'users', panelId: 'ai-bots' },
  { id: 'memory', label: '语义记忆', icon: 'brain', panelId: 'ai-memory-panel' },
  { id: 'messages', label: '回复与预览', icon: 'message-circle', panelId: 'ai-messages' }
]
let disposed = false

const busy = computed(() => loading.value || saving.value || memoryClearing.value || !!upstreamBusy.value)
const dirty = computed(() => loaded.value &&
  (JSON.stringify(form) !== baseline.value || !!apiKey.value || clearApiKey.value ||
    !!embeddingApiKey.value || clearEmbeddingApiKey.value))
const providers = [
  { value: 'custom', label: 'OpenAI 兼容接口' },
  { value: 'deepseek', label: 'DeepSeek 官方' },
  { value: 'sub2api', label: 'sub2API 中转站' }
]
const primary = computed(() => ({ id: 'primary', baseUrl: form.baseUrl, providerType: form.providerType,
  model: form.model, keyConfigured: keyConfigured.value }))
function validBaseUrl(baseUrl) {
  try {
    const value = new URL(baseUrl)
    const path = baseUrl.match(/^https?:\/\/[^/?#]+(\/[^?#]*)?$/i)?.[1] || ''
    return ['http:', 'https:'].includes(value.protocol) && value.hostname && !value.username && !value.password &&
      !baseUrl.includes('?') && !baseUrl.includes('#') && !baseUrl.includes('\\') && !/\s/.test(baseUrl) && !path.includes('..')
  } catch { return false }
}
function validKey(key) { return key.length <= 512 && !/[^\x21-\x7E]/.test(key.trim()) }
const validation = computed(() => {
  const name = form.name.trim()
  if (!name || name.length > 24) return '角色名称须为 1–24 个字符'
  if (!/^[\p{L}\p{N}_ -]+$/u.test(name)) return '角色名称仅可使用字母、数字、空格、下划线和连字符'
  if (!form.persona.trim() || form.persona.length > 4000) return '人格设定须为 1–4000 字符'
  if (!Number.isInteger(form.proactiveProbability) || form.proactiveProbability < 0 || form.proactiveProbability > 100) return '主动回复概率须为 0–100%'
  if (!Number.isInteger(form.cooldownSeconds) || form.cooldownSeconds < 5 || form.cooldownSeconds > 600) return '回应间隔须为 5–600 秒'
  if (clearApiKey.value && apiKey.value) return '清除密钥时不能同时输入新密钥'
  if (clearApiKey.value && form.enabled) return '请先关闭 AI，再清除密钥'
  const baseUrl = form.baseUrl.trim()
  if (!baseUrl) return '请填写 Base URL'
  if (baseUrl.length > 2048) return 'Base URL 不能超过 2048 字符'
  if (!validBaseUrl(baseUrl)) return 'Base URL 须为无账号、查询参数和锚点的 HTTP(S) 地址，路径不可包含 ..'
  if (baseUrl !== savedBaseUrl.value && keyConfigured.value && !apiKey.value.trim() && !clearApiKey.value) {
    return '更换 Base URL 时请重新填写 API Key，或关闭 AI 后清除旧密钥'
  }
  if (form.model.length > 128 || /[\r\n]/.test(form.model)) return '模型 ID 不能超过 128 字符或包含换行'
  if (form.enabled && !form.model.trim()) return '请填写模型 ID'
  if (!validKey(apiKey.value)) return 'API Key 只能包含无空格的 ASCII 可打印字符，最多 512 位'
  if (form.enabled && !keyConfigured.value && !apiKey.value.trim()) return '启用前请保存 API Key'
  if (!providers.some(item => item.value === form.providerType)) return '上游供应商类型无效'
  if (form.fallbackModel.length > 128 || /[\r\n]/.test(form.fallbackModel)) return '回退模型 ID 无效'
  if (form.bots.length > 4) return '最多添加 4 个 AI 角色'
  const names = new Set([name.toLowerCase()])
  for (const bot of form.bots) {
    if (!bot.name.trim() || bot.name.length > 24 || !/^[\p{L}\p{N}_ -]+$/u.test(bot.name)) return '附加角色名称无效'
    if (names.has(bot.name.toLowerCase())) return 'AI 角色名称不能重复'
    names.add(bot.name.toLowerCase())
    if (!bot.persona.trim() || bot.persona.length > 4000) return '附加角色人格设定无效'
    if (!providers.some(item => item.value === bot.providerType) || !validBaseUrl(bot.baseUrl) || bot.baseUrl.length > 2048) return '附加角色上游地址无效'
    if (bot.model.length > 128 || /[\r\n]/.test(bot.model) || bot.fallbackModel.length > 128 || /[\r\n]/.test(bot.fallbackModel)) return '附加角色模型 ID 无效'
    if (!validKey(bot.apiKey) || (bot.clearApiKey && bot.apiKey)) return '附加角色 API Key 无效'
    if (savedBotUrls.value[bot.id] && savedBotUrls.value[bot.id] !== bot.baseUrl && bot.keyConfigured && !bot.apiKey && !bot.clearApiKey) return '更换角色 Base URL 时需重新填写 API Key'
    if (form.enabled && bot.enabled && (bot.model.trim() === '' || (!bot.keyConfigured && !bot.apiKey) || bot.clearApiKey)) return '启用附加角色前需填写模型和 API Key'
  }
  if (form.embeddingBaseUrl.length > 2048 || !validBaseUrl(form.embeddingBaseUrl)) return '嵌入 Base URL 无效'
  if (form.embeddingModel.length > 128 || /[\r\n]/.test(form.embeddingModel)) return '嵌入模型 ID 无效'
  if (!validKey(embeddingApiKey.value) || (clearEmbeddingApiKey.value && embeddingApiKey.value)) return '嵌入 API Key 无效'
  if (savedEmbeddingBaseUrl.value !== form.embeddingBaseUrl && embeddingKeyConfigured.value && !embeddingApiKey.value && !clearEmbeddingApiKey.value) return '更换嵌入 Base URL 时需重新填写 API Key'
  if (form.memoryEnabled && (!form.embeddingModel.trim() || (!embeddingKeyConfigured.value && !embeddingApiKey.value) || clearEmbeddingApiKey.value)) return '启用记忆前需填写嵌入模型和 API Key'
  if (form.errorMessage.length > 200 || /[\r\n]/.test(form.errorMessage) || (form.replyOnError && !form.errorMessage.trim())) return '失败提示须为 1–200 字的单行文本'
  return ''
})

function applySettings(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || 'apiKey' in data) throw new Error(t('读取 AI 配置失败'))
  for (const key of Object.keys(form)) {
    if (data[key] !== undefined) form[key] = data[key]
  }
  form.bots = (data.bots || []).map(bot => ({ ...bot, apiKey: '', clearApiKey: false }))
  keyConfigured.value = data.keyConfigured === true
  embeddingKeyConfigured.value = data.embeddingKeyConfigured === true
  savedBaseUrl.value = form.baseUrl
  savedEmbeddingBaseUrl.value = form.embeddingBaseUrl
  savedBotUrls.value = Object.fromEntries(form.bots.map(bot => [bot.id, bot.baseUrl]))
  apiKey.value = ''
  clearApiKey.value = false
  embeddingApiKey.value = ''
  clearEmbeddingApiKey.value = false
  models.value = {}
  balances.value = {}
  baseline.value = JSON.stringify(form)
  savedIdentity.value = { enabled: form.enabled, name: form.name, model: form.model }
  loaded.value = true
  uncertain.value = false
}

async function confirmDiscard() {
  if (busy.value) return false
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm(t('有未保存的 AI 配置，确定放弃吗？'), t('修改尚未保存'), {
      type: 'warning', confirmButtonText: t('放弃修改'), cancelButtonText: t('继续编辑')
    })
    return true
  } catch { return false }
}

async function load(force = false) {
  if (busy.value || (!force && !await confirmDiscard())) return false
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const data = requireSuccess(await api.get(endpoint))
    if (disposed) return false
    applySettings(data)
    return true
  } catch (cause) {
    if (!disposed) error.value = cause.message || t('读取 AI 配置失败')
    return false
  } finally {
    if (!disposed) loading.value = false
  }
}

async function save() {
  if (!loaded.value || busy.value || !dirty.value || validation.value || uncertain.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  const body = {
    ...form,
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    model: form.model.trim(),
    persona: form.persona.trim(),
    fallbackModel: form.fallbackModel.trim(),
    embeddingBaseUrl: form.embeddingBaseUrl.trim(),
    embeddingModel: form.embeddingModel.trim(),
    errorMessage: form.errorMessage.trim(),
    bots: form.bots.map(({ keyConfigured, ...bot }) => ({ ...bot, name: bot.name.trim(),
      baseUrl: bot.baseUrl.trim(), model: bot.model.trim(), fallbackModel: bot.fallbackModel.trim(),
      persona: bot.persona.trim() }))
  }
  if (apiKey.value.trim()) body.apiKey = apiKey.value.trim()
  if (clearApiKey.value) body.clearApiKey = true
  if (embeddingApiKey.value.trim()) body.embeddingApiKey = embeddingApiKey.value.trim()
  if (clearEmbeddingApiKey.value) body.clearEmbeddingApiKey = true
  try {
    requireSuccess(await api.post(endpoint, body), t('保存 AI 配置失败'))
    apiKey.value = ''
    clearApiKey.value = false
    embeddingApiKey.value = ''
    clearEmbeddingApiKey.value = false
    saving.value = false
    if (!await load(true)) {
      uncertain.value = true
      return
    }
    notice.value = t('配置已保存')
  } catch (cause) {
    if (!disposed) {
      error.value = cause.message || t('保存 AI 配置失败')
      if (cause.status === 0) uncertain.value = true
    }
  } finally {
    if (!disposed) saving.value = false
  }
}

function addBot() {
  if (form.bots.length >= 4 || busy.value) return
  form.bots.push({ id: crypto.randomUUID(), enabled: false, name: '', persona: form.persona,
    providerType: 'custom', baseUrl: 'https://api.openai.com/v1', model: '', fallbackModel: '',
    keyConfigured: false, apiKey: '', clearApiKey: false })
}
function selectProvider(target, value) {
  target.providerType = value
  if (value === 'deepseek' && (!target.baseUrl || target.baseUrl === 'https://api.openai.com/v1'))
    target.baseUrl = 'https://api.deepseek.com/v1'
}
async function queryUpstream(kind, bot) {
  if (dirty.value || busy.value) return
  upstreamBusy.value = `${kind}:${bot.id}`
  error.value = ''
  try {
    const result = requireSuccess(await api.get(`/api/ext/chat/ai/${kind}?bot=${encodeURIComponent(bot.id)}`))
    if (disposed) return
    if (kind === 'models') models.value = { ...models.value, [bot.id]: result.models || [] }
    else balances.value = { ...balances.value, [bot.id]: result }
  } catch (cause) { if (!disposed) error.value = cause.message || t('上游查询失败') }
  finally { upstreamBusy.value = '' }
}
useAutoRefresh(async () => {
  for (const bot of [primary.value, ...form.bots]) {
    if (bot.providerType !== 'custom' && bot.keyConfigured) await queryUpstream('balance', bot)
  }
}, { seconds: () => 60, active: () => liveBalance.value && loaded.value && !dirty.value && !busy.value })
async function clearMemory() {
  if (busy.value || dirty.value) return
  memoryClearing.value = true
  try {
    const confirmed = await ElMessageBox.confirm(t(memoryPlayer.value ? '确定清除该玩家的所有 AI 记忆？' : '确定清空全部玩家的 AI 记忆？'), t('清理 AI 记忆'), { type: 'warning' }).then(() => true, () => false)
    if (!confirmed || disposed) return
    const body = memoryPlayer.value.trim() ? { playerUuid: memoryPlayer.value.trim() } : {}
    const result = requireSuccess(await api.post('/api/ext/chat/ai/memory/clear', body))
    if (!disposed) notice.value = t('已删除 {count} 条记忆', { count: result.removed })
  } catch (cause) { if (!disposed) error.value = cause.message || t('清理 AI 记忆失败') }
  finally { memoryClearing.value = false }
}

function beforeUnload(event) {
  if (!dirty.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(confirmDiscard)
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || confirmDiscard())
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  load(true)
})
onBeforeUnmount(() => {
  disposed = true
  apiKey.value = ''
  embeddingApiKey.value = ''
  form.bots.forEach(bot => { bot.apiKey = '' })
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="ai-workspace" :aria-busy="busy">
    <header class="page-header">
      <h1 class="page-title">{{ t('AI 聊天') }}</h1>
      <el-button :disabled="busy" :title="t('读取配置')" :aria-label="t('读取配置')" @click="load()"><Icon name="refresh-cw" :size="15" /></el-button>
    </header>

    <div class="ai-status" role="status">
      <span class="status-dot" :class="{ active: loaded && savedIdentity.enabled }" />
      <strong>{{ t(!loaded ? loading ? '读取配置中…' : '配置未读取' : savedIdentity.enabled ? '对话已开启' : '对话已关闭') }}</strong>
      <span v-if="loaded" class="status-detail">{{ savedIdentity.name }}<span class="status-separator">/</span>{{ savedIdentity.model || t('模型 ID') }}</span>
      <span v-if="dirty" class="unsaved">{{ t('修改尚未保存') }}</span>
    </div>

    <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
    <p v-if="uncertain" class="feedback warning" role="alert">{{ t('保存结果尚未核对，请重新读取配置后再操作。') }}</p>
    <p v-if="notice" class="feedback success" role="status">{{ notice }}</p>
    <el-button v-if="error && !loaded" :disabled="busy" @click="load(true)">{{ t('读取配置') }}</el-button>

    <form v-if="loaded" @submit.prevent="save">
      <WorkspaceTabs v-model="tab" :items="tabs" label="AI 聊天分区" id="ai-chat" />
      <div class="settings-grid">
        <section v-show="tab === 'identity'" id="ai-identity" class="settings-block identity-grid" role="tabpanel" aria-labelledby="ai-chat-tab-identity">
          <div>
          <div class="toggle-line"><label for="ai-enabled">{{ t('启用 AI 对话') }}</label><el-switch id="ai-enabled" v-model="form.enabled" :disabled="busy" :aria-label="t('启用 AI 对话')" /></div>
          <div class="field"><label for="ai-name">{{ t('角色名称') }}</label><el-input id="ai-name" v-model="form.name" maxlength="24" :disabled="busy" /></div>
          <div class="field"><label for="ai-mode">{{ t('回应方式') }}</label><el-select id="ai-mode" v-model="form.replyMode" :disabled="busy"><el-option value="mention" :label="t('仅在被提及时回应')" /><el-option value="all" :label="t('允许主动回复')" /></el-select></div>
          <div v-if="form.replyMode === 'all'" class="field"><label for="ai-proactive-probability">{{ t('主动回复概率') }}</label><div class="probability-control"><el-slider v-model="form.proactiveProbability" :min="0" :max="100" :step="1" :show-tooltip="false" :disabled="busy" :aria-label="t('主动回复概率')" /><el-input-number id="ai-proactive-probability" v-model="form.proactiveProbability" :min="0" :max="100" :step="1" controls-position="right" :disabled="busy" :aria-label="t('主动回复概率')" /><span>%</span></div><small>{{ t('仅对未提及 AI 的公开聊天生效；0% 只回应提及，100% 回应每条，仍受全服冷却限制。') }}</small></div>
          <div class="field"><label for="ai-cooldown">{{ t('最短回应间隔（秒）') }}</label><el-input-number id="ai-cooldown" v-model="form.cooldownSeconds" :min="5" :max="600" :step="5" controls-position="right" :disabled="busy" /></div>
          </div>
          <div class="persona-block">
            <label for="ai-persona">{{ t('人格设定') }}</label>
            <el-input id="ai-persona" v-model="form.persona" type="textarea" :rows="8" maxlength="4000" show-word-limit :disabled="busy" aria-describedby="ai-persona-disclosure" />
            <p id="ai-persona-disclosure">{{ t('玩家聊天会发送给选定的外部服务。') }}</p>
          </div>
        </section>

        <section v-show="tab === 'connection'" id="ai-connection" class="settings-block" role="tabpanel" aria-labelledby="ai-chat-tab-connection">
          <div class="field"><label for="ai-provider">{{ t('上游供应商') }}</label><el-select id="ai-provider" :model-value="form.providerType" :disabled="busy" @update:model-value="selectProvider(form, $event)"><el-option v-for="item in providers" :key="item.value" :value="item.value" :label="t(item.label)" /></el-select></div>
          <div class="field"><label for="ai-base-url">{{ t('Base URL') }}</label><el-input id="ai-base-url" v-model="form.baseUrl" maxlength="2048" placeholder="https://api.openai.com/v1" :disabled="busy" /></div>
          <div class="field"><label for="ai-model">{{ t('模型 ID') }}</label><el-input id="ai-model" v-model="form.model" maxlength="128" :disabled="busy" /></div>
          <div v-if="models.primary?.length" class="field"><label for="ai-model-list">{{ t('上游可用模型') }}</label><el-select id="ai-model-list" v-model="form.model" filterable :disabled="busy"><el-option v-for="item in models.primary" :key="item" :value="item" :label="item" /></el-select></div>
          <div class="field"><label for="ai-fallback">{{ t('回退模型 ID') }}</label><el-input id="ai-fallback" v-model="form.fallbackModel" maxlength="128" :disabled="busy" /><small>{{ t('限流、模型不存在或上游故障时尝试一次；鉴权或余额错误不回退。留空关闭。') }}</small></div>
          <div class="field"><label for="ai-key">{{ t('API Key') }}</label><el-input id="ai-key" v-model="apiKey" maxlength="512" type="password" show-password autocomplete="new-password" :disabled="busy || clearApiKey" /><small :class="{ 'key-present': keyConfigured }">{{ t(keyConfigured ? '已保存密钥（不会返回浏览器）' : '尚未保存密钥') }}<span class="key-hint">{{ t('留空保持现有密钥；输入新值会替换。') }}</span><span class="key-hint">{{ t('更换 Base URL 时需重填密钥，或关闭 AI 后清除旧密钥。') }}</span></small></div>
          <label v-if="keyConfigured" class="clear-key"><el-checkbox v-model="clearApiKey" :disabled="busy || !!apiKey" />{{ t('清除已保存密钥') }}</label>
          <div class="upstream-actions"><el-button :disabled="busy || dirty || !keyConfigured" :loading="upstreamBusy === 'models:primary'" @click="queryUpstream('models', primary)">{{ t('获取上游模型') }}</el-button><el-button v-if="form.providerType !== 'custom'" :disabled="busy || dirty || !keyConfigured" :loading="upstreamBusy === 'balance:primary'" @click="queryUpstream('balance', primary)">{{ t('查询余额') }}</el-button></div>
          <el-checkbox v-model="liveBalance" :disabled="busy || dirty">{{ t('每分钟自动查询余额') }}</el-checkbox>
          <p v-if="balances.primary" class="balance-line">{{ t('余额查询时间') }}: {{ new Date(balances.primary.fetchedAt).toLocaleString(localeTag) }} · {{ t(balances.primary.available ? '可用' : '不可用') }}<span v-for="entry in balances.primary.balances || []" :key="entry.currency"> · {{ entry.remaining }} {{ entry.currency }}</span><span v-if="balances.primary.remaining !== undefined"> · {{ balances.primary.remaining }} {{ balances.primary.currency || 'USD' }}</span><span v-if="balances.primary.remaining === undefined && !balances.primary.balances?.length"> · {{ t('上游未提供可用余额数字') }}</span></p>
        </section>
      </div>

      <section v-show="tab === 'bots'" id="ai-bots" class="additional-bots" role="tabpanel" aria-labelledby="ai-chat-tab-bots">
        <div class="section-heading"><el-button :disabled="busy || form.bots.length >= 4" @click="addBot"><Icon name="plus" :size="14" />{{ t('添加角色') }}</el-button></div>
        <div v-for="(bot, index) in form.bots" :key="bot.id" class="bot-editor">
          <div class="bot-title"><strong>{{ bot.name || t('新角色') }}</strong><el-switch v-model="bot.enabled" :disabled="busy" :aria-label="t('启用角色')" /><el-button text type="danger" :disabled="busy" @click="form.bots.splice(index, 1)">{{ t('移除') }}</el-button></div>
          <div class="bot-fields">
            <div class="field"><label :for="`bot-name-${bot.id}`">{{ t('角色名称') }}</label><el-input :id="`bot-name-${bot.id}`" v-model="bot.name" maxlength="24" :disabled="busy" /></div>
            <div class="field"><label :for="`bot-provider-${bot.id}`">{{ t('上游供应商') }}</label><el-select :id="`bot-provider-${bot.id}`" :model-value="bot.providerType" :disabled="busy" @update:model-value="selectProvider(bot, $event)"><el-option v-for="item in providers" :key="item.value" :value="item.value" :label="t(item.label)" /></el-select></div>
            <div class="field"><label :for="`bot-url-${bot.id}`">{{ t('Base URL') }}</label><el-input :id="`bot-url-${bot.id}`" v-model="bot.baseUrl" maxlength="2048" :disabled="busy" /></div>
            <div class="field"><label :for="`bot-model-${bot.id}`">{{ t('模型 ID') }}</label><el-input :id="`bot-model-${bot.id}`" v-model="bot.model" maxlength="128" :disabled="busy" /></div>
            <div v-if="models[bot.id]?.length" class="field"><label :for="`bot-model-list-${bot.id}`">{{ t('上游可用模型') }}</label><el-select :id="`bot-model-list-${bot.id}`" v-model="bot.model" filterable :disabled="busy"><el-option v-for="item in models[bot.id]" :key="item" :value="item" :label="item" /></el-select></div>
            <div class="field"><label :for="`bot-fallback-${bot.id}`">{{ t('回退模型 ID') }}</label><el-input :id="`bot-fallback-${bot.id}`" v-model="bot.fallbackModel" maxlength="128" :disabled="busy" /></div>
            <div class="field"><label :for="`bot-key-${bot.id}`">{{ t('API Key') }}</label><el-input :id="`bot-key-${bot.id}`" v-model="bot.apiKey" type="password" show-password autocomplete="new-password" maxlength="512" :disabled="busy || bot.clearApiKey" /><small>{{ t(bot.keyConfigured ? '已保存密钥（不会返回浏览器）' : '尚未保存密钥') }}</small></div>
            <div class="field"><label>{{ t('上游操作') }}</label><div class="upstream-actions"><el-button :disabled="busy || dirty || !bot.keyConfigured" :loading="upstreamBusy === `models:${bot.id}`" @click="queryUpstream('models', bot)">{{ t('获取上游模型') }}</el-button><el-button v-if="bot.providerType !== 'custom'" :disabled="busy || dirty || !bot.keyConfigured" :loading="upstreamBusy === `balance:${bot.id}`" @click="queryUpstream('balance', bot)">{{ t('查询余额') }}</el-button></div><p v-if="balances[bot.id]" class="balance-line">{{ t(balances[bot.id].available ? '可用' : '不可用') }}<span v-for="entry in balances[bot.id].balances || []" :key="entry.currency"> · {{ entry.remaining }} {{ entry.currency }}</span><span v-if="balances[bot.id].remaining !== undefined"> · {{ balances[bot.id].remaining }} {{ balances[bot.id].currency || 'USD' }}</span></p></div>
          </div>
          <label v-if="bot.keyConfigured" class="clear-key"><el-checkbox v-model="bot.clearApiKey" :disabled="busy || !!bot.apiKey" />{{ t('清除已保存密钥') }}</label>
          <div class="field bot-persona"><label :for="`bot-persona-${bot.id}`">{{ t('人格设定') }}</label><el-input :id="`bot-persona-${bot.id}`" v-model="bot.persona" type="textarea" :rows="3" maxlength="4000" :disabled="busy" /></div>
        </div>
      </section>

      <section v-show="tab === 'memory'" id="ai-memory-panel" class="advanced-panel" role="tabpanel" aria-labelledby="ai-chat-tab-memory">
        <div class="toggle-line"><label for="ai-memory">{{ t('启用玩家记忆') }}</label><el-switch id="ai-memory" v-model="form.memoryEnabled" :disabled="busy" /></div><p class="section-note">{{ t('默认关闭。只保留明确偏好，过滤敏感信息并去重；每个玩家与角色最多 24 条，保留 30 天。检索和新记忆分别调用嵌入接口。') }}</p><div class="field"><label for="ai-embedding-url">{{ t('嵌入 Base URL') }}</label><el-input id="ai-embedding-url" v-model="form.embeddingBaseUrl" maxlength="2048" :disabled="busy" /></div><div class="field"><label for="ai-embedding-model">{{ t('嵌入模型 ID') }}</label><el-input id="ai-embedding-model" v-model="form.embeddingModel" maxlength="128" :disabled="busy" /></div><div class="field"><label for="ai-embedding-key">{{ t('嵌入 API Key') }}</label><el-input id="ai-embedding-key" v-model="embeddingApiKey" type="password" show-password autocomplete="new-password" maxlength="512" :disabled="busy || clearEmbeddingApiKey" /><small>{{ t(embeddingKeyConfigured ? '已保存密钥（不会返回浏览器）' : '尚未保存密钥') }}</small></div><label v-if="embeddingKeyConfigured" class="clear-key"><el-checkbox v-model="clearEmbeddingApiKey" :disabled="busy || !!embeddingApiKey" />{{ t('清除嵌入密钥') }}</label><div class="memory-clear"><el-input v-model="memoryPlayer" :placeholder="t('玩家 UUID；留空表示全部玩家')" :aria-label="t('玩家 UUID；留空表示全部玩家')" :disabled="busy" /><el-button type="danger" plain :loading="memoryClearing" :disabled="busy || dirty" @click="clearMemory"><Icon name="trash-2" :size="14" />{{ t('清理记忆') }}</el-button></div>
      </section>

      <section v-show="tab === 'messages'" id="ai-messages" class="advanced-panel" role="tabpanel" aria-labelledby="ai-chat-tab-messages">
        <h2 id="ai-error-title"><Icon name="message-square-warning" :size="17" />{{ t('失败时的回复') }}</h2><div class="toggle-line"><label for="ai-error-reply">{{ t('请求失败时发消息') }}</label><el-switch id="ai-error-reply" v-model="form.replyOnError" :disabled="busy" /></div><div v-if="form.replyOnError" class="field"><label for="ai-error-message">{{ t('自定义失败消息') }}</label><el-input id="ai-error-message" v-model="form.errorMessage" maxlength="200" :disabled="busy" /><small>{{ t('会以当前 AI 角色的聊天格式发送；关闭后失败时保持沉默。') }}</small></div>
        <div class="chat-example" :aria-label="t('聊天示意')">
          <div><Icon name="message-circle" :size="15" /><strong>{{ t('聊天示意') }}</strong><small>{{ t('实际外观使用玩家的默认聊天格式。') }}</small></div>
          <p>{{ t('有人在游戏聊天中说：@{name} 你好', { name: form.name || 'AI' }) }}</p>
          <p class="example-reply"><strong>{{ form.name || 'AI' }}</strong><span>{{ t('你好！今天在服务器里想做些什么？') }}</span></p>
        </div>
      </section>

      <footer class="settings-actions">
        <span v-if="validation" class="validation" role="alert">{{ t(validation) }}</span>
        <el-button type="primary" native-type="submit" :loading="saving" :disabled="busy || !dirty || !!validation || uncertain"><Icon name="save" :size="15" />{{ t('保存 AI 配置') }}</el-button>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.ai-workspace { max-width: 1160px; min-width: 0; }
.persona-block p { margin: 8px 0 0; color: var(--lc-text-soft); font-size: 12px; line-height: 1.65; }
.settings-actions :deep(.el-button > span) { display: inline-flex; align-items: center; gap: 6px; }
.ai-status { display: flex; align-items: center; gap: 9px; min-height: 44px; padding: 10px 0; border-top: 1px solid var(--el-border-color); border-bottom: 1px solid var(--el-border-color); font-size: 12px; }
.ai-status strong { font-weight: 600; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--lc-text-soft); }
.status-dot.active { background: var(--el-color-success); }
.status-detail { min-width: 0; color: var(--lc-text-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-separator { padding: 0 8px; color: var(--el-border-color); }
.unsaved { margin-left: auto; color: var(--el-color-warning); white-space: nowrap; }
.feedback { margin: 15px 0 0; padding: 10px 12px; border: 1px solid var(--el-border-color-light); border-radius: 5px; font-size: 12px; }
.feedback.error, .validation { color: var(--el-color-danger); }
.feedback.warning { color: var(--el-color-warning); }
.feedback.success { color: var(--el-color-success); }
.settings-grid { display:block; margin-top:0; }
.settings-block { min-width:0; max-width:760px; }
.settings-block + .settings-block { padding:0; border:0; }
h2 { display: flex; align-items: center; gap: 9px; margin: 0 0 20px; font-size: 15px; font-weight: 600; }
h2 :deep(svg) { color: var(--el-color-primary); }
.field { margin-bottom: 18px; min-width: 0; }
.field :deep(.el-input) { max-width: 100%; }
.field > label, .toggle-line > label, .persona-block > label { display: block; margin-bottom: 6px; font-size: 12px; font-weight: 600; }
.field > small { display: block; margin-top: 6px; font-size: 11px; line-height: 1.55; color: var(--lc-text-soft); }
.field > small.key-present { color: var(--el-color-success); }
.key-hint { display: block; color: var(--lc-text-soft); }
.field :deep(.el-select), .field :deep(.el-input-number) { width: 100%; }
.probability-control { display: grid; grid-template-columns: minmax(100px, 1fr) 104px auto; gap: 12px; align-items: center; }
.probability-control :deep(.el-slider) { min-width: 0; padding: 0 5px; }
.probability-control :deep(.el-input-number) { width: 104px; }
.probability-control > span { color: var(--lc-text-soft); font-size: 12px; }
.toggle-line { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-bottom: 20px; padding: 0 0 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.toggle-line > label { margin: 0; }
.clear-key { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; cursor: pointer; }
.upstream-actions { display:flex; flex-wrap:wrap; gap:8px; margin:12px 0; }
.upstream-actions :deep(.el-button + .el-button) { margin-left:0; }
.balance-line { font-size:11px; line-height:1.6; color:var(--lc-text-soft); overflow-wrap:anywhere; }
.identity-grid { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1.2fr); align-items:start; gap:32px; max-width:940px; }
.persona-block { min-width:0; }
.persona-block :deep(textarea) { font-size: 13px; line-height: 1.6; }
.additional-bots { padding:0; }
.section-heading { display:flex; justify-content:flex-end; margin-bottom:12px; }
.section-note { color:var(--lc-text-soft); font-size:11px; line-height:1.6; margin:0 0 16px; }
.bot-editor { padding:16px 0; border-top:1px solid var(--el-border-color-light); }
.bot-title { display:flex; align-items:center; gap:12px; margin-bottom:14px; }
.bot-title strong { margin-right:auto; font-size:13px; }
.bot-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 20px; }
.bot-persona { margin:8px 0 0; }
.advanced-panel { max-width:760px; min-width:0; }
.advanced-panel h2 { margin-bottom:15px; }
.memory-clear { display:flex; gap:8px; margin-top:16px; }
.memory-clear :deep(.el-input) { min-width:0; }
.memory-clear :deep(.el-button) { flex:none; }
.chat-example { margin: 22px 0; font-size: 12px; }
.chat-example > div { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.chat-example > div :deep(svg) { color: var(--lc-text-soft); }
.chat-example > div small { color: var(--lc-text-soft); font-size: 11px; }
.chat-example p { margin: 8px 0; color: var(--lc-text-soft); }
.chat-example .example-reply { display: flex; gap: 12px; align-items: baseline; max-width: 560px; padding: 10px 12px; border-left: 2px solid var(--el-color-primary); background: var(--el-color-primary-light-9); color: var(--lc-text); }
.example-reply strong { color: var(--el-color-primary); }
.settings-actions { display:flex; justify-content:flex-end; align-items:center; gap:16px; border-top:1px solid var(--el-border-color-light); padding:16px 0; margin-top:24px; position:sticky; bottom:0; z-index:3; background:var(--lc-surface); }
.settings-actions > span { margin-right: auto; font-size: 12px; }
@media (max-width:850px) { .identity-grid { grid-template-columns:minmax(0,1fr); gap:12px; } }
@media (max-width: 560px) { .ai-status { flex-wrap: wrap; }.unsaved { margin-left: 0; }.chat-example > div { flex-wrap: wrap; }.bot-fields { grid-template-columns:minmax(0,1fr); }.settings-actions { flex-wrap: wrap; }.settings-actions :deep(.el-button) { margin-left: auto; }.memory-clear { flex-wrap:wrap; }.probability-control { grid-template-columns: minmax(0, 1fr) 92px auto; }.probability-control :deep(.el-input-number) { width: 92px; } }
</style>
