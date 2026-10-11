package com.shilapi.xcertplay.browser;

/** Browser PTS stays monotonic across iPhone reconnects and uses a small microsecond origin. */
final class FrameClock {
    private long firstSender, firstArrival, base, last;
    private boolean initialized;
    synchronized void reset() { initialized = false; }
    synchronized long timestamp(long senderNanos, long arrivalNanos) {
        if (!initialized) {
            initialized = true; firstSender = senderNanos; firstArrival = arrivalNanos; base = last + 1;
        }
        long delta = senderNanos > 0 && firstSender > 0 ? senderNanos - firstSender : arrivalNanos - firstArrival;
        long candidate = base + Math.max(0, delta / 1000);
        if (candidate < last || candidate > 9_007_199_254_740_000L) candidate = last + 1;
        last = Math.max(last + 1, candidate); return last;
    }
}
