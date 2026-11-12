<script setup>
import { t } from '../../i18n'
// 区块加载失败的占位：403 显示友好的无权限提示，其余显示错误信息
defineProps({ error: { type: Object, default: null } })
</script>

<template>
  <div v-if="error" class="section-error" role="alert">
    <span class="error-icon"><Icon :name="error.status === 403 ? 'lock-keyhole' : 'circle-alert'" :size="19" /></span>
    <div><strong>{{ error.status === 403 ? t('暂无查看权限') : t('内容加载或操作未完成') }}</strong><p>{{ error.status === 403 ? t('请联系管理员为当前账号分配所需权限。') : error.message || t('请稍后重试。') }}</p></div>
  </div>
</template>

<style scoped>
.section-error { display: flex; align-items: flex-start; gap: 9px; padding: 10px 12px; margin-bottom: 10px; border: 1px solid var(--el-color-danger-light-7); border-radius: 6px; background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.error-icon { flex: 0 0 auto; padding-top: 1px; }
strong { font-size: 12px; font-weight: 600; line-height: 1.45; }
p { margin: 2px 0 0; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; color: var(--lc-text-soft); }
</style>
