<script setup>
import { useAuth } from '../stores/auth'
import { t } from '../i18n'
const auth = useAuth()
</script>

<template>
  <div class="page welcome-page">
    <div class="page-header"><div><h2 class="page-title"><Icon name="shield" :size="22" />{{ t('账号与访问') }}</h2><div class="page-sub">{{ t('查看当前账号的访问状态。') }}</div></div></div>
    <section class="access-panel">
      <div class="access-icon"><Icon name="shield-check" :size="28" /></div>
      <div class="access-copy"><div class="account-label">{{ auth.user?.username || t('当前账号') }}</div><h1>{{ t('等待分配管理权限') }}</h1><p>{{ t('当前账号暂时没有可访问的管理页面。请联系管理员分配相应角色与权限。') }}</p><el-button type="primary" @click="$router.push({ name: 'profile' })">{{ t('前往个人设置') }} <Icon name="arrow-right" :size="15" /></el-button></div>
    </section>
    <div class="access-footer"><Icon name="user-round" :size="16" /><p>{{ t('个人设置可用于查看账号信息、修改密码和管理游戏账号绑定。') }}</p></div>
  </div>
</template>

<style scoped>
.welcome-page { color: var(--lbr-text); }
.access-panel { display: flex; align-items: flex-start; gap: 24px; padding: 40px; background: var(--lbr-surface); border: 1px solid var(--el-border-color-light); border-radius: 10px; max-width: 960px; }
.access-icon { width: 60px; height: 60px; flex: 0 0 60px; border: 1px solid var(--el-border-color-light); background: var(--el-fill-color-light); color: var(--el-text-color-secondary); display: grid; place-items: center; border-radius: 12px; }
.account-label { font-size: 12px; color: var(--lbr-text-soft); margin-bottom: 8px; overflow-wrap: anywhere; }
h1 { font-size: 23px; font-weight: 600; line-height: 1.4; letter-spacing: -.4px; margin: 0; }
.access-copy > p { max-width: 560px; font-size: 13px; line-height: 1.9; color: var(--lbr-text-soft); margin: 12px 0 24px; }
.el-button { gap: 10px; }
.access-footer { display: flex; gap: 9px; align-items: flex-start; color: var(--lbr-text-soft); margin-top: 18px; font-size: 12px; }.access-footer .lbr-icon { margin-top: 2px; }.access-footer p { margin: 0; line-height: 1.8; }
@media (max-width: 600px) { .access-panel { flex-direction: column; padding: 26px 22px; gap: 20px; } .access-icon { width: 48px; height: 48px; flex-basis: 48px; } h1 { font-size: 21px; } }
</style>
