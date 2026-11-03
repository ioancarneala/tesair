package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BrowserConnectionStateTest {
    @Test fun anOldServiceCannotOverwriteOrReleaseItsSuccessorsObservation() {
        val store = BrowserConnectionStateStore()
        val old = Any()
        val current = Any()
        val waiting = BrowserConnectionState(requested = true, serverRunning = true)
        val connected = waiting.copy(receiver = BrowserReceiverState(
            phase = BrowserReceiverState.Phase.CONNECTED, sessionRunning = true, iphoneActive = true),
            browserCount = 1, videoRendered = true)

        store.claim(old, waiting)
        store.claim(current, connected)
        assertFalse(store.publish(old, BrowserConnectionState()))
        store.release(old)

        assertSame(connected, store.value)
        assertTrue(store.value.streaming)
        assertFalse(store.value.stopped)
        store.release(current)
        assertTrue(store.value.stopped)
    }

    @Test fun observersReceiveAnInitialSnapshotAndOnlyChangedPublishedFactsUntilClosed() {
        val store = BrowserConnectionStateStore()
        val owner = Any()
        val received = mutableListOf<BrowserConnectionState>()
        val subscription = store.observe(received::add)
        val first = BrowserConnectionState(requested = true)
        store.claim(owner, first)
        assertFalse(store.publish(owner, first.copy()))
        val delayed = first.copy(receiver = BrowserReceiverState(
            phase = BrowserReceiverState.Phase.RETIRING, retirementDelayed = true))
        assertTrue(store.publish(owner, delayed))
        subscription.close()
        store.release(owner)

        assertEquals(listOf(BrowserConnectionState(), first, delayed), received)
        assertFalse(received.last().videoRendered)
        assertFalse(received.last().streaming)
    }

    @Test fun aFailingOptionalObserverDoesNotPreventOtherObserversOrStatePublication() {
        val store = BrowserConnectionStateStore()
        val owner = Any()
        val received = mutableListOf<BrowserConnectionState>()
        store.observe { error("Synthetic observer failure") }
        store.observe(received::add)
        val next = BrowserConnectionState(serverRunning = true, browserCount = 1)
        store.claim(owner, next)

        assertSame(next, store.value)
        assertEquals(next, received.last())
        assertTrue(next.active)
        assertFalse(next.streaming)
    }
}
