<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, socket } from '../api'
import { useAuth } from '../stores/auth'
import { t } from '../i18n'

const auth = useAuth()
const lines = ref([])
const filter = ref('')
const level = ref('ALL')
const autoScroll = ref(true)
const paused = ref(false)
const command = ref('')
const sending = ref(false)
const connected = ref(socket.connected)
const loadError = ref('')
const historyLoading = ref(false)
const box = ref(null)
// Remove legacy persisted commands without parsing untrusted/corrupt storage.
try { localStorage.removeItem('lc-cmd-history') } catch { /* storage unavailable */ }
let pending = []
let off
let offStatus
let idSeq = 0
let disposed = false

const MAX = 2000

function add(items) {
  if (!Array.isArray(items)) return
  const valid = items.filter(l => l && typeof l === 'object' && typeof l.message === 'string')
    .map(line => ({ ...line, _id: ++idSeq }))
  items = valid
  if (!items.length) return
  if (paused.value) {
    pending.push(...items)
    if (pending.length > MAX) pending = pending.slice(-MAX)
    return
  }
  const arr = lines.value.concat(items)
  lines.value = arr.length > MAX ? arr.slice(arr.length - MAX) : arr
  scroll()
}

function scroll() {
  if (!autoScroll.value) return
  nextTick(() => {
    if (box.value) box.value.scrollTop = box.value.scrollHeight
  })
}

function togglePause() {
  paused.value = !paused.value
  if (!paused.value && pending.length) {
    const p = pending
    pending = []
    add(p)
  }
}

function clearLog() {
  lines.value = []
  pending = []
}

const shown = computed(() => {
  const f = filter.value.trim().toLowerCase()
  return lines.value.filter((l) => {
    if (level.value === 'WARN' && !['WARN', 'ERROR', 'FATAL'].includes(l.level)) return false
    if (level.value === 'ERROR' && !['ERROR', 'FATAL'].includes(l.level)) return false
    if (f && !(l.message.toLowerCase().includes(f) || (l.logger || '').toLowerCase().includes(f))) return false
    return true
  })
})

async function send() {
  const cmd = command.value.trim()
  if (!cmd || sending.value || !auth.can('core.console.execute')) return
  sending.value = true
  try {
    const r = await api.post('/api/console/exec', { command: cmd })
    if (r.ok === false) { ElMessage.warning(r.message || t('命令未执行')); return }
    command.value = ''
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}

function onScroll() {
  const el = box.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

async function loadHistory() {
  if (historyLoading.value) return
  historyLoading.value = true
  try {
    const history = await api.get('/api/console/history')
    if (!disposed) {
      if (!Array.isArray(history)) throw new Error(t('日志返回格式错误'))
      if (!lines.value.length) add(history)
      loadError.value = ''
    }
  } catch (e) {
    if (!disposed) loadError.value = e.message
  } finally { historyLoading.value = false }
}
onMounted(async () => {
  offStatus = socket.onStatus(value => { connected.value = value })
  await loadHistory()
  if (!disposed) off = socket.subscribe('console', (data, backlog) => {
    add(backlog ? data : [data])
  })
})
onUnmounted(() => {
  disposed = true
  off?.()
  offStatus?.()
  off = null
})
</script>

<template>
  <div class="page console-page">
    <div class="page-header">
      <h1 class="page-title">{{ t('控制台') }}</h1>
    </div>
    <div class="toolbar log-toolbar">
        <el-input v-model="filter" :placeholder="t('过滤日志')" :aria-label="t('过滤日志')" clearable>
          <template #prefix><Icon name="filter" :size="14" /></template>
        </el-input>
        <el-select v-model="level" :aria-label="t('日志级别')">
          <el-option :label="t('全部级别')" value="ALL" />
          <el-option :label="t('警告及以上')" value="WARN" />
          <el-option :label="t('仅错误')" value="ERROR" />
        </el-select>
        <el-button @click="togglePause"><Icon :name="paused ? 'play' : 'pause'" :size="14" />&nbsp;{{ paused ? t('继续') : t('暂停') }}</el-button>
        <el-button @click="clearLog"><Icon name="eraser" :size="14" />&nbsp;{{ t('清屏') }}</el-button>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon><el-button size="small" :loading="historyLoading" @click="loadHistory">{{ t('重试') }}</el-button></el-alert>
    <div class="log-status"><span :class="{ paused: paused || !connected }"><i />{{ paused ? t('已暂停显示') : connected ? t('实时接收') : t('连接已断开') }}</span><span>{{ t('已显示 {shown} / {total} 条', { shown: shown.length, total: lines.length }) }}</span></div>
    <div class="term" ref="box" role="log" :aria-label="t('服务器日志')" @scroll="onScroll">
      <div v-for="l in shown" :key="l._id" class="line" :class="'lv-' + l.level">
        <span class="t">[{{ l.timeText }}]</span>
        <span class="lv">[{{ l.level }}]</span>
        <span v-if="l.logger" class="lg">[{{ l.logger }}]</span>
        <span class="msg">{{ l.message }}</span>
      </div>
      <div v-if="!shown.length" class="empty">{{ t('暂无日志') }}</div>
    </div>
    <form v-if="auth.can('core.console.execute')" class="cmd" @submit.prevent="send">
      <span class="prompt">&gt;</span>
      <el-input v-model="command" :placeholder="t('输入控制台命令')" :aria-label="t('控制台命令')" class="mono" :disabled="sending" />
      <el-button type="primary" native-type="submit" :loading="sending" :disabled="!command.trim()"><Icon name="send" :size="14" />&nbsp;{{ t('执行') }}</el-button>
    </form>
    <div v-if="!autoScroll" class="jump"><el-button size="small" round type="primary" @click="(autoScroll = true), scroll()">{{ t('回到底部') }}</el-button></div>
  </div>
</template>

<style scoped>
.console-page { display: flex; flex-direction: column; height: calc(100dvh - 56px); min-height: 380px; max-width: none; min-width: 0; position: relative; }
.page-header { margin-bottom: 16px; }.log-toolbar { gap: 8px; max-width: 100%; margin-bottom: 16px; }.log-toolbar .el-input { width: 280px; min-width: 0; }.log-toolbar .el-select { width: 150px; }.log-toolbar .el-button { gap: 7px; margin-left: 0; }.log-status { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; padding: 10px 14px; background: var(--lc-surface); border: 1px solid var(--el-border-color); border-bottom: 0; border-radius: 7px 7px 0 0; color: var(--lc-text-soft); font-size: 12px; }.log-status > span:first-child { display: flex; align-items: center; gap: 7px; }.log-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-success); }.log-status .paused i { background: var(--el-color-warning); }
.term {
  flex: 1; min-height: 200px; overflow: auto;
  background: var(--lc-console-bg); color: var(--lc-console-text);
  border-radius: 0 0 7px 7px; padding: 12px 14px; border: 1px solid var(--el-border-color);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace; font-size: 12.5px; line-height: 1.55;
}
.line { white-space: pre-wrap; word-break: break-all; }
.t { color: #93a0b0; }
.lv { color: #6fb6e8; margin-right: 6px; }
.lg { color: #8b9bd9; margin-right: 6px; }
.lv-WARN .msg, .lv-WARN .lv { color: #fbbf24; }
.lv-ERROR .msg, .lv-ERROR .lv, .lv-FATAL .msg { color: #f87171; }
.lv-DEBUG .msg, .lv-TRACE .msg { color: #93a0b0; }
.empty { color: #93a0b0; }
.cmd { display: flex; gap: 8px; align-items: center; margin-top: 12px; }
.prompt { font-family: monospace; font-weight: 700; color: var(--el-color-primary); font-size: 16px; }
.jump { position: absolute; right: 40px; bottom: 100px; }
.cmd { padding: 10px 0 0; border-top: 1px solid var(--el-border-color-light); }.cmd :deep(.el-input) { min-width: 0; flex: 1; }.cmd .el-button { flex-shrink: 0; }.term { min-width: 0; overscroll-behavior: contain; }.line { overflow-wrap: anywhere; }.el-alert { margin-bottom: 12px; flex-shrink: 0; }
@media (max-width: 700px) { .console-page { padding-bottom: 16px; }.log-toolbar { width: 100%; display: grid; grid-template-columns: minmax(0, 1fr) 130px; gap: 8px; }.log-toolbar .el-input, .log-toolbar .el-select { width: 100%; }.log-toolbar .el-button { margin: 0; }.jump { right: 26px; bottom: 86px; }.cmd { gap: 6px; }.prompt { display: none; }.term { font-size: 12px; padding: 10px; } }

</style>
