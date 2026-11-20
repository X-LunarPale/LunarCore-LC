<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppearanceControls from '../components/AppearanceControls.vue'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { url } from '../api'
import { t } from '../i18n'
import { loginDestination } from '../utils/login-destination'

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
const passwordInput = ref()
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
    if (!form.username.trim()) usernameInput.value?.focus()
    else passwordInput.value?.focus()
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
    await router.replace(loginDestination(route.query.redirect, path => router.resolve(path)))
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
      <AppearanceControls />
    </header>
    <main class="login-main">
      <section class="login-form lc-pane-in" aria-labelledby="login-title">
        <h1 id="login-title" class="login-brand"><img :src="logo" alt="" /><span>{{ challenge ? t('两步验证') : brandName }}</span></h1>
        <el-segmented v-if="!challenge" v-model="mode" :aria-label="t('登录')" :disabled="loading" :options="[{ label: t('密码'), value: 'password' }, { label: t('游戏验证码'), value: 'game' }]" block class="login-modes" />
        <el-form :disabled="loading" label-position="top" size="large" :aria-describedby="error ? 'login-error' : undefined" @submit.prevent="submit">
          <Transition name="route">
            <div v-if="challenge" key="factor" class="login-fields">
              <el-form-item :label="t('动态验证码或恢复码')">
                <el-input ref="factorInput" v-model="secondFactor" maxlength="20" autocomplete="one-time-code" autocapitalize="none" :spellcheck="false" @input="error = ''" />
              </el-form-item>
            </div>
            <div v-else-if="mode === 'password'" key="password" class="login-fields">
              <el-form-item :label="t('用户名')">
                <el-input ref="usernameInput" v-model="form.username" autocomplete="username" autocapitalize="none" :spellcheck="false" @input="error = ''" />
              </el-form-item>
              <el-form-item :label="t('密码')">
                <el-input ref="passwordInput" v-model="form.password" type="password" show-password autocomplete="current-password" @input="error = ''" />
              </el-form-item>
            </div>
            <div v-else key="game" class="login-fields">
              <el-form-item :label="t('游戏验证码')">
                <el-input ref="codeInput" v-model="form.code" maxlength="10" autocomplete="one-time-code" autocapitalize="none" :spellcheck="false" @input="error = ''" />
              </el-form-item>
              <details class="login-help game-help"><summary>{{ t('获取验证码') }}</summary><p><code>/lc web bind</code> {{ t('获取验证码。游戏账号需先在“个人设置”中与 Web 账号绑定。') }}</p></details>
            </div>
          </Transition>
          <div v-if="error" id="login-error" class="login-error lc-pane-in" role="alert"><Icon name="circle-alert" :size="16" /><span>{{ error }}</span></div>
          <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">{{ challenge ? t('验证并登录') : t('登录') }}</el-button>
          <el-button v-if="challenge" text :disabled="loading" class="return-button" @click="returnToLogin"><Icon name="undo-2" :size="15" />{{ t('返回登录') }}</el-button>
        </el-form>
        <details v-if="!challenge && mode === 'password'" class="login-help"><summary>{{ t('管理员登录帮助') }}</summary><p>{{ t('首次启动的管理员密码请查看服务器控制台。忘记密码时，可在控制台执行') }} <code>/lc web resetadmin</code></p></details>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: flex; flex-direction: column; background: var(--lc-surface); }
.login-header { display: flex; align-items: center; justify-content: flex-end; gap: 4px; min-height: 56px; padding: 8px 20px; }
.login-brand { display: flex; align-items: center; gap: 10px; min-width: 0; margin: 0 0 28px; font-size: 24px; font-weight: 650; line-height: 1.35; }
.login-brand img { width: 34px; height: 34px; flex: none; object-fit: contain; }
.login-brand span { min-width: 0; overflow-wrap: anywhere; }
.login-main { display: flex; align-items: flex-start; justify-content: center; flex: 1; min-height: 0; padding: max(40px, calc((100svh - 540px) / 2)) 24px 48px; }
.login-form { width: 100%; max-width: 340px; min-width: 0; }
.login-modes { margin-bottom: 24px; }
.login-error { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 20px; padding: 10px 12px; border: 1px solid var(--el-color-danger-light-7); border-radius: 5px; color: var(--el-color-danger); background: var(--el-color-danger-light-9); font-size: 13px; overflow-wrap: anywhere; }
.login-error .lc-icon { margin-top: 2px; }
.login-fields { display: flow-root; }
.login-submit { width: 100%; margin-top: 8px; }
.return-button { display: flex; margin: 12px auto 0; gap: 6px; }
.login-help { margin-top: 16px; color: var(--lc-text-soft); font-size: 12px; line-height: 1.8; }
.login-help.game-help { margin: -4px 0 12px; }
.login-help summary { display: list-item; width: fit-content; cursor: pointer; color: var(--el-text-color-regular); }
.login-help p { margin: 6px 0 0; animation: lc-pane-in var(--motion-standard) var(--motion-spring-soft) both; }
code { padding: 3px 6px; border-radius: 4px; background: var(--el-fill-color-light); color: var(--el-text-color-regular); font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; overflow-wrap: anywhere; }
:deep(.el-form-item__label) { font-size: 13px; line-height: 1.6; padding-bottom: 7px; }
:deep(.el-input__wrapper) { min-height: 40px; }
@media (max-width: 600px) {
  .login-header { min-height: 56px; padding: 8px 16px; }
  .login-main { padding-top: 28px; }
}
</style>
