<script setup>
import { computed, ref } from 'vue'
import GlassSurface from './GlassSurface.vue'
import { useApp } from '../stores/app'
import { localePreference, setLocale, t } from '../i18n'
import { AUTO_LOCALE } from '../utils/locale-preference'

const app = useApp()
const themeLabel = computed(() => t(app.dark ? '切换到亮色' : '切换到暗色'))
const effectsOpen = ref(false)
const effectsTrigger = ref()
const glassSwitch = ref()

function focusEffects() {
  if (document.activeElement === effectsTrigger.value) glassSwitch.value?.focus()
}

function closeEffects() {
  effectsOpen.value = false
  effectsTrigger.value?.focus()
}
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
  <el-popover v-if="app.effectsAvailable" v-model:visible="effectsOpen" trigger="click" placement="bottom-end" role="dialog"
    :width="208" :show-arrow="false" :persistent="false" popper-class="appearance-effects-popover" @after-enter="focusEffects">
    <template #reference>
      <button ref="effectsTrigger" class="lc-icon-button appearance-effects-trigger" type="button"
        :class="{ 'effects-active': app.liquidGlassEnabled || app.fullMotionEnabled }"
        :aria-label="t('外观效果')" :title="t('外观效果')" :aria-expanded="effectsOpen" aria-haspopup="dialog"
        @keydown.esc.stop="closeEffects">
        <Icon name="sparkles" :size="17" />
      </button>
    </template>
    <GlassSurface v-if="app.liquidGlassEnabled" />
    <div class="appearance-effects" role="group" :aria-label="t('外观效果')" @keydown.esc.stop="closeEffects">
      <div class="appearance-effect">
        <span>{{ t('液态玻璃') }}</span>
        <el-switch ref="glassSwitch" :model-value="app.liquidGlass" :aria-label="t('液态玻璃')" @update:model-value="app.setLiquidGlass" />
      </div>
      <div class="appearance-effect">
        <span>{{ t('全动效') }}</span>
        <el-switch :model-value="app.fullMotion" :aria-label="t('全动效')" @update:model-value="app.setFullMotion" />
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.effects-active { color: var(--lc-text); background: var(--lc-selected-bg); box-shadow: var(--lc-selection-shadow); }
.appearance-effects { display: grid; }
.appearance-effect { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 36px; }
.appearance-effect + .appearance-effect { border-top: 1px solid var(--lc-line); }
.appearance-effect > span { min-width: 0; overflow-wrap: anywhere; }
@media (max-width: 899px) { .appearance-effect { min-height: 44px; } }
</style>
