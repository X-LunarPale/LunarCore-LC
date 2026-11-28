<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { url } from '../api'
import { useApp } from '../stores/app'

const app = useApp()
const surface = ref()
let glass

onMounted(async () => {
  try {
    const { mountGlass } = await import(/* @vite-ignore */ url('/ext/webui-effects/glass.js'))
    if (surface.value) glass = mountGlass(surface.value, app.dark)
  } catch {
    // Keep the CSS material when optical refraction is unavailable.
  }
})

watch(() => app.dark, (dark) => glass?.setDark(dark))
onUnmounted(() => glass?.destroy())
</script>

<template>
  <div ref="surface" class="lc-glass-layer" aria-hidden="true"></div>
</template>

<style scoped>
.lc-glass-layer { position: absolute; inset: 0; z-index: -1; pointer-events: none; border-radius: inherit; }
</style>
