<script setup>
import { computed, watchEffect } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import zhHk from 'element-plus/es/locale/lang/zh-hk'
import en from 'element-plus/es/locale/lang/en'
import 'dayjs/locale/zh-hk'
import { locale } from './i18n'
import { url } from './api'
import { useApp } from './stores/app'

const elementLocales = { zh_CN: zhCn, zh_HK: zhHk, en_US: en }
const elementLocale = computed(() => elementLocales[locale.value])
const app = useApp()

watchEffect(() => {
  document.documentElement.classList.toggle('lc-liquid-glass', app.liquidGlassEnabled)
  document.documentElement.classList.toggle('lc-full-motion', app.fullMotionEnabled)
})
</script>

<template>
  <Teleport to="head">
    <link v-if="app.liquidGlassEnabled" rel="stylesheet" :href="url('/ext/webui-effects/glass.css')" />
    <link v-if="app.fullMotionEnabled" rel="stylesheet" :href="url('/ext/webui-effects/motion.css')" />
  </Teleport>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>
