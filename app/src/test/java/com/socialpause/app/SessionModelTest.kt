package com.socialpause.app

import org.junit.Assert.*
import org.junit.Test

class SessionModelTest {
    @Test fun customDurationBoundsAndInvalidInput() {
        assertEquals(1, parseCustomDuration("1")?.minutes)
        assertEquals(720, parseCustomDuration("720")?.minutes)
        listOf("", "0", "721", "-1", "1.5", " 15", "99999999999999999999").forEach {
            assertNull("Expected invalid: $it", parseCustomDuration(it))
        }
    }

    private val record = SessionRecord("current", setOf("org.example"), 100, 200, SessionStatus.ACTIVE)

    @Test fun storedActiveRequiresMatchingLiveEvidence() {
        assertEquals(SessionStatus.INTERRUPTED, reconcileSession(record, null, 150))
        assertEquals(SessionStatus.INTERRUPTED, reconcileSession(record, LiveSession("old", SessionStatus.ACTIVE), 150))
        assertEquals(SessionStatus.ACTIVE, reconcileSession(record, LiveSession("current", SessionStatus.ACTIVE), 150))
        assertEquals(SessionStatus.STARTING, reconcileSession(record, LiveSession("current", SessionStatus.STARTING), 150))
    }

    @Test fun overdueMissingServiceIsCompleted() {
        assertEquals(SessionStatus.COMPLETED, reconcileSession(record, null, 200))
        assertEquals(SessionStatus.INTERRUPTED, reconcileSession(record.copy(endsAtWallMillis = null), null, 1000))
    }

    @Test fun terminalRecordIsNotRevivedByStaleLiveStatus() {
        assertEquals(SessionStatus.ERROR, reconcileSession(record.copy(status = SessionStatus.ERROR), LiveSession("current", SessionStatus.ACTIVE), 150))
    }

    @Test fun expiryUsesElapsedDeadline() {
        assertFalse(isDeadlineReached(999, 1000))
        assertTrue(isDeadlineReached(1000, 1000))
        assertFalse(isDeadlineReached(Long.MAX_VALUE, null))
    }
}
