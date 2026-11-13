package com.socialpause.app

import java.util.UUID

/** An established tunnel. Closing it ends protection. */
fun interface TunnelHandle { fun close() }

/** Establishes a tunnel that captures traffic of exactly [packages]; throws if it can't. */
fun interface SessionTunnel { fun establish(packages: Set<String>): TunnelHandle }

interface SessionClock {
    fun elapsedRealtime(): Long
    fun wallTime(): Long
}

/** Arranges for [SessionController.expire] to be called with [generation] at [elapsedDeadline]. */
interface ExpiryScheduler {
    fun schedule(id: String, generation: Long, elapsedDeadline: Long)
    fun cancel()
}

fun interface SessionRecords { fun write(s: RuntimeState): Boolean }

/** Platform side effects the controller asks for, but does not own. */
interface SessionHost {
    /** The session is protected; start watching the tunnel and packages for [generation]. */
    fun onActive(state: RuntimeState, generation: Long)
    /** The session reached a terminal state; the host may stop itself. */
    fun onEnded()
}

/**
 * Owns the pause session lifecycle. Not thread-safe: callers serialize every command.
 * Events carry the generation they were started for; events from a superseded generation are ignored.
 */
class SessionController(
    private val tunnel: SessionTunnel,
    private val clock: SessionClock,
    private val scheduler: ExpiryScheduler,
    private val store: SessionRecords,
    private val host: SessionHost,
    private val isInstalled: (String) -> Boolean,
    private val hasConsent: () -> Boolean,
    private val ownPackage: String,
    private val publish: (RuntimeState?) -> Unit = SessionRuntime::publish,
) {
    var current: RuntimeState? = null
        private set
    private var handle: TunnelHandle? = null
    private var generation = 0L

    /** [minutes] is 1..720, or -1 for a session that runs until stopped. */
    fun start(id: String, packages: Set<String>, minutes: Int) {
        // Make before break: an edited session keeps the previous tunnel until the new one is up.
        cancelWatchers()
        val now = clock.wallTime()
        emit(RuntimeState(id, SessionStatus.STARTING, packages, now, null, null))
        val validId = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        if (!validId || packages.isEmpty() || ownPackage in packages || (minutes != -1 && minutes !in 1..720)) {
            fail(id, packages, now, "invalid"); return
        }
        if (!hasConsent()) { fail(id, packages, now, "consent"); return }
        val end = if (minutes == -1) null else now + minutes * 60_000L
        val elapsedEnd = if (minutes == -1) null else clock.elapsedRealtime() + minutes * 60_000L
        val starting = RuntimeState(id, SessionStatus.STARTING, packages, now, end, elapsedEnd)
        emit(starting)
        if (!store.write(starting)) { fail(id, packages, now, "storage"); return }
        try {
            // Every package must resolve, or the entire session fails.
            if (!packages.all(isInstalled)) error("package unavailable")
            val previous = handle
            handle = tunnel.establish(packages)
            try { previous?.close() } catch (_: Exception) { }
        } catch (_: Exception) { fail(id, packages, now, "establish"); return }
        val token = ++generation
        val active = starting.copy(status = SessionStatus.ACTIVE)
        if (!store.write(active)) { fail(id, packages, now, "storage"); return }
        emit(active)
        if (elapsedEnd != null) scheduler.schedule(id, token, elapsedEnd)
        host.onActive(active, token)
    }

    fun stop(id: String?) {
        if (completeIfOverdue()) return
        if (current?.id == id) finish(SessionStatus.IDLE, "stopped")
    }

    /** The in-process timer for [generation] fired. */
    fun expire(generation: Long) {
        if (generation == this.generation) finish(SessionStatus.COMPLETED, "expired")
    }

    /**
     * A wake-up alarm for session [id] fired. Alarms can fire early, late, or after the session
     * was replaced, so the deadline is re-checked against the clock rather than trusted.
     */
    fun expireIfDue(id: String?) {
        if (current?.id == id) completeIfOverdue()
    }

    /** Interrupts the session if any selected package is no longer installed. */
    fun checkPackages(generation: Long) {
        if (completeIfOverdue() || generation != this.generation) return
        val packages = current?.packages ?: return
        if (!packages.all(isInstalled)) finish(SessionStatus.INTERRUPTED, "package")
    }

    fun tunnelClosed(generation: Long) {
        if (completeIfOverdue()) return
        if (generation == this.generation) finish(SessionStatus.INTERRUPTED, "reader")
    }

    fun revoked() {
        if (completeIfOverdue()) return
        finish(SessionStatus.INTERRUPTED, "revoked")
    }

    /** Ends an active session whose deadline has passed; returns true if it did. */
    private fun completeIfOverdue(): Boolean {
        val running = current ?: return false
        if (running.status != SessionStatus.ACTIVE || !isDeadlineReached(clock.elapsedRealtime(), running.elapsedDeadline)) return false
        finish(SessionStatus.COMPLETED, "expired")
        return true
    }

    /** The host is going away; a running session cannot outlive it. */
    fun destroyed() {
        val running = current
        closeTunnel()
        if (running != null && running.status in RUNNING) {
            val terminal = running.copy(status = SessionStatus.INTERRUPTED, elapsedDeadline = null, reason = "service")
            emit(terminal)
            store.write(terminal)
        }
    }

    private fun fail(id: String, packages: Set<String>, now: Long, reason: String) {
        closeTunnel()
        val failed = RuntimeState(id, SessionStatus.ERROR, packages, now, null, null, reason)
        emit(failed)
        store.write(failed)
        host.onEnded()
    }

    private fun finish(status: SessionStatus, reason: String) {
        // A session that already ended keeps its first terminal state.
        val running = current?.takeIf { it.status in RUNNING } ?: return
        closeTunnel()
        val terminal = running.copy(status = status, elapsedDeadline = null, reason = reason)
        emit(terminal)
        store.write(terminal)
        host.onEnded()
    }

    /** Invalidates every timer, alarm and watcher event of the current generation. */
    private fun cancelWatchers() {
        generation++
        scheduler.cancel()
    }

    private fun closeTunnel() {
        cancelWatchers()
        try { handle?.close() } catch (_: Exception) { }
        handle = null
    }

    private fun emit(state: RuntimeState) {
        current = state
        publish(state)
    }

    private companion object {
        val RUNNING = setOf(SessionStatus.STARTING, SessionStatus.ACTIVE, SessionStatus.STOPPING)
    }
}
