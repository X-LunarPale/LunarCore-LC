<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { useAuth } from '../../stores/auth'
import { requireSuccess } from '../../utils/panel'
import { t } from '../../i18n'
import { baseConfigKeys, configMinY, defaultConfig, parseConfig, parseStatus, sameConfig, sameSpawn, validName, validSeed, validSpawn, validTerrainHeight, worldStates } from './terrain'

const auth = useAuth()
const snapshot = ref(null)
const loading = ref(false)
const pending = ref('')
const error = ref('')
const notice = ref('')
const uncertain = ref(false)
const verified = ref(false)
const selectedName = ref('')
const player = ref('')
const spawnDraft = reactive({ x: '', y: '', z: '' })
const chosenPresetId = ref('')
const copyConfig = config => ({ ...Object.fromEntries(baseConfigKeys.map(key => [key, config[key]])), advanced: { ...config.advanced } })
const draft = reactive({ name: '', seed: '', ...copyConfig(defaultConfig) })
const customHeight = reactive({ minY: -64, maxY: 640 })
let baseline = copyConfig(defaultConfig)
let initialized = false
let disposed = false
let timer
const worlds = computed(() => snapshot.value?.worlds || [])
const heights = computed(() => snapshot.value?.heights || [])
const heightRanges = computed(() => snapshot.value?.heightRanges
  || heights.value.map(height => ({ minY: -64, maxY: height - 64, height })))
const rangeKey = config => `${configMinY(config)}:${config.height}`
const rangeAvailable = config => heightRanges.value.some(range => `${range.minY}:${range.height}` === rangeKey(config))
const selectedHeightRange = computed({
  get: () => rangeKey(draft),
  set: key => {
    const range = heightRanges.value.find(range => `${range.minY}:${range.height}` === key)
    if (!range) return
    draft.height = range.height
    if (Object.hasOwn(draft.advanced, 'global_terrain.min_y')) draft.advanced['global_terrain.min_y'] = range.minY
  }
})
const selected = computed(() => worlds.value.find(world => world.name === selectedName.value))
const canManage = computed(() => auth.can('terrain.manage'))
const canTeleport = computed(() => auth.can('terrain.teleport'))
const busy = computed(() => loading.value || !!pending.value)
const blocked = computed(() => busy.value || uncertain.value || !verified.value || !snapshot.value?.supported || snapshot.value.busy)
const dirty = computed(() => !!draft.name || !!draft.seed || !sameConfig(draft, baseline))
const spawnDirty = computed(() => selected.value?.spawn ? !sameSpawn(spawnDraft, selected.value.spawn)
  : ['x', 'y', 'z'].some(key => spawnDraft[key] !== ''))
const spawnValidation = computed(() => selected.value && !validSpawn(spawnDraft, selected.value.minY, selected.value.maxY)
  ? '出生点坐标须为世界范围内的整数。' : '')
watch(selected, (world, previous) => {
  if (world?.name !== previous?.name || sameSpawn(spawnDraft, previous?.spawn)
      || !previous?.spawn && ['x', 'y', 'z'].every(key => spawnDraft[key] === '')) {
    Object.assign(spawnDraft, world?.spawn || { x: '', y: '', z: '' })
  }
})
const activePreset = computed(() => {
  const presets = snapshot.value?.presets || []
  return presets.find(item => item.id === chosenPresetId.value && sameConfig(item.config, draft))
    || presets.find(item => sameConfig(item.config, draft))
})
const preset = computed(() => activePreset.value?.id || '')
const advancedGroups = computed(() => {
  const groups = new Map()
  for (const field of snapshot.value?.schema || []) {
    if (field.key === 'global_terrain.min_y') continue
    if (!groups.has(field.group)) groups.set(field.group, [])
    groups.get(field.group).push(field)
  }
  return [...groups].map(([name, fields]) => ({ name, fields }))
})
const validation = computed(() => !validName(draft.name.trim()) ? '名称须以小写字母开头，仅含小写字母、数字、下划线或短横线，最长 32 位。'
  : worlds.value.some(world => world.name === draft.name.trim()) ? '同名地形世界已登记，请使用新名称。'
  : !validSeed(draft.seed.trim()) ? '种子须为 64 位整数，或留空随机生成。'
  : !rangeAvailable(draft) ? '此高度尚未在当前运行的服务器注册，重启服务器后可用。' : '')
const toggles = [
  { key: 'rollingHills', label: '连绵丘陵' }, { key: 'undergroundRivers', label: '地下河流' },
  { key: 'lavaTunnels', label: '熔岩隧道' }, { key: 'junglePillars', label: '丛林石柱' }
]

function schedulePoll() {
  clearTimeout(timer)
  if (!disposed && snapshot.value && (snapshot.value.busy || worlds.value.some(world => ['queued', 'running'].includes(world.status)))) {
    timer = setTimeout(() => refresh(), 3000)
  }
}

async function readSnapshot() {
  const result = parseStatus(requireSuccess(await api.get('/api/ext/terrain/status')))
  if (disposed) return false
  snapshot.value = result
  verified.value = true
  uncertain.value = false
  if (!initialized) {
    baseline = copyConfig(result.defaults)
    Object.assign(draft, copyConfig(result.defaults))
    initialized = true
  }
  if (!worlds.value.some(world => world.name === selectedName.value)) selectedName.value = worlds.value[0]?.name || ''
  schedulePoll()
  return true
}

async function refresh() {
  if (busy.value) { schedulePoll(); return }
  loading.value = true
  error.value = ''
  try { await readSnapshot() }
  catch (cause) { if (!disposed) { verified.value = false; error.value = t(cause.message || '读取地形状态失败') } }
  finally { if (!disposed) loading.value = false }
}

function choosePreset(id) {
  const match = snapshot.value?.presets.find(item => item.id === id)
  if (match && rangeAvailable(match.config)) {
    chosenPresetId.value = id
    Object.assign(draft, copyConfig(match.config))
  }
}

async function saveHeightRange() {
  if (blocked.value || !canManage.value || !validTerrainHeight(customHeight.maxY - customHeight.minY, customHeight.minY)) return
  pending.value = 'height-range'
  error.value = ''
  try {
    const result = requireSuccess(await api.post('/api/ext/terrain/height-ranges',
      { 'min-y': customHeight.minY, 'max-y': customHeight.maxY }))
    notice.value = t(result?.message || '高度范围已保存，完整重启服务器后可用')
  } catch (cause) {
    if (!disposed) error.value = t(cause.message || '操作失败，请稍后重试。')
  } finally {
    if (!disposed) { pending.value = ''; schedulePoll() }
  }
}

function restoreAdvanced(group) {
  if (blocked.value || !canManage.value) return
  for (const field of group.fields) draft.advanced[field.key] = field.defaultValue
}

async function confirm(message, title) {
  try {
    await ElMessageBox.confirm(message, title, { confirmButtonText: t('确认执行'), cancelButtonText: t('取消'), closeOnClickModal: false, type: 'warning' })
    return true
  } catch { return false }
}

function verifyWrite(operation, expectedName, body) {
  const world = worlds.value.find(item => item.name === expectedName)
  const mismatch = !world
    || operation === 'create' && (
      !sameConfig(world.config, body.config)
      || body.seed !== '' && BigInt(world.seed) !== BigInt(body.seed))
    || operation === 'load' && !world.autoLoad
    || operation === 'unload' && (world.loaded || world.autoLoad || world.status !== 'unloaded')
    || operation === 'spawn' && !sameSpawn(world.spawn, body)
  if (mismatch) throw new Error(t('地形操作结果与回读状态不一致，请刷新核对。'))
}

async function submit(operation, world) {
  if (blocked.value || operation !== 'teleport' && !canManage.value || operation === 'teleport' && !canTeleport.value) return
  let body = {}, endpoint, expectedName
  if (operation === 'create') {
    if (validation.value) { error.value = t(validation.value); return }
    let config
    try { config = parseConfig(draft, snapshot.value.schema) } catch (cause) { error.value = t(cause.message); return }
    expectedName = draft.name.trim()
    if (!await confirm(t('创建「{name}」并固定当前生成参数？新世界不会覆盖现有存档。', { name: expectedName }), t('创建世界'))) return
    body = { name: expectedName, seed: draft.seed.trim(), config }
    endpoint = '/api/ext/terrain/worlds'
  } else {
    if (!world || ['queued', 'running'].includes(world.status)) return
    if (operation === 'unload' && (!world.loaded || world.players > 0)) return
    if (operation === 'load' && (world.loaded || !rangeAvailable(world.config))) return
    if (operation === 'teleport' && (!world.loaded || world.status !== 'ready' || !/^[A-Za-z0-9_]{1,16}$/.test(player.value.trim()))) return
    if (operation === 'spawn') {
      if (!world.loaded || world.status !== 'ready' || !spawnDirty.value) return
      if (!validSpawn(spawnDraft, world.minY, world.maxY)) { error.value = t('出生点坐标须为世界范围内的整数。'); return }
      body = { x: spawnDraft.x, y: spawnDraft.y, z: spawnDraft.z }
    }
    expectedName = world.name
    const message = operation === 'unload' ? t('保存并卸载「{name}」，保留世界存档？', { name: world.name })
      : operation === 'load' ? t('加载「{name}」，沿用创建时的地形参数？', { name: world.name })
      : operation === 'spawn' ? t('将「{name}」的出生点设为 {x}, {y}, {z}？服务器将检查地板与两格净空。', { name: world.name, ...body })
      : t('将在线玩家「{player}」传送到「{name}」的安全出生点？', { player: player.value.trim(), name: world.name })
    if (!await confirm(message, t(operation === 'teleport' ? '传送在线玩家' : operation === 'spawn' ? '设置出生点' : operation === 'load' ? '加载世界' : '保存并卸载世界'))) return
    if (operation === 'teleport') body = { player: player.value.trim() }
    endpoint = `/api/ext/terrain/worlds/${encodeURIComponent(world.name)}/${operation}`
  }
  pending.value = operation
  error.value = ''
  notice.value = ''
  try {
    const result = requireSuccess(await api.post(endpoint, body))
    if (result?.ok !== true || !['unload', 'spawn'].includes(operation) && result.name !== expectedName) {
      throw new Error(t('地形操作结果与回读状态不一致，请刷新核对。'))
    }
    await readSnapshot()
    if (disposed) return
    verifyWrite(operation, expectedName, body)
    selectedName.value = expectedName
    if (operation === 'create') { draft.name = ''; draft.seed = ''; baseline = copyConfig(draft) }
    if (operation === 'teleport') player.value = ''
    notice.value = result?.message || t(operation === 'create' ? '世界创建请求已提交' : '世界状态已更新')
  } catch (cause) {
    if (!disposed) { uncertain.value = true; verified.value = false; error.value = t(cause.message || '操作失败，请稍后重试。') }
  } finally { if (!disposed) { pending.value = ''; schedulePoll() } }
}

async function leave() {
  if (pending.value) { await ElMessageBox.alert(t('当前操作尚未结束，请完成后再离开。'), t('操作进行中')); return false }
  if (uncertain.value) return confirm(t('操作结果尚未核对。仍要离开此页面吗？'), t('核对操作结果'))
  if (dirty.value) return confirm(t('新世界创建参数尚未提交。离开后将丢失，是否继续？'), t('放弃未保存修改'))
  if (spawnDirty.value) return confirm(t('出生点坐标尚未提交。离开后将丢失，是否继续？'), t('放弃未保存修改'))
  return true
}
function beforeUnload(event) {
  if (dirty.value || spawnDirty.value || pending.value || uncertain.value) { event.preventDefault(); event.returnValue = '' }
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); refresh() })
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="terrain-workspace" :aria-busy="busy">
    <header class="terrain-heading">
      <h1><Icon name="mountain-snow" :size="23" />{{ t('Tectonic 宏伟地形') }}</h1>
      <el-button :loading="loading" :disabled="busy" @click="refresh"><Icon name="refresh-cw" :size="14" />{{ t('刷新') }}</el-button>
    </header>
    <div class="generator-status" role="status">
      <Icon :name="snapshot?.supported && verified ? 'circle-check' : 'circle-alert'" :size="16" />
      <strong>{{ t(snapshot?.supported && verified ? '原生生成器可用' : loading ? '正在读取状态…' : '生成器状态待核对') }}</strong>
      <span v-if="snapshot">{{ snapshot.upstream?.name || 'Tectonic' }} {{ snapshot.upstream?.version }} / Paper {{ snapshot.gameVersion }}</span>
      <span v-if="snapshot?.busy" class="working">{{ t('正在生成世界') }}</span>
    </div>
    <p v-if="error" class="feedback failure" role="alert">{{ error }}</p>
    <p v-if="notice" class="feedback success" role="status">{{ notice }}</p>
    <p v-if="snapshot?.error" class="feedback failure" role="alert">{{ snapshot.error }}</p>
    <p v-if="uncertain" class="feedback warning" role="alert">{{ t('请求结果尚未核对，请刷新世界列表后再执行操作。') }}</p>
    <p v-if="snapshot && !snapshot.supported" class="feedback warning">{{ t('地形生成器不可用，请检查启动参数与启动日志。') }}</p>
    <div class="terrain-layout">
      <section class="creation-section" aria-labelledby="terrain-create">
        <h2 id="terrain-create">{{ t('创建地形世界') }}</h2>
        <p class="creation-scope">{{ t('Tectonic 参数只作用于新建专属地形世界的新区块，不会改变原主世界或已生成区块。') }}</p>
        <form novalidate @submit.prevent="submit('create')">
          <fieldset :disabled="blocked || !canManage">
            <div class="field"><label for="terrain-name">{{ t('世界名称') }}</label><el-input id="terrain-name" v-model="draft.name" maxlength="32" :disabled="blocked || !canManage" placeholder="alpine" /><small>{{ t('小写字母开头，最长 32 位；现有同名存档会被拒绝。') }}</small></div>
            <div class="field"><label for="terrain-seed">{{ t('世界种子') }}</label><el-input id="terrain-seed" v-model="draft.seed" maxlength="21" :disabled="blocked || !canManage" :placeholder="t('留空随机生成')" /><small>{{ t('支持完整 64 位整数，创建后固定。') }}</small></div>
            <div class="field"><label for="terrain-preset">{{ t('地形预设') }}</label><el-select id="terrain-preset" :model-value="preset" :disabled="blocked || !canManage" :placeholder="t('自定义参数')" @change="choosePreset"><el-option v-for="item in snapshot?.presets || []" :key="item.id" :value="item.id" :disabled="!rangeAvailable(item.config)" :label="rangeAvailable(item.config) ? t(item.name) : t('{name}（需重启）', { name: t(item.name) })" /></el-select></div>
            <div v-if="activePreset?.unsupported?.length" class="preset-adaptation" role="note">
              <strong>{{ t('官方预设适配差异') }}</strong>
              <p>{{ t('以下官方设置尚未移植，当前使用 Paper 的对应行为。') }}</p>
              <dl><div v-for="field in activePreset.unsupported" :key="field.key"><dt>{{ t(field.label) }}</dt><dd>{{ typeof field.value === 'boolean' ? t(field.value ? '开启' : '关闭') : field.value }}</dd></div></dl>
            </div>
            <div class="field"><label for="terrain-height">{{ t('高度范围') }}</label><el-select id="terrain-height" v-model="selectedHeightRange" :disabled="blocked || !canManage"><el-option v-if="!rangeAvailable(draft)" :value="rangeKey(draft)" :label="t('{height} 格（需重启）', { height: draft.height })" disabled /><el-option v-for="range in heightRanges" :key="`${range.minY}:${range.height}`" :value="`${range.minY}:${range.height}`" :label="`${range.minY} ~ ${range.maxY - 1} (${range.height})`" /></el-select><small>{{ t('可用 Y 范围：{min} 至 {max}', { min: configMinY(draft), max: configMinY(draft) + draft.height - 1 }) }}</small><small v-if="snapshot && !rangeAvailable(draft)" class="height-unavailable">{{ t('此高度尚未在当前运行的服务器注册，重启服务器后可用。') }}</small></div>
            <details v-if="canManage" class="advanced-group custom-height">
              <summary><span>{{ t('自定义高度范围') }}</span><Icon name="chevron-down" :size="15" /></summary>
              <div class="scale-fields">
                <div class="field"><label for="terrain-min-y">{{ t('最低高度') }}</label><el-input-number id="terrain-min-y" v-model="customHeight.minY" :min="-2032" :max="0" :step="16" step-strictly controls-position="right" :disabled="blocked" /></div>
                <div class="field"><label for="terrain-max-y">{{ t('高度上界') }}</label><el-input-number id="terrain-max-y" v-model="customHeight.maxY" :min="256" :max="2032" :step="16" step-strictly controls-position="right" :disabled="blocked" /></div>
              </div>
              <el-button :loading="pending === 'height-range'" :disabled="blocked || !validTerrainHeight(customHeight.maxY - customHeight.minY, customHeight.minY)" @click="saveHeightRange"><Icon name="save" :size="14" />{{ t('保存高度范围') }}</el-button>
              <p class="muted">{{ t('新增高度范围在完整重启后可选。高度上界不含在可用方块范围内。') }}</p>
            </details>
            <!-- InputNumber initializes aria-disabled once, so remount when editability changes. -->
            <div class="scale-fields">
              <div class="field"><label for="terrain-vertical">{{ t('山体高度倍率') }}</label><el-input-number :key="blocked || !canManage ? 'disabled' : 'enabled'" id="terrain-vertical" v-model="draft.verticalScale" :min=".1" :max="15" :step=".125" controls-position="right" :disabled="blocked || !canManage" /></div>
              <div class="field"><label for="terrain-horizontal">{{ t('地形水平倍率') }}</label><el-input-number :key="blocked || !canManage ? 'disabled' : 'enabled'" id="terrain-horizontal" v-model="draft.horizontalScale" :min=".5" :max="3" :step=".1" controls-position="right" :disabled="blocked || !canManage" /></div>
            </div>
            <div class="terrain-toggles"><label v-for="item in toggles" :key="item.key" class="toggle-row"><span>{{ t(item.label) }}</span><el-switch v-model="draft[item.key]" :aria-label="t(item.label)" :disabled="blocked || !canManage" /></label></div>
            <div v-if="advancedGroups.length" class="advanced-options">
              <details v-for="group in advancedGroups" :key="group.name" class="advanced-group">
                <summary><span>{{ t(group.name) }}</span><Icon name="chevron-down" :size="15" /></summary>
                <div class="advanced-fields">
                  <div v-for="field in group.fields" :key="field.key" class="advanced-field" :class="{ boolean: field.type === 'boolean' }">
                    <label :for="`terrain-advanced-${field.key}`">{{ t(field.label) }}</label>
                    <el-switch v-if="field.type === 'boolean'" :id="`terrain-advanced-${field.key}`" v-model="draft.advanced[field.key]" :aria-label="t(field.label)" :disabled="blocked || !canManage" />
                    <el-input-number v-else :key="blocked || !canManage ? 'disabled' : 'enabled'" :id="`terrain-advanced-${field.key}`" v-model="draft.advanced[field.key]" :min="field.min" :max="field.max" :step="field.step" controls-position="right" :disabled="blocked || !canManage" />
                  </div>
                  <el-button class="restore-advanced" text :disabled="blocked || !canManage" @click="restoreAdvanced(group)"><Icon name="undo-2" :size="13" />{{ t('恢复本组默认值') }}</el-button>
                </div>
              </details>
            </div>
          </fieldset>
          <p v-if="!canManage" class="muted">{{ t('当前账号仅可查看地形世界。') }}</p>
          <div class="create-actions"><el-button type="primary" native-type="submit" :loading="pending === 'create'" :disabled="blocked || !canManage || !!validation"><Icon name="plus" :size="15" />{{ t('创建世界') }}</el-button><span>{{ t('生成参数在创建后固定。') }}</span></div>
          <p v-if="draft.name && validation" class="validation">{{ t(validation) }}</p>
        </form>
      </section>
      <section class="worlds-section" aria-labelledby="terrain-worlds">
        <header class="section-heading"><h2 id="terrain-worlds">{{ t('地形世界') }}</h2><span>{{ t('{count} 个世界', { count: worlds.length }) }}</span></header>
        <div class="world-table-scroll">
          <table class="world-table">
            <thead><tr><th>{{ t('世界名称') }}</th><th>{{ t('生成状态') }}</th><th>{{ t('可用高度') }}</th><th>{{ t('在线玩家') }}</th></tr></thead>
            <tbody><tr v-for="world in worlds" :key="world.name" :class="{ selected: selectedName === world.name }"><td><button type="button" class="world-select" :aria-pressed="selectedName === world.name" @click="selectedName = world.name">{{ world.name }}</button></td><td><span class="state" :class="world.status">{{ t(worldStates[world.status]) }}</span></td><td>{{ world.minY }} ~ {{ world.maxY - 1 }}</td><td>{{ world.players }}</td></tr></tbody>
          </table>
        </div>
        <p v-if="!worlds.length" class="empty" role="status">{{ t(loading ? '正在读取世界列表…' : snapshot ? '尚未创建地形世界。' : '世界状态尚未读取。') }}</p>
        <template v-if="selected">
          <div class="selected-heading"><h3>{{ selected.name }}</h3><span>{{ t(selected.loaded ? '已加载' : '未加载') }}</span></div>
          <dl class="world-details"><div><dt>{{ t('世界种子') }}</dt><dd><code>{{ selected.seed }}</code></dd></div><div><dt>{{ t('世界总高度') }}</dt><dd>{{ selected.config.height }}</dd></div><div><dt>{{ t('山体高度倍率') }}</dt><dd>{{ selected.config.verticalScale }}</dd></div><div><dt>{{ t('地形水平倍率') }}</dt><dd>{{ selected.config.horizontalScale }}</dd></div><div><dt>{{ t('服务器启动时自动加载') }}</dt><dd>{{ t(selected.autoLoad ? '开启' : '关闭') }}</dd></div><div v-for="item in toggles" :key="item.key"><dt>{{ t(item.label) }}</dt><dd>{{ t(selected.config[item.key] ? '开启' : '关闭') }}</dd></div></dl>
          <details v-if="advancedGroups.length" class="saved-advanced"><summary>{{ t('完整生成参数') }}</summary><section v-for="group in advancedGroups" :key="group.name"><h4>{{ t(group.name) }}</h4><dl class="world-details"><div v-for="field in group.fields" :key="field.key"><dt>{{ t(field.label) }}</dt><dd>{{ field.type === 'boolean' ? t(selected.config.advanced[field.key] ? '开启' : '关闭') : selected.config.advanced[field.key] }}</dd></div></dl></section></details>
          <p v-if="selected.error" class="feedback failure" role="alert">{{ selected.error }}</p>
          <div class="world-actions"><el-button :loading="pending === (selected.loaded ? 'unload' : 'load')" :disabled="!canManage || blocked || ['queued', 'running'].includes(selected.status) || selected.loaded && selected.players > 0 || !selected.loaded && !rangeAvailable(selected.config)" @click="submit(selected.loaded ? 'unload' : 'load', selected)"><Icon :name="selected.loaded ? 'folder-down' : 'folder-up'" :size="14" />{{ t(selected.loaded ? '保存并卸载' : '加载') }}</el-button><span v-if="selected.loaded && selected.players">{{ t('有在线玩家时无法卸载。') }}</span><span v-else-if="!selected.loaded && !rangeAvailable(selected.config)">{{ t('此高度尚未在当前运行的服务器注册，重启服务器后可用。') }}</span></div>
          <div class="spawn-section">
            <div class="spawn-summary"><strong>{{ t('安全出生点') }}</strong><code v-if="selected.spawn">{{ selected.spawn.x }}, {{ selected.spawn.y }}, {{ selected.spawn.z }}</code><span v-else>{{ t('尚未读取出生点') }}</span></div>
            <form v-if="canManage" novalidate @submit.prevent="submit('spawn', selected)">
              <div class="spawn-coordinates">
                <label v-for="axis in ['x', 'y', 'z']" :key="axis" :for="`terrain-spawn-${axis}`"><span>{{ axis.toUpperCase() }}</span><input :id="`terrain-spawn-${axis}`" v-model.number="spawnDraft[axis]" type="number" step="1" :min="axis === 'y' ? selected.minY + 1 : -29999984" :max="axis === 'y' ? selected.maxY - 2 : 29999984" :aria-label="t('出生点 {axis}', { axis: axis.toUpperCase() })" :disabled="blocked || !selected.loaded || selected.status !== 'ready'" /></label>
              </div>
              <p class="spawn-hint">{{ t('脚下须为安全地板，上方须有两格净空。Y 范围：{min} 至 {max}。', { min: selected.minY + 1, max: selected.maxY - 2 }) }}</p>
              <p v-if="spawnDirty && spawnValidation" class="validation">{{ t(spawnValidation) }}</p>
              <el-button native-type="submit" :loading="pending === 'spawn'" :disabled="blocked || !selected.loaded || selected.status !== 'ready' || !spawnDirty || !!spawnValidation"><Icon name="map-pin" :size="14" />{{ t('设置出生点') }}</el-button>
            </form>
          </div>
          <form v-if="canTeleport" class="teleport-form" @submit.prevent="submit('teleport', selected)"><label for="terrain-player">{{ t('传送在线玩家') }}</label><div><el-input id="terrain-player" v-model="player" maxlength="16" :disabled="blocked || !selected.loaded || selected.status !== 'ready'" :placeholder="t('在线玩家精确名称')" /><el-button native-type="submit" type="primary" :loading="pending === 'teleport'" :disabled="blocked || !selected.loaded || selected.status !== 'ready' || !/^[A-Za-z0-9_]{1,16}$/.test(player.trim())"><Icon name="send" :size="14" />{{ t('传送') }}</el-button></div></form>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.terrain-workspace { min-width:0; max-width:1380px; letter-spacing:0; }
.terrain-workspace :deep(.el-button > span) { display:inline-flex; align-items:center; gap:5px; }
.terrain-heading,.section-heading,.selected-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.terrain-heading { margin-bottom:18px; flex-wrap:wrap; }
h1 { display:flex; align-items:center; gap:9px; margin:0; font-size:22px; line-height:1.4; letter-spacing:0; }
h1 :deep(svg) { color:var(--el-color-primary); }
h2 { margin:0 0 18px; font-size:15px; letter-spacing:0; }
h3 { font-size:15px; margin:0; overflow-wrap:anywhere; }
.generator-status { display:flex; gap:9px 14px; align-items:center; flex-wrap:wrap; padding:11px 0; margin-bottom:18px; border-top:1px solid var(--el-border-color); border-bottom:1px solid var(--el-border-color); font-size:12px; }
.generator-status > svg { color:var(--el-color-primary); }
.generator-status > span,.section-heading > span,.selected-heading > span { color:var(--lc-text-soft); font-size:12px; }
.generator-status .working { color:var(--el-color-warning); margin-left:auto; }
.feedback { margin:0 0 14px; padding:10px 12px; border:1px solid var(--el-border-color-light); border-radius:5px; font-size:12px; overflow-wrap:anywhere; }
.failure,.validation { color:var(--el-color-danger); }
.success { color:var(--el-color-success); }
.warning { color:var(--el-color-warning); }
.terrain-layout { display:grid; grid-template-columns:minmax(300px,.85fr) minmax(0,1.25fr); gap:32px; }
.custom-height { margin-bottom:18px; }
.custom-height > .scale-fields { margin-top:12px; }
.creation-section,.worlds-section { min-width:0; }
.creation-section { padding-right:30px; border-right:1px solid var(--el-border-color-light); }
.creation-scope { margin:4px 0 18px; color:var(--lc-text-soft); font-size:11px; line-height:1.65; }
fieldset { padding:0; margin:0; border:0; min-width:0; }
.field { margin-bottom:17px; min-width:0; }
.field > label,.teleport-form > label { display:block; margin-bottom:6px; font-size:12px; font-weight:600; }
.field > small { display:block; margin-top:6px; font-size:11px; line-height:1.65; color:var(--lc-text-soft); }
.field > small.height-unavailable { color:var(--el-color-warning); }
.preset-adaptation { margin:0 0 17px; padding-left:11px; border-left:2px solid var(--el-color-warning); font-size:11px; line-height:1.65; }
.preset-adaptation > strong { font-size:12px; }
.preset-adaptation > p { margin:4px 0 7px; color:var(--lc-text-soft); }
.preset-adaptation > dl { margin:0; }
.preset-adaptation > dl > div { display:flex; flex-wrap:wrap; gap:6px 12px; }
.preset-adaptation dt,.preset-adaptation dd { margin:0; font-size:11px; }
.field :deep(.el-select),.field :deep(.el-input-number) { width:100%; }
.scale-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
.toggle-row { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:9px 0; border-top:1px solid var(--el-border-color-lighter); font-size:12px; }
.toggle-row :deep(.el-switch) { flex-shrink:0; }
.advanced-options { margin-top:14px; border-top:1px solid var(--el-border-color-light); }
.advanced-group { border-bottom:1px solid var(--el-border-color-light); }
.advanced-group > summary { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px 0; cursor:pointer; font-size:12px; font-weight:600; list-style:none; }
.advanced-group > summary::-webkit-details-marker { display:none; }
.advanced-group[open] > summary > svg { transform:rotate(180deg); }
.advanced-fields { padding:0 0 8px; }
.advanced-field { display:grid; grid-template-columns:minmax(0,1fr) 138px; align-items:center; gap:12px; margin-bottom:12px; }
.advanced-field > label { font-size:12px; overflow-wrap:anywhere; }
.advanced-field :deep(.el-input-number) { width:138px; }
.advanced-field.boolean :deep(.el-switch) { justify-self:end; }
.restore-advanced { margin:0; font-size:11px; }
.saved-advanced { border-top:1px solid var(--el-border-color-light); padding:12px 0; }
.saved-advanced > summary { cursor:pointer; font-size:12px; font-weight:600; }
.saved-advanced h4 { font-size:12px; font-weight:600; margin:16px 0 0; }
.saved-advanced .world-details > div:first-child { grid-column:auto; }
.create-actions { display:flex; gap:10px; align-items:center; flex-wrap:wrap; padding-top:18px; }
.create-actions > span,.world-actions > span,.validation,.muted { font-size:11px; }
.create-actions > span,.world-actions > span { color:var(--lc-text-soft); }
.section-heading { margin-bottom:12px; }.section-heading h2 { margin:0; }
.world-table-scroll { overflow-x:auto; max-width:100%; }
.world-table { width:100%; border-collapse:collapse; text-align:left; font-size:12px; }
.world-table th { color:var(--lc-text-soft); font-weight:500; white-space:nowrap; }
.world-table td,.world-table th { padding:11px 10px; border-bottom:1px solid var(--el-border-color-light); }
.world-table tr.selected { background:var(--el-color-primary-light-9); }
.world-table td { font-variant-numeric:tabular-nums; white-space:nowrap; }
.world-select { padding:3px 0; border:0; color:var(--el-color-primary); background:none; cursor:pointer; font-size:12px; max-width:180px; overflow-wrap:anywhere; white-space:normal; text-align:left; }
.state.ready { color:var(--el-color-success); }.state.failed { color:var(--el-color-danger); }.state.running,.state.queued { color:var(--el-color-warning); }
.empty { padding:30px 0; text-align:center; font-size:12px; color:var(--lc-text-soft); border-bottom:1px solid var(--el-border-color-light); }
.selected-heading { padding-top:22px; margin-top:7px; }
.world-details { margin:16px 0; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px 20px; }
.world-details > div { min-width:0; }.world-details > div:first-child { grid-column:1/-1; }
dt { color:var(--lc-text-soft); font-size:11px; }dd { margin:4px 0 0; font-size:12px; overflow-wrap:anywhere; }dd code { font:inherit; font-variant-numeric:tabular-nums; }
.world-actions { display:flex; align-items:center; gap:10px; padding:15px 0; border-top:1px solid var(--el-border-color-light); }
.spawn-section { border-top:1px solid var(--el-border-color-light); padding:16px 0; }
.spawn-summary { display:flex; align-items:center; flex-wrap:wrap; gap:6px 14px; margin-bottom:12px; font-size:12px; }
.spawn-summary code { font:inherit; font-variant-numeric:tabular-nums; overflow-wrap:anywhere; }
.spawn-summary > span,.spawn-hint { color:var(--lc-text-soft); font-size:11px; }
.spawn-coordinates { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; max-width:480px; }
.spawn-coordinates label { display:block; min-width:0; font-size:11px; }
.spawn-coordinates label > span { display:block; margin-bottom:5px; }
.spawn-coordinates input { box-sizing:border-box; width:100%; min-width:0; height:32px; padding:6px 8px; border:1px solid var(--el-border-color); border-radius:5px; background:var(--lc-surface); color:var(--lc-text); font:inherit; font-size:12px; }
.spawn-coordinates input:disabled { opacity:.6; cursor:not-allowed; }
.spawn-coordinates input:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
.spawn-hint { margin:8px 0 12px; line-height:1.65; }
.teleport-form { border-top:1px solid var(--el-border-color-light); padding-top:16px; }
.teleport-form > div { display:flex; gap:8px; min-width:0; }.teleport-form :deep(.el-input) { flex:1; min-width:0; }.teleport-form :deep(.el-button) { margin:0; flex-shrink:0; }
@media(max-width:1050px) { .terrain-layout { grid-template-columns:minmax(260px,.8fr) minmax(0,1fr); gap:22px; }.creation-section { padding-right:20px; }.scale-fields { grid-template-columns:1fr; gap:0; } }
@media(max-width:760px) { .terrain-layout { grid-template-columns:minmax(0,1fr); gap:28px; }.creation-section { padding:0 0 24px; border-right:0; border-bottom:1px solid var(--el-border-color-light); }.scale-fields { grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }.world-table { table-layout:fixed; }.world-table td,.world-table th { padding:10px 6px; white-space:normal; overflow-wrap:anywhere; }.world-select { max-width:100%; }.generator-status .working { margin-left:0; } }
button:focus-visible { outline:2px solid var(--el-color-primary); outline-offset:2px; }
</style>
