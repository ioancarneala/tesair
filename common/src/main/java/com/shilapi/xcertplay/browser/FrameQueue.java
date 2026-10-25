package com.shilapi.xcertplay.browser;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Drop dependent frames together; resume only at a random access point. */
final class FrameQueue {
    static final int MAX_FRAMES = 12;
    static final long MAX_BYTES = 12L * 1024 * 1024;
    static final long MAX_AGE_NANOS = 150_000_000L;
    static final class Frame {
        private final byte[] packet; private final boolean key; private final long queuedNanos; private final String format; private final long epoch;
        private final long queueGeneration;
        Frame(byte[] packet, boolean key, long queuedNanos, String format) {
            this(packet, key, queuedNanos, format, 0);
        }
        Frame(byte[] packet, boolean key, long queuedNanos, String format, long epoch) {
            this(packet, key, queuedNanos, format, epoch, -1);
        }
        private Frame(byte[] packet, boolean key, long queuedNanos, String format, long epoch, long queueGeneration) {
            this.packet = packet; this.key = key; this.queuedNanos = queuedNanos; this.format = format; this.epoch = epoch;
            this.queueGeneration = queueGeneration;
        }
        byte[] packet() { return packet; } boolean key() { return key; }
        long queuedNanos() { return queuedNanos; } String format() { return format; }
        long epoch() { return epoch; }
    }
    static final class Recovery {
        final String reason; final int droppedFrames; final long droppedBytes, oldestAgeNanos;
        Recovery(String reason, int droppedFrames, long droppedBytes, long oldestAgeNanos) {
            this.reason = reason; this.droppedFrames = droppedFrames;
            this.droppedBytes = droppedBytes; this.oldestAgeNanos = oldestAgeNanos;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final LongSupplier clock;
    private final Consumer<Recovery> recovered;
    private boolean waitingForKey = true;
    private long queuedBytes, generation, skipped;
    FrameQueue() { this(System::nanoTime, recovery -> {}); }
    FrameQueue(LongSupplier clock, Consumer<Recovery> recovered) { this.clock = clock; this.recovered = recovered; }
    boolean offer(Frame frame) {
        Recovery recovery = null;
        boolean accepted;
        synchronized (this) {
            long now = Math.max(clock.getAsLong(), frame.queuedNanos());
            String reason = !frames.isEmpty() && now - frames.peek().queuedNanos() > MAX_AGE_NANOS ? "age"
                    : frames.size() >= MAX_FRAMES ? "frame_capacity"
                    : queuedBytes + frame.packet().length > MAX_BYTES ? "byte_capacity" : null;
            if (reason != null) recovery = recover(reason, now);
            if (frame.packet().length > MAX_BYTES || now - frame.queuedNanos() > MAX_AGE_NANOS) {
                if (recovery == null) recovery = recover(frame.packet().length > MAX_BYTES ? "byte_capacity" : "age", now);
                skipped++; accepted = false;
            } else if (waitingForKey && !frame.key()) {
                skipped++; accepted = false;
            } else {
                waitingForKey = false;
                frames.add(new Frame(frame.packet(), frame.key(), frame.queuedNanos(), frame.format(), frame.epoch(), generation));
                queuedBytes += frame.packet().length;
                notifyAll(); accepted = true;
            }
        }
        if (recovery != null) recovered.accept(recovery);
        return accepted;
    }
    Frame poll() throws InterruptedException {
        synchronized (this) { if (frames.isEmpty()) wait(250); }
        return poll(clock.getAsLong());
    }
    Frame poll(long nowNanos) {
        Frame next;
        Recovery recovery;
        synchronized (this) {
            recovery = !frames.isEmpty() && nowNanos - frames.peek().queuedNanos() > MAX_AGE_NANOS
                    ? recover("age", nowNanos) : null;
            next = frames.poll();
            if (next != null) queuedBytes -= next.packet().length;
        }
        if (recovery != null) recovered.accept(recovery);
        return next;
    }
    private Recovery recover(String reason, long nowNanos) {
        Recovery recovery = waitingForKey ? null : new Recovery(reason, frames.size(), queuedBytes,
                frames.isEmpty() ? 0 : Math.max(0, nowNanos - frames.peek().queuedNanos()));
        frames.clear(); queuedBytes = 0; waitingForKey = true; generation++;
        return recovery;
    }
    synchronized boolean current(Frame frame) { return frame.queueGeneration == generation; }
    synchronized boolean waitingForKey() { return waitingForKey; }
    synchronized long takeSkipped() { long count = skipped; skipped = 0; return count; }
    synchronized void reset() {
        frames.clear(); queuedBytes = 0; waitingForKey = true; generation++; skipped = 0; notifyAll();
    }
}
