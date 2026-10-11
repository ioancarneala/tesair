package com.shilapi.xcertplay.browser;

import java.util.ArrayDeque;

/** Drop dependent frames together; resume only at a random access point. */
final class FrameQueue {
    static final class Frame {
        private final byte[] packet; private final boolean key; private final long queuedNanos; private final String format; private final long epoch;
        Frame(byte[] packet, boolean key, long queuedNanos, String format) {
            this(packet, key, queuedNanos, format, 0);
        }
        Frame(byte[] packet, boolean key, long queuedNanos, String format, long epoch) {
            this.packet = packet; this.key = key; this.queuedNanos = queuedNanos; this.format = format; this.epoch = epoch;
        }
        byte[] packet() { return packet; } boolean key() { return key; }
        long queuedNanos() { return queuedNanos; } String format() { return format; }
        long epoch() { return epoch; }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private boolean waitingForKey = true;
    synchronized boolean offer(Frame frame) {
        if (frames.size() >= 3 || (!frames.isEmpty()
                && frame.queuedNanos() - frames.peek().queuedNanos() > 150_000_000L)) {
            frames.clear();
            waitingForKey = true;
        }
        if (waitingForKey && !frame.key()) return false;
        waitingForKey = false;
        frames.add(frame);
        notifyAll();
        return true;
    }
    synchronized Frame poll() throws InterruptedException {
        if (frames.isEmpty()) wait(250);
        return frames.poll();
    }
    synchronized void reset() { frames.clear(); waitingForKey = true; notifyAll(); }
}
