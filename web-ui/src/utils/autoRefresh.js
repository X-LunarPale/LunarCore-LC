import { onMounted, onBeforeUnmount, watch } from 'vue'
import { socket } from '../api.js'

// Refresh visible pages after reconnects, returning to the tab, and local mutations.
export function useAutoRefresh(refresh, { seconds = () => 5, namespace, active = () => true, immediate = false } = {}) {
  let timer
  let offStatus
  let stopWatch
  let mounted = false
  let running = false
  let initialRefreshRunning = false
  let queued = false
  let wasOnline = false
  let hasConnected = false
  const available = () => mounted && !document.hidden && active()

  function clearTimer() {
    clearTimeout(timer)
    timer = undefined
  }
  function schedule(delay) {
    clearTimer()
    if (!available() || running) return
    if (delay === undefined) {
      const value = Number(seconds())
      if (value === 0) return
      delay = Math.max(2, Number.isFinite(value) && value > 0 ? value : 5) * 1000
    }
    timer = setTimeout(run, delay)
  }
  async function run(initial = false) {
    if (!available()) return
    if (running) { queued = true; return }
    clearTimer()
    running = true
    initialRefreshRunning = initial
    try { await refresh() }
    catch { /* The caller owns its visible error state. */ }
    finally {
      running = false
      initialRefreshRunning = false
      const followUp = queued
      queued = false
      schedule(followUp ? 50 : undefined)
    }
  }
  function requestRefresh() {
    if (!available()) { queued = false; clearTimer(); return }
    if (running) { queued = true; return }
    // A tab becoming visible commonly also fires focus; refresh once for that burst.
    schedule(50)
  }
  function changed(event) {
    if (!namespace || event.detail?.namespace === namespace) requestRefresh()
  }
  onMounted(() => {
    mounted = true
    hasConnected = wasOnline = socket.connected
    document.addEventListener('visibilitychange', requestRefresh)
    window.addEventListener('focus', requestRefresh)
    window.addEventListener('lc:panel-changed', changed)
    offStatus = socket.onStatus(online => {
      if (online && !wasOnline) {
        // The first socket opening can share the initial load; reconnects must queue.
        if (hasConnected || !initialRefreshRunning) requestRefresh()
        hasConnected = true
      }
      wasOnline = online
    })
    stopWatch = watch([active, seconds], ([enabled], [previouslyEnabled]) => {
      if (enabled && !previouslyEnabled) requestRefresh()
      else schedule()
    })
    if (immediate) run(true)
    else schedule()
  })
  onBeforeUnmount(() => {
    mounted = false
    queued = false
    clearTimer()
    offStatus?.()
    stopWatch?.()
    document.removeEventListener('visibilitychange', requestRefresh)
    window.removeEventListener('focus', requestRefresh)
    window.removeEventListener('lc:panel-changed', changed)
  })
}
