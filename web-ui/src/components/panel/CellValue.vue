<script setup>
import { t } from '../../i18n'
import { computed } from 'vue'
import PlayerCell from '../PlayerCell.vue'
import { formatDateTime, formatDuration, formatMoney, formatNumber, formatRelative, tagType } from '../../utils/format'
import { panelEnum } from '../../utils/panel'

// 列类型：text | number | money | datetime | bool | player | tag | duration
const props = defineProps({ column: Object, row: Object })
const v = computed(() => props.row?.[props.column.key])
const type = computed(() => (props.column.type || 'text').toLowerCase())

const isUuid = (s) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(s || ''))
const playerName = computed(() => {
  const val = v.value
  if (!isUuid(val)) return val
  // 行内若有 name / playerName 等字段优先显示名字
  const r = props.row || {}
  return r[props.column.key + 'Name'] || r.name || r.playerName || r.player || val
})
const tags = computed(() => (Array.isArray(v.value) ? v.value : v.value === null || v.value === undefined || v.value === '' ? [] : [v.value]))
const empty = computed(() => v.value === null || v.value === undefined || v.value === '')
const displayValue = computed(() => panelEnum(v.value, props.column.valueLabels))
</script>

<template>
  <span v-if="type === 'player'"><PlayerCell v-if="!empty" :name="playerName" :uuid="isUuid(v) ? v : undefined" /><span v-else class="muted">-</span></span>
  <span v-else-if="type === 'datetime'">
    <el-tooltip v-if="!empty && Number(v) > 0" :content="formatRelative(v)" placement="top"><span class="nowrap">{{ formatDateTime(v) }}</span></el-tooltip>
    <span v-else class="muted">-</span>
  </span>
  <span v-else-if="type === 'bool'">
    <el-tag v-if="!empty" :type="v === true || v === 'true' || v === 1 ? 'success' : 'info'" size="small">{{ v === true || v === 'true' || v === 1 ? t('是') : t('否') }}</el-tag>
    <span v-else class="muted">-</span>
  </span>
  <span v-else-if="type === 'tag'">
    <el-tag v-for="tag in tags" :key="tag" :type="tagType(tag)" size="small" effect="light" style="margin-right: 4px">{{ panelEnum(tag, column.valueLabels) }}</el-tag>
  </span>
  <span v-else-if="type === 'money'" class="money">{{ formatMoney(v) }}</span>
  <span v-else-if="type === 'number'">{{ formatNumber(v) }}</span>
  <span v-else-if="type === 'duration'">{{ empty ? '-' : formatDuration(v) }}</span>
  <span v-else>{{ empty ? '-' : typeof displayValue === 'object' ? JSON.stringify(displayValue) : displayValue }}</span>
</template>

<style scoped>
.money { font-variant-numeric: tabular-nums; color: #d97706; font-weight: 500; }
</style>
