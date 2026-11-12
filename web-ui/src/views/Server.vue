<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import PlayerCell from '../components/PlayerCell.vue'
import MinecraftTextPreview from '../components/text-preview/MinecraftTextPreview.vue'
import { t } from '../i18n'

const broadcastText = ref('')
const sending = ref(false)
const saving = ref(false)
const wl = reactive({ enabled: false, enforced: false, items: [], loading: false, name: '' })

async function broadcast() {
  if (!broadcastText.value.trim()) return ElMessage.warning(t('请输入广播内容'))
  sending.value = true
  try {
    const r = await api.post('/api/server/broadcast', { message: broadcastText.value })
    ElMessage.success(r.message)
    broadcastText.value = ''
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sending.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const r = await api.post('/api/server/save')
    ElMessage.success(r.message)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function loadWl() {
  wl.loading = true
  try {
    const r = await api.get('/api/server/whitelist')
    Object.assign(wl, { enabled: r.enabled, enforced: r.enforced, items: r.items })
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    wl.loading = false
  }
}

async function toggleWl(v) {
  try {
    const r = await api.post('/api/server/whitelist', { enabled: v })
    ElMessage.success(r.message)
  } catch (e) {
    ElMessage.error(e.message)
  }
  loadWl()
}

async function addWl() {
  if (!wl.name.trim()) return
  try {
    const r = await api.post('/api/server/whitelist/add', { name: wl.name.trim() })
    ElMessage.success(r.message)
    wl.name = ''
    loadWl()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeWl(p) {
  const ok = await ElMessageBox.confirm(t('将 {name} 移出白名单？', { name: p.name }), t('白名单'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  try {
    const r = await api.post('/api/server/whitelist/remove', { name: p.uuid })
    ElMessage.success(r.message)
    loadWl()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(loadWl)
</script>

<template>
  <div class="page server-page">
    <div class="page-header"><div><p class="eyebrow">{{ t('运行管理') }}</p><h1 class="page-title">{{ t('服务器操作') }}</h1><p class="page-sub">{{ t('发布全服通知、保存运行数据并管理白名单。') }}</p></div></div>
    <div class="grid">
      <div class="card">
        <div class="card-title"><Icon name="megaphone" :size="16" />{{ t('全服广播') }}</div>
        <p class="section-description">{{ t('向所有在线玩家发送通知，支持 MiniMessage 格式。') }}</p>
        <el-input v-model="broadcastText" type="textarea" :rows="3" :placeholder="t('请输入广播内容')" />
        <MinecraftTextPreview :text="broadcastText" :label="t('广播预览')" />
        <div class="actions"><el-button type="primary" :loading="sending" @click="broadcast"><Icon name="send" :size="14" />&nbsp;{{ t('发送广播') }}</el-button></div>
      </div>
      <div class="card">
        <div class="card-title"><Icon name="hard-drive" :size="16" />{{ t('保存数据') }}</div>
        <p class="muted">{{ t('立即保存所有世界与在线玩家数据（等同于 save-all）。') }}</p>
        <el-button type="primary" plain :loading="saving" @click="save"><Icon name="save" :size="14" />&nbsp;{{ t('保存所有世界') }}</el-button>
      </div>
    </div>
    <div class="card" style="margin-top: 16px" v-loading="wl.loading">
      <div class="card-title">
        <Icon name="list-checks" :size="16" />{{ t('白名单') }}
        <el-switch :model-value="wl.enabled" :active-text="t('已开启')" :inactive-text="t('已关闭')" inline-prompt style="margin-left: 12px" @change="toggleWl" />
        <span v-if="wl.enforced" class="muted" style="font-weight: 400; font-size: 12px">{{ t('强制模式：开启白名单时，不在名单内的在线玩家可能被移出服务器。') }}</span>
      </div>
      <div class="toolbar">
        <el-input v-model="wl.name" :placeholder="t('玩家名或 UUID')" style="width: 240px" @keyup.enter="addWl" />
        <el-button type="primary" @click="addWl"><Icon name="plus" :size="14" />&nbsp;{{ t('添加') }}</el-button>
        <div class="spacer" />
        <span class="muted">{{ t('共 {count} 人', { count: wl.items.length }) }}</span>
      </div>
      <el-table :data="wl.items" stripe max-height="420">
        <el-table-column :label="t('玩家')" min-width="200"><template #default="{ row }"><PlayerCell :name="row.name" :uuid="row.uuid" /> <el-tag v-if="row.online" size="small" type="success">{{ t('在线') }}</el-tag></template></el-table-column>
        <el-table-column label="UUID" min-width="290"><template #default="{ row }"><span class="mono muted">{{ row.uuid }}</span></template></el-table-column>
        <el-table-column :label="t('操作')" width="110"><template #default="{ row }"><el-button size="small" type="danger" plain @click="removeWl(row)">{{ t('移除') }}</el-button></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.server-page{color:var(--lc-text);min-width:0}.page-header{margin-bottom:20px}.page-title{font-size:25px;letter-spacing:-.5px}.eyebrow{margin:0 0 5px;color:var(--lc-text-soft);font-size:11px;letter-spacing:.08em}.page-sub{margin:6px 0 0}.grid{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:16px}.grid .card+.card{margin-top:0}.card{padding:18px;border-radius:8px;overflow:hidden}.card-title{font-size:14px;margin-bottom:14px;flex-wrap:wrap;gap:8px}.section-description,.grid .muted{font-size:12px;color:var(--lc-text-soft);line-height:1.7;margin:0 0 12px}.actions{margin-top:12px;text-align:right}.toolbar{gap:8px;margin-bottom:14px}.toolbar :deep(.el-input){max-width:100%;min-width:0}.server-page :deep(td.el-table__cell){padding:10px 0}@media(max-width:760px){.grid{grid-template-columns:1fr}.page-title{font-size:23px}.card{padding:14px}.toolbar :deep(.el-input){width:auto!important;flex:1}.toolbar .spacer{display:none}.toolbar>.muted{width:100%;font-size:12px}.card-title>.muted{width:100%;line-height:1.7}.card-title :deep(.el-switch){margin-left:auto!important}}
</style>
