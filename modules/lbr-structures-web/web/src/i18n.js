import { ref } from 'vue'
import structures from './en.js'

export const locale = ref('zh_CN')
const messages = {
  ...structures,
  '等待重启': 'Restart pending', '重新读取': 'Refresh', '取消': 'Cancel',
  '结构生成插件不可用': 'Structure generation plugin unavailable',
  '从独立插件仓库下载 LunarCoreStructures.jar，放入 plugins 文件夹后完整重启。': 'Download LunarCoreStructures.jar from its separate repository, place it in plugins/, then fully restart.',
  '结构生成插件未安装或未启用': 'Structure generation plugin is missing or disabled',
  '结构生成插件管理接口不兼容，请更新插件与桥接模块': 'Incompatible management API; update the generation plugin and bridge module',
  '结构生成插件操作失败': 'The generation plugin operation failed',
  '结构生成插件返回了无效数据': 'The generation plugin returned invalid data',
  '结构管理模块已停用': 'The management module is disabled',
  '结构配置读写失败，请检查服务器日志': 'Cannot read or write configuration; check server logs',
  '服务器重启已安排': 'A server restart is already scheduled',
  '结构管理页面地址无效': 'Invalid management page address',
  '禁止请求外部接口地址': 'External API addresses are not allowed',
  '登录已失效，请在管理面板重新登录': 'Session expired; sign in again in the management panel',
  '结构管理请求失败': 'Management request failed',
  '请求超时，请刷新核对后再重试': 'Request timed out; refresh to check the result before retrying',
  '无法连接结构管理接口': 'Cannot connect to the management API'
}
export function syncPreferences() {
  locale.value = localStorage.getItem('lbr-locale') === 'en_US' ? 'en_US' : 'zh_CN'
  document.documentElement.lang = locale.value === 'en_US' ? 'en' : 'zh-CN'
  document.documentElement.classList.toggle('dark', localStorage.getItem('lbr-theme') === 'dark')
}
export function t(source, params = {}) {
  const message = locale.value === 'en_US' ? messages[source] || source : source
  return String(message).replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
}
