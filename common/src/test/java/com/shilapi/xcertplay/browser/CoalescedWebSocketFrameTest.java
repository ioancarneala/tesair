package com.shilapi.xcertplay.browser;

import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoalescedWebSocketFrameTest {
    @Test public void binaryFramesPreserveAllLengthEncodingsAndFlushImmediately() throws Exception {
        for (int length : new int[]{0, 1, 125, 126, 16373, 16374, 16375, 65535, 65536, 200000}) {
            RecordingOutput output = check(new WebSocketFrame(OpCode.Binary, true, payload(length)));
            assertEquals(0, output.singleWrites);
            assertEquals(1, output.flushes);
            assertTrue("Each frame needs at most two bulk writes", output.lengths.size() <= 2);
            if (length > CoalescedWebSocketFrame.BUFFER_SIZE)
                assertEquals(CoalescedWebSocketFrame.BUFFER_SIZE, (int) output.lengths.get(0));
        }
    }

    @Test public void textControlAndContinuationFramesKeepTheirWireRepresentation() throws Exception {
        check(new WebSocketFrame(OpCode.Text, true, "Connected · CarPlay"));
        check(new WebSocketFrame(OpCode.Ping, true, payload(125)));
        check(new WebSocketFrame(OpCode.Pong, true, payload(0)));
        check(new CloseFrame(CloseCode.NormalClosure, "Done"));
        check(new WebSocketFrame(OpCode.Binary, false, payload(126)));
        check(new WebSocketFrame(OpCode.Continuation, true, payload(40)));
    }

    @Test public void maskingPreservesPayloadAndUsesBoundedBulkWrites() throws Exception {
        byte[] payload = payload(40000), original = payload.clone();
        RecordingOutput output = check(new WebSocketFrame(OpCode.Binary, true, payload, new byte[]{3, 9, 1, 7}));
        assertArrayEquals(original, payload);
        assertEquals(0, output.singleWrites);
        assertEquals(3, output.lengths.size());
        assertEquals(1, output.flushes);
    }

    @Test public void largePayloadIsNotClonedAndItsTailIsWrittenDirectly() throws Exception {
        byte[] payload = payload(200000);
        WebSocketFrame frame = new WebSocketFrame(OpCode.Binary, true, payload);
        CoalescedWebSocketFrame wrapped = new CoalescedWebSocketFrame(frame, buffer());
        assertSame(payload, wrapped.getBinaryPayload());
        RecordingOutput output = new RecordingOutput(); wrapped.write(output);
        assertSame(payload, output.sources.get(1));
        assertEquals(payload.length + 10 - CoalescedWebSocketFrame.BUFFER_SIZE, (int) output.lengths.get(1));
    }

    @Test public void reusedBufferNeverMixesFramesAndDoesNotDelayControlMessages() throws Exception {
        byte[] buffer = buffer();
        RecordingOutput output = new RecordingOutput();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (WebSocketFrame frame : new WebSocketFrame[]{
                new WebSocketFrame(OpCode.Binary, true, payload(40000)),
                new WebSocketFrame(OpCode.Pong, true, payload(1)),
                new WebSocketFrame(OpCode.Text, true, "ready")}) {
            frame.write(expected);
            new CoalescedWebSocketFrame(frame, buffer).write(output);
            assertArrayEquals(expected.toByteArray(), output.bytes.toByteArray());
        }
        assertEquals(3, output.flushes);
    }

    @Test public void writeAndFlushFailuresPropagateWithoutFlushingPartialFrames() throws Exception {
        CoalescedWebSocketFrame frame = new CoalescedWebSocketFrame(
                new WebSocketFrame(OpCode.Binary, true, payload(20000)), buffer());
        IOException failure = new IOException("unavailable");
        int[] flushes = {0};
        assertSame(failure, assertThrows(IOException.class, () -> frame.write(new OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw failure; }
            @Override public void flush() { flushes[0]++; }
        })));
        assertEquals(0, flushes[0]);
        assertSame(failure, assertThrows(IOException.class, () -> frame.write(new ByteArrayOutputStream() {
            @Override public void flush() throws IOException { throw failure; }
        })));
    }

    @Test public void undersizedScratchBufferIsRejected() {
        WebSocketFrame frame = new WebSocketFrame(OpCode.Binary, true, payload(1));
        assertThrows(IllegalArgumentException.class, () -> new CoalescedWebSocketFrame(frame, new byte[13]));
    }

    private static RecordingOutput check(WebSocketFrame frame) throws IOException {
        ByteArrayOutputStream expected = new ByteArrayOutputStream(); frame.write(expected);
        RecordingOutput output = new RecordingOutput();
        new CoalescedWebSocketFrame(frame, buffer()).write(output);
        assertArrayEquals(expected.toByteArray(), output.bytes.toByteArray());
        return output;
    }
    private static byte[] buffer() { return new byte[CoalescedWebSocketFrame.BUFFER_SIZE]; }
    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 19);
        return bytes;
    }
    private static final class RecordingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<byte[]> sources = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        int singleWrites, flushes;
        @Override public void write(int value) { singleWrites++; bytes.write(value); }
        @Override public void write(byte[] source, int offset, int length) {
            sources.add(source); lengths.add(length); bytes.write(source, offset, length);
        }
        @Override public void flush() { flushes++; }
    }
}
