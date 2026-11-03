<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import { t, locale } from '../../i18n'

const auth = useAuth()
const pageSize = 20
const configKeys = ['alias', 'pvp', 'allowWeather', 'autoLoad']
const worlds = ref([])
const total = ref(0)
const page = ref(1)
const query = ref('')
const searchDraft = ref('')
const selected = ref(null)
const saved = ref(null)
const bridge = ref('')
const updatedAt = ref('')
const loading = ref(false)
const pending = ref('')
const error = ref('')
const notice = ref('')
const uncertain = ref(false)
const playerName = ref('')
const draft = reactive({ alias: '', pvp: false, allowWeather: false, autoLoad: false })
let disposed = false

const canConfigure = computed(() => auth.can('multiverse.configure'))
const canManageLifecycle = computed(() => auth.can('multiverse.lifecycle'))
const canTeleport = computed(() => auth.can('multiverse.teleport'))
const busy = computed(() => loading.value || !!pending.value)
const dirty = computed(() => !!saved.value && configKeys.some(key => draft[key] !== saved.value[key]))
const configReady = computed(() => !!selected.value &&
  typeof selected.value.alias === 'string' &&
  ['pvp', 'allowWeather', 'autoLoad'].every(key => typeof selected.value[key] === 'boolean'))
const locked = computed(() => busy.value || uncertain.value || !canConfigure.value || !configReady.value)
const selectedLabel = computed(() => selected.value?.alias || selected.value?.name || '')
const loadedCount = computed(() => worlds.value.filter(world => world.loaded).length)
const bridgeAvailable = computed(() => bridge.value.includes('可用') && !bridge.value.includes('不可用'))
const configStatus = computed(() => t(uncertain.value ? '待核对' : !configReady.value ? '不可用' : dirty.value ? '未保存' : '已保存'))

function configOf(world) {
  return {
    alias: typeof world?.alias === 'string' ? world.alias : '',
    pvp: world?.pvp === true,
    allowWeather: world?.allowWeather === true,
    autoLoad: world?.autoLoad === true
  }
}

function applySelection(world) {
  selected.value = world || null
  saved.value = world ? configOf(world) : null
  Object.assign(draft, saved.value || configOf(null))
}

function updateTimestamp() {
  updatedAt.value = new Date().toLocaleTimeString(locale.value === 'en_US' ? 'en-US' : 'zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function parseWorlds(result) {
  requireSuccess(result)
  if (!Array.isArray(result?.items) || !Number.isFinite(Number(result.total))) throw new Error(t('世界列表接口返回格式错误'))
  return { items: result.items, total: Number(result.total) }
}

function parseBridge(result) {
  requireSuccess(result)
  const rows = Array.isArray(result) ? result : result?.items
  if (!Array.isArray(rows)) throw new Error(t('桥接状态接口返回格式错误'))
  return rows.find(row => row.label === 'Multiverse桥接')?.value || t('桥接状态未知')
}

async function fetchSnapshot() {
  const [worldResult, bridgeResult] = await Promise.all([
    api.get('/api/ext/multiverse/worlds', { page: page.value, size: pageSize, q: query.value }),
    api.get('/api/ext/multiverse/status')
  ])
  return { worlds: parseWorlds(worldResult), bridge: parseBridge(bridgeResult) }
}

async function confirmDiscard(message = t('重新读取将放弃当前未保存的配置修改。是否继续？')) {
  if (!dirty.value && !uncertain.value) return true
  try {
    await ElMessageBox.confirm(message, t(uncertain.value ? '核对操作结果' : '放弃未保存修改'), {
      confirmButtonText: t(uncertain.value ? '重新读取' : '放弃修改'),
      cancelButtonText: t('继续编辑'),
      type: 'warning',
      closeOnClickModal: false
    })
    return true
  } catch { return false }
}

function applySnapshot(snapshot, chooseFirst = false) {
  worlds.value = snapshot.worlds.items
  total.value = snapshot.worlds.total
  bridge.value = snapshot.bridge
  let next = worlds.value.find(world => world.name === selected.value?.name)
  if (!next && (chooseFirst || !selected.value)) next = worlds.value[0] || null
  if (next) applySelection(next)
  else if (!worlds.value.some(world => world.name === selected.value?.name)) applySelection(null)
  uncertain.value = false
  updateTimestamp()
}

async function load({ force = false, chooseFirst = false } = {}) {
  if (busy.value) return false
  if (!force && !await confirmDiscard()) return false
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const snapshot = await fetchSnapshot()
    if (disposed) return false
    applySnapshot(snapshot, chooseFirst)
    if (selected.value && !worlds.value.some(world => world.name === selected.value.name)) {
      applySelection(worlds.value[0] || null)
    }
    return true
  } catch (cause) {
    if (!disposed) error.value = cause.message || t('读取世界配置失败')
    return false
  } finally {
    if (!disposed) loading.value = false
  }
}

async function selectWorld(world) {
  if (world?.name === selected.value?.name) return
  if (uncertain.value) {
    if (!await confirmDiscard(t('保存结果尚未核对。请先重新读取服务器状态，再切换世界。'))) return
    if (!await load({ force: true })) return
    const fresh = worlds.value.find(item => item.name === world?.name)
    if (fresh) applySelection(fresh)
    return
  }
  if (!await confirmDiscard()) return
  applySelection(world)
  notice.value = ''
  error.value = ''
  uncertain.value = false
}

async function searchWorlds() {
  if (busy.value) return
  if (!await confirmDiscard(t('搜索世界将离开当前配置草稿。是否继续？'))) {
    searchDraft.value = query.value
    return
  }
  query.value = searchDraft.value.trim()
  page.value = 1
  await load({ force: true, chooseFirst: true })
}

async function changePage(nextPage) {
  if (busy.value) return
  const previousPage = page.value
  if (!await confirmDiscard(t('切换列表页面将放弃当前配置草稿。是否继续？'))) {
    page.value = previousPage
    return
  }
  page.value = nextPage
  await load({ force: true, chooseFirst: true })
}

async function restoreDraft() {
  if (saved.value) Object.assign(draft, saved.value)
}

function message(cause) {
  if (cause?.status === 403) return t('当前账号没有执行此操作的权限。')
  return cause?.message || t('操作失败，请稍后重试。')
}

async function saveConfig() {
  if (locked.value || !dirty.value || !selected.value || !saved.value) return
  pending.value = 'save'
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    submitted = true
    const result = requireSuccess(await api.post('/api/ext/multiverse/configure', {
      name: selected.value.name,
      values: configOf(draft),
      expected: { ...saved.value }
    }))
    const snapshot = await fetchSnapshot()
    if (disposed) return
    applySnapshot(snapshot)
    notice.value = result?.message || t('世界配置已保存')
  } catch (cause) {
    if (disposed) return
    error.value = message(cause)
    if (submitted) {
      uncertain.value = true
      error.value += t(' 请重新读取并核对实际配置后再继续。')
    }
  } finally {
    if (!disposed) pending.value = ''
  }
}

async function runWorldAction(operation) {
  if (busy.value || dirty.value || uncertain.value || !selected.value) return
  if (operation === 'load' && !canManageLifecycle.value) return
  if (operation === 'unload' && (!canManageLifecycle.value || selected.value.players > 0)) return
  if (operation === 'teleport' && (!canTeleport.value || !selected.value.loaded || !playerName.value.trim())) return
  if (operation === 'load' || operation === 'unload') {
    const text = operation === 'unload'
      ? t('将保存并卸载「{name}」。主世界、Multiverse 默认世界及有玩家的世界不会被卸载。是否继续？', { name: selected.value.name })
      : t('将加载「{name}」。是否继续？', { name: selected.value.name })
    try {
      await ElMessageBox.confirm(text, t(operation === 'unload' ? '保存并卸载世界' : '加载世界'), {
        confirmButtonText: t(operation === 'unload' ? '保存并卸载' : '加载世界'),
        cancelButtonText: t('取消'),
        type: operation === 'unload' ? 'warning' : 'info',
        closeOnClickModal: false
      })
    } catch { return }
  } else {
    try {
      await ElMessageBox.confirm(
        t('将把在线玩家「{player}」传送到「{name}」的出生点，并执行 Multiverse 安全检查。提交成功不代表已到达。', { player: playerName.value.trim(), name: selected.value.name }),
        t('传送在线玩家'),
        { confirmButtonText: t('提交传送'), cancelButtonText: t('取消'), type: 'warning', closeOnClickModal: false }
      )
    } catch { return }
  }

  pending.value = operation
  error.value = ''
  notice.value = ''
  let submitted = false
  try {
    submitted = true
    let result
    if (operation === 'teleport') {
      result = await api.post('/api/ext/multiverse/teleport', { name: selected.value.name, player: playerName.value.trim() })
    } else {
      result = await api.post('/api/ext/multiverse/lifecycle', { name: selected.value.name, operation })
    }
    requireSuccess(result)
    const snapshot = await fetchSnapshot()
    if (disposed) return
    applySnapshot(snapshot)
    notice.value = result?.message || t(operation === 'teleport' ? '传送请求已提交；请核对最终位置与审计结果。' : '世界状态已更新')
    if (operation === 'teleport') playerName.value = ''
  } catch (cause) {
    if (disposed) return
    error.value = message(cause)
    if (submitted) {
      uncertain.value = true
      error.value += t(' 请重新读取核对状态，避免重复提交。')
    }
  } finally {
    if (!disposed) pending.value = ''
  }
}

async function leave() {
  if (busy.value) {
    await ElMessageBox.alert(t('当前操作尚未结束，请完成后再离开。'), t('操作进行中'))
    return false
  }
  if (uncertain.value) return confirmDiscard(t('操作结果尚未核对。仍要离开此页面吗？'))
  if (dirty.value) return confirmDiscard(t('当前世界配置尚未保存。离开后将丢失修改，是否继续？'))
  return true
}

function beforeUnload(event) {
  if (dirty.value || busy.value || uncertain.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  load({ force: true, chooseFirst: true })
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="multiverse-workspace" :aria-busy="busy">
    <header class="workspace-heading">
      <div>
        <h1><Icon name="globe" :size="23" />{{ t('多世界管理') }}</h1>
      </div>
      <el-button :loading="loading" :disabled="busy" @click="load()">
        <Icon name="refresh-cw" :size="14" />{{ t('重新读取') }}
      </el-button>
    </header>

    <div class="bridge-strip" :class="{ unavailable: !bridgeAvailable }" role="status" :aria-busy="loading">
      <span class="bridge-indicator" />
      <strong>{{ t('Multiverse 桥接') }}</strong>
      <span class="bridge-description">{{ bridge || t(loading ? '正在读取状态…' : '状态待确认') }}</span>
      <span class="bridge-count">{{ t('{count} 个世界', { count: total }) }}</span>
      <time v-if="updatedAt">{{ t('更新于 {time}', { time: updatedAt }) }}</time>
    </div>

    <div v-if="error" class="feedback error" role="alert">{{ error }}</div>
    <div v-if="notice" class="feedback success" role="status">{{ notice }}</div>
    <div v-if="uncertain" class="feedback warning" role="alert">{{ t('操作结果尚未核对。请重新读取后再保存或执行操作。') }}</div>
    <div v-if="!canConfigure" class="feedback" role="note">{{ t('当前账号仅可查看世界配置。') }}</div>

    <div class="workspace-layout">
      <aside class="world-roster" :aria-label="t('Multiverse 世界列表')" :aria-busy="loading">
        <header class="roster-heading"><h2>{{ t('世界列表') }}</h2><span>{{ total }}</span></header>
        <form class="search-row" @submit.prevent="searchWorlds">
          <el-input v-model="searchDraft" :aria-label="t('按世界名或别名搜索')" :placeholder="t('世界名或别名')" clearable />
          <el-button native-type="submit" :disabled="busy" :title="t('搜索世界')" :aria-label="t('搜索世界')"><Icon name="search" :size="15" /></el-button>
        </form>
        <div v-if="loading && !worlds.length" class="roster-state" role="status">{{ t('正在读取世界列表…') }}</div>
        <div v-else-if="!worlds.length" class="roster-state">{{ t(query ? '没有匹配的世界。' : '没有已注册的世界。') }}</div>
        <ul v-else class="world-list">
          <li v-for="world in worlds" :key="world.name">
            <button type="button" class="world-option" :class="{ selected: selected?.name === world.name }" :aria-current="selected?.name === world.name ? 'true' : undefined" @click="selectWorld(world)">
              <span class="world-state-dot" :class="{ loaded: world.loaded }" />
              <span class="world-option-copy"><strong>{{ world.alias || world.name }}</strong><small>{{ world.name }}<span v-if="world.players"> · {{ t('{count} 名玩家', { count: world.players }) }}</span></small></span>
              <span class="world-option-status">{{ t(world.loaded ? '已加载' : '未加载') }}</span>
            </button>
          </li>
        </ul>
        <el-pagination v-if="total > pageSize" class="roster-pagination" small layout="prev, pager, next" :total="total" :page-size="pageSize" :current-page="page" :disabled="busy" @current-change="changePage" />
        <div v-if="worlds.length" class="roster-summary">{{ t('当前页已加载 {loaded} / {total} 个世界', { loaded: loadedCount, total: worlds.length }) }}</div>
      </aside>

      <main class="world-editor">
        <div v-if="!selected" class="editor-empty" role="status">
          <Icon name="map" :size="24" />
          <strong>{{ t(loading ? '正在读取世界…' : '选择一个世界') }}</strong>
          <span>{{ t('世界配置、运行状态与操作会显示在这里。') }}</span>
        </div>
        <template v-else>
          <header class="world-heading">
            <div class="world-identity">
              <h2>{{ selectedLabel }}</h2>
              <code>{{ selected.name }}</code>
            </div>
            <div class="world-summary">
              <span class="player-count">{{ t('{count} 名在线玩家', { count: selected.players }) }}</span>
              <span class="load-state" :class="{ loaded: selected.loaded }"><span class="world-state-dot" :class="{ loaded: selected.loaded }" />{{ t(selected.loaded ? '已加载' : '未加载') }}</span>
            </div>
          </header>

          <section class="config-section" aria-labelledby="world-config-title">
            <header class="section-heading">
              <h3 id="world-config-title">{{ t('世界配置') }}</h3>
              <span class="save-state" :class="{ unsaved: dirty || uncertain, verified: configReady && !dirty && !uncertain, unavailable: !configReady }">{{ configStatus }}</span>
            </header>
            <div v-if="selected && !configReady" class="feedback warning" role="alert">
              {{ t('当前桥接未返回完整配置状态。为避免误覆盖，请更新桥接插件后重新读取。') }}
            </div>
            <div class="config-fields" :aria-disabled="locked">
              <div class="alias-field">
                <label for="world-alias">{{ t('世界别名') }}</label>
                <div class="alias-control">
                  <el-input id="world-alias" v-model="draft.alias" :aria-label="t('世界别名')" maxlength="128" show-word-limit :disabled="locked" :placeholder="t('留空时显示世界名')" />
                </div>
                <small>{{ t('留空可清除别名；最多 128 个字符。') }}</small>
              </div>
              <label class="setting-row">
                <span><strong>{{ t('允许 PVP') }}</strong><small>{{ t('控制玩家之间是否可以互相攻击。') }}</small></span>
                <el-switch v-model="draft.pvp" :disabled="locked" :aria-label="t('允许 PVP')" />
              </label>
              <label class="setting-row">
                <span><strong>{{ t('允许天气变化') }}</strong><small>{{ t('控制世界是否运行自然天气变化。') }}</small></span>
                <el-switch v-model="draft.allowWeather" :disabled="locked" :aria-label="t('允许天气变化')" />
              </label>
              <label class="setting-row">
                <span><strong>{{ t('服务器启动时自动加载') }}</strong><small>{{ t('控制该世界是否随 Multiverse 启动自动加载。') }}</small></span>
                <el-switch v-model="draft.autoLoad" :disabled="locked" :aria-label="t('服务器启动时自动加载')" />
              </label>
            </div>
            <footer class="config-actions">
              <span v-if="dirty">{{ t('存在未保存的配置更改。') }}</span>
              <span v-else>{{ t('显示的是服务器当前保存值。') }}</span>
              <div>
                <el-button :disabled="locked || !dirty" @click="restoreDraft"><Icon name="undo-2" :size="14" />{{ t('还原修改') }}</el-button>
                <el-button type="primary" :loading="pending === 'save'" :disabled="locked || !dirty" @click="saveConfig"><Icon name="save" :size="14" />{{ t('保存世界配置') }}</el-button>
              </div>
            </footer>
          </section>

          <section class="operations-section" aria-labelledby="world-actions-title">
            <header class="section-heading"><h3 id="world-actions-title">{{ t('世界操作') }}</h3></header>
            <div class="operation-grid">
              <div v-if="canManageLifecycle" class="operation-row">
                <div><strong>{{ t(selected.loaded ? '保存并卸载' : '加载世界') }}</strong><small>{{ t(selected.loaded ? '主世界、默认世界及有玩家的世界不会被卸载。' : '从已注册世界中加载此世界。') }}</small></div>
                <el-button :type="selected.loaded ? 'warning' : 'primary'" :plain="selected.loaded" :loading="pending === (selected.loaded ? 'unload' : 'load')" :disabled="busy || dirty || uncertain || selected.loaded && selected.players > 0" @click="runWorldAction(selected.loaded ? 'unload' : 'load')">
                  <Icon :name="selected.loaded ? 'folder-down' : 'folder-up'" :size="14" />{{ t(selected.loaded ? '保存并卸载' : '加载世界') }}
                </el-button>
              </div>
              <form v-if="canTeleport" class="operation-row teleport-row" @submit.prevent="runWorldAction('teleport')">
                <div class="teleport-copy"><label for="teleport-player">{{ t('传送在线玩家') }}</label><small>{{ t('传送至此世界出生点，执行 Multiverse 安全检查。') }}</small></div>
                <div class="teleport-control">
                  <el-input id="teleport-player" v-model="playerName" :aria-label="t('在线玩家精确名称')" maxlength="128" :disabled="busy || dirty || uncertain || !selected.loaded" :placeholder="t('在线玩家精确名称')" />
                  <el-button type="primary" native-type="submit" :loading="pending === 'teleport'" :disabled="busy || dirty || uncertain || !selected.loaded || !playerName.trim()"><Icon name="send" :size="14" />{{ t('提交传送') }}</el-button>
                </div>
              </form>
            </div>
            <div v-if="!canManageLifecycle && !canTeleport" class="no-actions">{{ t('当前账号没有世界操作权限。') }}</div>
          </section>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.multiverse-workspace { width:100%; max-width:1320px; min-width:0; color:var(--lbr-text); }
.workspace-heading,.world-heading,.section-heading,.config-actions,.operation-row { display:flex; justify-content:space-between; align-items:center; gap:16px; }
.workspace-heading { margin-bottom:16px; }
.workspace-heading h1 { display:flex; align-items:center; gap:10px; margin:0; font-size:24px; line-height:1.3; }
.workspace-heading h1 :deep(svg) { color:var(--el-color-primary); }
.workspace-heading p,.section-heading p { margin:0; color:var(--lbr-text-soft); font-size:12px; line-height:1.7; }
.workspace-heading :deep(.el-button) { flex-shrink:0; }
.bridge-strip { display:flex; align-items:center; gap:7px 12px; flex-wrap:wrap; min-height:38px; margin-bottom:14px; padding:7px 12px; border:1px solid var(--el-border-color-light); border-left:3px solid var(--el-color-success); background:var(--lbr-surface); color:var(--lbr-text); font-size:12px; }
.bridge-strip.unavailable { border-left-color:var(--el-color-warning); }
.bridge-indicator,.world-state-dot { width:7px; height:7px; flex:0 0 7px; border-radius:50%; background:var(--el-color-warning); }
.bridge-indicator { background:var(--el-color-success); }
.bridge-strip.unavailable .bridge-indicator { background:var(--el-color-warning); }
.bridge-strip strong { font-weight:650; }
.bridge-description { color:var(--lbr-text-soft); overflow-wrap:anywhere; }
.bridge-count { margin-left:auto; font-variant-numeric:tabular-nums; }
.bridge-strip time { color:var(--lbr-text-soft); font-size:11px; font-variant-numeric:tabular-nums; }
.feedback { margin:0 0 14px; padding:10px 12px; border:1px solid var(--el-border-color-light); border-radius:6px; color:var(--lbr-text-soft); font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.feedback.error { border-color:var(--el-color-danger-light-5); color:var(--el-color-danger); }
.feedback.warning { border-color:var(--el-color-warning-light-7); background:var(--el-color-warning-light-9); color:var(--el-color-warning); }
.feedback.success { border-color:var(--el-color-success-light-7); color:var(--el-color-success); }
.workspace-layout { display:grid; grid-template-columns:240px minmax(0,1fr); align-items:stretch; min-width:0; border:1px solid var(--el-border-color-light); border-radius:8px; background:var(--lbr-surface); overflow:hidden; }
.world-roster { min-width:0; padding:18px 14px; border-right:1px solid var(--el-border-color-light); background:var(--el-fill-color-extra-light); }
.roster-heading { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:12px; }
.roster-heading h2 { margin:0; font-size:13px; font-weight:650; }
.roster-heading > span { color:var(--lbr-text-soft); font-size:11px; font-variant-numeric:tabular-nums; }
.search-row { display:flex; gap:6px; margin-bottom:12px; }
.search-row :deep(.el-input) { min-width:0; }
.search-row :deep(.el-input__wrapper),.teleport-control :deep(.el-input__wrapper) { min-height:34px; border-radius:5px; }
.search-row :deep(.el-button) { flex:0 0 36px; width:36px; height:34px; padding:0; margin:0; border-radius:5px; }
.world-list { display:grid; gap:3px; padding:0; margin:0; list-style:none; max-height:min(58vh,620px); overflow:auto; }
.world-option { display:flex; align-items:center; gap:8px; width:100%; min-height:54px; padding:9px 8px; border:1px solid transparent; border-radius:5px; background:transparent; color:var(--lbr-text); text-align:left; cursor:pointer; transition:background-color 160ms, border-color 160ms, transform 320ms var(--motion-spring); }
.world-option:active { transform:scale(.98); }
.world-option:hover { background:var(--el-fill-color-light); }
.world-option.selected { border-color:var(--el-color-primary-light-8); background:var(--el-color-primary-light-9); }
.world-option .world-state-dot.loaded,.load-state .world-state-dot.loaded { background:var(--el-color-success); }
.world-option-copy { display:block; flex:1; min-width:0; }
.world-option-copy strong,.world-option-copy small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.world-option-copy strong { font-size:12px; font-weight:600; }
.world-option-copy small { margin-top:3px; color:var(--lbr-text-soft); font-size:10px; }
.world-option-status { color:var(--lbr-text-soft); font-size:10px; white-space:nowrap; }
.roster-state { padding:22px 8px; color:var(--lbr-text-soft); font-size:12px; text-align:center; }
.roster-pagination { justify-content:center; margin-top:12px; }
.roster-summary { margin-top:12px; padding-top:10px; border-top:1px solid var(--el-border-color-lighter); color:var(--lbr-text-soft); font-size:10px; }
.world-editor { min-width:0; padding:20px 24px; }
.editor-empty { display:flex; flex-direction:column; align-items:center; justify-content:center; min-height:260px; gap:9px; color:var(--lbr-text-soft); text-align:center; }
.editor-empty strong { color:var(--lbr-text); font-size:14px; }
.editor-empty span { font-size:12px; }
.world-heading { align-items:center; padding-bottom:18px; }
.world-identity { min-width:0; }
.world-identity h2 { margin:0 0 3px; font-size:20px; line-height:1.35; overflow-wrap:anywhere; }
.world-identity code { display:inline-block; max-width:100%; color:var(--lbr-text-soft); font-size:11px; overflow-wrap:anywhere; }
.world-summary { display:flex; flex-wrap:wrap; justify-content:flex-end; align-items:center; gap:8px 12px; }
.player-count { color:var(--lbr-text-soft); font-size:12px; white-space:nowrap; }
.load-state { display:flex; align-items:center; gap:7px; flex-shrink:0; padding:5px 8px; border:1px solid var(--el-border-color-light); border-radius:5px; color:var(--lbr-text-soft); font-size:11px; }
.load-state.loaded { color:var(--el-color-success); border-color:var(--el-color-success-light-7); }
.config-section,.operations-section { min-width:0; padding:16px 0 0; border-top:1px solid var(--el-border-color-light); }
.section-heading { align-items:center; margin-bottom:12px; }
.section-heading h3 { margin:0; font-size:14px; font-weight:650; }
.save-state { flex-shrink:0; color:var(--lbr-text-soft); font-size:11px; }
.save-state.verified { color:var(--el-color-success); }
.save-state.unsaved { color:var(--el-color-warning); }
.save-state.unavailable { color:var(--lbr-text-soft); }
.config-fields { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); column-gap:20px; }
.alias-field { grid-column:1/-1; padding:0 0 16px; }
.alias-field > label { display:block; margin-bottom:7px; font-size:12px; font-weight:600; }
.alias-control { display:flex; align-items:center; gap:12px; min-width:0; }
.alias-control :deep(.el-input) { max-width:560px; }
.alias-control :deep(.el-input__wrapper) { min-height:36px; border-radius:5px; }
.alias-field > small { display:block; margin-top:6px; color:var(--lbr-text-soft); font-size:11px; }
.setting-row { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; min-width:0; padding:12px 0; border-top:1px solid var(--el-border-color-lighter); cursor:pointer; }
.setting-row > span { min-width:0; }
.setting-row strong,.operation-row strong { display:block; font-size:12px; font-weight:600; }
.setting-row small,.operation-row small { display:block; margin-top:4px; color:var(--lbr-text-soft); font-size:11px; line-height:1.6; }
.setting-row :deep(.el-switch) { flex-shrink:0; margin-top:-5px; }
.config-actions { align-items:center; flex-wrap:wrap; margin-top:8px; padding:12px 0 16px; }
.config-actions > span { color:var(--lbr-text-soft); font-size:11px; }
.config-actions > div { display:flex; gap:7px; flex-wrap:wrap; }
.config-actions :deep(.el-button) { margin:0; }
.operations-section { margin-top:0; }
.operation-grid { display:grid; gap:0; }
.operation-row { align-items:center; min-width:0; padding:12px 0; border-top:1px solid var(--el-border-color-lighter); }
.operation-row:first-child { padding-top:0; border-top:0; }
.operation-row > div:first-child { min-width:0; }
.operation-row :deep(.el-button) { flex-shrink:0; margin:0; }
.teleport-copy { flex:0 0 210px; }
.teleport-copy label { font-size:12px; font-weight:600; }
.teleport-control { display:flex; flex:1; min-width:0; gap:7px; justify-content:flex-end; }
.teleport-control :deep(.el-input) { max-width:300px; min-width:0; }
.teleport-control :deep(.el-button) { flex-shrink:0; }
.no-actions { padding:14px 0; border-top:1px solid var(--el-border-color-lighter); color:var(--lbr-text-soft); font-size:11px; }
button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
@media (min-width:901px) and (max-width:1180px) {
  .workspace-layout { grid-template-columns:210px minmax(0,1fr); }
  .world-editor { padding:18px; }
  .config-fields { grid-template-columns:minmax(0,1fr); }
  .setting-row { align-items:center; }
  .setting-row :deep(.el-switch) { margin-top:0; }
  .teleport-row { flex-wrap:wrap; }
  .teleport-control { flex-basis:100%; }
}
@media (max-width:900px) {
  .workspace-layout { grid-template-columns:minmax(0,1fr); }
  .world-roster { padding:14px 16px; border-right:0; border-bottom:1px solid var(--el-border-color-light); }
  .world-list { max-height:164px; }
  .world-editor { padding:18px; }
}
@media (max-width:620px) {
  .workspace-heading h1 { font-size:21px; }
  .workspace-heading { align-items:center; }
  .bridge-strip { align-items:flex-start; }
  .bridge-count { margin-left:0; }
  .bridge-strip time { flex-basis:100%; margin-left:19px; }
  .config-fields { grid-template-columns:minmax(0,1fr); }
  .setting-row { align-items:center; }
  .setting-row :deep(.el-switch) { margin-top:0; }
  .alias-field { grid-column:auto; }
  .alias-control { align-items:stretch; flex-direction:column; gap:6px; }
  .alias-control :deep(.el-input) { max-width:none; }
  .world-heading { gap:10px; }
  .world-identity h2 { font-size:18px; }
  .world-summary { align-items:flex-end; flex-direction:column; gap:6px; }
  .operation-row,.teleport-row { align-items:flex-start; flex-direction:column; }
  .teleport-copy { flex-basis:auto; }
  .teleport-control { width:100%; justify-content:flex-start; }
  .teleport-control :deep(.el-input) { flex:1; max-width:none; }
  .config-actions { align-items:flex-start; flex-direction:column; }
}
@media (prefers-reduced-motion:reduce) {
  .world-option,.world-option :deep(*) { transition:none !important; animation:none !important; }
}
</style>
