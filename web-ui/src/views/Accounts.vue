<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAuth } from '../stores/auth'
import { formatDateTime, formatRelative } from '../utils/format'
import PlayerCell from '../components/PlayerCell.vue'
import { requireSuccess } from '../utils/panel'
import { t } from '../i18n'

const auth = useAuth()
const tab = ref('users')
const users = ref([])
const roles = ref([])
const perms = ref([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const busy = ref('')
const permissionQuery = ref('')

const userDlg = reactive({ visible: false, editing: false, username: '', password: '', role: 'viewer' })
const roleDlg = reactive({ visible: false, editing: false, name: '', display: '', selected: [], custom: '' })

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const [u, r, p] = await Promise.all([api.get('/api/users'), api.get('/api/roles'), api.get('/api/permissions')])
    requireSuccess(u); requireSuccess(r); requireSuccess(p)
    if (!Array.isArray(u?.items) || !Array.isArray(r?.items) || !Array.isArray(p))
      throw new Error(t('账号列表返回格式错误'))
    users.value = u.items
    roles.value = r.items
    perms.value = p
    loaded.value = true
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const permGroups = computed(() => {
  const map = new Map()
  for (const p of perms.value) {
    const query = permissionQuery.value.trim().toLowerCase()
    if (query && !`${p.owner} ${p.node} ${p.description}`.toLowerCase().includes(query)) continue
    if (!map.has(p.owner)) map.set(p.owner, [])
    map.get(p.owner).push(p)
  }
  return [...map.entries()].map(([owner, list]) => ({ owner, list }))
})

function openUser(u) {
  if (busy.value || loading.value) return
  Object.assign(userDlg, u
    ? { visible: true, editing: true, username: u.username, password: '', role: u.role }
    : { visible: true, editing: false, username: '', password: '', role: 'viewer' })
}

async function saveUser() {
  if (busy.value || loading.value) return
  if (!userDlg.editing && (!userDlg.username.trim() || !userDlg.password))
    return ElMessage.warning(t('请填写用户名和密码'))
  busy.value = 'user'
  try {
    if (userDlg.editing) {
      const body = { role: userDlg.role }
      if (userDlg.password) body.password = userDlg.password
      const r = requireSuccess(await api.put(`/api/users/${encodeURIComponent(userDlg.username)}`, body))
      ElMessage.success(r.message)
    } else {
      const r = requireSuccess(await api.post('/api/users', { username: userDlg.username.trim(), password: userDlg.password, role: userDlg.role }))
      ElMessage.success(r.message)
    }
    userDlg.visible = false
    userDlg.password = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { busy.value = '' }
}

async function removeUser(u) {
  if (busy.value || loading.value || u.username === auth.user?.username) return
  busy.value = 'delete-user'
  try {
    const ok = await ElMessageBox.confirm(t('确定删除账号「{name}」吗？', { name: u.username }), t('删除账号'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
    const r = requireSuccess(await api.del(`/api/users/${encodeURIComponent(u.username)}`))
    ElMessage.success(r.message)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { busy.value = '' }
}

async function resetTotp(u) {
  if (busy.value || loading.value) return
  busy.value = 'totp'
  try {
    const result = await ElMessageBox.prompt(t('输入当前管理员密码以重置「{name}」的两步验证。', { name: u.username }),
      t('重置两步验证'), { inputType: 'password', type: 'warning' }).catch(() => null)
    if (!result) return
    const response = requireSuccess(await api.del(`/api/users/${encodeURIComponent(u.username)}/totp`, { password: result.value }))
    ElMessage.success(response.message)
    await load()
  } catch (error) { ElMessage.error(error.message) }
  finally { busy.value = '' }
}

function openRole(r) {
  if (busy.value || loading.value) return
  permissionQuery.value = ''
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
  if (busy.value || loading.value) return
  if (!roleDlg.name.trim() || !roleDlg.display.trim()) return ElMessage.warning(t('请填写角色标识和显示名称'))
  const custom = roleDlg.custom.split(/[\n,，\s]+/).map((s) => s.trim()).filter(Boolean)
  const body = { name: roleDlg.name.trim(), display: roleDlg.display.trim(), permissions: [...new Set([...roleDlg.selected, ...custom])] }
  busy.value = 'role'
  try {
    const r = requireSuccess(await (roleDlg.editing
      ? api.put(`/api/roles/${encodeURIComponent(roleDlg.name)}`, body)
      : api.post('/api/roles', body)))
    ElMessage.success(r.message)
    roleDlg.visible = false
    await load()
    await auth.fetchMe()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { busy.value = '' }
}

async function removeRole(r) {
  if (busy.value || loading.value || r.builtin) return
  busy.value = 'delete-role'
  try {
    const ok = await ElMessageBox.confirm(t('确定删除角色「{name}」吗？', { name: r.display }), t('删除角色'), { type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
    const res = requireSuccess(await api.del(`/api/roles/${encodeURIComponent(r.name)}`))
    ElMessage.success(res.message)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { busy.value = '' }
}

onMounted(load)
</script>

<template>
  <div class="page accounts-page">
    <header class="page-header">
      <h1 class="page-title">{{ t('账号与角色') }}</h1>
      <div class="page-actions">
        <el-tooltip :content="t('刷新')"><el-button class="refresh-button" :aria-label="t('刷新')" :loading="loading" :disabled="!!busy" @click="load"><Icon name="refresh-cw" :size="15" /></el-button></el-tooltip>
        <el-button v-if="tab === 'users'" type="primary" :disabled="!!busy || loading" @click="openUser()"><Icon name="user-plus" :size="14" />{{ t('新建账号') }}</el-button>
        <el-button v-else type="primary" :disabled="!!busy || loading" @click="openRole()"><Icon name="plus" :size="14" />{{ t('新建角色') }}</el-button>
      </div>
    </header>
    <el-alert v-if="error" :title="t('账号列表加载失败')" :description="error" type="error" show-icon :closable="false" />
    <div class="account-panel" v-loading="loading && !loaded">
      <el-tabs v-model="tab">
        <el-tab-pane :label="t('账号（{count}）', { count: users.length })" name="users">
          <el-table :data="users" class="account-table">
            <el-table-column type="expand" width="38"><template #default="{ row }"><dl class="account-details"><div><dt>{{ t('最后 IP') }}</dt><dd>{{ row.lastIp || '-' }}</dd></div><div><dt>{{ t('创建时间') }}</dt><dd>{{ formatDateTime(row.created) }}</dd></div></dl></template></el-table-column>
            <el-table-column :label="t('用户名')" min-width="140">
              <template #default="{ row }"><b>{{ row.username }}</b> <el-tag v-if="row.username === auth.user?.username" size="small" type="success">{{ t('我') }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('角色')" width="130"><template #default="{ row }"><el-tag effect="plain">{{ row.roleName }}</el-tag></template></el-table-column>
            <el-table-column :label="t('绑定游戏账号')" min-width="160"><template #default="{ row }"><PlayerCell v-if="row.mcName" :name="row.mcName" :uuid="row.mcUuid" /><span v-else class="muted">{{ t('未绑定') }}</span></template></el-table-column>
            <el-table-column :label="t('两步验证')" min-width="120"><template #default="{ row }"><span>{{ t(row.totpEnabled ? '已启用' : '未启用') }}</span><el-button v-if="row.totpEnabled && auth.user?.permissions?.includes('*') && row.username !== auth.user?.username" text type="danger" size="small" :disabled="!!busy" @click="resetTotp(row)">{{ t('重置') }}</el-button></template></el-table-column>
            <el-table-column :label="t('最后登录')" width="150"><template #default="{ row }"><el-tooltip :content="formatDateTime(row.lastLogin)"><span>{{ formatRelative(row.lastLogin) }}</span></el-tooltip></template></el-table-column>
            <el-table-column :label="t('操作')" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" :disabled="!!busy || loading" @click="openUser(row)">{{ t('编辑') }}</el-button>
                <el-button size="small" type="danger" plain :disabled="!!busy || loading || row.username === auth.user?.username" @click="removeUser(row)">{{ t('删除') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="mobile-accounts">
            <article v-for="user in users" :key="user.username" class="account-row">
              <div class="account-row-heading"><strong>{{ user.username }}</strong><el-tag v-if="user.username === auth.user?.username" size="small" type="success">{{ t('我') }}</el-tag><span class="row-role">{{ user.roleName }}</span></div>
              <div class="mobile-user-meta"><span>{{ t('两步验证') }}: {{ t(user.totpEnabled ? '已启用' : '未启用') }}</span><PlayerCell v-if="user.mcName" :name="user.mcName" :uuid="user.mcUuid" /></div>
              <details class="mobile-user-details"><summary>{{ t('账号信息') }}</summary><dl><div><dt>{{ t('最后登录') }}</dt><dd>{{ formatDateTime(user.lastLogin) }}</dd></div><div><dt>{{ t('最后 IP') }}</dt><dd>{{ user.lastIp || '-' }}</dd></div><div><dt>{{ t('创建时间') }}</dt><dd>{{ formatDateTime(user.created) }}</dd></div></dl></details>
              <div class="mobile-account-actions"><el-button size="small" :disabled="!!busy || loading" @click="openUser(user)">{{ t('编辑') }}</el-button><el-button size="small" type="danger" plain :disabled="!!busy || loading || user.username === auth.user?.username" @click="removeUser(user)">{{ t('删除') }}</el-button><el-button v-if="user.totpEnabled && auth.user?.permissions?.includes('*') && user.username !== auth.user?.username" size="small" text type="danger" :disabled="!!busy" @click="resetTotp(user)">{{ t('重置两步验证') }}</el-button></div>
            </article>
            <el-empty v-if="loaded && !users.length" :description="t('暂无账号')" />
          </div>
        </el-tab-pane>
        <el-tab-pane :label="t('角色（{count}）', { count: roles.length })" name="roles" lazy>
          <el-table :data="roles" class="account-table">
            <el-table-column :label="t('角色')" min-width="150">
              <template #default="{ row }"><b>{{ row.display }}</b> <span class="muted mono">{{ row.name }}</span> <el-tag v-if="row.builtin" size="small" type="info">{{ t('内置') }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('权限')" min-width="320">
              <template #default="{ row }">
                <el-tag v-for="p in row.permissions.slice(0, 3)" :key="p" size="small" class="ptag" effect="plain">{{ p }}</el-tag>
                <el-popover v-if="row.permissions.length > 3" trigger="click" :width="320"><template #reference><el-button link>{{ t('等 {count} 项', { count: row.permissions.length }) }}</el-button></template><div class="all-permissions"><code v-for="p in row.permissions" :key="p">{{ p }}</code></div></el-popover>
              </template>
            </el-table-column>
            <el-table-column :label="t('账号数')" prop="users" width="80" />
            <el-table-column :label="t('操作')" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" :disabled="!!busy || loading || row.name === 'admin'" @click="openRole(row)">{{ t('编辑') }}</el-button>
                <el-button size="small" type="danger" plain :disabled="!!busy || loading || row.builtin" @click="removeRole(row)">{{ t('删除') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="mobile-accounts">
            <article v-for="role in roles" :key="role.name" class="account-row">
              <div class="account-row-heading"><strong>{{ role.display }}</strong><el-tag v-if="role.builtin" size="small" type="info">{{ t('内置') }}</el-tag><span class="row-role">{{ t('账号数') }} {{ role.users }}</span></div>
              <div class="mobile-user-meta"><code>{{ role.name }}</code></div>
              <details class="mobile-user-details"><summary>{{ t('权限') }} ({{ role.permissions.length }})</summary><div class="all-permissions"><code v-for="permission in role.permissions" :key="permission">{{ permission }}</code></div></details>
              <div class="mobile-account-actions"><el-button size="small" :disabled="!!busy || loading || role.name === 'admin'" @click="openRole(role)">{{ t('编辑') }}</el-button><el-button size="small" type="danger" plain :disabled="!!busy || loading || role.builtin" @click="removeRole(role)">{{ t('删除') }}</el-button></div>
            </article>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="userDlg.visible" :title="userDlg.editing ? t('编辑账号 {name}', { name: userDlg.username }) : t('新建账号')" width="440px" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" append-to-body>
      <el-form label-position="top" class="account-form" :disabled="!!busy" @submit.prevent="saveUser">
        <el-form-item :label="t('用户名')"><el-input v-model="userDlg.username" :disabled="userDlg.editing" :placeholder="t('字母、数字、_-.')" /></el-form-item>
        <el-form-item :label="userDlg.editing ? t('新密码') : t('密码')"><el-input v-model="userDlg.password" type="password" show-password :placeholder="userDlg.editing ? t('留空则不修改') : t('至少 8 位')" /></el-form-item>
        <el-form-item :label="t('角色')">
          <el-select v-model="userDlg.role" style="width: 100%">
            <el-option v-for="r in roles" :key="r.name" :label="t('{display}（{name}）', { display: r.display, name: r.name })" :value="r.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="!!busy" @click="userDlg.visible = false; userDlg.password = ''">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="busy === 'user'" :disabled="!!busy && busy !== 'user'" @click="saveUser">{{ t('保存') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDlg.visible" :title="roleDlg.editing ? t('编辑角色 {name}', { name: roleDlg.name }) : t('新建角色')" width="720px" top="6vh" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" append-to-body>
      <el-form label-position="top" class="role-form" :disabled="!!busy" @submit.prevent="saveRole">
        <div class="role-identity">
          <el-form-item :label="t('角色标识')"><el-input v-model="roleDlg.name" :disabled="roleDlg.editing" :placeholder="t('小写字母，如 helper')" /></el-form-item>
          <el-form-item :label="t('显示名称')"><el-input v-model="roleDlg.display" :placeholder="t('如 助理')" /></el-form-item>
        </div>
        <el-form-item :label="t('权限节点')">
          <div class="perm-box">
            <div class="permission-search"><el-input v-model="permissionQuery" clearable :placeholder="t('搜索权限')" :aria-label="t('搜索权限')"><template #prefix><Icon name="search" :size="14" /></template></el-input><span>{{ t('已选择 {count} 项', { count: roleDlg.selected.length }) }}</span></div>
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
            <el-empty v-if="!permGroups.length" :description="t('没有找到权限')" :image-size="60" />
          </div>
        </el-form-item>
        <el-form-item :label="t('自定义权限节点')">
          <el-input v-model="roleDlg.custom" type="textarea" :rows="3" class="mono" :placeholder="t('每行一个，例如：economy.*  或  *.view  或  -ban.manage')" />
        </el-form-item>
        <details class="permission-help"><summary>{{ t('权限支持') }}</summary><p><code>*</code>{{ t('、') }}<code>economy.*</code>{{ t('、') }}<code>*.view</code> {{ t('通配；以') }} <code>-</code> {{ t('开头表示显式拒绝。') }}</p></details>
      </el-form>
      <template #footer>
        <el-button :disabled="!!busy" @click="roleDlg.visible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="busy === 'role'" :disabled="!!busy && busy !== 'role'" @click="saveRole">{{ t('保存') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.accounts-page { min-width: 0; }
.mobile-accounts { display: none; }
.account-panel { min-width: 0; overflow: hidden; }
.accounts-page > .el-alert { margin-bottom: 16px; }
.refresh-button { width: 34px; padding: 0; }
.account-panel :deep(.el-table td.el-table__cell) { padding-block: 10px; }
.role-identity { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.role-form :deep(.el-form-item__label), .account-form :deep(.el-form-item__label) { padding-bottom: 6px; }
.ptag { margin: 2px 4px 2px 0; max-width: 100%; }
.perm-box { width: 100%; max-height: 36vh; overflow: auto; border: 1px solid var(--el-border-color); border-radius: 7px; padding: 0 14px; background: var(--el-fill-color-extra-light); }
.perm-group { padding: 10px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.perm-group:last-child { border-bottom: none; }
.permission-search { display: flex; align-items: center; gap: 14px; position: sticky; top: 0; z-index: 2; padding: 12px 0; background: var(--el-fill-color-extra-light); border-bottom: 1px solid var(--el-border-color-light); }.permission-search .el-input { flex: 1; min-width: 0; }.permission-search > span { font-size: 12px; color: var(--lc-text-soft); white-space: nowrap; }
.account-details { display: flex; flex-wrap: wrap; gap: 16px 40px; margin: 0; padding: 14px 32px; font-size: 12px; }.account-details dt { color: var(--lc-text-soft); margin-bottom: 4px; }.account-details dd { margin: 0; overflow-wrap: anywhere; }.all-permissions { display: flex; flex-wrap: wrap; gap: 6px; max-height: 280px; overflow: auto; font-size: 12px; }
.perm-items { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: 2px 12px; padding-left: 22px; }
.perm-items :deep(.el-checkbox) { margin-right: 0; height: auto; min-height: 30px; white-space: normal; }
.perm-items :deep(.el-checkbox__label) { display: flex; flex-wrap: wrap; gap: 0 6px; line-height: 1.5; padding-block: 4px; overflow-wrap: anywhere; }
.permission-help { font-size: 12px; color: var(--lc-text-soft); line-height: 1.8; }
.permission-help summary { width: fit-content; cursor: pointer; }
.permission-help p { margin: 8px 0 0; }
.small { font-size: 11px; }
code { background: var(--el-fill-color-light); padding: 1px 5px; border-radius: 4px; }
@media (max-width: 650px) {
  .account-table { display: none; }.mobile-accounts { display: block; border-top: 1px solid var(--el-border-color); }
  .account-row { padding: 16px 0; border-bottom: 1px solid var(--el-border-color-light); }
  .account-row-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; font-size: 13px; }.account-row-heading strong { overflow-wrap: anywhere; }.row-role { margin-left: auto; color: var(--lc-text-soft); font-size: 12px; }
  .mobile-user-meta { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 10px; font-size: 12px; color: var(--lc-text-soft); }
  .mobile-user-details { margin-top: 12px; font-size: 12px; color: var(--lc-text-soft); }.mobile-user-details summary { cursor: pointer; width: fit-content; }.mobile-user-details dl { margin: 10px 0 0; display: grid; gap: 8px; }.mobile-user-details dl > div { display: flex; gap: 12px; }.mobile-user-details dt { flex: 0 0 72px; }.mobile-user-details dd { margin: 0; overflow-wrap: anywhere; }.mobile-user-details .all-permissions { margin-top: 10px; }
  .mobile-account-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }.mobile-account-actions .el-button { margin-left: 0; }
  .role-identity { grid-template-columns: 1fr; gap: 0; }.perm-items { padding-left: 8px; }.permission-search { flex-wrap: wrap; gap: 8px; }.permission-search > span { flex-basis: 100%; }.account-details { padding-inline: 16px; }
}
</style>
