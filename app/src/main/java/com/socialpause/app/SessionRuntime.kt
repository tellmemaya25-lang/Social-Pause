package com.socialpause.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local evidence only. Never restore this from preferences. */
object SessionRuntime {
    private val mutable = MutableStateFlow<RuntimeState?>(null)
    val state = mutable.asStateFlow()
    fun publish(state: RuntimeState?) { mutable.value = state }
}

data class RuntimeState(
    val id: String,
    val status: SessionStatus,
    val packages: Set<String>,
    val startedAt: Long,
    val endsAt: Long?,
    val elapsedDeadline: Long?,
    val reason: String? = null,
)

/** Atomic single-record storage. Backup is disabled in the manifest. */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun read(): RuntimeState? {
        val id = prefs.getString("id", null) ?: return null
        val status = runCatching { SessionStatus.valueOf(prefs.getString("status", "") ?: "") }.getOrNull() ?: return null
        return RuntimeState(id, status, prefs.getStringSet("packages", emptySet())?.toSet() ?: emptySet(),
            prefs.getLong("start", 0), prefs.getLong("end", -1).takeIf { it >= 0 }, null, prefs.getString("reason", null))
    }
    fun write(s: RuntimeState): Boolean = prefs.edit().clear()
        .putString("id", s.id).putString("status", s.status.name).putStringSet("packages", s.packages.toSet())
        .putLong("start", s.startedAt).putLong("end", s.endsAt ?: -1L).putString("reason", s.reason).commit()

    fun reconcile(): RuntimeState? {
        val stored = read() ?: return null
        val live = SessionRuntime.state.value
        if (live != null && live.id == stored.id) return live
        if (stored.status !in setOf(SessionStatus.STARTING, SessionStatus.ACTIVE, SessionStatus.STOPPING)) return stored
        val status = if (stored.endsAt != null && System.currentTimeMillis() >= stored.endsAt) SessionStatus.COMPLETED else SessionStatus.INTERRUPTED
        return stored.copy(status = status, reason = "recovery").also { write(it) }
    }
}
