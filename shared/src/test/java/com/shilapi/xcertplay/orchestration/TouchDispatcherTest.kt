package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TouchDispatcherTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.add(command)
        }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Fixture(capacity: Int = 32) {
        val worker = Worker()
        var session = Any()
        var token = Any()
        var now = 0L
        val sent = mutableListOf<List<AirPlayContact>>()
        val sentSessions = mutableListOf<Any>()
        val sentTokens = mutableListOf<Any>()
        val diagnostics = mutableListOf<String>()
        var duringSend: (() -> Unit)? = null
        var failSend = false
        val dispatcher = TouchDispatcher(
            executor = worker,
            isCurrent = { s: Any, t: Any -> s === session && t === token },
            send = { s, t, contacts ->
                sent.add(contacts)
                sentSessions.add(s)
                sentTokens.add(t)
                duringSend?.invoke()
                if (failSend) throw IllegalStateException("transport failure")
                true
            },
            diagnostic = diagnostics::add,
            nanoTime = { now },
            capacity = capacity,
        )
        fun offer(x: Double, down: Boolean = true): Boolean =
            dispatcher.offer(session, token, listOf(AirPlayContact(0, x, 0.5, down)))
    }

    @Test fun blockedExecutorRetainsPressLatestMotionAndFinalRelease() {
        val f = Fixture()
        assertTrue(f.offer(0.0))
        repeat(1_000) { assertTrue(f.offer((it + 1) / 1_000.0)) }
        assertTrue(f.offer(1.0, down = false))
        assertEquals(1, f.worker.tasks.size)
        f.worker.run()
        assertEquals(listOf(0.0, 1.0, 1.0), f.sent.map { it.single().x })
        assertEquals(listOf(true, true, false), f.sent.map { it.single().down })
    }

    @Test fun motionArrivingDuringBlockedWriteIsCoalescedWithoutReplacingPress() {
        val f = Fixture()
        f.duringSend = {
            f.duringSend = null
            repeat(1_000) { f.offer(it / 1_000.0) }
            f.offer(0.999, down = false)
        }
        f.offer(0.1)
        f.worker.run()
        assertEquals(listOf(0.1, 0.999, 0.999), f.sent.map { it.single().x })
        assertFalse(f.sent.last().single().down)
    }

    @Test fun consecutiveGesturesAndFinalMoveAreNotMerged() {
        val f = Fixture()
        repeat(3) { gesture ->
            f.offer(gesture / 10.0)
            f.offer((gesture + 1) / 10.0)
            f.offer((gesture + 2) / 10.0)
            f.offer((gesture + 2) / 10.0, down = false)
        }
        f.worker.run()
        assertEquals(listOf(0.0, 0.2, 0.2, 0.1, 0.3, 0.3, 0.2, 0.4, 0.4),
            f.sent.map { it.single().x })
        assertEquals(3, f.sent.count { !it.single().down })
    }

    @Test fun addedRemovedAndChangedContactIdsRemainBoundaries() {
        val f = Fixture()
        val first = AirPlayContact(0, 0.1, 0.2, true)
        val second = AirPlayContact(1, 0.8, 0.2, true)
        val reports = listOf(
            listOf(first),
            listOf(first, second),
            listOf(first, second.copy(x = 0.7)),
            listOf(first, second.copy(x = 0.6)),
            listOf(first.copy(down = false), second.copy(x = 0.6)),
            listOf(second.copy(x = 0.6)),
            emptyList(),
        )
        reports.forEach { f.dispatcher.offer(f.session, f.token, it) }
        f.worker.run()
        assertEquals(reports.filterIndexed { index, _ -> index != 2 }, f.sent)
    }

    @Test fun sourceListMutationCannotChangeQueuedContactState() {
        val f = Fixture()
        val contacts = mutableListOf(AirPlayContact(0, 0.2, 0.3, true))
        f.dispatcher.offer(f.session, f.token, contacts)
        contacts.clear()
        f.worker.run()
        assertEquals(1, f.sent.single().size)
    }

    @Test fun replacementSessionDropsAllOldPendingInput() {
        val f = Fixture()
        f.offer(0.1)
        f.offer(0.2)
        f.offer(0.2, down = false)
        f.session = Any()
        f.token = Any()
        f.offer(0.7)
        f.offer(0.7, down = false)
        f.worker.run()
        assertEquals(listOf(0.7, 0.7), f.sent.map { it.single().x })
        assertTrue(f.sentSessions.all { it === f.session })
        assertTrue(f.sentTokens.all { it === f.token })
    }

    @Test fun replacedMainStreamTokenIsRecheckedBeforeSending() {
        val f = Fixture()
        f.offer(0.1)
        f.token = Any()
        f.worker.run()
        assertTrue(f.sent.isEmpty())
        f.offer(0.2)
        f.offer(0.2, down = false)
        f.worker.run()
        assertEquals(listOf(0.2, 0.2), f.sent.map { it.single().x })
    }

    @Test fun invalidationDuringFirstWritePreventsLaterQueuedMotion() {
        val f = Fixture()
        f.duringSend = { f.token = Any() }
        f.offer(0.1)
        f.offer(0.2)
        f.offer(0.2, down = false)
        f.worker.run()
        assertEquals(listOf(0.1), f.sent.map { it.single().x })
    }

    @Test fun overflowCancelsDeliveredContactAndWaitsForPhysicalRelease() {
        val f = Fixture(capacity = 4)
        f.offer(0.1)
        f.worker.run()
        f.offer(0.2)
        f.offer(0.2, down = false)
        f.offer(0.3)
        f.offer(0.3, down = false)
        assertFalse(f.offer(0.4))
        repeat(1_000) { assertFalse(f.offer(0.5)) }
        f.worker.run()
        assertEquals(listOf(0.1, 0.1), f.sent.map { it.single().x })
        assertFalse(f.sent.last().single().down)
        assertFalse(f.offer(0.5, down = false))
        assertTrue(f.offer(0.6))
        assertTrue(f.offer(0.6, down = false))
        f.worker.run()
        assertEquals(listOf(true, false, true, false), f.sent.map { it.single().down })
    }

    @Test fun overflowDuringWriteQueuesReleaseForTheInflightPress() {
        val f = Fixture(capacity = 2)
        f.duringSend = {
            f.duringSend = null
            f.offer(0.2)
            f.offer(0.2, down = false)
            assertFalse(f.offer(0.3))
        }
        f.offer(0.1)
        f.worker.run()
        assertEquals(listOf(0.1, 0.1), f.sent.map { it.single().x })
        assertEquals(listOf(true, false), f.sent.map { it.single().down })
    }

    @Test fun concurrentOverflowBeforePressWriteCompletesAlwaysSendsRelease() {
        val worker = Executors.newSingleThreadExecutor()
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val sent = Collections.synchronizedList(mutableListOf<AirPlayContact>())
        val session = Any()
        val token = Any()
        val dispatcher = TouchDispatcher(
            executor = worker,
            isCurrent = { s: Any, t: Any -> s === session && t === token },
            send = { _, _, contacts ->
                if (contacts.single().down) {
                    writeEntered.countDown()
                    check(allowWrite.await(5, TimeUnit.SECONDS))
                }
                sent.add(contacts.single())
                true
            },
            capacity = 2,
        )
        fun offer(x: Double, down: Boolean = true) =
            dispatcher.offer(session, token, listOf(AirPlayContact(0, x, 0.5, down)))
        try {
            assertTrue(offer(0.1))
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(offer(0.2))
            assertTrue(offer(0.2, down = false))
            assertFalse(offer(0.3))
            assertFalse(offer(0.3, down = false))
            allowWrite.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(true, false), sent.map { it.down })
            assertEquals(listOf(0.1, 0.1), sent.map { it.x })
            assertTrue(offer(0.4))
            assertTrue(offer(0.4, down = false))
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(true, false, true, false), sent.map { it.down })
        } finally {
            allowWrite.countDown()
            dispatcher.close()
            worker.shutdownNow()
        }
    }

    @Test fun overflowBeforeAnyWriteDoesNotInventAContact() {
        val f = Fixture(capacity = 2)
        f.offer(0.1)
        f.offer(0.1, down = false)
        assertFalse(f.offer(0.2))
        f.worker.run()
        assertTrue(f.sent.isEmpty())
        f.offer(0.2, down = false)
        f.offer(0.3)
        f.offer(0.3, down = false)
        f.worker.run()
        assertEquals(listOf(true, false), f.sent.map { it.single().down })
    }

    @Test fun rejectedExecutorReturnsFalseAndDoesNotReplayTheFailedGesture() {
        val f = Fixture()
        f.worker.reject = true
        assertFalse(f.offer(0.1))
        f.worker.reject = false
        assertFalse(f.offer(0.2))
        assertFalse(f.offer(0.2, down = false))
        assertTrue(f.offer(0.3))
        assertTrue(f.offer(0.3, down = false))
        f.worker.run()
        assertEquals(listOf(0.3, 0.3), f.sent.map { it.single().x })
        assertTrue(f.diagnostics.single().contains("rejected=1"))
    }

    @Test fun closeDropsPendingInputAndRejectsNewEvents() {
        val f = Fixture()
        f.offer(0.1)
        f.dispatcher.close()
        f.worker.run()
        assertFalse(f.offer(0.2))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun sendFailureDoesNotStrandTheWorkerOrLoseTheRelease() {
        val f = Fixture()
        f.failSend = true
        f.offer(0.1)
        f.offer(0.1, down = false)
        f.worker.run()
        f.failSend = false
        f.offer(0.2)
        f.offer(0.2, down = false)
        f.worker.run()
        assertEquals(listOf(true, false, true, false), f.sent.map { it.single().down })
    }

    @Test fun drainingYieldsToOtherExecutorCommands() {
        val f = Fixture()
        repeat(12) {
            f.offer(it / 20.0)
            f.offer(it / 20.0, down = false)
        }
        var countAtCommand = 0
        f.worker.execute { countAtCommand = f.sent.size }
        f.worker.run()
        assertEquals(16, countAtCommand)
        assertEquals(24, f.sent.size)
    }

    @Test fun queueTimingsMeasureNewestMotionAndDoNotLogCoordinates() {
        val f = Fixture()
        f.offer(0.1234)
        f.now = 1_000_000
        f.offer(0.2345)
        f.now = 2_000_000
        f.offer(0.3456)
        f.now = 5_000_000_000
        f.worker.run()
        val line = f.diagnostics.single()
        assertTrue(line.contains("samples=2 queueAvgUs=4999000 queueMaxUs=5000000 coalesced=1"))
        assertFalse(line.contains("0.1234"))
        assertTrue(line.length < 250)
    }
}
