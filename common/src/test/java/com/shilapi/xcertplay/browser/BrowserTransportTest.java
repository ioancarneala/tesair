package com.shilapi.xcertplay.browser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserTransportTest {
    @Test public void spsCroppingPreservesNative1080pAnd900p() {
        assertArrayEquals(new int[]{1920, 1080}, H264Format.spsSize(sps(1920, 1080, 1, true)));
        assertArrayEquals(new int[]{1600, 900}, H264Format.spsSize(sps(1600, 900, 1, true)));
        assertArrayEquals(new int[]{1080, 1920}, H264Format.spsSize(sps(1080, 1920, 1, true)));
    }
    @Test public void cropUnitsFollowChromaAndInterlaceFlags() {
        assertArrayEquals(new int[]{1600, 901}, H264Format.spsSize(sps(1600, 901, 2, true)));
        assertArrayEquals(new int[]{1280, 720}, H264Format.spsSize(sps(1280, 720, 1, false)));
    }
    @Test public void avcConfigurationProducesAnnexBWithoutDecoding() {
        byte[] sps = sps(1920, 1080, 1, true), pps = {0x68, (byte) 0xce, 0x3c, (byte) 0x80};
        H264Format parsed = H264Format.fromAvcC(avcc(sps, pps));
        assertEquals(1920, parsed.width); assertEquals(1080, parsed.height);
        ByteBuffer expected = ByteBuffer.allocate(8 + sps.length + pps.length);
        expected.putInt(1).put(sps).putInt(1).put(pps);
        assertArrayEquals(expected.array(), parsed.annexB);
        assertEquals("avc1.640028", VideoFormat.codec(parsed.annexB));
    }
    @Test public void malformedConfigurationAndTruncatedSpsFailExplicitly() {
        byte[] valid = avcc(sps(1920, 1080, 1, true), new byte[]{0x68, 1});
        assertThrows(IllegalArgumentException.class, () -> H264Format.fromAvcC(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> H264Format.fromAvcC(new byte[6]));
        assertThrows(IllegalArgumentException.class, () -> H264Format.spsSize(new byte[]{0x67, 100, 0, 40}));
        byte[] wrong = valid.clone(); wrong[8] = 0x68;
        assertThrows(IllegalArgumentException.class, () -> H264Format.fromAvcC(wrong));
    }
    @Test public void recoveryWaitsForIdrNotJustSpsOrPredictedFrame() {
        assertFalse(H264Format.isKeyframe(new byte[]{0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x41}));
        assertTrue(H264Format.isKeyframe(new byte[]{0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x65}));
        assertTrue(H264Format.isKeyframe(new byte[]{0, 0, 0, 1, 0x65}));
    }
    @Test public void timestampsRemainMonotonicAcrossReconnection() {
        FrameClock clock = new FrameClock();
        long first = clock.timestamp(5_000_000_000L, 9_000_000_000L);
        long next = clock.timestamp(5_016_667_000L, 9_016_667_000L);
        assertEquals(16667, next - first);
        clock.reset();
        assertTrue(clock.timestamp(100, 100) > next);
        assertTrue(clock.timestamp(50, 50) > next);
    }
    @Test public void framesWithoutSenderTimeUseArrivalTime() {
        FrameClock clock = new FrameClock(); long first = clock.timestamp(0, 1_000_000);
        assertEquals(first + 40000, clock.timestamp(0, 41_000_000));
    }
    @Test public void browserViewportUsesEvenNativeSizeAndBoundsThePixelBudget() {
        assertArrayEquals(new int[]{1600, 900}, BrowserViewport.fit(1600, 900));
        assertArrayEquals(new int[]{1920, 1080}, BrowserViewport.fit(1920, 1080));
        int[] huge = BrowserViewport.fit(16384, 16384);
        assertTrue((long) huge[0] * huge[1] <= 2560 * 1440);
        assertThrows(IllegalArgumentException.class, () -> BrowserViewport.fit(0, 1080));
    }
    @Test public void automaticCookieIsIsolatedToOneServerLifetime() {
        BrowserSession session = new BrowserSession();
        assertTrue(session.authorized(session.cookie()));
        assertFalse(new BrowserSession().authorized(session.cookie()));
        assertFalse(session.authorized(null)); assertFalse(session.authorized("bridge=wrong"));
    }
    @Test public void queueOverflowDropsDependenciesUntilAnotherKeyframe() throws Exception {
        FrameQueue queue = new FrameQueue();
        assertFalse(queue.offer(frame(false, 0)));
        assertTrue(queue.offer(frame(true, 1))); queue.offer(frame(false, 2)); queue.offer(frame(false, 3));
        assertFalse(queue.offer(frame(false, 4))); assertFalse(queue.offer(frame(false, 5)));
        assertTrue(queue.offer(frame(true, 6))); assertEquals(6, queue.poll().queuedNanos());
        queue.reset(); assertFalse(queue.offer(frame(false, 7)));
    }
    @Test public void slowFrameQueueDiscardsStaleDependencies() {
        FrameQueue queue = new FrameQueue(); queue.offer(frame(true, 1));
        assertFalse(queue.offer(frame(false, 150_000_002L)));
    }
    @Test public void frameProtocolPreservesIdrAndMicrosecondTimestamp() {
        byte[] config = {0, 0, 0, 1, 0x67}, data = {0, 0, 1, 0x65};
        ByteBuffer packet = ByteBuffer.wrap(VideoFormat.packet(data, config, true, 123456789));
        assertEquals(1, packet.get()); assertEquals(123456789, packet.getLong());
        byte[] annexB = new byte[packet.remaining()]; packet.get(annexB);
        assertArrayEquals(ByteBuffer.allocate(config.length + data.length).put(config).put(data).array(), annexB);
    }

    private static FrameQueue.Frame frame(boolean key, long time) { return new FrameQueue.Frame(new byte[]{1}, key, time, "format"); }
    private static byte[] avcc(byte[] sps, byte[] pps) {
        return ByteBuffer.allocate(11 + sps.length + pps.length).put(new byte[]{1, 100, 0, 40, (byte) 255, (byte) 225})
                .putShort((short) sps.length).put(sps).put((byte) 1).putShort((short) pps.length).put(pps).array();
    }
    private static byte[] sps(int width, int height, int chroma, boolean progressive) {
        Bits bits = new Bits(); bits.fixed(100, 8); bits.fixed(0, 8); bits.fixed(40, 8); bits.ue(0);
        bits.ue(chroma); bits.ue(0); bits.ue(0); bits.fixed(0, 1); bits.fixed(0, 1);
        bits.ue(0); bits.ue(0); bits.ue(0); bits.ue(1); bits.fixed(0, 1);
        int mbWidth = (width + 15) / 16, mapHeight = (height + (progressive ? 15 : 31)) / (progressive ? 16 : 32);
        bits.ue(mbWidth - 1); bits.ue(mapHeight - 1); bits.fixed(progressive ? 1 : 0, 1);
        if (!progressive) bits.fixed(0, 1);
        bits.fixed(1, 1); bits.fixed(1, 1);
        bits.ue(0); bits.ue((mbWidth * 16 - width) / 2); bits.ue(0);
        bits.ue((mapHeight * (progressive ? 16 : 32) - height) / ((chroma == 1 ? 2 : 1) * (progressive ? 1 : 2)));
        bits.fixed(0, 1); bits.fixed(1, 1);
        byte[] rbsp = bits.bytes(); return ByteBuffer.allocate(1 + rbsp.length).put((byte) 0x67).put(rbsp).array();
    }
    private static final class Bits {
        private final StringBuilder bits = new StringBuilder();
        void fixed(int value, int count) { for (int i = count - 1; i >= 0; i--) bits.append((value >>> i) & 1); }
        void ue(int value) { int count = 32 - Integer.numberOfLeadingZeros(value + 1); fixed(0, count - 1); fixed(value + 1, count); }
        byte[] bytes() {
            while (bits.length() % 8 != 0) bits.append('0');
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < bits.length(); i += 8) out.write(Integer.parseInt(bits.substring(i, i + 8), 2));
            return out.toByteArray();
        }
    }
}
