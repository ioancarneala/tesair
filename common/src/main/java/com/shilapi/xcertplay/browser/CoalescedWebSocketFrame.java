package com.shilapi.xcertplay.browser;

import fi.iki.elonen.NanoWSD.WebSocketFrame;
import java.io.IOException;
import java.io.OutputStream;

/** Batches NanoWSD's small header writes without buffering a whole video frame. */
final class CoalescedWebSocketFrame extends WebSocketFrame {
    static final int BUFFER_SIZE = 16 * 1024;
    private final byte[] buffer;

    CoalescedWebSocketFrame(WebSocketFrame frame, byte[] buffer) {
        super(frame);
        if (buffer.length < 14) throw new IllegalArgumentException("WebSocket header buffer is too small");
        this.buffer = buffer;
    }

    @Override public void write(OutputStream output) throws IOException {
        // NanoWSD still owns framing, masking and its flush at every frame boundary.
        super.write(new FrameOutput(output, buffer));
    }

    private static final class FrameOutput extends OutputStream {
        private final OutputStream output;
        private final byte[] buffer;
        private int count;

        FrameOutput(OutputStream output, byte[] buffer) {
            this.output = output; this.buffer = buffer;
        }

        @Override public void write(int value) throws IOException {
            buffer[count++] = (byte) value;
            if (count == buffer.length) drain();
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length)
                throw new IndexOutOfBoundsException();
            if (count > 0) {
                int copied = Math.min(length, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copied);
                count += copied; offset += copied; length -= copied;
                if (count == buffer.length) drain();
            }
            if (length >= buffer.length) {
                // The remaining compressed payload goes directly to TLS, without another copy.
                output.write(bytes, offset, length);
            } else if (length > 0) {
                System.arraycopy(bytes, offset, buffer, count, length);
                count += length;
            }
        }

        private void drain() throws IOException {
            if (count > 0) { output.write(buffer, 0, count); count = 0; }
        }

        @Override public void flush() throws IOException {
            drain(); output.flush();
        }
    }
}
