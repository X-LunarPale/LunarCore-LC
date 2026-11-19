<script setup>
import { t } from '../i18n'
</script>

<template>
  <div class="page welcome-page">
    <header class="page-header"><h1 class="page-title">{{ t('账号与访问') }}</h1></header>
    <section class="access-panel">
      <div class="access-copy">
        <h2><Icon name="lock" :size="16" />{{ t('等待分配管理权限') }}</h2>
        <p>{{ t('当前账号暂时没有可访问的管理页面。请联系管理员分配相应角色与权限。') }}</p>
        <router-link class="access-link" :to="{ name: 'profile' }">{{ t('前往个人设置') }}<Icon name="chevron-right" :size="14" /></router-link>
      </div>
    </section>
  </div>
</template>

<style scoped>
.access-panel { max-width: 600px; padding-block: 20px; border-top: 1px solid var(--lc-line); }
.access-copy h2 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 15px; font-weight: 600; }
.access-copy > p { margin: 8px 0 16px; color: var(--lc-text-soft); }
.access-link { display: inline-flex; align-items: center; gap: 4px; }
</style>
