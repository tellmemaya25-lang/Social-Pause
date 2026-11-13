package com.socialpause.app

import org.junit.Assert.*
import org.junit.Test

class SessionControllerTest {
    private val id = "0b7c8a7e-4a35-4a47-9a26-6c9ad2c6f2a1"
    private val other = "5f0f7c43-1f0e-4c38-9a1e-0b1b8f2b2c11"
    private val apps = setOf("org.example.chat")

    private class FakeTunnel : SessionTunnel {
        var failNext = false
        val opened = mutableListOf<Set<String>>()
        val events = mutableListOf<String>()
        var openCount = 0
        override fun establish(packages: Set<String>): TunnelHandle {
            if (failNext) { failNext = false; throw IllegalStateException("establish failed") }
            opened += packages
            openCount++
            val n = opened.size
            events += "open$n"
            return object : TunnelHandle { override fun close() { openCount--; events += "close$n" } }
        }
    }

    private class FakeClock(var elapsed: Long = 1_000, var wall: Long = 50_000) : SessionClock {
        override fun elapsedRealtime() = elapsed
        override fun wallTime() = wall
    }

    private class FakeScheduler : ExpiryScheduler {
        var scheduled: Triple<String, Long, Long>? = null
        override fun schedule(id: String, generation: Long, elapsedDeadline: Long) { scheduled = Triple(id, generation, elapsedDeadline) }
        override fun cancel() { scheduled = null }
    }

    private class FakeStore : SessionRecords {
        var failWrites = false
        val written = mutableListOf<RuntimeState>()
        override fun write(s: RuntimeState): Boolean { if (failWrites) return false; written += s; return true }
    }

    private class FakeHost : SessionHost {
        val active = mutableListOf<Pair<RuntimeState, Long>>()
        var ended = 0
        override fun onActive(state: RuntimeState, generation: Long) { active += state to generation }
        override fun onEnded() { ended++ }
    }

    private val tunnel = FakeTunnel()
    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val store = FakeStore()
    private val host = FakeHost()
    private val published = mutableListOf<RuntimeState?>()
    private var installed = apps + "org.example.other"
    private var consent = true

    private val controller = SessionController(
        tunnel = tunnel, clock = clock, scheduler = scheduler, store = store, host = host,
        isInstalled = { it in installed }, hasConsent = { consent }, ownPackage = "com.socialpause.app",
        publish = { published += it },
    )

    private val state get() = controller.current

    @Test fun startPublishesStartingThenActive() {
        controller.start(id, apps, 30)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        assertTrue(published.any { it?.status == SessionStatus.STARTING })
        assertEquals(listOf(apps), tunnel.opened)
        assertEquals(1, tunnel.openCount)
        assertEquals(SessionStatus.ACTIVE, store.written.last().status)
        assertEquals(1, host.active.size)
        assertEquals(clock.wall + 30 * 60_000L, state?.endsAt)
        assertEquals(clock.elapsed + 30 * 60_000L, state?.elapsedDeadline)
    }

    @Test fun timedStartSchedulesExpiryAndIndefiniteDoesNot() {
        controller.start(id, apps, 15)
        assertEquals(clock.elapsed + 15 * 60_000L, scheduler.scheduled?.third)
        controller.start(other, apps, -1)
        assertNull(scheduler.scheduled)
        assertNull(state?.endsAt)
    }

    @Test fun expiryCompletesOnlyTheCurrentGeneration() {
        controller.start(id, apps, 15)
        val gen = host.active.last().second
        controller.expire(gen - 1)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        controller.expire(gen)
        assertEquals(SessionStatus.COMPLETED, state?.status)
        assertEquals(0, tunnel.openCount)
        assertEquals(1, host.ended)
    }

    @Test fun alarmExpiryRechecksDeadlineAgainstClock() {
        controller.start(id, apps, 15)
        val deadline = state!!.elapsedDeadline!!
        clock.elapsed = deadline - 1
        controller.expireIfDue(id)
        assertEquals("An early alarm must not end the pause", SessionStatus.ACTIVE, state?.status)
        clock.elapsed = deadline
        controller.expireIfDue(id)
        assertEquals(SessionStatus.COMPLETED, state?.status)
        assertEquals("expired", state?.reason)
        assertEquals(0, tunnel.openCount)
        assertEquals(1, host.ended)
    }

    @Test fun overdueAlarmCompletesWhenTheDeviceWakesLate() {
        controller.start(id, apps, 15)
        clock.elapsed = state!!.elapsedDeadline!! + 3 * 60 * 60_000L
        controller.expireIfDue(id)
        assertEquals(SessionStatus.COMPLETED, state?.status)
    }

    @Test fun staleAlarmForAnotherSessionIsIgnored() {
        controller.start(id, apps, 15)
        controller.start(other, apps, 30)
        clock.elapsed += 20 * 60_000L
        controller.expireIfDue(id)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        assertEquals(other, state?.id)
    }

    @Test fun alarmAfterSessionEndedIsIgnored() {
        controller.start(id, apps, 15)
        controller.stop(id)
        clock.elapsed += 20 * 60_000L
        controller.expireIfDue(id)
        assertEquals(SessionStatus.IDLE, state?.status)
    }

    @Test fun indefiniteSessionNeverExpires() {
        controller.start(id, apps, -1)
        clock.elapsed += 365L * 24 * 60 * 60_000L
        controller.expireIfDue(id)
        assertEquals(SessionStatus.ACTIVE, state?.status)
    }

    @Test fun anyCommandEndsAnOverdueSession() {
        controller.start(id, apps, 15)
        clock.elapsed = state!!.elapsedDeadline!!
        controller.stop(other) // unrelated command
        assertEquals(SessionStatus.COMPLETED, state?.status)
    }

    @Test fun failingAndStoppingCancelTheScheduledExpiry() {
        controller.start(id, apps, 15)
        assertNotNull(scheduler.scheduled)
        controller.revoked()
        assertNull(scheduler.scheduled)
    }

    @Test fun editEstablishesNewTunnelBeforeClosingOld() {
        controller.start(id, apps, 30)
        controller.start(other, apps + "org.example.other", 15)
        assertEquals(listOf("open1", "open2", "close1"), tunnel.events)
        assertEquals(1, tunnel.openCount)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        assertEquals(other, state?.id)
    }

    @Test fun editReplacesOldWatchersAndExpiry() {
        controller.start(id, apps, 30)
        val oldGen = host.active.last().second
        controller.start(other, apps, 15)
        assertEquals(other, scheduler.scheduled?.first)
        installed = emptySet()
        controller.checkPackages(oldGen)
        controller.tunnelClosed(oldGen)
        controller.expire(oldGen)
        assertEquals("Events from the old session must not end the new one", SessionStatus.ACTIVE, state?.status)
        assertEquals(other, state?.id)
    }

    @Test fun failedEditClosesEverythingAndErrors() {
        controller.start(id, apps, 30)
        tunnel.failNext = true
        controller.start(other, apps, 15)
        assertEquals(SessionStatus.ERROR, state?.status)
        assertEquals("establish", state?.reason)
        assertEquals(0, tunnel.openCount)
        assertNull(scheduler.scheduled)
    }

    @Test fun invalidEditClosesOldTunnel() {
        controller.start(id, apps, 30)
        controller.start(other, emptySet(), 15)
        assertEquals(SessionStatus.ERROR, state?.status)
        assertEquals(0, tunnel.openCount)
    }

    @Test fun stopPassesThroughStopping() {
        controller.start(id, apps, 30)
        published.clear()
        controller.stop(id)
        assertEquals(listOf(SessionStatus.STOPPING, SessionStatus.IDLE), published.map { it?.status })
        assertEquals(SessionStatus.IDLE, store.written.last().status)
    }

    @Test fun staleStopChangesNothing() {
        controller.start(id, apps, 30)
        controller.stop(id)
        published.clear()
        val writes = store.written.size
        controller.stop(id)
        controller.stop(other)
        assertTrue(published.isEmpty())
        assertEquals(writes, store.written.size)
        assertEquals(1, host.ended)
    }

    @Test fun stopEndsMatchingSessionOnly() {
        controller.start(id, apps, 30)
        controller.stop(other)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        controller.stop(id)
        assertEquals(SessionStatus.IDLE, state?.status)
        assertEquals("stopped", state?.reason)
        assertEquals(0, tunnel.openCount)
        assertNull(scheduler.scheduled)
        assertEquals(SessionStatus.IDLE, store.written.last().status)
    }

    @Test fun revokeAndTunnelClosureInterrupt() {
        controller.start(id, apps, 30)
        controller.revoked()
        assertEquals(SessionStatus.INTERRUPTED, state?.status)
        assertEquals("revoked", state?.reason)

        controller.start(other, apps, 30)
        val gen = host.active.last().second
        controller.tunnelClosed(gen - 1)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        controller.tunnelClosed(gen)
        assertEquals(SessionStatus.INTERRUPTED, state?.status)
        assertEquals("reader", state?.reason)
    }

    @Test fun missingSelectedPackageInterruptsCurrentGeneration() {
        controller.start(id, apps, 30)
        val gen = host.active.last().second
        installed = emptySet()
        controller.checkPackages(gen - 1)
        assertEquals(SessionStatus.ACTIVE, state?.status)
        controller.checkPackages(gen)
        assertEquals(SessionStatus.INTERRUPTED, state?.status)
        assertEquals("package", state?.reason)
    }

    @Test fun removingSelectedAppInterruptsImmediately() {
        controller.start(id, apps, 30)
        val gen = host.active.last().second
        controller.packageRemoved("org.example.chat", replacing = false, generation = gen)
        assertEquals(SessionStatus.INTERRUPTED, state?.status)
        assertEquals("package", state?.reason)
        assertEquals(0, tunnel.openCount)
    }

    @Test fun updatingSelectedAppKeepsPausing() {
        controller.start(id, apps, 30)
        val gen = host.active.last().second
        controller.packageRemoved("org.example.chat", replacing = true, generation = gen)
        assertEquals(SessionStatus.ACTIVE, state?.status)
    }

    @Test fun removingUnselectedAppDoesNothing() {
        controller.start(id, apps, 30)
        val gen = host.active.last().second
        controller.packageRemoved("org.example.other", replacing = false, generation = gen)
        assertEquals(SessionStatus.ACTIVE, state?.status)
    }

    @Test fun removalFromSupersededGenerationIsIgnored() {
        controller.start(id, apps, 30)
        val oldGen = host.active.last().second
        controller.start(other, apps, 30)
        controller.packageRemoved("org.example.chat", replacing = false, generation = oldGen)
        assertEquals(SessionStatus.ACTIVE, state?.status)
    }

    @Test fun invalidInputErrorsWithoutEstablishing() {
        listOf(
            Triple("not-a-uuid", apps, 30),
            Triple(id, emptySet<String>(), 30),
            Triple(id, setOf("com.socialpause.app"), 30),
            Triple(id, apps, 0),
            Triple(id, apps, 721),
        ).forEach { (sid, pkgs, minutes) ->
            controller.start(sid, pkgs, minutes)
            assertEquals(SessionStatus.ERROR, state?.status)
            assertEquals("invalid", state?.reason)
        }
        assertTrue(tunnel.opened.isEmpty())
    }

    @Test fun missingConsentErrors() {
        consent = false
        controller.start(id, apps, 30)
        assertEquals("consent", state?.reason)
        assertTrue(tunnel.opened.isEmpty())
    }

    @Test fun uninstalledPackageAtStartErrors() {
        installed = emptySet()
        controller.start(id, apps, 30)
        assertEquals(SessionStatus.ERROR, state?.status)
        assertEquals("establish", state?.reason)
    }

    @Test fun establishFailureErrors() {
        tunnel.failNext = true
        controller.start(id, apps, 30)
        assertEquals(SessionStatus.ERROR, state?.status)
        assertEquals("establish", state?.reason)
        assertEquals(1, host.ended)
    }

    @Test fun storeWriteFailureErrorsAndClosesTunnel() {
        store.failWrites = true
        controller.start(id, apps, 30)
        assertEquals(SessionStatus.ERROR, state?.status)
        assertEquals("storage", state?.reason)
        assertEquals(0, tunnel.openCount)
    }

    @Test fun destroyInterruptsRunningSession() {
        controller.start(id, apps, 30)
        controller.destroyed()
        assertEquals(SessionStatus.INTERRUPTED, state?.status)
        assertEquals("service", state?.reason)
        assertEquals(0, tunnel.openCount)
    }

    @Test fun destroyLeavesTerminalSessionAlone() {
        controller.start(id, apps, 30)
        controller.stop(id)
        val writes = store.written.size
        controller.destroyed()
        assertEquals(SessionStatus.IDLE, state?.status)
        assertEquals(writes, store.written.size)
    }
}
