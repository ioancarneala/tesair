package com.shilapi.xcertplay.browser;

/** Bounded, content-free timings on the Android monotonic clock. */
final class BrowserLatencyStats {
    private final Sample queue = new Sample(), write = new Sample(), ack = new Sample();
    private long reportedAt = System.nanoTime(), creditWaits, creditWaitNanos;

    synchronized void queued(long nanos) { queue.add(nanos); }
    synchronized void written(long nanos) { write.add(nanos); }
    synchronized void acknowledged(long nanos) { ack.add(nanos); }
    synchronized void waited(long nanos) { creditWaits++; creditWaitNanos += Math.max(0, nanos); }

    synchronized String report(long now) {
        if (now - reportedAt < 5_000_000_000L) return null;
        reportedAt = now;
        if (queue.count == 0 && write.count == 0 && ack.count == 0 && creditWaits == 0) return null;
        String result = "relay_latency" + queue.report("queue") + write.report("write") + ack.report("ack")
                + " creditWaits=" + creditWaits + " creditWaitMs=" + creditWaitNanos / 1_000_000L;
        queue.clear(); write.clear(); ack.clear(); creditWaits = 0; creditWaitNanos = 0;
        return result;
    }

    private static final class Sample {
        long count, total, maximum;
        void add(long nanos) {
            long value = Math.max(0, nanos);
            count++; total += value; maximum = Math.max(maximum, value);
        }
        String report(String name) {
            return " " + name + "Samples=" + count + " " + name + "AvgUs="
                    + (count == 0 ? 0 : total / count / 1000) + " " + name + "MaxUs=" + maximum / 1000;
        }
        void clear() { count = 0; total = 0; maximum = 0; }
    }
}
