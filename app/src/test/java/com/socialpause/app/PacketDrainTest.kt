package com.socialpause.app

import org.junit.Assert.*
import org.junit.Test

class PacketDrainTest {
    @Test fun cancellationExitsWithinOnePollEvenWithNoTraffic() {
        var polls = 0
        var running = true
        drainPackets(
            running = { running },
            poll = { polls++; if (polls == 3) running = false; PollResult.TIMEOUT },
            read = { fail("Nothing to read"); ReadResult.PACKET },
        )
        assertEquals(3, polls)
    }

    @Test fun discardsPacketsUntilCancelled() {
        var reads = 0
        var running = true
        drainPackets(
            running = { running },
            poll = { PollResult.READY },
            read = { reads++; if (reads == 5) running = false; ReadResult.PACKET },
        )
        assertEquals(5, reads)
    }

    @Test fun closedDescriptorEndsTheLoop() {
        var polls = 0
        drainPackets(running = { true }, poll = { polls++; PollResult.CLOSED }, read = { ReadResult.PACKET })
        assertEquals(1, polls)
    }

    @Test fun endOfStreamOrReadErrorEndsTheLoop() {
        drainPackets(running = { true }, poll = { PollResult.READY }, read = { ReadResult.END })
        drainPackets(running = { true }, poll = { PollResult.READY }, read = { throw java.io.IOException("closed") })
    }

    @Test fun spuriousWakeupsAreRetried() {
        var reads = 0
        drainPackets(
            running = { true },
            poll = { PollResult.READY },
            read = { reads++; if (reads < 3) ReadResult.RETRY else ReadResult.END },
        )
        assertEquals(3, reads)
    }
}
