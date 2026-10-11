package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Keeps contact transitions ordered while replacing motion that has not reached the HID writer. */
internal class TouchDispatcher<S : Any>(
    private val executor: Executor,
    private val isCurrent: (S, Any) -> Boolean,
    private val send: (S, Any, List<AirPlayContact>) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val capacity: Int = 32,
) {
    private data class Report<S>(
        val session: S,
        val token: Any,
        val contacts: List<AirPlayContact>,
        val motion: Boolean,
        val queuedAt: Long,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Report<S>>()
    private var running = false
    private var session: S? = null
    private var token: Any? = null
    private var accepted = emptyList<AirPlayContact>()
    private var dispatched: Report<S>? = null
    private var waitForRelease = false
    private var stopped = false
    private var samples = 0L
    private var queueNanos = 0L
    private var maxQueueNanos = 0L
    private var coalesced = 0L
    private var stale = 0L
    private var overflows = 0L
    private var rejected = 0L
    private var failures = 0L
    private var peak = 0
    private var lastDiagnostic = nanoTime()

    init { require(capacity >= 2) }

    fun offer(nextSession: S, nextToken: Any, contacts: List<AirPlayContact>): Boolean {
        var start = false
        val result = synchronized(lock) {
            if (stopped || !isCurrent(nextSession, nextToken)) return false
            if (session !== nextSession || token !== nextToken) {
                pending.clear()
                session = nextSession
                token = nextToken
                accepted = emptyList()
                dispatched = null
                waitForRelease = false
            }
            if (waitForRelease) {
                if (contacts.none { it.down }) waitForRelease = false
                return false
            }
            val snapshot = contacts.toList()
            val motion = snapshot.any { it.down } && sameContactState(accepted, snapshot)
            val report = Report(nextSession, nextToken, snapshot, motion, nanoTime())
            val tail = pending.peekLast()
            val acceptedReport = if (motion && tail?.motion == true) {
                pending.removeLast()
                pending.addLast(report)
                coalesced++
                true
            } else if (pending.size >= capacity) {
                // An edge storm cannot be retained indefinitely. Release the contact already sent,
                // discard queued gestures, and require a physical release before accepting another.
                pending.clear()
                dispatched?.takeIf { it.contacts.any { contact -> contact.down } }?.let {
                    pending.addLast(it.copy(contacts = it.contacts.map { contact -> contact.copy(down = false) },
                        motion = false, queuedAt = nanoTime()))
                }
                overflows++
                waitForRelease = snapshot.any { it.down }
                false
            } else {
                pending.addLast(report)
                true
            }
            accepted = if (acceptedReport) snapshot else emptyList()
            peak = maxOf(peak, pending.size)
            if (!running && pending.isNotEmpty()) {
                running = true
                start = true
            }
            acceptedReport
        }
        if (start && !schedule()) return false
        return result
    }

    fun close() {
        synchronized(lock) {
            stopped = true
            pending.clear()
            accepted = emptyList()
            dispatched = null
        }
        reportDiagnostic(force = true)
    }

    private fun schedule(): Boolean = try {
        executor.execute(::drain)
        true
    } catch (_: RejectedExecutionException) {
        synchronized(lock) {
            running = false
            pending.clear()
            accepted = emptyList()
            waitForRelease = true
            rejected++
        }
        reportDiagnostic(force = true)
        false
    }

    private fun drain() {
        // Yield to the other commands sharing this executor even under a continuous input flood.
        repeat(16) {
            val report = synchronized(lock) {
                var next: Report<S>? = null
                while (!stopped && pending.isNotEmpty()) {
                    val candidate = pending.removeFirst()
                    if (session !== candidate.session || token !== candidate.token ||
                        !isCurrent(candidate.session, candidate.token)) {
                        stale++
                    } else {
                        // Selection and marking must be atomic: overflow must see this contact
                        // even if it happens before the network write starts or while it blocks.
                        dispatched = candidate
                        val elapsed = (nanoTime() - candidate.queuedAt).coerceAtLeast(0)
                        samples++
                        queueNanos += elapsed
                        maxQueueNanos = maxOf(maxQueueNanos, elapsed)
                        next = candidate
                        break
                    }
                }
                if (next == null) running = false
                next
            } ?: run {
                reportDiagnostic()
                return
            }
            val sent = try { send(report.session, report.token, report.contacts) } catch (_: Exception) { false }
            if (!sent) synchronized(lock) { failures++ }
        }
        val more = synchronized(lock) {
            if (stopped || pending.isEmpty()) {
                running = false
                false
            } else true
        }
        reportDiagnostic()
        if (more) schedule()
    }

    private fun reportDiagnostic(force: Boolean = false) {
        val line = synchronized(lock) {
            val now = nanoTime()
            if ((!force && now - lastDiagnostic < 5_000_000_000L) ||
                samples + coalesced + stale + overflows + rejected + failures == 0L) return
            val line = "touch_dispatch samples=$samples queueAvgUs=${if (samples == 0L) 0 else queueNanos / samples / 1_000} " +
                "queueMaxUs=${maxQueueNanos / 1_000} coalesced=$coalesced stale=$stale " +
                "overflows=$overflows rejected=$rejected failures=$failures peak=$peak"
            samples = 0
            queueNanos = 0
            maxQueueNanos = 0
            coalesced = 0
            stale = 0
            overflows = 0
            rejected = 0
            failures = 0
            peak = pending.size
            lastDiagnostic = now
            line
        }
        try { diagnostic(line) } catch (_: Exception) { /* Diagnostics must not interrupt input. */ }
    }

    private fun sameContactState(a: List<AirPlayContact>, b: List<AirPlayContact>): Boolean =
        a.size == b.size && a.indices.all { a[it].id == b[it].id && a[it].down == b[it].down }
}
