<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { formatDateTime } from '../utils/format'
import PlayerCell from '../components/PlayerCell.vue'
import TotpSettings from '../components/TotpSettings.vue'
import { requireSuccess } from '../utils/panel'
import { t } from '../i18n'

const auth = useAuth()
const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const code = ref('')
const busy = ref(false)
const bindingBusy = ref(false)
const tab = ref('security')

async function changePassword() {
  if (busy.value) return
  if (!pw.oldPassword || !pw.newPassword) return ElMessage.warning(t('请填写完整'))
  if (pw.newPassword !== pw.confirm) return ElMessage.warning(t('两次输入的新密码不一致'))
  if (pw.newPassword.length < 8) return ElMessage.warning(t('请使用至少 8 位的新密码。'))
  busy.value = true
  try {
    const r = requireSuccess(await api.post('/api/auth/password', { oldPassword: pw.oldPassword, newPassword: pw.newPassword }))
    if (r.token) auth.setToken(r.token)
    ElMessage.success(r.message)
    Object.assign(pw, { oldPassword: '', newPassword: '', confirm: '' })
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

async function bind() {
  if (bindingBusy.value) return
  if (!/^[A-Za-z0-9]{6,10}$/.test(code.value.trim())) return ElMessage.warning(t('请输入游戏内获取的验证码'))
  bindingBusy.value = true
  try {
    const r = requireSuccess(await api.post('/api/auth/bind', { code: code.value.trim() }))
    ElMessage.success(r.message)
    code.value = ''
    await auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { bindingBusy.value = false }
}

async function unbind() {
  if (bindingBusy.value) return
  bindingBusy.value = true
  try {
    const ok = await ElMessageBox.confirm(t('解除后将无法使用游戏账号登录，确定吗？'), t('解除绑定'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
    const r = requireSuccess(await api.post('/api/auth/unbind'))
    ElMessage.success(r.message)
    await auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { bindingBusy.value = false }
}
</script>

<template>
  <div class="page profile-page">
    <div class="page-header"><h1 class="page-title">{{ t('个人设置') }}</h1></div>
      <section class="identity-panel">
        <div class="identity-head"><Icon name="user-round" :size="22" /><div><h2>{{ auth.user?.username }}</h2><span>{{ auth.user?.roleName }} <span class="muted">{{ auth.user?.role }}</span></span></div></div>
        <dl class="identity-details"><div><dt>{{ t('上次登录') }}</dt><dd>{{ formatDateTime(auth.user?.lastLogin) }}</dd></div><div><dt>{{ t('会话到期') }}</dt><dd>{{ formatDateTime(auth.user?.expiresAt) }}</dd></div></dl>
      </section>
      <el-tabs v-model="tab" class="workspace-tabs">
      <el-tab-pane :label="t('登录安全')" name="security">
      <div class="security-layout">
        <TotpSettings />
        <section class="setting-section">
          <header><Icon name="key-round" :size="18" /><h3>{{ t('修改密码') }}</h3></header>
          <el-form label-position="top" class="password-form" :disabled="busy" @submit.prevent="changePassword">
            <el-form-item :label="t('原密码')" class="full-field"><el-input v-model="pw.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
            <el-form-item :label="t('新密码')"><el-input v-model="pw.newPassword" type="password" show-password autocomplete="new-password" :placeholder="t('至少 8 位')" /></el-form-item>
            <el-form-item :label="t('确认新密码')"><el-input v-model="pw.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
            <div class="form-footer"><el-button type="primary" native-type="submit" :loading="busy">{{ t('更新密码') }}</el-button></div>
          </el-form>
        </section>
      </div>
      </el-tab-pane>
      <el-tab-pane :label="t('游戏账号绑定')" name="binding">
        <section class="setting-section">
          <div v-if="auth.user?.mcName" class="bound"><PlayerCell :name="auth.user.mcName" :uuid="auth.user.mcUuid" :size="32" /><el-button type="danger" plain size="small" :loading="bindingBusy" @click="unbind">{{ t('解除绑定') }}</el-button></div>
          <template v-else><p class="binding-help">{{ t('在游戏内执行') }} <code>/lc web bind</code> {{ t('获取验证码，输入后完成绑定。') }}</p><el-form class="bind-form" :disabled="bindingBusy" @submit.prevent="bind"><el-input v-model="code" maxlength="10" :placeholder="t('游戏内获取的验证码')" :aria-label="t('游戏内获取的验证码')" /><el-button type="primary" native-type="submit" :loading="bindingBusy" :disabled="!code.trim()">{{ t('绑定账号') }}</el-button></el-form></template>
        </section>
      </el-tab-pane>
      <el-tab-pane :label="t('已分配权限')" name="permissions"><div class="permission-section"><div class="permission-tags"><el-tag v-for="p in auth.user?.permissions || []" :key="p" size="small" effect="plain">{{ p }}</el-tag><span v-if="!auth.user?.permissions?.length" class="muted">{{ t('暂无已分配权限') }}</span></div></div></el-tab-pane>
      </el-tabs>
  </div>
</template>

<style scoped>
.profile-page { min-width: 0; }
.identity-panel { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 16px 30px; padding: 0 0 22px; margin-bottom: 14px; border-bottom: 1px solid var(--el-border-color); }
.identity-head { display: flex; align-items: center; gap: 12px; min-width: 0; }
.identity-head > .lc-icon { color: var(--lc-text-soft); flex-shrink: 0; }
.identity-head h2 { font-size: 16px; margin: 0 0 3px; overflow-wrap: anywhere; }
.identity-head span { font-size: 12px; color: var(--lc-text-soft); }
.identity-details { margin: 0; display: flex; flex-wrap: wrap; gap: 15px 32px; }
.identity-details dt { color: var(--lc-text-soft); font-size: 11px; margin-bottom: 4px; }
.identity-details dd { margin: 0; font-size: 12px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.permission-section { padding-top: 12px; }
.permission-tags { display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; }.permission-tags :deep(.el-tag) { max-width: 100%; height: auto; min-height: 24px; white-space: normal; overflow-wrap: anywhere; }
.security-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; gap: 32px; padding-top: 8px; max-width: 1200px; }
.security-layout :deep(.totp-setting) { padding: 0; border: 0; min-width: 0; }
.security-layout :deep(.totp-setting h3) { font-size: 14px; }
.setting-section { min-width: 0; padding-top: 8px; max-width: 660px; }.security-layout .setting-section { padding: 0 0 0 32px; border-left: 1px solid var(--el-border-color-light); }
.setting-section header { display: flex; align-items: center; gap: 10px; margin-bottom: 22px; }.setting-section header > .lc-icon { color: var(--lc-text-soft); }.setting-section h3 { font-size: 14px; margin: 0; font-weight: 600; }
.password-form { max-width: 520px; }.password-form :deep(.el-form-item) { margin-bottom: 18px; }.form-footer { margin-top: 2px; }
.bound { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; padding: 0 0 18px; border-bottom: 1px solid var(--el-border-color-light); }
.binding-help { color: var(--lc-text-soft); font-size: 12px; margin: 0 0 14px; line-height: 1.9; }.bind-form { display: flex; gap: 10px; max-width: 430px; }.bind-form .el-input { min-width: 0; }.bind-form .el-button { margin-left: 0; }
code { background: var(--el-fill-color-light); padding: 2px 5px; border-radius: 4px; overflow-wrap: anywhere; }
@media (max-width: 850px) { .security-layout { grid-template-columns: 1fr; gap: 28px; }.security-layout .setting-section { border-left: 0; border-top: 1px solid var(--el-border-color-light); padding: 24px 0 0; } }
@media (max-width: 480px) { .bind-form { flex-wrap: wrap; }.bind-form .el-button { width: 100%; }.identity-details { gap: 14px 24px; }.identity-head { flex-basis: 100%; } }
</style>
