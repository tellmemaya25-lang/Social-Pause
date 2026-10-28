package com.socialpause.app

/** Validated duration. A null duration is represented by Indefinite, never by an invalid number. */
sealed interface SessionDuration {
    class Timed private constructor(val minutes: Int) : SessionDuration {
        companion object {
            fun create(minutes: Int): Timed? = if (minutes in 1..720) Timed(minutes) else null
        }
    }
    data object Indefinite : SessionDuration
}

/** Only plain decimal digits are accepted, without clamping or integer overflow. */
fun parseCustomDuration(input: String): SessionDuration.Timed? {
    if (input.isEmpty() || input.any { it !in '0'..'9' }) return null
    return input.toIntOrNull()?.let { SessionDuration.Timed.create(it) }
}

enum class SessionStatus { IDLE, STARTING, ACTIVE, STOPPING, COMPLETED, INTERRUPTED, ERROR }

data class SessionRecord(
    val id: String,
    val packages: Set<String>,
    val startedAtWallMillis: Long,
    val endsAtWallMillis: Long?,
    val status: SessionStatus,
)

/** In-memory service evidence; this must never be restored from persistent storage. */
data class LiveSession(val id: String, val status: SessionStatus)

/** A stored record is never sufficient evidence of a running tunnel. */
fun reconcileSession(record: SessionRecord, live: LiveSession?, nowWallMillis: Long): SessionStatus {
    if (record.status != SessionStatus.STARTING && record.status != SessionStatus.ACTIVE && record.status != SessionStatus.STOPPING) {
        return record.status
    }
    if (live?.id == record.id && live.status in setOf(SessionStatus.STARTING, SessionStatus.ACTIVE, SessionStatus.STOPPING)) {
        return live.status
    }
    return if (record.endsAtWallMillis != null && nowWallMillis >= record.endsAtWallMillis) {
        SessionStatus.COMPLETED
    } else {
        SessionStatus.INTERRUPTED
    }
}

/** During a live session elapsed time, not wall time, determines expiry. */
fun isDeadlineReached(elapsedNowMillis: Long, elapsedDeadlineMillis: Long?): Boolean =
    elapsedDeadlineMillis != null && elapsedNowMillis >= elapsedDeadlineMillis
