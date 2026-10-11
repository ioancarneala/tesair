package com.shilapi.xcertplay.browser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameSendSignalTest {
    @Test public void acknowledgementWakesAnAlreadyWaitingSender() throws Exception {
        FrameSendSignal signal = new FrameSendSignal();
        long version = signal.version();
        CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread sender = new Thread(() -> {
            started.countDown();
            try { signal.awaitChange(version, 10000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        });
        try {
            sender.start(); assertTrue(started.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (sender.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING, sender.getState());
            signal.signal();
            assertTrue("ACK must wake the sender without the fallback timeout", finished.await(2, TimeUnit.SECONDS));
        } finally { sender.interrupt(); sender.join(2000); }
    }

    @Test(timeout = 2000) public void acknowledgementBetweenCreditCheckAndWaitIsNotLost() throws Exception {
        FrameSendSignal signal = new FrameSendSignal();
        long observed = signal.version(); signal.signal();
        signal.awaitChange(observed, 10000);
    }

    @Test(timeout = 2000) public void missingAcknowledgementStillReachesRecoveryDeadline() throws Exception {
        FrameSendSignal signal = new FrameSendSignal();
        signal.awaitChange(signal.version(), 20);
    }
}
