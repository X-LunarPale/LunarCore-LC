<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, url } from '../../api'
import { useApp } from '../../stores/app'
import { t } from '../../i18n'

const app = useApp()
const emit = defineEmits(['dirty-change', 'busy-change'])
const name = ref('')
const savedName = ref('')
const pendingLogo = ref('')
const pendingFile = ref(null)
const dimensions = ref(null)
const input = ref(null)
const loading = ref(false)
const nameBusy = ref(false)
const logoBusy = ref(false)
const error = ref('')
let selection = 0

const busy = computed(() => loading.value || nameBusy.value || logoBusy.value)
const currentLogo = computed(() => {
  const path = app.info?.logoUrl
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') ? url(path) : url('/favicon.svg')
})
const hasCustomLogo = computed(() => !!app.info?.logoUrl && app.info.logoUrl !== '/favicon.svg')
const previewLogo = computed(() => pendingLogo.value || currentLogo.value)
const previewName = computed(() => name.value.trim() || savedName.value || 'LunarCore')
const nameChanged = computed(() => name.value.trim() !== savedName.value)
const nameLength = computed(() => Array.from(name.value.trim()).length)
const validName = computed(() => nameLength.value >= 2 && nameLength.value <= 48 && !/[\u0000-\u001f\u007f-\u009f<>]/.test(name.value))
watch(() => nameChanged.value || !!pendingLogo.value, (value) => emit('dirty-change', value), { immediate: true })
watch(() => nameBusy.value || logoBusy.value, (value) => emit('busy-change', value), { immediate: true })

async function refresh(resetDraft = true) {
  loading.value = true
  error.value = ''
  try {
    const info = await app.loadInfo()
    if (!info || typeof info.brand !== 'string') throw new Error(t('品牌信息加载失败，请重试。'))
    savedName.value = info.brand
    if (resetDraft) name.value = savedName.value
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function reload() {
  if (nameChanged.value || pendingLogo.value) {
    const confirmed = await ElMessageBox.confirm(t('重新加载会丢弃尚未保存的品牌修改，继续吗？'), t('重新加载'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!confirmed) return
  }
  selection++
  pendingLogo.value = ''
  pendingFile.value = null
  dimensions.value = null
  await refresh()
}

async function updateInfo() {
  const info = await app.loadInfo()
  if (!info) {
    ElMessage.warning(t('操作已完成，但品牌预览刷新失败，请刷新页面核对。'))
    return false
  }
  savedName.value = info.brand
  return true
}

async function saveName() {
  if (busy.value || !nameChanged.value) return
  if (!validName.value) return ElMessage.warning(t('品牌名称须为 2–48 个字符。'))
  nameBusy.value = true
  error.value = ''
  try {
    await api.put('/api/branding', { name: name.value.trim() })
    if (await updateInfo()) {
      name.value = savedName.value
      ElMessage.success(t('品牌名称已保存'))
    }
  } catch (e) {
    error.value = e.message
  } finally {
    nameBusy.value = false
  }
}

async function restoreName() {
  if (busy.value) return
  const confirmed = await ElMessageBox.confirm(t('恢复默认名称？当前自定义名称将被替换。'), t('恢复默认名称'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  nameBusy.value = true
  error.value = ''
  try {
    await api.del('/api/branding')
    if (await updateInfo()) {
      name.value = savedName.value
      ElMessage.success(t('已恢复默认名称'))
    }
  } catch (e) {
    error.value = e.message
  } finally {
    nameBusy.value = false
  }
}

function readDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error(t('无法读取图片，请重新选择。')))
    reader.readAsDataURL(file)
  })
}

function imageSize(src) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight })
    image.onerror = () => reject(new Error(t('图片无法解码，请选择有效的 PNG 或 JPEG 文件。')))
    image.src = src
  })
}

async function selectLogo(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const current = ++selection
  pendingLogo.value = ''
  pendingFile.value = null
  dimensions.value = null
  error.value = ''
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    error.value = t('仅支持 PNG 或 JPEG 图片。')
    return
  }
  if (file.size > 256 * 1024) {
    error.value = t('图片不得超过 256 KiB。')
    return
  }
  try {
    const dataUrl = await readDataUrl(file)
    const size = await imageSize(dataUrl)
    if (current !== selection) return
    if (!size.width || !size.height || size.width > 1024 || size.height > 1024) throw new Error(t('图片宽高均须在 1–1024 像素之间。'))
    pendingLogo.value = dataUrl
    pendingFile.value = file
    dimensions.value = size
  } catch (e) {
    if (current === selection) error.value = e.message
  }
}

async function uploadLogo() {
  if (busy.value || !pendingLogo.value) return
  logoBusy.value = true
  error.value = ''
  try {
    await api.post('/api/branding/logo', { dataUrl: pendingLogo.value })
    pendingLogo.value = ''
    pendingFile.value = null
    dimensions.value = null
    if (await updateInfo()) ElMessage.success(t('Logo 已更新'))
  } catch (e) {
    error.value = e.message
  } finally {
    logoBusy.value = false
  }
}

async function restoreLogo() {
  if (busy.value || !hasCustomLogo.value) return
  const confirmed = await ElMessageBox.confirm(t('恢复默认 Logo？当前上传的图片将被移除。'), t('恢复默认 Logo'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  logoBusy.value = true
  error.value = ''
  try {
    await api.del('/api/branding/logo')
    pendingLogo.value = ''
    pendingFile.value = null
    dimensions.value = null
    if (await updateInfo()) ElMessage.success(t('已恢复默认 Logo'))
  } catch (e) {
    error.value = e.message
  } finally {
    logoBusy.value = false
  }
}

onMounted(refresh)
onBeforeUnmount(() => { selection++ })
</script>

<template>
  <section class="branding-settings" v-loading="loading" :aria-busy="busy">
    <div class="section-head">
      <div><h3>{{ t('面板品牌') }}</h3><p>{{ t('名称与 Logo 将显示在登录页和侧边栏。') }}</p></div>
      <el-button :disabled="busy" @click="reload"><Icon name="rotate-ccw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="t('品牌设置未完成')" :description="error" show-icon :closable="false" class="load-error" />
    <div class="brand-preview" :aria-label="t('品牌预览')">
      <span class="brand-mark"><img :src="previewLogo" :alt="t('品牌 Logo 预览')" /></span>
      <div><strong>{{ previewName }}</strong><small>{{ t('管理控制台') }}</small></div>
      <el-tag v-if="pendingLogo || nameChanged" size="small" type="warning" effect="plain">{{ t('预览中，尚未保存') }}</el-tag>
    </div>
    <div class="setting-block">
      <label for="brand-name">{{ t('品牌名称') }}</label>
      <p>{{ t('长度为 2–48 个字符；不会修改服务器或插件的内部名称。') }}</p>
      <div class="field-row">
        <el-input id="brand-name" v-model="name" :disabled="busy || !app.info" :placeholder="t('输入品牌名称')" @keyup.enter="saveName" />
        <el-button type="primary" :loading="nameBusy" :disabled="busy || !nameChanged || !validName" @click="saveName">{{ t('保存名称') }}</el-button>
        <el-button :disabled="busy || !app.info" @click="restoreName">{{ t('恢复默认名称') }}</el-button>
      </div>
      <p :class="{ 'field-error': nameChanged && !validName }">{{ nameLength }}/48<span v-if="nameChanged && !validName"> · {{ t('品牌名称须为 2–48 个字符，且不能包含尖括号或控制字符。') }}</span></p>
    </div>
    <div class="setting-block logo-block">
      <label>{{ t('品牌 Logo') }}</label>
      <p>{{ t('PNG 或 JPEG，最大 256 KiB，宽高均不超过 1024 像素。') }}</p>
      <input ref="input" type="file" accept="image/png,image/jpeg" class="file-input" :aria-label="t('选择品牌 Logo')" @change="selectLogo" />
      <div class="field-row">
        <el-button :disabled="busy || !app.info" @click="input?.click()"><Icon name="image-up" :size="14" />&nbsp;{{ t('选择图片') }}</el-button>
        <span v-if="pendingFile" class="file-meta">{{ pendingFile.name }} · {{ dimensions?.width }} × {{ dimensions?.height }}</span>
        <el-button type="primary" :loading="logoBusy" :disabled="busy || !pendingLogo" @click="uploadLogo">{{ t('上传 Logo') }}</el-button>
        <el-button :disabled="busy || !hasCustomLogo" @click="restoreLogo">{{ t('恢复默认 Logo') }}</el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.branding-settings { padding: 20px 0; color: var(--lc-text); }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.section-head h3 { margin: 0; font-size: 15px; font-weight: 650; }
.section-head p, .setting-block p { margin: 5px 0 0; color: var(--lc-text-soft); font-size: 12px; line-height: 1.7; }
.brand-preview { display: flex; align-items: center; gap: 14px; min-height: 86px; padding: 14px 18px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-extra-light); }
.brand-mark { width: 56px; height: 56px; flex: 0 0 56px; display: grid; place-items: center; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--lc-surface); }
.brand-mark img { max-width: 46px; max-height: 46px; object-fit: contain; }
.brand-preview > div { display: grid; gap: 3px; min-width: 0; }
.brand-preview strong { font-size: 17px; font-weight: 650; overflow-wrap: anywhere; }
.brand-preview small { font-size: 11px; color: var(--lc-text-soft); }
.brand-preview .el-tag { margin-left: auto; flex-shrink: 0; }
.setting-block { padding-top: 22px; }
.setting-block + .setting-block { border-top: 1px solid var(--el-border-color-lighter); margin-top: 22px; }
.setting-block > label { font-size: 13px; font-weight: 600; }
.field-row { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; margin-top: 12px; }
.field-row :deep(.el-input) { max-width: 360px; min-width: 0; }
.field-row .el-button { margin-left: 0; }
.file-input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.file-meta { min-width: 0; overflow-wrap: anywhere; color: var(--lc-text-soft); font-size: 12px; }
.field-error { color: var(--el-color-danger) !important; }
.load-error { margin-bottom: 16px; }
@media (max-width: 600px) {
  .branding-settings { padding: 14px 0; }
  .section-head { align-items: flex-start; }
  .brand-preview { flex-wrap: wrap; padding: 12px; }
  .brand-preview .el-tag { margin-left: 0; }
  .field-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .field-row :deep(.el-input), .file-meta { grid-column: 1 / -1; max-width: none; }
  .field-row .el-button { min-width: 0; width: 100%; min-height: 32px; height: auto; padding-inline: 6px; white-space: normal; line-height: 1.4; }
}
</style>
