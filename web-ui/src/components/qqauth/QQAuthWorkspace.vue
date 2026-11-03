<script setup>
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { Ellipsis } from 'lucide-vue-next'
import { api } from '../../api'
import { t } from '../../i18n'
import { useAuth } from '../../stores/auth'
import { formatDateTime } from '../../utils/format'
import { useAutoRefresh } from '../../utils/autoRefresh'
import { latestRequest, panelText, requireSuccess } from '../../utils/panel'
import ActionRunner from '../panel/ActionRunner.vue'
import QQAuthConfig from './QQAuthConfig.vue'

const props = defineProps({ panel: { type: Object, required: true } })
const auth = useAuth()
const runner = ref(null)
const searchInput = ref('')
const stats = ref([])
const statsError = ref('')
const state = reactive({ items: [], total: 0, page: 1, size: 20, q: '', loading: false, loaded: false, error: '' })
const beginAccounts = latestRequest()
const beginStats = latestRequest()
const canManage = computed(() => auth.can('qqauth.manage'))
const table = computed(() => props.panel.sections?.find(section => section.type === 'table' && section.endpoint === 'accounts'))
const actions = computed(() => Object.fromEntries((table.value?.rowActions || []).map(action => [action.id, action])))
const configuration = computed(() => props.panel.sections?.find(section => section.type === 'text'))
const moduleReady = computed(() => !state.error && state.loaded)

async function loadStats() {
  const current = beginStats()
  try {
    const result = requireSuccess(await api.get('/api/ext/qqauth/stats'))
    if (!current()) return
    if (!Array.isArray(result)) throw new Error(t('认证状态格式无效'))
    stats.value = result
    statsError.value = ''
  } catch (cause) {
    if (!current()) return
    stats.value = []
    statsError.value = cause.message || t('读取认证状态失败')
  }
}

async function loadAccounts() {
  const current = beginAccounts()
  state.loading = true
  try {
    const result = requireSuccess(await api.get('/api/ext/qqauth/accounts', {
      q: state.q, page: state.page, size: state.size
    }))
    if (!current()) return
    if (!Array.isArray(result?.items) || !Number.isFinite(Number(result.total))) {
      throw new Error(t('账号列表格式无效'))
    }
    state.items = result.items
    state.total = Number(result.total)
    state.loaded = true
    state.error = ''
  } catch (cause) {
    if (!current()) return
    state.items = []
    state.total = 0
    state.error = cause.message || t('读取认证账号失败')
  } finally {
    if (current()) state.loading = false
  }
}

function refresh() {
  return Promise.all([loadStats(), loadAccounts()])
}

function search() {
  state.q = searchInput.value.trim()
  state.page = 1
  loadAccounts()
}

function runAction(id, row) {
  if (!canManage.value || state.error) return
  const action = actions.value[id]
  if (action) runner.value?.run(action, row)
}

function actionDone() {
  if (state.items.length === 1 && state.page > 1) state.page--
  refresh()
  window.dispatchEvent(new CustomEvent('lbr:panel-changed', { detail: { namespace: 'qqauth' } }))
}

useAutoRefresh(() => { if (!state.loading && !runner.value?.busy) return refresh() }, {
  seconds: () => 10, namespace: 'qqauth', immediate: true
})
onBeforeUnmount(() => { beginAccounts(); beginStats() })
</script>

<template>
  <div class="qq-workspace">
    <header class="qq-heading">
      <div>
        <h1><Icon name="shield" :size="22" />{{ t(panel.title || 'QQ验证与账号认证') }}</h1>
        <p>{{ t('查看游戏账号的登录状态，并处理锁定、密码恢复与 QQ 解绑。') }}</p>
      </div>
      <el-button :loading="state.loading" @click="refresh"><Icon name="refresh-cw" :size="15" />{{ t('刷新列表') }}</el-button>
    </header>

    <div class="qq-status" role="status">
      <span class="state-dot" :class="{ ready: moduleReady, problem: !!state.error }" />
      <span class="status-label">{{ t('模块状态') }}</span>
      <strong>{{ statsError ? t('状态暂不可用') : stats[0] ? t(stats[0].value) : t('读取中…') }}</strong>
      <span class="status-divider" />
      <span class="status-label">{{ t('账号数') }}</span>
      <strong>{{ stats[1]?.value ?? '—' }}</strong>
      <span v-if="!canManage" class="view-only">{{ t('当前账号仅可查看认证状态') }}</span>
    </div>

    <section class="account-section" aria-labelledby="account-title">
      <div class="section-heading">
        <div><h2 id="account-title">{{ t('游戏认证账号') }}</h2><span v-if="state.loaded" class="count">{{ t('共 {count} 个账号', { count: state.total }) }}</span></div>
        <div class="search-tools">
          <el-input v-model="searchInput" clearable :placeholder="t('搜索玩家名称')" :aria-label="t('搜索玩家名称')" @keyup.enter="search" @clear="search">
            <template #prefix><Icon name="search" :size="15" /></template>
          </el-input>
          <el-button type="primary" plain @click="search">{{ t('搜索') }}</el-button>
        </div>
      </div>

      <p v-if="state.error" class="qq-error" role="alert">{{ state.error }} <el-button link type="primary" @click="refresh">{{ t('重试') }}</el-button></p>
      <div v-else class="account-list" v-loading="state.loading">
        <div v-if="state.items.length" class="list-header" :class="{ readonly: !canManage }" aria-hidden="true">
          <span>{{ t('玩家 / UUID') }}</span><span>{{ t('账号状态') }}</span><span>{{ t('注册时间') }}</span><span v-if="canManage">{{ t('管理操作') }}</span>
        </div>
        <div v-for="account in state.items" :key="account.uuid" class="account-row" :class="{ readonly: !canManage }">
          <div class="identity"><strong>{{ account.name }}</strong><span class="uuid" :title="account.uuid">{{ account.uuid }}</span></div>
          <div class="account-state"><span class="state-label">{{ t('账号状态') }}</span><div class="badges"><span class="badge" :class="account.locked ? 'locked' : 'normal'">{{ t(account.locked ? '已锁定' : '可登录') }}</span><span class="badge" :class="account.authenticated ? 'online' : 'offline'">{{ t(account.authenticated ? '已登录' : '未登录') }}</span></div></div>
          <div class="created"><span class="state-label">{{ t('注册时间') }}</span>{{ account.createdAt ? formatDateTime(account.createdAt) : '—' }}</div>
          <div v-if="canManage" class="row-actions">
            <el-button v-if="actions[account.locked ? 'unlock' : 'lock']" size="small" @click="runAction(account.locked ? 'unlock' : 'lock', account)">{{ t(actions[account.locked ? 'unlock' : 'lock'].label) }}</el-button>
            <el-button v-if="actions.reset" size="small" @click="runAction('reset', account)">{{ t(actions.reset.label) }}</el-button>
            <el-dropdown v-if="actions.forceunbind || actions.delete" trigger="click" @command="id => runAction(id, account)">
              <el-button size="small" :title="t('更多账号操作')" :aria-label="t('更多账号操作')"><Ellipsis :size="16" /></el-button>
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item v-if="actions.forceunbind" command="forceunbind">{{ t(actions.forceunbind.label) }}</el-dropdown-item>
                <el-dropdown-item v-if="actions.delete" command="delete" class="danger-item">{{ t(actions.delete.label) }}</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
          </div>
        </div>
        <el-empty v-if="!state.loading && !state.items.length" :description="t(state.q ? '没有匹配的认证账号' : '尚无游戏认证账号')" :image-size="72" />
      </div>
      <el-pagination v-if="!state.error && state.total > state.size" v-model:current-page="state.page" class="qq-pager" :page-size="state.size" :total="state.total" :pager-count="5" layout="prev, pager, next" @current-change="loadAccounts" />
    </section>

    <QQAuthConfig v-if="canManage" />
    <details v-if="configuration" class="configuration-note">
      <summary>{{ t(configuration.title || '配置与迁移') }}</summary>
      <p>{{ panelText(configuration.markdown, configuration.markdownKey) }}</p>
    </details>
    <ActionRunner ref="runner" namespace="qqauth" :columns="table?.columns || []" @done="actionDone" />
  </div>
</template>

<style scoped>
.qq-workspace { max-width: 1200px; min-width: 0; color: var(--lbr-text); }
.qq-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
.qq-heading h1 { display: flex; align-items: center; gap: 10px; margin: 0 0 6px; font-size: 22px; font-weight: 650; line-height: 1.4; }
.qq-heading h1 :deep(svg) { color: var(--el-color-primary); }
.qq-heading p { margin: 0; color: var(--lbr-text-soft); font-size: 12px; line-height: 1.6; }
.qq-heading :deep(.el-button > span) { display: inline-flex; align-items: center; gap: 6px; }
.qq-status { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; min-height: 46px; padding: 11px 0; border-top: 1px solid var(--el-border-color); border-bottom: 1px solid var(--el-border-color); font-size: 12px; }
.state-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--lbr-text-soft); flex: none; }
.state-dot.ready { background: var(--el-color-success); }.state-dot.problem { background: var(--el-color-danger); }
.status-label, .count { color: var(--lbr-text-soft); }.qq-status strong { font-weight: 650; }
.status-divider { height: 14px; width: 1px; background: var(--el-border-color); margin: 0 8px; }
.view-only { margin-left: auto; color: var(--lbr-text-soft); }
.account-section { margin-top: 25px; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 14px; }
.section-heading > div:first-child { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.section-heading h2 { margin: 0; font-size: 16px; font-weight: 650; }
.count { font-size: 12px; }
.search-tools { display: flex; gap: 8px; align-items: center; width: min(420px, 100%); }
.search-tools :deep(.el-input) { min-width: 0; }
.qq-error { margin: 14px 0; padding: 14px; border-left: 3px solid var(--el-color-danger); color: var(--el-color-danger); background: var(--el-color-danger-light-9); font-size: 12px; line-height: 1.6; }
.account-list { min-height: 170px; border-top: 1px solid var(--el-border-color); }
.list-header, .account-row { display: grid; grid-template-columns: minmax(210px, 1.45fr) minmax(160px, .9fr) minmax(125px, .75fr) minmax(195px, auto); align-items: center; column-gap: 18px; }
.list-header.readonly, .account-row.readonly { grid-template-columns: minmax(210px, 1.45fr) minmax(160px, .9fr) minmax(125px, .75fr); }
.list-header { padding: 11px 12px; color: var(--lbr-text-soft); font-size: 11px; border-bottom: 1px solid var(--el-border-color-light); }
.account-row { padding: 14px 12px; border-bottom: 1px solid var(--el-border-color-light); min-width: 0; background: var(--lbr-surface); }
.account-row:hover { background: var(--el-fill-color-light); }
.identity { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.identity strong { font-size: 13px; font-weight: 650; line-height: 1.3; overflow: hidden; text-overflow: ellipsis; }
.uuid { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--lbr-text-soft); user-select: text; }
.badges { display: flex; gap: 5px; flex-wrap: wrap; }.badge { display: inline-flex; align-items: center; padding: 3px 7px; border-radius: 4px; white-space: nowrap; font-size: 11px; }
.badge.normal, .badge.online { color: var(--el-color-success); background: var(--el-color-success-light-9); }
.badge.locked { color: var(--el-color-warning); background: var(--el-color-warning-light-9); }.badge.offline { color: var(--lbr-text-soft); background: var(--el-fill-color); }
.created { color: var(--lbr-text-soft); font-size: 12px; white-space: nowrap; }.state-label { display: none; }
.row-actions { display: flex; gap: 4px; justify-content: flex-end; min-width: 0; }
.row-actions :deep(.el-button + .el-button) { margin-left: 0; }.row-actions :deep(.el-button) { font-size: 12px; }
:global(.danger-item) { color: var(--el-color-danger) !important; }
.qq-pager { display: flex; justify-content: flex-end; margin-top: 15px; }
.configuration-note { margin-top: 24px; border-top: 1px solid var(--el-border-color-light); padding: 14px 0; font-size: 12px; }
.configuration-note summary { color: var(--lbr-text-soft); cursor: pointer; font-weight: 600; width: fit-content; }
.configuration-note summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.configuration-note p { margin: 10px 0 0; max-width: 80ch; color: var(--lbr-text-soft); line-height: 1.7; }
@media (max-width: 1060px) { .list-header, .account-row, .list-header.readonly, .account-row.readonly { grid-template-columns: minmax(180px, 1.2fr) minmax(145px, .8fr) minmax(180px, auto); }.list-header > :nth-child(3), .created { display: none; }.list-header.readonly, .account-row.readonly { grid-template-columns: minmax(180px, 1.2fr) minmax(145px, .8fr); } }
@media (max-width: 760px) { .qq-heading, .section-heading { align-items: flex-start; flex-direction: column; }.search-tools { width: 100%; }.list-header { display: none; }.account-row, .account-row.readonly { grid-template-columns: minmax(0, 1fr); row-gap: 11px; padding: 15px 0; }.identity .uuid { white-space: normal; overflow-wrap: anywhere; }.account-state, .created { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }.created { display: flex; }.state-label { display: inline; color: var(--lbr-text-soft); font-size: 11px; min-width: 50px; }.row-actions { justify-content: flex-start; padding-left: 58px; }.view-only { margin-left: 0; width: 100%; }.qq-pager { justify-content: center; } }
@media (prefers-reduced-motion: reduce) { .account-row { transition: none; } }
</style>
