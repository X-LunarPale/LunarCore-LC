<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { url } from '../api'
import { locale, setLocale, t } from '../i18n'

const auth = useAuth()
const app = useApp()
const router = useRouter()
const route = useRoute()
const logo = computed(() => url(app.info?.logoUrl || '/favicon.svg'))
const brandName = computed(() => app.info?.brand || 'LunarCore 琉月')
const mode = ref('password')
const loading = ref(false)
const challenge = ref('')
const secondFactor = ref('')
const error = ref('')
const usernameInput = ref()
const codeInput = ref()
const factorInput = ref()
const form = reactive({ username: '', password: '', code: '' })

onMounted(async () => {
  app.loadInfo()
  await nextTick()
  usernameInput.value?.focus()
})
watch([mode, challenge], async () => {
  error.value = ''
  await nextTick()
  focusCurrentInput()
})
function focusCurrentInput() {
  if (challenge.value) factorInput.value?.focus()
  else if (mode.value === 'password') usernameInput.value?.focus()
  else codeInput.value?.focus()
}

function returnToLogin() {
  if (loading.value) return
  challenge.value = ''
  secondFactor.value = ''
}
async function submit() {
  if (loading.value) return
  error.value = ''
  if (challenge.value && !secondFactor.value.trim()) {
    error.value = t('请输入动态验证码或恢复码')
    factorInput.value?.focus()
    return
  }
  if (!challenge.value && mode.value === 'password' && (!form.username.trim() || !form.password)) {
    error.value = t('请输入用户名和密码')
    return
  }
  if (!challenge.value && mode.value === 'game' && !/^[A-Za-z0-9]{6,10}$/.test(form.code.trim())) {
    error.value = t('请输入游戏内获取的验证码')
    codeInput.value?.focus()
    return
  }
  loading.value = true
  try {
    const result = challenge.value
      ? await auth.completeTotp(challenge.value, secondFactor.value.trim())
      : mode.value === 'password'
        ? await auth.login(form.username.trim(), form.password)
        : await auth.gameLogin(form.code.trim())
    if (result?.totpRequired && result.challenge) {
      challenge.value = result.challenge
      secondFactor.value = ''
      form.password = ''
      form.code = ''
      return
    }
    if (!result?.token) throw new Error(t('登录响应无效，请重试'))
    app.panelsLoaded = false
    ElMessage.success(t('登录成功'))
    const redirect = typeof route.query.redirect === 'string'
      && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
      ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (failure) {
    error.value = failure.message || t('登录失败，请重试')
    if (challenge.value) {
      secondFactor.value = ''
      await nextTick()
      factorInput.value?.focus()
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <header class="login-header">
      <div class="login-tools">
        <el-dropdown trigger="click" @command="setLocale">
          <button type="button" class="tool-button" :aria-label="t('语言')"><Icon name="languages" :size="17" /><span>{{ locale === 'en_US' ? 'English' : '中文' }}</span></button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="zh_CN" :disabled="locale === 'zh_CN'">中文</el-dropdown-item>
            <el-dropdown-item command="en_US" :disabled="locale === 'en_US'">English</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
        <el-tooltip :content="app.dark ? t('切换到亮色') : t('切换到暗色')">
          <button type="button" class="tool-button theme-button" :aria-label="app.dark ? t('切换到亮色') : t('切换到暗色')" @click="app.toggleTheme()"><Icon :name="app.dark ? 'sun' : 'moon'" :size="17" /></button>
        </el-tooltip>
      </div>
    </header>
    <main class="login-main">
      <section class="login-form" aria-labelledby="login-title">
        <div class="login-brand"><img :src="logo" alt="" /><span>{{ brandName }}</span></div>
        <h1 id="login-title">{{ challenge ? t('两步验证') : t('登录') }}</h1>
        <el-segmented v-if="!challenge" v-model="mode" :aria-label="t('登录')" :disabled="loading" :options="[{ label: t('账号密码'), value: 'password' }, { label: t('游戏账号登录'), value: 'game' }]" block class="login-modes" />
        <div v-if="error" id="login-error" class="login-error" role="alert"><Icon name="circle-alert" :size="16" /><span>{{ error }}</span></div>
        <el-form :disabled="loading" label-position="top" size="large" :aria-describedby="error ? 'login-error' : undefined" @submit.prevent="submit">
          <Transition name="login-mode" mode="out-in" @after-enter="focusCurrentInput">
            <div v-if="challenge" key="factor" class="login-fields factor-fields">
              <el-form-item :label="t('动态验证码或恢复码')">
                <el-input ref="factorInput" v-model="secondFactor" maxlength="20" autocomplete="one-time-code" autocapitalize="none" :spellcheck="false" @input="error = ''" />
              </el-form-item>
            </div>
            <div v-else-if="mode === 'password'" key="password" class="login-fields">
              <el-form-item :label="t('用户名')">
                <el-input ref="usernameInput" v-model="form.username" autocomplete="username" autocapitalize="none" :spellcheck="false" @input="error = ''" />
              </el-form-item>
              <el-form-item :label="t('密码')">
                <el-input v-model="form.password" type="password" show-password autocomplete="current-password" @input="error = ''" />
              </el-form-item>
            </div>
            <div v-else key="game" class="login-fields">
              <el-form-item :label="t('游戏验证码')">
                <el-input ref="codeInput" v-model="form.code" maxlength="10" :placeholder="t('游戏内获取的验证码')" autocomplete="one-time-code" autocapitalize="none" :spellcheck="false" @input="error = ''">
                  <template #prefix><Icon name="key-round" :size="16" /></template>
                </el-input>
              </el-form-item>
              <p class="game-code-help"><code>/lc web bind</code><span>{{ t('在游戏内获取验证码后登录') }}</span></p>
            </div>
          </Transition>
          <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">{{ challenge ? t('验证并登录') : t('登录') }}</el-button>
          <el-button v-if="challenge" text :disabled="loading" class="return-button" @click="returnToLogin"><Icon name="undo-2" :size="15" />{{ t('返回登录') }}</el-button>
        </el-form>
        <details class="login-help"><summary>{{ t('管理员登录帮助') }}</summary><p>{{ t('首次启动的管理员密码请查看服务器控制台。忘记密码时，可在控制台执行') }}</p><code>/lc web resetadmin</code></details>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: flex; flex-direction: column; background: var(--lc-surface); }
.login-header { display: flex; align-items: center; justify-content: flex-end; min-height: 64px; padding: 12px 28px; }
.login-brand { display: flex; align-items: center; gap: 12px; min-width: 0; margin-bottom: 24px; color: var(--lc-text); font-size: 17px; font-weight: 650; }
.login-brand img { width: 38px; height: 38px; flex: none; object-fit: contain; }
.login-brand span { min-width: 0; overflow-wrap: anywhere; }
.login-tools { display: flex; align-items: center; gap: 6px; flex: none; }
.tool-button { display: flex; align-items: center; justify-content: center; gap: 7px; height: 34px; min-width: 34px; padding: 0 8px; border: 0; border-radius: 5px; background: none; color: var(--el-text-color-regular); font-size: 12px; cursor: pointer; }
.tool-button:hover { background: var(--el-fill-color-light); }
.login-main { display: flex; align-items: center; justify-content: center; flex: 1; min-height: 0; padding: 32px 24px 80px; }
.login-form { width: 100%; max-width: 340px; min-width: 0; }
.login-form h1 { margin: 0 0 20px; font-size: 22px; font-weight: 650; line-height: 1.4; }
.login-modes { margin-bottom: 20px; }
.login-error { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 20px; padding: 10px 12px; border: 1px solid var(--el-color-danger-light-7); border-radius: 5px; color: var(--el-color-danger); background: var(--el-color-danger-light-9); font-size: 13px; overflow-wrap: anywhere; }
.login-error .lc-icon { margin-top: 2px; }
.login-fields { display: flow-root; min-height: 176px; }
.factor-fields { min-height: 96px; }
.login-submit { width: 100%; margin-top: 8px; }
.return-button { display: flex; margin: 12px auto 0; gap: 6px; }
.login-help { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--lc-line); color: var(--lc-text-soft); font-size: 12px; line-height: 1.8; }
.login-help summary { display: list-item; width: fit-content; cursor: pointer; color: var(--el-text-color-regular); }
.login-help p { margin: 12px 0 8px; }
code { padding: 3px 6px; border-radius: 4px; background: var(--el-fill-color-light); color: var(--el-text-color-regular); font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; overflow-wrap: anywhere; }
.game-code-help { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; margin: 0 0 20px; color: var(--lc-text-soft); font-size: 12px; }
.login-mode-enter-active { transition: opacity 150ms ease-out, transform 240ms var(--motion-spring-soft); }
.login-mode-leave-active { transition: opacity 100ms ease-in; }
.login-mode-enter-from { opacity: 0; transform: translateY(4px); }
.login-mode-leave-to { opacity: 0; }
:deep(.el-form-item__label) { font-size: 13px; line-height: 1.6; padding-bottom: 7px; }
:deep(.el-input__wrapper) { min-height: 40px; }
@media (max-width: 600px) {
  .login-header { min-height: 56px; padding: 8px 16px; }
  .login-tools { gap: 2px; }
  .tool-button span { display: none; }
  .login-main { align-items: flex-start; padding: 24px 24px 40px; }
}
</style>
