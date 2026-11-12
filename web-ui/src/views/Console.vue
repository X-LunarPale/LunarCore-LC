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
const box = ref(null)
// Remove legacy persisted commands without parsing untrusted/corrupt storage.
try { localStorage.removeItem('lc-cmd-history') } catch { /* storage unavailable */ }
let pending = []
let off
let idSeq = 0
let disposed = false

const MAX = 2000

function add(items) {
  if (!Array.isArray(items)) return
  const valid = items.filter((l) => l && typeof l === 'object' && typeof l.message === 'string')
  for (const l of valid) l._id = ++idSeq
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
  if (!cmd || sending.value) return
  sending.value = true
  try {
    const r = await api.post('/api/console/exec', { command: cmd })
    if (!r.ok) ElMessage.warning(r.message)
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

onMounted(async () => {
  try {
    const history = await api.get('/api/console/history')
    if (!disposed) add(history)
  } catch (e) {
    if (!disposed) ElMessage.error(e.message)
  }
  if (!disposed) off = socket.subscribe('console', (data, backlog) => {
    add(backlog ? data : [data])
  })
})
onUnmounted(() => {
  disposed = true
  off?.()
  off = null
})
</script>

<template>
  <div class="page console-page">
    <div class="page-header">
      <div><p class="eyebrow">{{ t('实时运行日志') }}</p><h1 class="page-title">{{ t('控制台') }}</h1></div>
      <div class="toolbar" style="margin: 0">
        <el-input v-model="filter" :placeholder="t('过滤日志')" clearable style="width: 200px">
          <template #prefix><Icon name="filter" :size="14" /></template>
        </el-input>
        <el-select v-model="level" style="width: 120px">
          <el-option :label="t('全部级别')" value="ALL" />
          <el-option :label="t('警告及以上')" value="WARN" />
          <el-option :label="t('仅错误')" value="ERROR" />
        </el-select>
        <el-button @click="togglePause"><Icon :name="paused ? 'play' : 'pause'" :size="14" />&nbsp;{{ paused ? t('继续') : t('暂停') }}</el-button>
        <el-button @click="clearLog"><Icon name="eraser" :size="14" />&nbsp;{{ t('清屏') }}</el-button>
      </div>
    </div>
    <div class="log-status"><span :class="{ paused }"><i />{{ paused ? t('已暂停显示') : t('实时接收') }}</span><span>{{ t('已显示 {shown} / {total} 条', { shown: shown.length, total: lines.length }) }}</span></div>
    <div class="term" ref="box" role="log" :aria-label="t('服务器日志')" @scroll="onScroll">
      <div v-for="l in shown" :key="l._id" class="line" :class="'lv-' + l.level">
        <span class="t">[{{ l.timeText }}]</span>
        <span class="lv">[{{ l.level }}]</span>
        <span v-if="l.logger" class="lg">[{{ l.logger }}]</span>
        <span class="msg">{{ l.message }}</span>
      </div>
      <div v-if="!shown.length" class="empty">{{ t('暂无日志') }}</div>
    </div>
    <div v-if="auth.can('core.console.execute')" class="cmd">
      <span class="prompt">&gt;</span>
      <el-input v-model="command" :placeholder="t('输入控制台命令，回车执行（不保存历史）')" class="mono" :disabled="sending" @keyup.enter="send" />
      <el-button type="primary" :loading="sending" @click="send"><Icon name="send" :size="14" />&nbsp;{{ t('执行') }}</el-button>
    </div>
    <div v-if="!autoScroll" class="jump"><el-button size="small" round type="primary" @click="(autoScroll = true), scroll()">{{ t('回到底部') }}</el-button></div>
  </div>
</template>

<style scoped>
.console-page { display: flex; flex-direction: column; height: calc(100dvh - 54px); min-height:480px; min-width:0; position: relative; }
.page-header{margin-bottom:16px;gap:12px}.page-title{font-size:25px;letter-spacing:-.5px}.eyebrow{margin:0 0 5px;font-size:11px;color:var(--lc-text-soft);letter-spacing:.08em}.toolbar{gap:8px;max-width:100%}.toolbar :deep(.el-input){min-width:0}.log-status{display:flex;justify-content:space-between;gap:12px;padding:10px 14px;background:var(--lc-surface);border:1px solid var(--lc-sidebar-border);border-bottom:0;border-radius:8px 8px 0 0;color:var(--lc-text-soft);font-size:11px}.log-status>span:first-child{display:flex;align-items:center;gap:6px}.log-status i{width:6px;height:6px;border-radius:50%;background:var(--el-color-success)}.log-status .paused i{background:var(--el-color-warning)}
.term {
  flex: 1; min-height: 200px; overflow: auto;
  background: var(--lc-console-bg); color: var(--lc-console-text);
  border-radius: 0 0 8px 8px; padding: 12px 14px; border: 1px solid var(--lc-sidebar-border);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace; font-size: 12.5px; line-height: 1.55;
}
.line { white-space: pre-wrap; word-break: break-all; }
.t { color: #5b7a99; }
.lv { color: #6fb6e8; margin-right: 6px; }
.lg { color: #8b9bd9; margin-right: 6px; }
.lv-WARN .msg, .lv-WARN .lv { color: #fbbf24; }
.lv-ERROR .msg, .lv-ERROR .lv, .lv-FATAL .msg { color: #f87171; }
.lv-DEBUG .msg, .lv-TRACE .msg { color: #7c8ea3; }
.empty { color: #5b7a99; }
.cmd { display: flex; gap: 8px; align-items: center; margin-top: 12px; }
.prompt { font-family: monospace; font-weight: 700; color: var(--el-color-primary); font-size: 16px; }
.jump { position: absolute; right: 40px; bottom: 100px; }
.cmd{padding:10px;background:var(--lc-surface);border:1px solid var(--lc-sidebar-border);border-radius:8px}.cmd :deep(.el-input){min-width:0;flex:1}.cmd .el-button{flex-shrink:0}.term{min-width:0;overscroll-behavior:contain}.line{overflow-wrap:anywhere}@media(max-width:700px){.console-page{height:calc(100dvh - 54px);padding-bottom:16px}.page-header{align-items:flex-start}.page-title{font-size:23px}.toolbar{width:100%;display:grid;grid-template-columns:minmax(0,1fr) 120px;gap:8px}.toolbar :deep(.el-input),.toolbar :deep(.el-select){width:100%!important}.toolbar .el-button{margin:0}.jump{right:26px;bottom:86px}.cmd{gap:6px;padding:8px}.prompt{display:none}.term{font-size:12px;padding:10px}}

</style>
