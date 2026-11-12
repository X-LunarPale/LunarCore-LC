<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { useApp } from '../stores/app'
import { url } from '../api'
import { formatVersion, locale, setLocale, t } from '../i18n'

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
const form = reactive({ username: '', password: '', code: '' })

onMounted(() => app.loadInfo())

function done() {
  app.panelsLoaded = false
  const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
  router.replace(redirect)
}

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    let result
    if (challenge.value) {
      result = await auth.completeTotp(challenge.value, secondFactor.value.trim())
    } else if (mode.value === 'password') {
      if (!form.username || !form.password) return ElMessage.warning(t('请输入用户名和密码'))
      result = await auth.login(form.username.trim(), form.password)
    } else {
      if (!/^[A-Za-z0-9]{6,10}$/.test(form.code.trim())) return ElMessage.warning(t('请输入游戏内获取的验证码'))
      result = await auth.gameLogin(form.code.trim())
    }
    if (result?.totpRequired) {
      challenge.value = result.challenge
      form.password = ''
      form.code = ''
      return
    }
    ElMessage.success(t('登录成功'))
    done()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-bg">
    <aside class="brand-panel">
      <div class="brand-wordmark"><img :src="logo" alt="" /><span>{{ brandName }}</span></div>
      <div class="brand-copy"><div class="brand-label">{{ t('服务器管理控制台') }}</div><h2>{{ t('集中管理，') }}<br />{{ t('有序运行。') }}</h2><p>{{ t('查看服务器状态，管理玩家、模块与核心配置。') }}</p><div class="brand-features"><span><Icon name="activity" :size="15" />{{ t('运行监控') }}</span><span><Icon name="shield-check" :size="15" />{{ t('权限管理') }}</span><span><Icon name="file-cog" :size="15" />{{ t('配置管理') }}</span></div></div>
      <div class="brand-footer">{{ brandName }} · {{ t('管理控制台') }}<span v-if="app.info"> · {{ formatVersion(app.info.version) }}</span></div>
    </aside>
    <div class="login-tools">
      <el-dropdown trigger="click" @command="setLocale">
        <button class="language-button" :aria-label="t('语言')"><Icon name="languages" :size="17" />{{ locale === 'en_US' ? 'English' : '中文' }}</button>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item command="zh_CN" :disabled="locale === 'zh_CN'">中文</el-dropdown-item>
          <el-dropdown-item command="en_US" :disabled="locale === 'en_US'">English</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
      <div class="theme-btn">
        <el-button circle text :aria-label="t('切换主题')" @click="app.toggleTheme()"><Icon :name="app.dark ? 'sun' : 'moon'" /></el-button>
      </div>
    </div>
    <div class="login-card">
      <div class="head">
        <img :src="logo" class="logo" alt="" />
        <div>
          <h1 class="title">{{ t('登录控制台') }}</h1>
          <div class="subtitle">{{ t('使用已授权的账号访问 {brand}', { brand: brandName }) }}</div>
        </div>
      </div>
      <el-segmented v-if="!challenge" v-model="mode" :options="[{ label: t('账号密码'), value: 'password' }, { label: t('游戏账号登录'), value: 'game' }]" block class="seg" />
      <el-form @submit.prevent="submit" size="large" label-position="top">
        <Transition name="login-mode" mode="out-in">
          <div v-if="challenge" key="totp" class="mode-fields">
            <el-form-item :label="t('动态验证码或恢复码')">
              <el-input v-model="secondFactor" maxlength="20" autocomplete="one-time-code" :placeholder="t('动态验证码或恢复码')" />
            </el-form-item>
            <el-button text @click="challenge = ''; secondFactor = ''">{{ t('返回登录') }}</el-button>
          </div>
          <div v-else-if="mode === 'password'" key="password" class="mode-fields">
            <el-form-item :label="t('用户名')">
              <el-input v-model="form.username" :placeholder="t('用户名')" autocomplete="username" autofocus>
                <template #prefix><Icon name="user" :size="16" /></template>
              </el-input>
            </el-form-item>
            <el-form-item :label="t('密码')">
              <el-input v-model="form.password" type="password" show-password :placeholder="t('密码')" autocomplete="current-password">
                <template #prefix><Icon name="lock" :size="16" /></template>
              </el-input>
            </el-form-item>
          </div>
          <div v-else key="game" class="mode-fields">
            <el-form-item :label="t('游戏验证码')">
              <el-input v-model="form.code" maxlength="10" :placeholder="t('游戏内获取的验证码')">
                <template #prefix><Icon name="key-round" :size="16" /></template>
              </el-input>
            </el-form-item>
            <div class="tip">{{ t('在游戏内执行') }} <code>/lc web bind</code> {{ t('获取验证码。游戏账号需先在“个人设置”中与 Web 账号绑定。') }}</div>
          </div>
        </Transition>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">{{ t('登 录') }}</el-button>
      </el-form>
      <details class="foot"><summary>{{ t('管理员登录帮助') }}</summary><p>{{ t('首次启动的管理员密码请查看服务器控制台。忘记密码时，可在控制台执行') }} <code>/lc web resetadmin</code> {{ t('重置。') }}</p></details>
    </div>
  </div>
</template>

<style scoped>
.brand-features { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 32px; padding-top: 22px; border-top: 1px solid var(--el-border-color); }
.brand-features span { display: inline-flex; gap: 7px; align-items: center; font-size: 12px; color: var(--lc-text-soft); }
.foot summary { cursor: pointer; font-weight: 500; color: var(--el-text-color-regular); }
.foot p { margin: 10px 0 0; }
.head > div { min-width: 0; }
.subtitle { overflow-wrap: anywhere; }

.login-bg{min-height:100dvh;display:grid;grid-template-columns:minmax(300px,40%) minmax(0,1fr);background:var(--lc-surface,#fff);color:var(--lc-text,#19283b);position:relative;}
.brand-panel{background:var(--el-fill-color-light);color:var(--lc-text);border-right:1px solid var(--el-border-color-light);padding:48px clamp(32px,5vw,80px);display:flex;flex-direction:column;justify-content:space-between;min-height:100dvh;box-sizing:border-box;}
.brand-wordmark{display:flex;align-items:center;gap:12px;font-size:20px;font-weight:650;letter-spacing:-.4px}.brand-wordmark img{width:36px;height:36px}.brand-label{font-size:13px;color:var(--el-color-primary);margin-bottom:24px;letter-spacing:2px}.brand-copy h2{font-size:clamp(32px,3.4vw,48px);line-height:1.3;letter-spacing:-1.6px;font-weight:550;margin:0 0 28px}.brand-copy p{font-size:14px;line-height:1.9;color:var(--lc-text-soft);max-width:290px}.brand-footer{font-size:12px;color:var(--lc-text-soft);border-top:1px solid var(--el-border-color);padding-top:24px}
.login-tools{position:absolute;top:24px;right:24px;z-index:2;display:flex;align-items:center;gap:8px}.theme-btn{position:static}.language-button{display:flex;align-items:center;gap:6px;border:0;border-radius:6px;padding:6px 8px;color:var(--el-text-color-regular);background:transparent;cursor:pointer;font-size:12px}.language-button:hover{background:var(--el-fill-color-light)}.language-button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.login-card{width:100%;max-width:460px;padding:56px 40px;margin:auto;box-sizing:border-box;animation:form-enter 480ms cubic-bezier(.2,1.24,.34,1) both}.head{display:flex;align-items:center;gap:14px;margin-bottom:32px}.head .logo{display:none}.title{font-size:30px;font-weight:650;letter-spacing:-.8px;margin:0 0 10px;line-height:1.3}.subtitle{font-size:14px;color:var(--lc-text-soft,#718096)}.seg{margin-bottom:28px}.mode-fields{min-height:166px}.login-mode-enter-active{transition:opacity 230ms ease,transform 430ms cubic-bezier(.2,1.24,.34,1)}.login-mode-leave-active{transition:opacity 120ms ease,transform 150ms ease}.login-mode-enter-from{opacity:0;transform:translateY(8px) scale(.985)}.login-mode-leave-to{opacity:0;transform:translateY(-4px)}.submit{width:100%;margin-top:8px;letter-spacing:2px;transition:background-color 180ms ease,transform 260ms cubic-bezier(.2,1.24,.34,1)}.submit:active{transform:scale(.97)}.tip{font-size:13px;color:var(--lc-text-soft,#718096);margin:-4px 0 20px;line-height:1.8}.foot{margin-top:28px;padding-top:24px;border-top:1px solid var(--lc-sidebar-border,#e3e8ef);font-size:12px;color:var(--lc-text-soft,#718096);line-height:1.9}code{background:var(--el-fill-color-light,#f4f6f9);padding:2px 5px;border-radius:4px;overflow-wrap:anywhere}:deep(.el-form-item__label){font-size:13px}:deep(.el-input__wrapper){min-height:40px;box-sizing:border-box}
@keyframes form-enter{from{opacity:0;transform:translateY(12px) scale(.985)}to{opacity:1;transform:translateY(0) scale(1)}}
.brand-wordmark img,.head .logo{object-fit:contain;flex-shrink:0}
@media(max-width:760px){.login-bg{grid-template-columns:1fr}.brand-panel{min-height:auto;padding:24px;gap:28px}.brand-copy,.brand-footer{display:none}.login-card{padding:40px 24px}.login-tools{top:22px;right:18px}.theme-btn :deep(.el-button){color:var(--lc-text)}.brand-panel{border-right:0;border-bottom:1px solid var(--el-border-color-light)}.brand-wordmark{max-width:calc(100% - 132px);min-width:0;font-size:16px;line-height:1.35;letter-spacing:0}.brand-wordmark img{flex:none}.brand-wordmark span{min-width:0;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden;overflow-wrap:anywhere}}
@media(prefers-reduced-motion:reduce){.login-card{animation:none}.login-mode-enter-active,.login-mode-leave-active,.submit{transition:none}}
</style>
