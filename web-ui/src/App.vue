<script setup>
import { computed } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import zhHk from 'element-plus/es/locale/lang/zh-hk'
import en from 'element-plus/es/locale/lang/en'
import 'dayjs/locale/zh-hk'
import { locale } from './i18n'

const elementLocales = { zh_CN: zhCn, zh_HK: zhHk, en_US: en }
const elementLocale = computed(() => elementLocales[locale.value])
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>
