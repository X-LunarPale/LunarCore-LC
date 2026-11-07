<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { t } from '../../i18n'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import MinecraftTextPreview from '../text-preview/MinecraftTextPreview.vue'

const auth = useAuth()
const values = ref({})
const messages = ref({})
const revision = ref('')
const secrets = ref({})
const clearSecrets = ref([])
const busy = ref(false)
const loaded = ref(false)
const error = ref('')
const saved = ref(false)
const pendingReload = ref(false)
const selectedMessage = ref('login.bind-required')
const filter = ref('')
const plain = ref('')
const quotas = ref([])
const baseline = ref('')
const snapshot = () => JSON.stringify([values.value, messages.value, quotas.value, clearSecrets.value])
const dirty = computed(() => loaded.value && snapshot() !== baseline.value)
const bounds = {
  'integration.astrbot.port': [1, 65535], 'integration.onebot.reconnect-seconds': [1, 86400],
  'binding.code-length': [6, 8], 'auth.bcrypt-cost': [4, 16],
  'auth.min-password-length': [1, 64], 'auth.max-password-length': [1, 64],
  'binding.bind-ttl-seconds': [1, 1000000], 'binding.unbind-ttl-seconds': [1, 1000000],
  'binding.reset-ttl-seconds': [1, 1000000], 'binding.max-pending-codes': [1, 1000000],
  'binding.max-per-qq': [1, 1000000], 'auth.max-attempts': [1, 1000000],
  'auth.attempt-window-seconds': [1, 1000000], 'auth.max-attempt-entries': [2, 1000000],
  'auth.max-accounts-per-ip': [1, 1000000], 'auth.sessions.ttl-seconds': [1, 1000000],
  'auth.sessions.max-sessions': [1, 1000000]
}
const labels = {
  'integration.mode': '桥接模式', 'integration.group-id': '验证群 ID（群号或 group_openid）',
  'integration.astrbot.bind-address': 'QQAuth 监听地址', 'integration.astrbot.port': 'QQAuth 桥接端口',
  'integration.astrbot.token': 'AstrBot 桥接密钥', 'integration.onebot.endpoint': 'OneBot WebSocket 地址',
  'integration.onebot.token': 'OneBot 访问密钥', 'integration.onebot.reconnect-seconds': 'OneBot 重连间隔（秒）',
  'binding.code-length': '验证码长度', 'binding.bind-ttl-seconds': '绑定码有效期（秒）',
  'binding.unbind-ttl-seconds': '解绑码有效期（秒）', 'binding.reset-ttl-seconds': '改密码有效期（秒）',
  'binding.max-pending-codes': '待处理验证码上限', 'binding.max-per-qq': '每 QQ 默认绑定上限',
  'binding.required': '强制 QQ 验证', 'binding.reminder.enabled': '未绑定每日提醒',
  'binding.reminder.timezone': '每日提醒时区',
  'auth.min-password-length': '最短密码长度', 'auth.max-password-length': '最长密码长度',
  'auth.bcrypt-cost': '密码哈希强度', 'auth.max-attempts': '登录尝试上限',
  'auth.attempt-window-seconds': '登录限流窗口（秒）', 'auth.max-attempt-entries': '限流记录上限',
  'auth.max-accounts-per-ip': '每 IP 注册上限', 'auth.sessions.enabled': '启用免密会话',
  'auth.sessions.ttl-seconds': '免密会话有效期（秒）', 'auth.sessions.max-sessions': '会话数量上限',
  'auth.restrictions.allow-commands': '未登录时允许的额外命令'
}
const groups = [
  { title: 'QQ 桥接', prefix: 'integration.' }, { title: '验证码与绑定', prefix: 'binding.' },
  { title: '密码与会话', prefix: 'auth.' }
]
const previewVariables = { player: 'Steve', code: '123456', group: '示例群', ttl: '600', command: '验证', status: '成功', count: '3', ready: 'true', error: '示例错误' }
const messageKeys = computed(() => Object.keys(messages.value).filter(key => !filter.value || `${key} ${messages.value[key]}`.includes(filter.value)))
function activeField(key) {
  if (key.startsWith('integration.astrbot.')) return values.value['integration.mode'] === 'astrbot'
  if (key.startsWith('integration.onebot.')) return values.value['integration.mode'] === 'onebot'
  return true
}
function visibleFields(prefix) {
  return Object.keys(values.value).filter(key => key.startsWith(prefix) && labels[key] && activeField(key))
}
const validationError = computed(() => {
  for (const [key, [min, max]] of Object.entries(bounds)) {
    if (!activeField(key) || !Object.hasOwn(values.value, key)) continue
    const value = values.value[key]
    if (!Number.isInteger(value) || value < min || value > max)
      return t('{field}须为 {min} 至 {max} 的整数', { field: t(labels[key]), min, max })
  }
  if (values.value['auth.min-password-length'] > values.value['auth.max-password-length'])
    return t('最短密码长度不能大于最长密码长度')
  return ''
})
function numericBounds(key) {
  const [min, max] = bounds[key]
  const minimum = values.value['auth.min-password-length']
  const maximum = values.value['auth.max-password-length']
  if (key === 'auth.min-password-length' && Number.isInteger(maximum))
    return [min, Math.max(min, Math.min(max, maximum))]
  if (key === 'auth.max-password-length' && Number.isInteger(minimum))
    return [Math.max(min, Math.min(max, minimum)), max]
  return [min, max]
}
function accept(result) {
  values.value = result.values
  messages.value = result.messages
  revision.value = result.revision
  secrets.value = result.configuredSecrets
  pendingReload.value = result.requiresReload === true
  clearSecrets.value = []
  quotas.value = Object.entries(result.values['binding.per-qq-limits'] || {}).map(([qq, limit]) => ({ qq, limit }))
  loaded.value = true
  plain.value = ''
  baseline.value = snapshot()
}
async function confirmDiscard() {
  if (busy.value) return false
  if (!dirty.value) return true
  return ElMessageBox.confirm(t('有尚未保存的修改，确定放弃这些修改吗？'), t('未保存的修改'),
    { type: 'warning' }).then(() => true).catch(() => false)
}
async function load(force = false) {
  if (!force && !await confirmDiscard()) return
  busy.value = true
  error.value = ''
  try {
    accept(requireSuccess(await api.get('/api/ext/qqauth/config')))
    saved.value = false
    return true
  }
  catch (cause) { error.value = cause.message; return false }
  finally { busy.value = false }
}
async function save() {
  if (busy.value || !loaded.value) return
  busy.value = true
  saved.value = false
  try {
    if (validationError.value) throw new Error(validationError.value)
    const limits = {}
    for (const row of quotas.value) {
      if (!/^[0-9]{5,20}$/.test(row.qq) || Object.hasOwn(limits, row.qq)) throw new Error(t('QQ 号码无效或重复'))
      if (!Number.isInteger(row.limit) || row.limit < 1 || row.limit > 10000)
        throw new Error(t('{field}须为 {min} 至 {max} 的整数', { field: t('绑定上限'), min: 1, max: 10000 }))
      limits[row.qq] = row.limit
    }
    accept(requireSuccess(await api.post('/api/ext/qqauth/config', { revision: revision.value, values: { ...values.value, 'binding.per-qq-limits': limits }, messages: messages.value, clearSecrets: clearSecrets.value })))
    saved.value = true
    ElMessage.success(t(pendingReload.value ? '已保存，重载模块后生效' : '提示消息已生效'))
  } catch (cause) { ElMessage.error(cause.message) }
  finally { busy.value = false }
}
async function applyConfig() {
  if (busy.value || dirty.value || !pendingReload.value || !auth.can('core.modules.manage')) return
  busy.value = true
  try {
    const confirmed = await ElMessageBox.confirm(t('重载 QQauth 会撤销在线玩家认证并断开连接。'), t('应用配置'),
      { type: 'warning', confirmButtonText: t('应用配置'), cancelButtonText: t('取消') }).then(() => true).catch(() => false)
    if (!confirmed) return
    requireSuccess(await api.post('/api/modules/qqauth/reload', {}))
    saved.value = false
    const refreshed = await load(true)
    window.dispatchEvent(new CustomEvent('lbr:panel-changed', { detail: { namespace: 'qqauth' } }))
    if (refreshed && !pendingReload.value) ElMessage.success(t('配置已应用'))
  } catch (cause) { ElMessage.error(cause.message) }
  finally { busy.value = false }
}
async function preview() {
  try { plain.value = (await api.post('/api/ext/qqauth/messages/preview', { text: messages.value[selectedMessage.value] })).plain }
  catch (cause) { ElMessage.error(cause.message) }
}
function beforeUnload(event) {
  if (!dirty.value && !busy.value) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => { load(true); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
onBeforeRouteLeave(confirmDiscard)
onBeforeRouteUpdate(confirmDiscard)
</script>

<template>
  <section class="qq-config" v-loading="busy">
    <header><h2>{{ t('QQauth 配置') }} <small v-if="dirty">{{ t('尚未保存') }}</small></h2><el-button :disabled="busy" @click="load()"><Icon name="refresh-cw" :size="15" />{{ t('重新读取') }}</el-button></header>
    <el-alert v-if="error || validationError" :title="error || validationError" type="error" :closable="false" />
    <el-alert v-if="saved || pendingReload" :title="t(pendingReload ? '已保存，重载模块后生效' : '提示消息已生效')" :description="pendingReload ? t('重载 QQauth 会撤销在线玩家认证并断开连接。') : undefined" :type="pendingReload ? 'warning' : 'success'" :closable="false" />
    <template v-if="loaded">
      <section v-for="group in groups" :key="group.prefix" class="config-section">
        <h3>{{ t(group.title) }}</h3>
        <el-alert v-if="group.prefix === 'integration.' && values['integration.mode'] === 'astrbot'"
          :title="t('QQAuth 桥接端口独立于 Web 和 RCON 端口。')"
          :description="t('AstrBot 在 Docker 或其他机器运行时，127.0.0.1 指向机器人自身。QQAuth 监听地址可设为 0.0.0.0，机器人须填写游戏服可达的地址，并放行桥接端口。')"
          type="info" :closable="false" class="bridge-note" />
        <el-form label-position="top" class="field-grid">
          <el-form-item v-for="key in visibleFields(group.prefix)" :key="key" :for="`qq-${key}`" :label="t(labels[key])">
            <el-select v-if="key === 'integration.mode'" :id="`qq-${key}`" v-model="values[key]"><el-option v-for="mode in ['astrbot', 'onebot', 'disabled']" :key="mode" :label="mode" :value="mode" /></el-select>
            <el-switch v-else-if="typeof values[key] === 'boolean'" :id="`qq-${key}`" v-model="values[key]" />
            <el-input-number v-else-if="Object.hasOwn(bounds, key)" :id="`qq-${key}`" v-model="values[key]" :min="numericBounds(key)[0]" :max="numericBounds(key)[1]" :precision="0" controls-position="right" />
            <el-select v-else-if="Array.isArray(values[key])" :id="`qq-${key}`" v-model="values[key]" multiple filterable allow-create default-first-option />
            <template v-else-if="key.endsWith('.token')">
              <el-input :id="`qq-${key}`" v-model="values[key]" type="password" show-password autocomplete="new-password" :placeholder="t(secrets[key] ? '已设置，留空保留' : '尚未设置')" />
              <el-checkbox-group v-model="clearSecrets"><el-checkbox :value="key">{{ t('清除密钥') }}</el-checkbox></el-checkbox-group>
            </template>
            <el-input v-else :id="`qq-${key}`" v-model="values[key]" maxlength="2048" :placeholder="key === 'binding.reminder.timezone' ? t('留空使用服务器时区，例如 Asia/Shanghai') : undefined" />
          </el-form-item>
        </el-form>
      </section>
      <section class="config-section">
        <header><h3>{{ t('每 QQ 自定义配额') }}</h3><el-button @click="quotas.push({ qq: '', limit: 1 })"><Icon name="plus" :size="15" />{{ t('添加') }}</el-button></header>
        <div v-for="(quota, index) in quotas" :key="index" class="quota-row">
          <el-input v-model="quota.qq" :aria-label="t('QQ 号码')" :placeholder="t('QQ 号码')" maxlength="20" />
          <el-input-number v-model="quota.limit" :min="1" :max="10000" :aria-label="t('绑定上限')" />
          <el-button :aria-label="t('移除')" :title="t('移除')" @click="quotas.splice(index, 1)"><Icon name="trash-2" :size="16" /></el-button>
        </div>
      </section>
      <section class="config-section">
        <h3>{{ t('自定义提示消息') }}</h3>
        <div class="message-editor">
          <aside><el-input v-model="filter" :placeholder="t('搜索提示消息')" clearable /><el-select v-model="selectedMessage" filterable @change="plain = ''"><el-option v-for="key in messageKeys" :key="key" :value="key" :label="`${key} · ${messages[key].slice(0, 36)}`" /></el-select></aside>
          <div v-if="selectedMessage in messages" class="message-body">
            <el-input v-model="messages[selectedMessage]" type="textarea" :rows="4" maxlength="4096" :aria-label="t('提示消息内容')" @input="plain = ''" />
            <MinecraftTextPreview :text="messages[selectedMessage]" :variables="previewVariables" atomic-variables :placeholder-api="false" :label="t('游戏内提示预览')" />
            <el-button @click="preview">{{ t('预览 QQ 纯文本') }}</el-button>
            <pre v-if="plain">{{ plain }}</pre>
          </div>
        </div>
      </section>
      <footer>
        <el-button type="primary" :disabled="busy || !dirty || !!validationError" @click="save"><Icon name="save" :size="15" />{{ t('保存设置') }}</el-button>
        <el-button v-if="auth.can('core.modules.manage')" :disabled="busy || dirty || !pendingReload" @click="applyConfig"><Icon name="refresh-cw" :size="15" />{{ t('应用配置') }}</el-button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.qq-config{margin-top:28px;border-top:1px solid var(--el-border-color);padding-top:20px;min-height:100px;min-width:0}
header{display:flex;align-items:center;justify-content:space-between;gap:12px}h2{font-size:17px;margin:0}h3{font-size:14px;margin:0 0 16px}
.config-section{padding:22px 0;border-bottom:1px solid var(--el-border-color-light)}
.bridge-note{margin:0 0 18px}
.field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:24px}.field-grid :deep(.el-select),.field-grid :deep(.el-input-number){width:100%}
.quota-row{display:flex;gap:10px;max-width:580px;margin-bottom:10px}.quota-row>.el-input{min-width:0}
.message-editor{display:grid;grid-template-columns:minmax(180px,300px) minmax(0,1fr);gap:20px}.message-editor aside{display:flex;flex-direction:column;gap:10px}
.message-body{min-width:0}.message-body pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:var(--el-fill-color-light);font:inherit}
footer{padding-top:20px}.el-alert{margin-top:16px}
@media(max-width:760px){.field-grid,.message-editor{grid-template-columns:minmax(0,1fr)}.quota-row{flex-wrap:wrap}}
</style>
