<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { useAutoRefresh } from '../utils/autoRefresh'
import { formatDateTime, formatDuration, formatRelative } from '../utils/format'
import PlayerCell from '../components/PlayerCell.vue'
import { requireSuccess } from '../utils/panel'
import { t } from '../i18n'

const auth = useAuth()
const tab = ref('online')
const online = ref([])
const onlineLoading = ref(false)
const onlineQuery = ref('')
const onlineApplied = ref('')
const onlineError = ref('')
const historyQuery = ref('')
const historyError = ref('')
const playerBusy = ref('')
let onlineRevision = 0
let historyRevision = 0

const hist = reactive({ items: [], total: 0, page: 1, size: 20, q: '', sort: 'lastJoin', order: 'desc', loading: false, loaded: false })

const cmdDialog = reactive({ visible: false, player: null, command: '', asConsole: false })

async function loadOnline() {
  const revision = ++onlineRevision
  onlineLoading.value = true
  try {
    const r = requireSuccess(await api.get('/api/players/online', { q: onlineApplied.value }))
    if (revision !== onlineRevision) return
    if (!Array.isArray(r?.items)) throw new Error(t('玩家列表返回格式错误'))
    online.value = r.items
    onlineError.value = ''
  } catch (e) {
    if (revision === onlineRevision) onlineError.value = e.message
  } finally {
    if (revision === onlineRevision) onlineLoading.value = false
  }
}

async function loadHistory() {
  const revision = ++historyRevision
  hist.loading = true
  try {
    const r = requireSuccess(await api.get('/api/players/history', { page: hist.page, size: hist.size, q: hist.q, sort: hist.sort, order: hist.order }))
    if (revision !== historyRevision) return
    if (!Array.isArray(r?.items)) throw new Error(t('玩家列表返回格式错误'))
    hist.items = r.items
    hist.total = r.total
    hist.loaded = true
    historyError.value = ''
  } catch (e) {
    if (revision === historyRevision) historyError.value = e.message
  } finally {
    if (revision === historyRevision) hist.loading = false
  }
}

function searchOnline() { onlineApplied.value = onlineQuery.value.trim(); loadOnline() }
function searchHistory() { hist.q = historyQuery.value.trim(); hist.page = 1; loadHistory() }

function onSort({ prop, order }) {
  hist.sort = prop || 'lastJoin'
  hist.order = order === 'ascending' ? 'asc' : 'desc'
  hist.page = 1
  loadHistory()
}

async function kick(p) {
  if (playerBusy.value) return
  playerBusy.value = 'kick'
  try {
    const r = await ElMessageBox.prompt(t('踢出玩家 {name}，请输入原因（支持 MiniMessage）', { name: p.name }), t('踢出玩家'), {
      inputPlaceholder: t('你已被管理员踢出服务器'), confirmButtonText: t('踢出'), type: 'warning'
    }).catch(() => null)
    if (!r) return
    const res = requireSuccess(await api.post(`/api/players/${p.uuid}/kick`, { reason: r.value }))
    ElMessage.success(res.message)
    await loadOnline()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { playerBusy.value = '' }
}

async function message(p) {
  if (playerBusy.value) return
  playerBusy.value = 'message'
  try {
    const r = await ElMessageBox.prompt(t('发送私信给 {name}（支持 MiniMessage）', { name: p.name }), t('发送消息'), { inputPattern: /\S/, inputErrorMessage: t('消息不能为空') }).catch(() => null)
    if (!r) return
    const res = requireSuccess(await api.post(`/api/players/${p.uuid}/message`, { message: r.value }))
    ElMessage.success(res.message)
  } catch (e) {
    ElMessage.error(e.message)
  } finally { playerBusy.value = '' }
}

function openCommand(p) {
  if (playerBusy.value) return
  Object.assign(cmdDialog, { visible: true, player: p, command: '', asConsole: false })
}

async function runCommand() {
  if (playerBusy.value || !cmdDialog.player) return
  if (!cmdDialog.command.trim()) return ElMessage.warning(t('请输入命令'))
  playerBusy.value = 'command'
  try {
    const res = requireSuccess(await api.post(`/api/players/${cmdDialog.player.uuid}/command`, { command: cmdDialog.command, asConsole: cmdDialog.asConsole }))
    ElMessage.success(res.message)
    cmdDialog.visible = false
  } catch (e) {
    ElMessage.error(e.message)
  } finally { playerBusy.value = '' }
}

useAutoRefresh(() => { if (!onlineLoading.value) return loadOnline() }, {
  seconds: () => 15,
  active: () => tab.value === 'online' && !playerBusy.value && !cmdDialog.visible,
  immediate: true
})
watch(tab, value => { if (value === 'history' && !hist.loaded && !hist.loading) loadHistory() })
</script>

<template>
  <div class="page players-page">
    <header class="page-header"><h1 class="page-title">{{ t('玩家管理') }}</h1></header>
    <div class="player-list">
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('在线玩家')" name="online" :class="{ 'lc-pane-in': tab === 'online' }">
          <form class="toolbar" role="search" @submit.prevent="searchOnline">
            <el-input v-model="onlineQuery" :placeholder="t('搜索玩家名')" :aria-label="t('搜索玩家名')" clearable @clear="searchOnline">
              <template #prefix><Icon name="search" :size="14" /></template>
            </el-input>
            <el-button native-type="submit" :loading="onlineLoading">{{ t('搜索') }}</el-button>
            <el-tooltip :content="t('刷新')"><el-button class="refresh-button" :aria-label="t('刷新')" :disabled="onlineLoading || !!playerBusy" @click="loadOnline"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
          </form>
          <el-alert v-if="onlineError" :title="onlineError" type="error" show-icon :closable="false" />
          <el-table :data="online" v-loading="onlineLoading" :empty-text="t('当前没有在线玩家')">
            <el-table-column :label="t('玩家')" min-width="170">
              <template #default="{ row }"><PlayerCell :name="row.name" :uuid="row.uuid" /> <el-tag v-if="row.op" size="small" type="warning">OP</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('位置')" min-width="190">
              <template #default="{ row }">{{ row.world }} <span class="muted">({{ row.x }}, {{ row.y }}, {{ row.z }})</span></template>
            </el-table-column>
            <el-table-column prop="gamemode" :label="t('模式')" width="110" />
            <el-table-column :label="t('生命/饱食')" width="110">
              <template #default="{ row }">{{ row.health }} / {{ row.food }}</template>
            </el-table-column>
            <el-table-column :label="t('延迟')" width="90">
              <template #default="{ row }"><el-tag :type="row.ping < 100 ? 'success' : row.ping < 250 ? 'warning' : 'danger'" size="small">{{ row.ping }} ms</el-tag></template>
            </el-table-column>
            <el-table-column prop="ip" label="IP" width="140" />
            <el-table-column :label="t('游戏时长')" width="130">
              <template #default="{ row }">{{ formatDuration(row.playTime) }}</template>
            </el-table-column>
            <el-table-column v-if="auth.can('core.players.manage')" :label="t('操作')" width="195" fixed="right" align="right">
              <template #default="{ row }">
                <el-button link size="small" :disabled="!!playerBusy" @click="message(row)">{{ t('私信') }}</el-button>
                <el-button v-if="auth.can('core.console.execute')" link size="small" :disabled="!!playerBusy" @click="openCommand(row)">{{ t('命令') }}</el-button>
                <el-button link size="small" type="danger" :disabled="!!playerBusy" @click="kick(row)">{{ t('踢出') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="list-count">{{ t('共 {count} 人', { count: online.length }) }}</div>
        </el-tab-pane>
        <el-tab-pane :label="t('历史记录')" name="history" lazy :class="{ 'lc-pane-in': tab === 'history' }">
          <form class="toolbar" role="search" @submit.prevent="searchHistory">
            <el-input v-model="historyQuery" :placeholder="t('搜索名字 / UUID / IP')" :aria-label="t('搜索名字 / UUID / IP')" clearable @clear="searchHistory">
              <template #prefix><Icon name="search" :size="14" /></template>
            </el-input>
            <el-button native-type="submit" :loading="hist.loading">{{ t('搜索') }}</el-button>
            <el-tooltip :content="t('刷新')"><el-button class="refresh-button" :aria-label="t('刷新')" :disabled="hist.loading" @click="loadHistory"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
          </form>
          <el-alert v-if="historyError" :title="historyError" type="error" show-icon :closable="false" />
          <el-table :data="hist.items" v-loading="hist.loading" @sort-change="onSort" :default-sort="{ prop: 'lastJoin', order: 'descending' }">
            <el-table-column :label="t('玩家')" prop="name" sortable="custom" min-width="200">
              <template #default="{ row }"><PlayerCell :name="row.name" :uuid="row.uuid" /> <el-tag v-if="row.online" size="small" type="success">{{ t('在线') }}</el-tag></template>
            </el-table-column>
            <el-table-column label="UUID" min-width="290"><template #default="{ row }"><span class="mono muted">{{ row.uuid }}</span></template></el-table-column>
            <el-table-column :label="t('首次加入')" prop="firstJoin" sortable="custom" width="170"><template #default="{ row }">{{ formatDateTime(row.firstJoin) }}</template></el-table-column>
            <el-table-column :label="t('最后登录')" prop="lastJoin" sortable="custom" width="130"><template #default="{ row }"><el-tooltip :content="formatDateTime(row.lastJoin)"><span>{{ formatRelative(row.lastJoin) }}</span></el-tooltip></template></el-table-column>
            <el-table-column :label="t('游戏时长')" prop="playTime" sortable="custom" width="140"><template #default="{ row }">{{ formatDuration(row.playTime) }}</template></el-table-column>
            <el-table-column :label="t('最后 IP')" prop="lastIp" sortable="custom" width="140" />
          </el-table>
          <el-pagination class="pager" :pager-count="5" background layout="total, sizes, prev, pager, next, jumper" :total="hist.total" v-model:current-page="hist.page" v-model:page-size="hist.size" :page-sizes="[20, 50, 100]" @current-change="loadHistory" @size-change="(hist.page = 1), loadHistory()" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="cmdDialog.visible" :title="t('对 {name} 执行命令', { name: cmdDialog.player?.name || '' })" width="480px" :show-close="!playerBusy" :close-on-click-modal="!playerBusy" :close-on-press-escape="!playerBusy" append-to-body>
      <el-form label-position="top" :disabled="!!playerBusy" @submit.prevent="runCommand">
        <el-form-item :label="t('命令')"><el-input v-model="cmdDialog.command" :placeholder="t('例如 spawn（无需斜杠）')" class="mono" @keyup.enter="runCommand" /></el-form-item>
        <el-form-item :label="t('执行身份')">
          <el-radio-group v-model="cmdDialog.asConsole">
            <el-radio :value="false">{{ t('以玩家身份') }}</el-radio>
            <el-radio :value="true">{{ t('以控制台身份') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="muted command-note" v-if="cmdDialog.asConsole">{{ t('控制台命令中可用 {player} 代表该玩家名。') }}</div>
      </el-form>
      <template #footer>
        <el-button :disabled="!!playerBusy" @click="cmdDialog.visible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="playerBusy === 'command'" :disabled="!cmdDialog.command.trim()" @click="runCommand">{{ t('执行') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.players-page, .player-list { min-width: 0; }
.player-list { overflow: hidden; }
.toolbar { margin: 4px 0 12px; gap: 8px; flex-wrap: nowrap; }
.toolbar .el-input { width: min(100%, 270px); min-width: 0; }
.toolbar .el-button { flex: none; margin-left: 0; }
.refresh-button { width: 34px; padding: 0; }
.el-alert { margin-bottom: 14px; }
.list-count { margin-top: 10px; color: var(--lc-text-soft); font-size: 12px; }
.pager { margin-top: 18px; justify-content: flex-end; gap: 6px; flex-wrap: wrap; }
.command-note { font-size: 12px; overflow-wrap: anywhere; }
.players-page :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 8px; }
.players-page :deep(.el-radio) { margin-right: 10px; }
@media (max-width: 600px) { .pager { justify-content: center; }.pager :deep(.el-pagination__sizes), .pager :deep(.el-pagination__jump) { display: none; }.players-page :deep(.el-table__cell.is-fixed-column--right) { position: static !important; }.players-page :deep(.el-table__cell.is-fixed-column--right::before) { display: none; } }
</style>
