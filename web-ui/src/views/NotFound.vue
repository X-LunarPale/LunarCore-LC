<script setup>
import { t } from '../i18n'
</script>

<template>
  <div class="page missing-page">
    <section class="missing-panel">
      <div class="error-code" aria-hidden="true">404</div>
      <div class="missing-copy"><p class="eyebrow">{{ t('页面访问') }}</p><h1>{{ t('页面不可用') }}</h1><p class="description">{{ t('该页面不存在，或对应模块已被禁用。') }}<br />{{ t('请检查访问地址，或返回首页继续操作。') }}</p><el-button type="primary" @click="$router.push('/')">{{ t('返回首页') }} <Icon name="arrow-right" :size="15" /></el-button></div>
    </section>
  </div>
</template>

<style scoped>
.missing-page { color: var(--lbr-text); }
.missing-panel { display: flex; align-items: center; gap: 40px; max-width: 960px; margin: 16px 0; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--lbr-surface); padding: 44px 40px; }
.error-code { display: grid; place-items: center; padding: 28px 30px; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); border-radius: 10px; font-size: 64px; font-weight: 600; line-height: 1; letter-spacing: -3px; font-variant-numeric: tabular-nums; }
.eyebrow { font-size: 12px; color: var(--lbr-text-soft); margin: 0 0 8px; }
h1 { font-size: 23px; font-weight: 600; line-height: 1.4; margin: 0; letter-spacing: -.4px; }
.description { color: var(--lbr-text-soft); font-size: 13px; line-height: 1.9; margin: 12px 0 24px; }
.el-button { gap: 10px; }
@media (max-width: 600px) { .missing-panel { padding: 26px 22px; flex-direction: column; align-items: flex-start; gap: 24px; margin-top: 0; }.error-code { font-size: 42px; padding: 18px 22px; } h1 { font-size: 21px; } }
</style>
