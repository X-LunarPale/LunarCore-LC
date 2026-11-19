<script setup>
import { computed } from 'vue'
import { useApp } from '../stores/app'
import { locale, setLocale, t } from '../i18n'

const app = useApp()
const themeLabel = computed(() => t(app.dark ? '切换到亮色' : '切换到暗色'))
</script>

<template>
  <el-dropdown trigger="click" @command="setLocale">
    <button class="lc-icon-button" type="button" :aria-label="t('语言')" :title="t('语言')">
      <Icon name="languages" :size="17" />
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="zh_CN" :disabled="locale === 'zh_CN'">中文</el-dropdown-item>
        <el-dropdown-item command="en_US" :disabled="locale === 'en_US'">English</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <button class="lc-icon-button" type="button" :aria-label="themeLabel" :title="themeLabel" @click="app.toggleTheme()">
    <Icon :name="app.dark ? 'sun' : 'moon'" :size="17" />
  </button>
</template>
