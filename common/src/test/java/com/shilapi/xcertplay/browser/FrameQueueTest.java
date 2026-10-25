package com.shilapi.xcertplay.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameQueueTest {
    private final AtomicLong clock = new AtomicLong();
    private final List<FrameQueue.Recovery> recoveries = new ArrayList<>();
    private final FrameQueue queue = new FrameQueue(clock::get, recoveries::add);

    @Test public void framesDrainImmediatelyWithoutWaitingForATargetDepth() {
        assertTrue(offer(true, 0, 1));
        assertNotNull(queue.poll(0));
        assertTrue(offer(false, 1, 1));
        assertNotNull(queue.poll(1));
        assertTrue(recoveries.isEmpty());
    }
    @Test public void sixtyFpsBurstCanDrainAfterOneHundredMillisecondsWithoutRecovery() {
        burstAndDrain(60, 6);
    }
    @Test public void thirtyFpsBurstCanDrainAfterOneHundredMillisecondsWithoutRecovery() {
        burstAndDrain(30, 3);
    }
    @Test public void dependencyChainIsDiscardedOnlyAtTheFrameCapacity() {
        assertFalse(offer(false, 0, 1));
        for (int i = 0; i < FrameQueue.MAX_FRAMES; i++) assertTrue(offer(i == 0, i, 1));
        assertFalse(offer(false, FrameQueue.MAX_FRAMES, 1));
        assertEquals(1, recoveries.size());
        assertEquals("frame_capacity", recoveries.get(0).reason);
        assertEquals(FrameQueue.MAX_FRAMES, recoveries.get(0).droppedFrames);
        assertEquals(FrameQueue.MAX_FRAMES, recoveries.get(0).droppedBytes);
        assertEquals(FrameQueue.MAX_FRAMES, recoveries.get(0).oldestAgeNanos);
        for (int i = 0; i < 100; i++) assertFalse(offer(false, 100 + i, 1));
        assertEquals(1, recoveries.size());
        assertNull(queue.poll(clock.get()));
        assertTrue(offer(true, 300, 1));
        assertTrue(queue.poll(300).key());
    }
    @Test public void byteBudgetDropsTheEntireDependentChain() {
        int half = (int) FrameQueue.MAX_BYTES / 2;
        assertTrue(offer(true, 0, half));
        assertTrue(offer(false, 1, half));
        assertFalse(offer(false, 2, 1));
        assertEquals("byte_capacity", recoveries.get(0).reason);
        assertEquals(FrameQueue.MAX_BYTES, recoveries.get(0).droppedBytes);
        assertNull(queue.poll(2));
        assertTrue(offer(true, 3, half));
        assertNotNull(queue.poll(3));
    }
    @Test public void aLargeIdrFitsWithoutAnArtificialSmallPacketLimit() {
        assertTrue(offer(true, 0, 8 * 1024 * 1024));
        assertEquals(8 * 1024 * 1024, queue.poll(0).packet().length);
        assertTrue(recoveries.isEmpty());
    }
    @Test public void anOversizedIdrIsNeverRetainedAndStillRequiresARandomAccessPoint() {
        assertTrue(offer(true, 0, 1));
        assertFalse(offer(true, 1, (int) FrameQueue.MAX_BYTES + 1));
        assertNull(queue.poll(1));
        assertTrue(queue.waitingForKey());
        assertEquals(1, recoveries.size());
        assertFalse(offer(false, 2, 1));
        assertTrue(offer(true, 3, 1));
    }
    @Test public void incomingStaleDependenciesTriggerOneRecovery() {
        assertTrue(offer(true, 0, 1));
        assertFalse(offer(false, FrameQueue.MAX_AGE_NANOS + 1, 1));
        assertEquals("age", recoveries.get(0).reason);
        assertEquals(FrameQueue.MAX_AGE_NANOS + 1, recoveries.get(0).oldestAgeNanos);
        assertEquals(1, recoveries.size());
    }
    @Test public void dequeueEnforcesTheAgeDeadlineEvenWithoutAnyNewIncomingFrames() {
        assertTrue(offer(true, 0, 1));
        assertTrue(offer(false, 1, 1));
        assertNull(queue.poll(FrameQueue.MAX_AGE_NANOS + 1));
        assertTrue(queue.waitingForKey());
        assertEquals(2, recoveries.get(0).droppedFrames);
        assertEquals(1, recoveries.size());
        assertNull(queue.poll(FrameQueue.MAX_AGE_NANOS + 2));
        assertEquals(1, recoveries.size());
    }
    @Test public void ageDeadlineAllowsItsBoundaryAndRejectsAnAlreadyOldIncomingFrame() {
        assertTrue(offer(true, 0, 1));
        assertNotNull(queue.poll(FrameQueue.MAX_AGE_NANOS));
        clock.set(FrameQueue.MAX_AGE_NANOS + 1);
        assertFalse(queue.offer(frame(true, 0, 1)));
        assertTrue(queue.waitingForKey());
        assertNull(queue.poll(clock.get()));
    }
    @Test public void resetInvalidatesAFrameAlreadyPolledByTheSender() {
        assertTrue(offer(true, 0, 1));
        FrameQueue.Frame old = queue.poll(0);
        assertTrue(queue.current(old));
        queue.reset();
        assertFalse(queue.current(old));
        assertFalse(offer(false, 1, 1));
        assertTrue(offer(true, 2, 1));
        assertTrue(queue.current(queue.poll(2)));
        assertTrue(recoveries.isEmpty());
    }
    @Test public void recoveryAlsoInvalidatesAPolledDependentFrame() {
        assertTrue(offer(true, 0, 1));
        FrameQueue.Frame old = queue.poll(0);
        for (int i = 0; i < FrameQueue.MAX_FRAMES; i++) assertTrue(offer(false, i, 1));
        assertFalse(offer(false, FrameQueue.MAX_FRAMES, 1));
        assertFalse(queue.current(old));
    }
    @Test public void waitingSkipsAreCountedSeparatelyFromRecoveryTransitions() {
        assertFalse(offer(false, 0, 1));
        assertFalse(offer(false, 1, 1));
        assertEquals(2, queue.takeSkipped());
        assertEquals(0, queue.takeSkipped());
        assertTrue(recoveries.isEmpty());
    }
    @Test public void repeatedOverflowsRemainBoundedAndOnlyNewIdrsResumeTheQueue() {
        for (int round = 0; round < 100; round++) {
            long now = round * 1000L;
            for (int i = 0; i < FrameQueue.MAX_FRAMES; i++) assertTrue(offer(i == 0, now + i, 1));
            assertFalse(offer(false, now + FrameQueue.MAX_FRAMES, 1));
            assertNull(queue.poll(clock.get()));
            assertTrue(queue.waitingForKey());
        }
        assertEquals(100, recoveries.size());
    }
    private void burstAndDrain(int fps, int frames) {
        long interval = 1_000_000_000L / fps;
        for (int i = 0; i < frames; i++) assertTrue(offer(i == 0, i * interval, 1));
        long drainTime = 100_000_000L;
        for (int i = 0; i < frames; i++) assertEquals(i * interval, queue.poll(drainTime).queuedNanos());
        assertNull(queue.poll(drainTime));
        assertTrue(recoveries.isEmpty());
    }
    private boolean offer(boolean key, long now, int bytes) {
        clock.set(now); return queue.offer(frame(key, now, bytes));
    }
    private static FrameQueue.Frame frame(boolean key, long time, int bytes) {
        return new FrameQueue.Frame(new byte[bytes], key, time, "format");
    }
}
