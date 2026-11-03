<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { t } from '../i18n'
import QRCode from 'qrcode'

const auth = useAuth()
const password = ref('')
const code = ref('')
const setup = ref(null)
const recovery = ref([])
const busy = ref(false)
const qrCode = ref('')
async function act(action) {
  if (busy.value) return
  busy.value = true
  try {
    const result = await api.post(`/api/auth/totp/${action}`, { password: password.value, code: code.value.trim() })
    if (action === 'setup') {
      setup.value = result
      qrCode.value = await QRCode.toDataURL(result.uri, { width: 220, margin: 2, errorCorrectionLevel: 'M' })
    }
    else {
      auth.setToken(result.token)
      await auth.fetchMe()
      recovery.value = result.recoveryCodes || []
      setup.value = null
      qrCode.value = ''
      password.value = ''
      code.value = ''
      ElMessage.success(t('已保存'))
    }
  } catch (error) { ElMessage.error(error.message) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="totp-setting">
    <h3><Icon name="shield-check" :size="18" />{{ t('登录两步验证') }}</h3>
    <el-tag :type="auth.user?.totpEnabled ? 'success' : 'info'" effect="plain">{{ t(auth.user?.totpEnabled ? '已启用' : '未启用') }}</el-tag>
    <el-form label-position="top" @submit.prevent="act(auth.user?.totpEnabled ? 'disable' : setup ? 'enable' : 'setup')">
      <el-form-item v-if="!setup" :label="t('当前密码')"><el-input v-model="password" type="password" show-password autocomplete="current-password" /></el-form-item>
      <template v-if="setup">
        <img v-if="qrCode" :src="qrCode" width="220" height="220" :alt="t('验证器绑定二维码')" class="totp-qr" />
        <el-form-item :label="t('验证器密钥')"><el-input :model-value="setup.secret" readonly /></el-form-item>
        <el-form-item :label="t('验证器导入地址')"><el-input :model-value="setup.uri" readonly /></el-form-item>
      </template>
      <el-form-item v-if="setup || auth.user?.totpEnabled" :label="t('动态验证码或恢复码')"><el-input v-model="code" maxlength="20" autocomplete="one-time-code" /></el-form-item>
      <el-button :type="auth.user?.totpEnabled ? 'danger' : 'primary'" native-type="submit" :loading="busy">{{ t(auth.user?.totpEnabled ? '停用两步验证' : setup ? '确认启用' : '设置两步验证') }}</el-button>
      <el-button v-if="setup" @click="setup = null; qrCode = ''; password = ''; code = ''">{{ t('取消') }}</el-button>
    </el-form>
    <div v-if="recovery.length" class="recovery" role="status">
      <strong>{{ t('恢复码仅显示一次，请妥善保存') }}</strong>
      <div class="codes"><code v-for="item in recovery" :key="item">{{ item }}</code></div>
      <el-button @click="recovery = []">{{ t('已保存恢复码') }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.totp-setting{padding:24px;border-top:1px solid var(--el-border-color-light);min-width:0}
h3{display:flex;gap:10px;align-items:center;margin:0 0 14px;font-size:15px}
.el-form{max-width:540px;margin-top:18px}.codes{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:16px 0}
.totp-qr{display:block;max-width:100%;height:auto;margin-bottom:18px;image-rendering:pixelated}
.recovery{margin-top:20px;padding:16px;background:var(--el-fill-color-light);border-left:3px solid var(--el-color-warning)}
code{overflow-wrap:anywhere;user-select:all}@media(max-width:480px){.codes{grid-template-columns:1fr}}
</style>
