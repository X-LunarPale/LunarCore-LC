<script setup>
import { t } from '../../i18n'
defineProps({ error: { type: Object, default: null } })
</script>

<template>
  <div v-if="error" class="section-error" role="alert">
    <Icon class="error-icon" :name="error.status === 403 ? 'lock-keyhole' : 'circle-alert'" :size="16" />
    <span>{{ error.status === 403 ? t('请联系管理员为当前账号分配所需权限。') : error.message || t('内容加载或操作未完成') }}</span>
  </div>
</template>

<style scoped>
.section-error { display: flex; align-items: flex-start; gap: 8px; padding: 10px 12px; margin-bottom: 10px; border-left: 2px solid var(--el-color-danger); background: var(--el-color-danger-light-9); color: var(--el-color-danger); font-size: 13px; overflow-wrap: anywhere; }
.error-icon { flex: none; margin-top: 2px; }
</style>
