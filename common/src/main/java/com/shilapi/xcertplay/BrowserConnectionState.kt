package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.io.Closeable

/** Runtime facts, without network credentials, UI permissions, or inferred browser compatibility. */
internal data class BrowserReceiverState(
    val phase: Phase = Phase.ABSENT,
    val sessionRunning: Boolean = false,
    val iphoneActive: Boolean = false,
    val status: CarPlayStatus? = null,
    val setupNeedsAttention: Boolean = false,
    val retryReason: String? = null,
    val retirementDelayed: Boolean = false,
) {
    enum class Phase { ABSENT, STARTING, WAITING, CONNECTED, RETRYING, PARKED, RETIRING, CLOSED }
    val available: Boolean get() = phase != Phase.ABSENT && phase != Phase.CLOSED
    val retiring: Boolean get() = phase == Phase.RETIRING
}

/** One published connection observation shared by the phone UI and reliability reports. */
internal data class BrowserConnectionState(
    val requested: Boolean = false,
    val serverRunning: Boolean = false,
    val receiver: BrowserReceiverState = BrowserReceiverState(),
    val browserCount: Int = 0,
    val videoRendered: Boolean = false,
    val outputStatus: String? = null,
) {
    val sessionRunning: Boolean get() = receiver.sessionRunning
    val iphoneActive: Boolean get() = receiver.iphoneActive
    val active: Boolean get() = iphoneActive || browserCount > 0
    val streaming: Boolean get() = iphoneActive && browserCount > 0
    val stopped: Boolean get() = !requested && !sessionRunning
}

/** Main-thread publication; the Service serializes writes and subscriptions. Reads are thread-safe. */
internal class BrowserConnectionStateStore {
    @Volatile var value = BrowserConnectionState()
        private set
    private var owner: Any? = null
    private val observers = LinkedHashSet<(BrowserConnectionState) -> Unit>()

    fun claim(candidate: Any, initial: BrowserConnectionState) {
        val notify = synchronized(this) {
            owner = candidate
            replace(initial)
        }
        notify.forEach { runCatching { it(initial) } }
    }

    fun publish(candidate: Any, next: BrowserConnectionState): Boolean {
        val notify = synchronized(this) {
            if (owner !== candidate) return false
            if (value == next) return false
            replace(next)
        }
        notify.forEach { runCatching { it(next) } }
        return true
    }

    fun release(candidate: Any, final: BrowserConnectionState = BrowserConnectionState()) {
        val notify = synchronized(this) {
            if (owner !== candidate) return
            owner = null
            replace(final)
        }
        notify.forEach { runCatching { it(final) } }
    }

    fun observe(observer: (BrowserConnectionState) -> Unit): Closeable {
        // Give each subscription a stable identity, even for a bound function on a mutable receiver.
        val listener: (BrowserConnectionState) -> Unit = { observer(it) }
        val current = synchronized(this) { observers.add(listener); value }
        runCatching { listener(current) }
        return Closeable { synchronized(this) { observers.remove(listener) } }
    }

    private fun replace(next: BrowserConnectionState): List<(BrowserConnectionState) -> Unit> {
        if (value == next) return emptyList()
        value = next
        return observers.toList()
    }
}
