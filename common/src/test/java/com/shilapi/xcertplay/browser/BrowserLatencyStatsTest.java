package com.shilapi.xcertplay.browser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserLatencyStatsTest {
    @Test public void reportsIndependentLocalIntervalsAndResetsWithoutRetainingFrames() {
        BrowserLatencyStats stats = new BrowserLatencyStats();
        stats.queued(100_000); stats.queued(300_000);
        stats.written(50_000); stats.acknowledged(8_000_000); stats.waited(2_000_000);
        assertNull(stats.report(System.nanoTime()));
        long now = System.nanoTime() + 5_000_000_000L;
        String report = stats.report(now);
        assertTrue(report.contains("queueSamples=2 queueAvgUs=200 queueMaxUs=300"));
        assertTrue(report.contains("writeSamples=1 writeAvgUs=50 writeMaxUs=50"));
        assertTrue(report.contains("ackSamples=1 ackAvgUs=8000 ackMaxUs=8000"));
        assertTrue(report.contains("creditWaits=1 creditWaitMs=2"));
        assertNull(stats.report(now + 5_000_000_000L));
        stats.queued(-1);
        assertTrue(stats.report(now + 10_000_000_000L).contains("queueSamples=1 queueAvgUs=0 queueMaxUs=0"));
    }
}
