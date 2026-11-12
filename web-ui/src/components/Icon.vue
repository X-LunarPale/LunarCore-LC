<script setup>
import { computed } from 'vue'
import {
  Activity, AppWindow, ArrowRight, Award, BookOpen, Bot, Box, Blocks, Brain,
  CalendarCheck, ChartNoAxesCombined, CheckCheck, ChevronDown, ChevronRight,
  CircleAlert, CircleCheck, CircleUserRound, Coins, Compass, Download, Eraser, FileCog,
  FilePenLine, FileText, Filter, FolderDown, FolderUp, Gamepad2, Gavel, Globe,
  HardDrive, Hourglass, ImageUp, Inbox, Info, KeyRound, Landmark, Languages,
  Layers, LayoutDashboard, Link, ListChecks, Lock, LockKeyhole, LogOut, Map,
  MapPin, Megaphone, MessageCircle, MessageSquareWarning, Moon, Mountain, MountainSnow, Package,
  PanelLeftClose, PanelLeftOpen, Pause, Pickaxe, Play, Plus, Puzzle, RefreshCw,
  RotateCcw, RotateCw, Save, ScanSearch, ScrollText, Search, Send, Server,
  Settings2, Shield, ShieldAlert, ShieldBan, ShieldCheck, SlidersHorizontal,
  Sun, Table2, Terminal, Trash2, TriangleAlert, Undo2, User, UserCog,
  UserPlus, UserRound, Users, Vote, Workflow, Wrench, X
} from 'lucide-vue-next'

// Keep the supported panel icons explicit so the initial bundle does not include the full icon library.
const icons = {
  Activity, AppWindow, ArrowRight, Award, BookOpen, Bot, Box, Blocks, Brain,
  CalendarCheck, ChartNoAxesCombined, CheckCheck, ChevronDown, ChevronRight,
  CircleAlert, CircleCheck, CircleUserRound, Coins, Compass, Download, Eraser, FileCog,
  FilePenLine, FileText, Filter, FolderDown, FolderUp, Gamepad2, Gavel, Globe,
  HardDrive, Hourglass, ImageUp, Inbox, Info, KeyRound, Landmark, Languages,
  Layers, LayoutDashboard, Link, ListChecks, Lock, LockKeyhole, LogOut, Map,
  MapPin, Megaphone, MessageCircle, MessageSquareWarning, Moon, Mountain, MountainSnow, Package,
  PanelLeftClose, PanelLeftOpen, Pause, Pickaxe, Play, Plus, Puzzle, RefreshCw,
  RotateCcw, RotateCw, Save, ScanSearch, ScrollText, Search, Send, Server,
  Settings2, Shield, ShieldAlert, ShieldBan, ShieldCheck, SlidersHorizontal,
  Sun, Table2, Terminal, Trash2, TriangleAlert, Undo2, User, UserCog,
  UserPlus, UserRound, Users, Vote, Workflow, Wrench, X
}

const props = defineProps({
  name: { type: String, default: 'box' },
  size: { type: [Number, String], default: 18 },
  strokeWidth: { type: [Number, String], default: 2 }
})

function pascal(name) {
  return String(name || '')
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')
}

const comp = computed(() => {
  const n = props.name || 'box'
  return icons[pascal(n)] || icons[n] || icons[pascal(n) + 'Icon'] || icons.Box
})
</script>

<template>
  <component :is="comp" :size="size" :stroke-width="strokeWidth" class="lc-icon" />
</template>
