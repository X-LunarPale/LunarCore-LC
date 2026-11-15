<script setup>
import { t } from '../i18n'
</script>

<template>
  <div class="page missing-page">
    <section class="missing-content">
      <Icon name="scan-search" :size="32" class="missing-icon" />
      <h1>{{ t('页面不可用') }}</h1>
      <p>{{ t('该页面不存在，或对应模块已被禁用。') }}</p>
      <el-button type="primary" @click="$router.push('/')"><Icon name="layout-dashboard" :size="16" /><span>{{ t('返回首页') }}</span></el-button>
    </section>
  </div>
</template>

<style scoped>
.missing-content { max-width: 480px; padding: 48px 0; }
.missing-icon { color: var(--lc-text-soft); margin-bottom: 18px; }
h1 { margin: 0; font-size: 22px; line-height: 1.5; font-weight: 650; letter-spacing: 0; }
p { margin: 8px 0 24px; color: var(--lc-text-soft); font-size: 13px; line-height: 1.75; }
.el-button { gap: 7px; }
@media (max-width: 600px) { .missing-content { padding: 24px 0; } }
</style>
