<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { formatDateTime } from '../utils/format'
import PlayerCell from '../components/PlayerCell.vue'
import TotpSettings from '../components/TotpSettings.vue'
import { t } from '../i18n'

const auth = useAuth()
const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const code = ref('')
const busy = ref(false)

async function changePassword() {
  if (!pw.oldPassword || !pw.newPassword) return ElMessage.warning(t('请填写完整'))
  if (pw.newPassword !== pw.confirm) return ElMessage.warning(t('两次输入的新密码不一致'))
  busy.value = true
  try {
    const r = await api.post('/api/auth/password', { oldPassword: pw.oldPassword, newPassword: pw.newPassword })
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
  if (!/^[A-Za-z0-9]{6,10}$/.test(code.value.trim())) return ElMessage.warning(t('请输入游戏内获取的验证码'))
  try {
    const r = await api.post('/api/auth/bind', { code: code.value.trim() })
    ElMessage.success(r.message)
    code.value = ''
    auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function unbind() {
  const ok = await ElMessageBox.confirm(t('解除后将无法使用游戏账号登录，确定吗？'), t('解除绑定'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  try {
    const r = await api.post('/api/auth/unbind')
    ElMessage.success(r.message)
    auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<template>
  <div class="page profile-page">
    <div class="page-header"><div><h2 class="page-title"><Icon name="user-cog" :size="22" />{{ t('个人设置') }}</h2><div class="page-sub">{{ t('查看账号信息，管理登录安全与游戏账号绑定。') }}</div></div></div>
    <div class="profile-layout">
      <aside class="identity-panel">
        <div class="identity-head"><div class="identity-icon"><Icon name="user-round" :size="24" /></div><div><h3>{{ auth.user?.username }}</h3><span>{{ auth.user?.roleName }} · {{ auth.user?.role }}</span></div></div>
        <dl class="identity-details"><div><dt>{{ t('上次登录') }}</dt><dd>{{ formatDateTime(auth.user?.lastLogin) }}</dd></div><div><dt>{{ t('会话到期') }}</dt><dd>{{ formatDateTime(auth.user?.expiresAt) }}</dd></div></dl>
        <div class="permission-section"><h4>{{ t('已分配权限') }} <span>{{ (auth.user?.permissions || []).length }}</span></h4><div class="permission-tags"><el-tag v-for="p in auth.user?.permissions || []" :key="p" size="small" effect="plain">{{ p }}</el-tag><span v-if="!auth.user?.permissions?.length" class="muted">{{ t('暂无已分配权限') }}</span></div></div>
      </aside>
      <div class="settings-panel">
        <TotpSettings />
        <section class="setting-section">
          <header><Icon name="key-round" :size="18" /><div><h3>{{ t('修改密码') }}</h3><p>{{ t('请使用至少 8 位的新密码。') }}</p></div></header>
          <el-form label-position="top" class="password-form" @submit.prevent="changePassword">
            <el-form-item :label="t('原密码')" class="full-field"><el-input v-model="pw.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
            <el-form-item :label="t('新密码')"><el-input v-model="pw.newPassword" type="password" show-password autocomplete="new-password" :placeholder="t('至少 8 位')" /></el-form-item>
            <el-form-item :label="t('确认新密码')"><el-input v-model="pw.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
            <div class="form-footer"><el-button type="primary" native-type="submit" :loading="busy">{{ t('更新密码') }}</el-button></div>
          </el-form>
        </section>
        <section class="setting-section">
          <header><Icon name="gamepad-2" :size="18" /><div><h3>{{ t('游戏账号绑定') }}</h3><p>{{ t('绑定后，可使用游戏内验证码登录控制台。') }}</p></div></header>
          <div v-if="auth.user?.mcName" class="bound"><PlayerCell :name="auth.user.mcName" :uuid="auth.user.mcUuid" :size="32" /><el-button type="danger" plain size="small" @click="unbind">{{ t('解除绑定') }}</el-button></div>
          <template v-else><p class="binding-help">{{ t('在游戏内执行') }} <code>/lc web bind</code> {{ t('获取验证码，输入后完成绑定。') }}</p><div class="bind-form"><el-input v-model="code" maxlength="10" :placeholder="t('游戏内获取的验证码')" @keyup.enter="bind" /><el-button type="primary" @click="bind">{{ t('绑定账号') }}</el-button></div></template>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-layout { display: grid; grid-template-columns: minmax(250px, 320px) minmax(0, 1fr); gap: 20px; align-items: start; }
.identity-panel, .settings-panel { min-width: 0; border: 1px solid var(--el-border-color-light); background: var(--lc-surface); border-radius: 10px; }
.identity-head { padding: 24px 22px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.identity-icon { width: 44px; height: 44px; display: grid; place-items: center; background: var(--el-fill-color-light); border-radius: 9px; color: var(--el-text-color-secondary); flex-shrink: 0; }
.identity-head h3 { font-size: 17px; margin: 0 0 3px; overflow-wrap: anywhere; }
.identity-head span { font-size: 12px; color: var(--lc-text-soft); }
.identity-details { margin: 0; padding: 18px 22px; display: grid; gap: 15px; }
.identity-details dt { color: var(--lc-text-soft); font-size: 11px; margin-bottom: 4px; }
.identity-details dd { margin: 0; font-size: 12px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.permission-section { border-top: 1px solid var(--el-border-color-lighter); padding: 18px 22px 22px; }
.permission-section h4 { font-size: 12px; font-weight: 500; margin: 0 0 12px; }.permission-section h4 span { color: var(--lc-text-soft); margin-left: 6px; }
.permission-tags { display: flex; flex-wrap: wrap; gap: 6px; max-height: 240px; overflow: auto; font-size: 12px; }.permission-tags :deep(.el-tag) { max-width: 100%; height: auto; min-height: 22px; white-space: normal; overflow-wrap: anywhere; }
.setting-section { padding: 24px; }.setting-section + .setting-section { border-top: 1px solid var(--el-border-color-light); }
.setting-section header { display: flex; gap: 12px; margin-bottom: 22px; }.setting-section header > .lc-icon { color: var(--lc-text-soft); margin-top: 2px; }.setting-section h3 { font-size: 15px; margin: 0; font-weight: 600; }.setting-section header p { font-size: 12px; color: var(--lc-text-soft); margin: 5px 0 0; }
.password-form { display: grid; grid-template-columns: 1fr 1fr; column-gap: 16px; max-width: 660px; }.password-form :deep(.el-form-item) { margin-bottom: 18px; }.full-field, .form-footer { grid-column: 1 / -1; }.full-field { max-width: calc(50% - 8px); }.form-footer { margin-top: 2px; }
.bound { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 14px; border-radius: 6px; background: var(--el-fill-color-extra-light); border: 1px solid var(--el-border-color-lighter); }
.binding-help { color: var(--lc-text-soft); font-size: 12px; margin: 0 0 14px; line-height: 1.9; }.bind-form { display: flex; gap: 10px; max-width: 430px; }.bind-form .el-input { min-width: 0; }.bind-form .el-button { margin-left: 0; }
code { background: var(--el-fill-color-light); padding: 2px 5px; border-radius: 4px; overflow-wrap: anywhere; }
@media (max-width: 1000px) { .profile-layout { grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: 16px; } .password-form { grid-template-columns: 1fr; }.full-field { max-width: none; } }
@media (max-width: 760px) { .profile-layout { grid-template-columns: 1fr; }.identity-details { grid-template-columns: 1fr 1fr; }.setting-section { padding: 20px; }.permission-tags { max-height: 140px; } }
@media (max-width: 400px) { .identity-details { grid-template-columns: 1fr; }.bind-form { flex-wrap: wrap; }.bind-form .el-button { width: 100%; } }
</style>
