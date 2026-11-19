<script setup>
import { t } from '../i18n'
</script>

<template>
  <div class="page missing-page">
    <section class="missing-content">
      <h1 class="page-title">{{ t('页面不可用') }}</h1>
      <p>{{ t('该页面不存在，或对应模块已被禁用。') }}</p>
      <router-link class="return-home" to="/">{{ t('返回首页') }}<Icon name="chevron-right" :size="14" /></router-link>
    </section>
  </div>
</template>

<style scoped>
.missing-content { max-width: 480px; }
p { margin: 8px 0 16px; color: var(--lc-text-soft); }
.return-home { display: inline-flex; align-items: center; gap: 4px; }
</style>
