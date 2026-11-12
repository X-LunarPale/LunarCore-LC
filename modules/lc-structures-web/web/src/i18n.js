import { ref } from 'vue'
import structures from './en.js'

export const locale = ref('zh_CN')
const messages = {
  ...structures,
  '知道了': 'OK', '稍后重启': 'Restart later',
  '立即重启生效': 'Restart to apply', '查看重启说明': 'Restart instructions',
  '自动重启不可用，请配置 restart-script 或从服务端面板手动重启': 'Automatic restart is unavailable. Configure restart-script or restart from the server control panel.',
  '结构设置已保存，自动重启不可用。请从服务端面板完整重启后生效，服务器会继续运行。': 'Settings saved. Automatic restart is unavailable. Fully restart from the server control panel to apply them; the server will keep running.',
  '服务器已重新启动，结构设置已生效': 'The server has restarted and the structure settings are active.',
  '服务器已重新启动，但仍有新的设置等待重启': 'The server has restarted, but newer settings are still pending.',
  '自动重启未执行，请检查重启脚本或从服务端面板手动重启': 'Automatic restart did not run. Check the restart script or restart from the server control panel.',
  '尚未确认服务器恢复，请检查服务端面板；不会重复发送重启请求': 'Server recovery has not been confirmed. Check the server control panel; no restart request will be repeated.',
  '重启已安排，正在等待服务器重新启动；页面会自动检查恢复状态': 'Restart scheduled. Waiting for the server to start again; this page will automatically check its status.',
  '重启请求结果未确认，正在检查服务器状态；不会重复发送请求': 'The restart request outcome is uncertain. Checking the server status without repeating the request.',
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
  locale.value = localStorage.getItem('lc-locale') === 'en_US' ? 'en_US' : 'zh_CN'
  document.documentElement.lang = locale.value === 'en_US' ? 'en' : 'zh-CN'
  document.documentElement.classList.toggle('dark', localStorage.getItem('lc-theme') === 'dark')
}
export function t(source, params = {}) {
  const message = locale.value === 'en_US' ? messages[source] || source : source
  return String(message).replace(/\{(\w+)\}/g, (match, key) => params[key] == null ? match : String(params[key]))
}
