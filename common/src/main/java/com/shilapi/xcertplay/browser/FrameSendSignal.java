package com.shilapi.xcertplay.browser;

/** A versioned wakeup avoids losing an ACK between checking credit and waiting. */
final class FrameSendSignal {
    private long version;

    synchronized long version() { return version; }

    synchronized void signal() { version++; notifyAll(); }

    synchronized void awaitChange(long observed, long timeoutMs) throws InterruptedException {
        long remaining = Math.max(0, timeoutMs) * 1_000_000L;
        long deadline = System.nanoTime() + remaining;
        while (version == observed && remaining > 0) {
            wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            remaining = deadline - System.nanoTime();
        }
    }
}
