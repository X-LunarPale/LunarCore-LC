<script setup>
import { computed } from 'vue'
import { avatarUrl } from '../utils/format'

const props = defineProps({ name: String, uuid: String, size: { type: Number, default: 24 } })
const isUuid = (s) => /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i.test(s || '')
const src = computed(() => avatarUrl(props.uuid || props.name, props.size))
const label = computed(() => props.name || props.uuid || '-')
</script>

<template>
  <span class="player-cell">
    <img v-if="src" :src="src" loading="lazy" :alt="label" :style="{ width: size + 'px', height: size + 'px' }" @error="(e) => (e.target.style.visibility = 'hidden')" />
    <span :class="{ mono: isUuid(label) }">{{ label }}</span>
  </span>
</template>
