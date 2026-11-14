<script setup>
import { computed, ref, watch } from 'vue'
import MinecraftTextPreview from '../text-preview/MinecraftTextPreview.vue'
import { chatPreview } from './chat-preview'
import { t } from '../../i18n'

const props = defineProps({ model: { type: Object, required: true }, disabled: Boolean })
const player = ref('Steve'), title = ref('&6[旅行者]'), message = ref('大家好，今天一起探险吗？'), selected = ref('')
const rows = computed(() => Array.isArray(props.model.worldAliases) ? props.model.worldAliases : [])
watch(() => rows.value.map(row => row.world), values => { if (!values.includes(selected.value)) selected.value = values[0] || '' }, { immediate: true })
const current = computed(() => rows.value.find(row => row.world === selected.value))
function add() { props.model.worldAliases = [...rows.value, { world: '', name: '', color: props.model.worldDefaultColor || '#55ffff' }] }
function remove(index) { props.model.worldAliases = rows.value.filter((_, i) => index !== i) }
const values = computed(() => ({
  world: current.value?.world || 'world', worldName: current.value?.name || current.value?.world || 'world',
  color: current.value?.color || props.model.worldDefaultColor || '#55ffff', player: player.value,
  title: title.value, message: message.value, titlePlaceholder: props.model.titlePlaceholder
}))
const chat = computed(() => chatPreview(props.model.defaultChatFormat, { ...values.value, showTitle: props.model.chatShowTitle !== false }))
const tab = computed(() => chatPreview(props.model.tabName, { ...values.value, showTitle: props.model.tabShowTitle !== false }))
</script>

<template>
  <section class="chat-appearance">
    <header><h4>{{ t('世界显示名称') }}</h4><el-button v-if="!disabled" @click="add"><Icon name="plus" :size="14" />{{ t('添加世界') }}</el-button></header>
    <div class="world-labels">
      <div class="column-labels"><span>{{ t('原始世界名') }}</span><span>{{ t('显示名称') }}</span><span>{{ t('颜色') }}</span><span /></div>
      <div v-for="(row, index) in rows" :key="index" class="world-row">
        <el-input v-model="row.world" :disabled="disabled" :aria-label="t('原始世界名')" maxlength="256" />
        <el-input v-model="row.name" :disabled="disabled" :aria-label="t('显示名称')" maxlength="64" />
        <div class="color-field"><el-color-picker :model-value="row.color" @update:model-value="row.color = $event || model.worldDefaultColor || '#55ffff'" :disabled="disabled" :aria-label="t('世界颜色')" /><el-input v-model="row.color" :disabled="disabled" :aria-label="t('颜色值')" maxlength="24" /></div>
        <el-tooltip :content="t('移除此显示设置')"><el-button class="remove" :disabled="disabled" :aria-label="t('移除此显示设置')" @click="remove(index)"><Icon name="trash-2" :size="15" /></el-button></el-tooltip>
      </div>
      <p v-if="!rows.length" class="empty">{{ t('尚未设置世界显示名称') }}</p>
    </div>
    <section class="format-preview">
      <h4>{{ t('聊天与 TAB 预览') }}</h4>
      <div class="preview-controls">
        <label>{{ t('预览世界') }}<el-select v-model="selected" :aria-label="t('预览世界')"><el-option v-for="(row, index) in rows" :key="index" :value="row.world" :label="`${row.name || row.world} (${row.world})`" /></el-select></label>
        <label>{{ t('预览玩家') }}<el-input v-model="player" maxlength="64" /></label>
        <label>{{ t('预览称号') }}<el-input v-model="title" maxlength="256" /></label>
        <label>{{ t('预览消息') }}<el-input v-model="message" maxlength="256" /></label>
      </div>
      <MinecraftTextPreview :text="chat" :placeholder-api="false" :label="'聊天预览'" />
      <MinecraftTextPreview v-if="model.tabEnabled" :text="tab" :placeholder-api="false" :label="'TAB 玩家名称预览'" />
    </section>
  </section>
</template>

<style scoped>
.chat-appearance{margin-top:24px;padding-top:24px;border-top:1px solid var(--el-border-color-light);min-width:0}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}
h4{font-size:13px;margin:0;font-weight:600}
.column-labels,.world-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr) minmax(145px,.8fr) 36px;gap:12px;align-items:center}
.column-labels{font-size:12px;color:var(--lc-text-soft);padding-bottom:8px}
.world-row+.world-row{margin-top:10px}
.color-field{display:flex;gap:8px;align-items:center;min-width:0}
.color-field :deep(.el-color-picker){flex:0 0 auto}
.color-field :deep(.el-input){min-width:0}
.remove{padding:8px;width:36px;height:36px}
.empty{font-size:12px;color:var(--lc-text-soft)}
.format-preview{margin-top:24px;padding-top:20px;border-top:1px solid var(--el-border-color-lighter)}
.preview-controls{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 20px;margin-top:14px}
.preview-controls label{display:flex;flex-direction:column;gap:6px;font-size:12px;min-width:0}
.preview-controls :deep(.el-select){width:100%}
@container (max-width:620px){.column-labels{display:none}.world-row{grid-template-columns:minmax(0,1fr) 36px;padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}.world-row>.el-input:first-child{grid-column:1}.world-row>.el-input:nth-child(2){grid-column:1}.color-field{grid-column:1;grid-row:3}.remove{grid-column:2;grid-row:1}.preview-controls{grid-template-columns:minmax(0,1fr)}}
</style>
