// A successful POST only means queued. A different JVM start time confirms a real restart.
export function restartOutcome(ticket, status, now = Date.now()) {
  if (!ticket) return 'idle'
  if (Number.isFinite(status?.serverStartedAt) && status.serverStartedAt !== ticket.serverStartedAt)
    return status.restartRequired ? 'restarted-pending' : 'applied'
  if (now - ticket.requestedAt >= 120000) return 'timeout'
  if (now - ticket.requestedAt >= 8000 && status?.serverStartedAt === ticket.serverStartedAt && status.restartQueued === false)
    return 'cancelled'
  return 'waiting'
}
