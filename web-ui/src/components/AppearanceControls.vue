<script setup>
import { computed } from 'vue'
import { useApp } from '../stores/app'
import { localePreference, setLocale, t } from '../i18n'
import { AUTO_LOCALE } from '../utils/locale-preference'

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
        <el-dropdown-item :command="AUTO_LOCALE" :disabled="localePreference === AUTO_LOCALE">{{ t('跟随系统') }}</el-dropdown-item>
        <el-dropdown-item command="zh_CN" lang="zh-CN" :disabled="localePreference === 'zh_CN'">简体中文</el-dropdown-item>
        <el-dropdown-item command="zh_HK" lang="zh-HK" :disabled="localePreference === 'zh_HK'">繁體中文（香港）</el-dropdown-item>
        <el-dropdown-item command="en_US" lang="en" :disabled="localePreference === 'en_US'">English</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <button class="lc-icon-button" type="button" :aria-label="themeLabel" :title="themeLabel" @click="app.toggleTheme()">
    <Icon :name="app.dark ? 'sun' : 'moon'" :size="17" />
  </button>
</template>
