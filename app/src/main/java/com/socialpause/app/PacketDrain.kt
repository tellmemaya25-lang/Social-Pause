package com.socialpause.app

enum class PollResult { READY, TIMEOUT, CLOSED }
enum class ReadResult { PACKET, RETRY, END }

/**
 * Discards tunnel packets until cancelled or the descriptor closes. [poll] must time out, so a
 * cancelled reader exits within one poll interval instead of blocking on a dead descriptor.
 * Any exception from [poll] or [read] ends the loop; the caller decides what that means.
 */
fun drainPackets(running: () -> Boolean, poll: () -> PollResult, read: () -> ReadResult) {
    try {
        while (running()) {
            when (poll()) {
                PollResult.TIMEOUT -> continue
                PollResult.CLOSED -> return
                PollResult.READY -> if (read() == ReadResult.END) return
            }
        }
    } catch (_: Exception) { }
}
