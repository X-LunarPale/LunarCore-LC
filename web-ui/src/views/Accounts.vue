<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { formatDateTime, formatRelative } from '../utils/format'
import PlayerCell from '../components/PlayerCell.vue'
import { t } from '../i18n'

const auth = useAuth()
const tab = ref('users')
const users = ref([])
const roles = ref([])
const perms = ref([])
const loading = ref(false)

const userDlg = reactive({ visible: false, editing: false, username: '', password: '', role: 'viewer' })
const roleDlg = reactive({ visible: false, editing: false, name: '', display: '', selected: [], custom: '' })

async function load() {
  loading.value = true
  try {
    const [u, r, p] = await Promise.all([api.get('/api/users'), api.get('/api/roles'), api.get('/api/permissions')])
    users.value = u.items
    roles.value = r.items
    perms.value = p
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const permGroups = computed(() => {
  const map = new Map()
  for (const p of perms.value) {
    if (!map.has(p.owner)) map.set(p.owner, [])
    map.get(p.owner).push(p)
  }
  return [...map.entries()].map(([owner, list]) => ({ owner, list }))
})

function openUser(u) {
  Object.assign(userDlg, u
    ? { visible: true, editing: true, username: u.username, password: '', role: u.role }
    : { visible: true, editing: false, username: '', password: '', role: 'viewer' })
}

async function saveUser() {
  try {
    if (userDlg.editing) {
      const body = { role: userDlg.role }
      if (userDlg.password) body.password = userDlg.password
      const r = await api.put(`/api/users/${encodeURIComponent(userDlg.username)}`, body)
      ElMessage.success(r.message)
    } else {
      if (!userDlg.username || !userDlg.password) return ElMessage.warning(t('请填写用户名和密码'))
      const r = await api.post('/api/users', { username: userDlg.username, password: userDlg.password, role: userDlg.role })
      ElMessage.success(r.message)
    }
    userDlg.visible = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeUser(u) {
  const ok = await ElMessageBox.confirm(t('确定删除账号「{name}」吗？', { name: u.username }), t('删除账号'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  try {
    const r = await api.del(`/api/users/${encodeURIComponent(u.username)}`)
    ElMessage.success(r.message)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function resetTotp(u) {
  const result = await ElMessageBox.prompt(t('输入当前管理员密码以重置「{name}」的两步验证。', { name: u.username }),
    t('重置两步验证'), { inputType: 'password', type: 'warning' }).catch(() => null)
  if (!result) return
  try {
    const response = await api.del(`/api/users/${encodeURIComponent(u.username)}/totp`, { password: result.value })
    ElMessage.success(response.message)
    await load()
  } catch (error) { ElMessage.error(error.message) }
}

function openRole(r) {
  const known = new Set(perms.value.map((p) => p.node))
  if (r) {
    Object.assign(roleDlg, {
      visible: true, editing: true, name: r.name, display: r.display,
      selected: r.permissions.filter((p) => known.has(p)),
      custom: r.permissions.filter((p) => !known.has(p)).join('\n')
    })
  } else {
    Object.assign(roleDlg, { visible: true, editing: false, name: '', display: '', selected: [], custom: '' })
  }
}

function toggleGroup(g, checked) {
  const nodes = g.list.map((p) => p.node)
  const set = new Set(roleDlg.selected)
  for (const n of nodes) checked ? set.add(n) : set.delete(n)
  roleDlg.selected = [...set]
}

const groupState = (g) => {
  const n = g.list.filter((p) => roleDlg.selected.includes(p.node)).length
  return { all: n === g.list.length && n > 0, some: n > 0 && n < g.list.length }
}

async function saveRole() {
  const custom = roleDlg.custom.split(/[\n,，\s]+/).map((s) => s.trim()).filter(Boolean)
  const body = { name: roleDlg.name, display: roleDlg.display, permissions: [...new Set([...roleDlg.selected, ...custom])] }
  try {
    const r = roleDlg.editing
      ? await api.put(`/api/roles/${encodeURIComponent(roleDlg.name)}`, body)
      : await api.post('/api/roles', body)
    ElMessage.success(r.message)
    roleDlg.visible = false
    load()
    auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeRole(r) {
  const ok = await ElMessageBox.confirm(t('确定删除角色「{name}」吗？', { name: r.display }), t('删除角色'), { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  try {
    const res = await api.del(`/api/roles/${encodeURIComponent(r.name)}`)
    ElMessage.success(res.message)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title"><Icon name="shield-check" :size="22" />{{ t('账号与角色') }}</h2>
        <div class="page-sub">{{ t('管理控制台访问账号、角色及权限分配。') }}</div>
      </div>
      <el-button @click="load"><Icon name="refresh-cw" :size="14" />&nbsp;{{ t('刷新') }}</el-button>
    </div>
    <div class="card account-panel" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('账号（{count}）', { count: users.length })" name="users">
          <div class="list-toolbar"><div><h3>{{ t('访问账号') }}</h3><p>{{ t('为管理人员分配角色，控制可访问的功能。') }}</p></div><el-button type="primary" @click="openUser()"><Icon name="user-plus" :size="14" />&nbsp;{{ t('新建账号') }}</el-button></div>
          <el-table :data="users" stripe>
            <el-table-column :label="t('用户名')" min-width="140">
              <template #default="{ row }"><b>{{ row.username }}</b> <el-tag v-if="row.username === auth.user?.username" size="small" type="success">{{ t('我') }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('角色')" width="130"><template #default="{ row }"><el-tag effect="plain">{{ row.roleName }}</el-tag></template></el-table-column>
            <el-table-column :label="t('绑定游戏账号')" min-width="160"><template #default="{ row }"><PlayerCell v-if="row.mcName" :name="row.mcName" :uuid="row.mcUuid" /><span v-else class="muted">{{ t('未绑定') }}</span></template></el-table-column>
            <el-table-column :label="t('两步验证')" min-width="120"><template #default="{ row }"><span>{{ t(row.totpEnabled ? '已启用' : '未启用') }}</span><el-button v-if="row.totpEnabled && auth.user?.permissions?.includes('*') && row.username !== auth.user?.username" text type="danger" size="small" @click="resetTotp(row)">{{ t('重置') }}</el-button></template></el-table-column>
            <el-table-column :label="t('最后登录')" width="150"><template #default="{ row }"><el-tooltip :content="formatDateTime(row.lastLogin)"><span>{{ formatRelative(row.lastLogin) }}</span></el-tooltip></template></el-table-column>
            <el-table-column :label="t('最后 IP')" prop="lastIp" width="140" />
            <el-table-column :label="t('创建时间')" width="170"><template #default="{ row }">{{ formatDateTime(row.created) }}</template></el-table-column>
            <el-table-column :label="t('操作')" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openUser(row)">{{ t('编辑') }}</el-button>
                <el-button size="small" type="danger" plain :disabled="row.username === auth.user?.username" @click="removeUser(row)">{{ t('删除') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="t('角色（{count}）', { count: roles.length })" name="roles">
          <div class="list-toolbar"><div><h3>{{ t('角色与权限') }}</h3><p>{{ t('按职责配置权限，供多个账号复用。') }}</p></div><el-button type="primary" @click="openRole()"><Icon name="plus" :size="14" />&nbsp;{{ t('新建角色') }}</el-button></div>
          <el-table :data="roles" stripe>
            <el-table-column :label="t('角色')" min-width="150">
              <template #default="{ row }"><b>{{ row.display }}</b> <span class="muted mono">{{ row.name }}</span> <el-tag v-if="row.builtin" size="small" type="info">{{ t('内置') }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('权限')" min-width="320">
              <template #default="{ row }">
                <el-tag v-for="p in row.permissions.slice(0, 8)" :key="p" size="small" class="ptag" effect="plain">{{ p }}</el-tag>
                <span v-if="row.permissions.length > 8" class="muted">{{ t('等 {count} 项', { count: row.permissions.length }) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('账号数')" prop="users" width="80" />
            <el-table-column :label="t('操作')" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" :disabled="row.name === 'admin'" @click="openRole(row)">{{ t('编辑') }}</el-button>
                <el-button size="small" type="danger" plain :disabled="row.builtin" @click="removeRole(row)">{{ t('删除') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="userDlg.visible" :title="userDlg.editing ? t('编辑账号 {name}', { name: userDlg.username }) : t('新建账号')" width="440px" append-to-body>
      <el-form label-position="top" class="account-form">
        <el-form-item :label="t('用户名')"><el-input v-model="userDlg.username" :disabled="userDlg.editing" :placeholder="t('字母、数字、_-.')" /></el-form-item>
        <el-form-item :label="userDlg.editing ? t('新密码') : t('密码')"><el-input v-model="userDlg.password" type="password" show-password :placeholder="userDlg.editing ? t('留空则不修改') : t('至少 8 位')" /></el-form-item>
        <el-form-item :label="t('角色')">
          <el-select v-model="userDlg.role" style="width: 100%">
            <el-option v-for="r in roles" :key="r.name" :label="t('{display}（{name}）', { display: r.display, name: r.name })" :value="r.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDlg.visible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" @click="saveUser">{{ t('保存') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDlg.visible" :title="roleDlg.editing ? t('编辑角色 {name}', { name: roleDlg.name }) : t('新建角色')" width="720px" top="6vh" append-to-body>
      <el-form label-position="top" class="role-form">
        <div class="role-identity">
          <el-form-item :label="t('角色标识')"><el-input v-model="roleDlg.name" :disabled="roleDlg.editing" :placeholder="t('小写字母，如 helper')" /></el-form-item>
          <el-form-item :label="t('显示名称')"><el-input v-model="roleDlg.display" :placeholder="t('如 助理')" /></el-form-item>
        </div>
        <el-form-item :label="t('权限节点')">
          <div class="perm-box">
            <div v-for="g in permGroups" :key="g.owner" class="perm-group">
              <el-checkbox :model-value="groupState(g).all" :indeterminate="groupState(g).some" @change="(v) => toggleGroup(g, v)">
                <b>{{ g.owner }}</b>
              </el-checkbox>
              <el-checkbox-group v-model="roleDlg.selected" class="perm-items">
                <el-checkbox v-for="p in g.list" :key="p.node" :value="p.node">
                  {{ p.description }} <span class="muted mono small">{{ p.node }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
        <p class="permission-help">{{ t('权限支持') }} <code>*</code>{{ t('、') }}<code>economy.*</code>{{ t('、') }}<code>*.view</code> {{ t('通配；以') }} <code>-</code> {{ t('开头表示显式拒绝。') }}</p>
        <el-form-item :label="t('自定义权限节点')">
          <el-input v-model="roleDlg.custom" type="textarea" :rows="3" class="mono" :placeholder="t('每行一个，例如：economy.*  或  *.view  或  -ban.manage')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDlg.visible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" @click="saveRole">{{ t('保存') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.account-panel { padding: 0 22px 12px; overflow: hidden; }
.account-panel :deep(.el-tabs__header) { margin-bottom: 0; }
.account-panel :deep(.el-tabs__item) { height: 50px; }
.account-panel :deep(.el-table td.el-table__cell) { padding-block: 10px; }
.list-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 20px 0; }
.list-toolbar h3 { margin: 0; font-size: 15px; font-weight: 600; }
.list-toolbar p { margin: 4px 0 0; font-size: 12px; color: var(--lbr-text-soft); }
.role-identity { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.role-form :deep(.el-form-item__label), .account-form :deep(.el-form-item__label) { padding-bottom: 6px; }
.ptag { margin: 2px 4px 2px 0; max-width: 100%; }
.perm-box { width: 100%; max-height: 36vh; overflow: auto; border: 1px solid var(--el-border-color); border-radius: 7px; padding: 0 14px; background: var(--el-fill-color-extra-light); }
.perm-group { padding: 10px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.perm-group:last-child { border-bottom: none; }
.perm-items { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: 2px 12px; padding-left: 22px; }
.perm-items :deep(.el-checkbox) { margin-right: 0; height: auto; min-height: 30px; white-space: normal; }
.perm-items :deep(.el-checkbox__label) { display: flex; flex-wrap: wrap; gap: 0 6px; line-height: 1.5; padding-block: 4px; overflow-wrap: anywhere; }
.permission-help { font-size: 12px; color: var(--lbr-text-soft); line-height: 1.8; margin: -2px 0 14px; }
.small { font-size: 11px; }
code { background: var(--el-fill-color-light); padding: 1px 5px; border-radius: 4px; }
@media (max-width: 600px) { .account-panel { padding: 0 12px 8px; } .list-toolbar { align-items: flex-start; gap: 10px; } .list-toolbar p { max-width: 190px; } .role-identity { grid-template-columns: 1fr; gap: 0; } .perm-items { padding-left: 8px; } }
</style>
