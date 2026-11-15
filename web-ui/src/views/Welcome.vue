<script setup>
import { useAuth } from '../stores/auth'
import { t } from '../i18n'
const auth = useAuth()
</script>

<template>
  <div class="page welcome-page">
    <div class="page-header"><h1 class="page-title">{{ t('账号与访问') }}</h1></div>
    <section class="access-panel">
      <div class="access-icon"><Icon name="shield-check" :size="28" /></div>
      <div class="access-copy"><div class="account-label">{{ auth.user?.username || t('当前账号') }}</div><h2>{{ t('等待分配管理权限') }}</h2><p>{{ t('当前账号暂时没有可访问的管理页面。请联系管理员分配相应角色与权限。') }}</p><el-button type="primary" @click="$router.push({ name: 'profile' })"><Icon name="user-cog" :size="15" />{{ t('前往个人设置') }}</el-button></div>
    </section>
  </div>
</template>

<style scoped>
.welcome-page { color: var(--lc-text); }
.access-panel { display: flex; align-items: flex-start; gap: 24px; padding: 28px 0; border-block: 1px solid var(--el-border-color-light); max-width: 960px; }
.access-icon { width: 44px; height: 44px; flex: 0 0 44px; color: var(--el-text-color-secondary); display: grid; place-items: center; }
.account-label { font-size: 12px; color: var(--lc-text-soft); margin-bottom: 8px; overflow-wrap: anywhere; }
.access-copy h2 { font-size: 18px; font-weight: 600; line-height: 1.4; margin: 0; }
.access-copy > p { max-width: 560px; font-size: 13px; line-height: 1.9; color: var(--lc-text-soft); margin: 12px 0 24px; }
.el-button { gap: 10px; }
@media (max-width: 600px) { .access-panel { flex-direction: column; padding: 24px 0; gap: 16px; } }
</style>
