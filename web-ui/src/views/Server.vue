<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import PlayerCell from '../components/PlayerCell.vue'
import MinecraftTextPreview from '../components/text-preview/MinecraftTextPreview.vue'
import { requireSuccess } from '../utils/panel'
import { t } from '../i18n'

const tab = ref('broadcast')
const broadcastText = ref('')
const sending = ref(false)
const saving = ref(false)
const whitelistBusy = ref(false)
const whitelistError = ref('')
const whitelistQuery = ref('')
const wl = reactive({ enabled: false, enforced: false, items: [], loading: false, name: '' })
const shown = computed(() => {
  const query = whitelistQuery.value.trim().toLowerCase()
  return query ? wl.items.filter(player => `${player.name} ${player.uuid}`.toLowerCase().includes(query)) : wl.items
})

async function broadcast() {
  if (sending.value) return
  const message = broadcastText.value.trim()
  if (!message) return ElMessage.warning(t('请输入广播内容'))
  sending.value = true
  try {
    const response = requireSuccess(await api.post('/api/server/broadcast', { message }))
    ElMessage.success(response.message || t('已发送'))
    if (broadcastText.value.trim() === message) broadcastText.value = ''
  } catch (error) { ElMessage.error(error.message) }
  finally { sending.value = false }
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const response = requireSuccess(await api.post('/api/server/save'))
    ElMessage.success(response.message || t('已保存'))
  } catch (error) { ElMessage.error(error.message) }
  finally { saving.value = false }
}

async function loadWl() {
  if (wl.loading) return
  wl.loading = true
  try {
    const response = requireSuccess(await api.get('/api/server/whitelist'))
    if (!Array.isArray(response?.items)) throw new Error(t('白名单返回格式错误'))
    Object.assign(wl, { enabled: response.enabled, enforced: response.enforced, items: response.items })
    whitelistError.value = ''
  } catch (error) { whitelistError.value = error.message }
  finally { wl.loading = false }
}

async function changeWhitelist(operation) {
  if (whitelistBusy.value || wl.loading) return
  whitelistBusy.value = true
  try {
    await operation()
    await loadWl()
  } catch (error) { ElMessage.error(error.message) }
  finally { whitelistBusy.value = false }
}

function toggleWl(value) {
  return changeWhitelist(async () => {
    if (value && wl.enforced) {
      const accepted = await ElMessageBox.confirm(t('强制白名单会移出不在名单内的在线玩家，确定开启吗？'), t('开启白名单'), { type: 'warning' }).then(() => true).catch(() => false)
      if (!accepted) return
    }
    const response = requireSuccess(await api.post('/api/server/whitelist', { enabled: value }))
    ElMessage.success(response.message || t('已保存'))
  })
}

function addWl() {
  const name = wl.name.trim()
  if (!name) return
  return changeWhitelist(async () => {
    const response = requireSuccess(await api.post('/api/server/whitelist/add', { name }))
    ElMessage.success(response.message || t('已添加'))
    if (wl.name.trim() === name) wl.name = ''
  })
}

function removeWl(player) {
  return changeWhitelist(async () => {
    const accepted = await ElMessageBox.confirm(t('将 {name} 移出白名单？', { name: player.name }), t('白名单'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!accepted) return
    const response = requireSuccess(await api.post('/api/server/whitelist/remove', { name: player.uuid }))
    ElMessage.success(response.message || t('已移除'))
  })
}

onMounted(loadWl)
</script>

<template>
  <div class="page server-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('服务器操作') }}</h1>
      <el-button :loading="saving" @click="save"><Icon name="save" :size="15" />{{ t('保存所有世界') }}</el-button>
    </header>
    <el-tabs v-model="tab">
      <el-tab-pane :label="t('全服广播')" name="broadcast" lazy :class="{ 'lc-pane-in': tab === 'broadcast' }">
        <section class="broadcast-layout">
          <el-form label-position="top" @submit.prevent="broadcast">
            <el-form-item :label="t('广播内容')">
              <el-input v-model="broadcastText" type="textarea" :rows="5" :disabled="sending" />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="sending" :disabled="!broadcastText.trim()"><Icon name="send" :size="15" />{{ t('发送广播') }}</el-button>
          </el-form>
          <MinecraftTextPreview :text="broadcastText" :label="t('广播预览')" :show-help="false" />
        </section>
      </el-tab-pane>
      <el-tab-pane :label="t('白名单')" name="whitelist" lazy :class="{ 'lc-pane-in': tab === 'whitelist' }">
        <section class="whitelist-section">
          <header class="section-heading">
            <div class="whitelist-state">
              <el-switch :model-value="wl.enabled" :disabled="whitelistBusy || wl.loading" :aria-label="t('白名单')" @change="toggleWl" />
              <span>{{ t(wl.enabled ? '已开启' : '已关闭') }}</span>
              <el-tag v-if="wl.enforced" type="warning" size="small">{{ t('强制模式') }}</el-tag>
            </div>
          </header>
          <el-alert v-if="whitelistError" :title="whitelistError" type="error" show-icon :closable="false" />
          <div class="toolbar whitelist-toolbar">
            <el-input v-model="whitelistQuery" clearable :placeholder="t('搜索玩家名')" :aria-label="t('搜索玩家名')"><template #prefix><Icon name="search" :size="14" /></template></el-input>
            <el-tooltip :content="t('刷新')"><el-button class="refresh-button" :aria-label="t('刷新')" :loading="wl.loading" :disabled="whitelistBusy" @click="loadWl"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
            <span class="spacer" aria-hidden="true" />
            <el-input v-model="wl.name" :disabled="whitelistBusy" :placeholder="t('玩家名或 UUID')" :aria-label="t('玩家名或 UUID')" @keyup.enter="addWl" />
            <el-button type="primary" :loading="whitelistBusy" :disabled="!wl.name.trim() || wl.loading" @click="addWl"><Icon name="plus" :size="15" />{{ t('添加') }}</el-button>
          </div>
          <el-table :data="shown" v-loading="wl.loading" :empty-text="t('没有找到玩家')">
            <el-table-column :label="t('玩家')" min-width="180"><template #default="{ row }"><PlayerCell :name="row.name" :uuid="row.uuid" /> <el-tag v-if="row.online" size="small" type="success">{{ t('在线') }}</el-tag></template></el-table-column>
            <el-table-column label="UUID" min-width="290"><template #default="{ row }"><span class="mono muted">{{ row.uuid }}</span></template></el-table-column>
            <el-table-column :label="t('操作')" width="90"><template #default="{ row }"><el-button size="small" type="danger" text :disabled="whitelistBusy" @click="removeWl(row)">{{ t('移除') }}</el-button></template></el-table-column>
          </el-table>
          <div class="list-count">{{ t('共 {count} 人', { count: wl.items.length }) }}</div>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.server-page { min-width: 0; }
.page-header .el-button, .toolbar .el-button, .broadcast-layout .el-button { gap: 7px; }
.broadcast-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(240px, .8fr); align-items: start; gap: 24px; max-width: 960px; padding-top: 4px; }
.broadcast-layout > * { min-width: 0; }
.whitelist-section { padding-top: 8px; min-width: 0; }
.section-heading { margin: 0 0 12px; font-size: 13px; }
.whitelist-state { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.whitelist-toolbar { margin: 12px 0; }
.whitelist-toolbar .el-input { width: 220px; min-width: 0; }
.whitelist-toolbar .el-button { margin-left: 0; }
.refresh-button { width: 34px; padding: 0; }
.list-count { margin-top: 10px; color: var(--lc-text-soft); font-size: 12px; }
.el-alert { margin-bottom: 14px; }
@media (max-width: 850px) { .broadcast-layout { grid-template-columns: 1fr; gap: 24px; }.whitelist-toolbar .spacer { flex-basis: 100%; }.whitelist-toolbar .el-input { flex: 1; width: auto; }.whitelist-toolbar { gap: 8px; } }
@media (max-width: 480px) { .whitelist-state { gap: 8px; } }
</style>
