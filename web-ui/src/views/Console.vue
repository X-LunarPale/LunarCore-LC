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
    <header class="page-header">
      <h1 class="page-title">{{ t('控制台') }}</h1>
    </header>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon><el-button size="small" :loading="historyLoading" @click="loadHistory">{{ t('重试') }}</el-button></el-alert>
    <section class="console-workbench" :aria-label="t('控制台')">
      <div class="toolbar log-toolbar">
        <el-input v-model="filter" :placeholder="t('过滤日志')" :aria-label="t('过滤日志')" clearable>
          <template #prefix><Icon name="filter" :size="14" /></template>
        </el-input>
        <el-select v-model="level" :aria-label="t('日志级别')">
          <el-option :label="t('全部级别')" value="ALL" />
          <el-option :label="t('警告及以上')" value="WARN" />
          <el-option :label="t('仅错误')" value="ERROR" />
        </el-select>
        <div class="log-actions">
          <el-tooltip :content="paused ? t('继续') : t('暂停')">
            <el-button :aria-label="paused ? t('继续') : t('暂停')" :class="{ 'is-paused': paused }" @click="togglePause"><Icon :key="paused" class="control-icon" :name="paused ? 'play' : 'pause'" :size="15" /></el-button>
          </el-tooltip>
          <el-tooltip :content="t('清屏')">
            <el-button :aria-label="t('清屏')" @click="clearLog"><Icon name="eraser" :size="15" /></el-button>
          </el-tooltip>
        </div>
      </div>
      <div class="term" ref="box" role="log" tabindex="0" :aria-label="t('服务器日志')" @scroll="onScroll">
        <div v-for="l in shown" :key="l._id" class="line" :class="'lv-' + l.level">
          <span class="t">[{{ l.timeText }}]</span>
          <span class="lv">[{{ l.level }}]</span>
          <span v-if="l.logger" class="lg">[{{ l.logger }}]</span>
          <span class="msg">{{ l.message }}</span>
        </div>
        <div v-if="!shown.length" class="empty">{{ t('暂无日志') }}</div>
      </div>
      <form v-if="auth.can('core.console.execute')" class="cmd" @submit.prevent="send">
        <el-input v-model="command" :placeholder="t('输入控制台命令')" :aria-label="t('控制台命令')" class="mono" :disabled="sending">
          <template #prefix><Icon name="terminal" :size="15" /></template>
        </el-input>
        <el-button type="primary" native-type="submit" :loading="sending" :disabled="!command.trim()"><Icon name="send" :size="14" />{{ t('执行') }}</el-button>
      </form>
      <div class="log-footer">
        <span class="log-status" role="status" :class="{ paused: paused || !connected }">
          <i aria-hidden="true" />{{ paused ? t('已暂停显示') : connected ? t('实时接收') : t('连接已断开') }}
        </span>
        <span class="log-count">{{ t('已显示 {shown} / {total} 条', { shown: shown.length, total: lines.length }) }}</span>
        <div class="log-jump">
          <el-tooltip v-if="!autoScroll" :content="t('回到底部')"><el-button text size="small" class="lc-pane-in" :aria-label="t('回到底部')" @click="(autoScroll = true), scroll()"><Icon name="chevron-down" :size="15" /></el-button></el-tooltip>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.console-page { display: flex; flex-direction: column; height: calc(100dvh - var(--lc-header-height)); min-height: 420px; }
.console-workbench { display: flex; flex: 1; flex-direction: column; min-height: 0; border: 1px solid var(--lc-line); border-radius: 8px; background: var(--lc-surface); }
.log-toolbar { gap: 8px; flex-wrap: nowrap; padding: 10px 12px; margin: 0; }
.log-toolbar .el-input { width: min(100%, 280px); min-width: 0; }
.log-toolbar .el-select { width: 148px; flex: none; }
.log-actions { display: flex; gap: 4px; margin-left: auto; }
.log-actions .el-button { width: 34px; margin: 0; padding: 0; }
.log-actions .is-paused { background: var(--el-fill-color-light); color: var(--lc-accent); }
.control-icon { animation: console-control-in var(--motion-standard) var(--motion-spring) both; }
@keyframes console-control-in { from { transform: scale(.67); opacity: .45; } to { transform: scale(1); opacity: 1; } }
.log-status { display: flex; align-items: center; gap: 7px; color: var(--lc-text-soft); font-size: 12px; }
.log-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-success); }
.log-status.paused i { background: var(--el-color-warning); }
.term {
  flex: 1; min-height: 160px; min-width: 0; overflow: auto; overscroll-behavior: contain;
  background: var(--lc-console-bg); color: var(--lc-console-text);
  padding: 12px 14px; border-block: 1px solid var(--lc-line);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace; font-size: 12.5px; line-height: 1.55;
}
.term:focus-visible { outline-offset: -3px; }
.line { white-space: pre-wrap; overflow-wrap: anywhere; }
.t { color: #93a0b0; margin-right: 6px; }
.lv { color: #6fb6e8; margin-right: 6px; }
.lg { color: #8b9bd9; margin-right: 6px; }
.lv-WARN .msg, .lv-WARN .lv { color: #fbbf24; }
.lv-ERROR .msg, .lv-ERROR .lv, .lv-FATAL .msg { color: #f87171; }
.lv-DEBUG .msg, .lv-TRACE .msg { color: #93a0b0; }
.empty { color: #93a0b0; }
.cmd { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--lc-line); }
.cmd .el-input { min-width: 0; flex: 1; }
.cmd .el-button { flex: none; }
.log-footer { display: flex; align-items: center; gap: 10px; min-height: 35px; padding: 2px 12px; color: var(--lc-text-soft); font-size: 12px; }
.log-count { margin-inline-start: auto; font-variant-numeric: tabular-nums; }
.log-jump { width: 28px; flex: none; }
.log-jump .el-button { width: 28px; padding: 0; }
.el-alert { margin-bottom: 12px; flex-shrink: 0; }
@media (max-width: 600px) {
  .log-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 124px) auto; gap: 6px; padding-inline: 8px; }
  .log-toolbar .el-input, .log-toolbar .el-select { width: 100%; }
  .log-actions .el-button { width: 30px; }
  .cmd { gap: 6px; padding-inline: 8px; }
  .term { font-size: 12px; padding: 10px; }
}
</style>
