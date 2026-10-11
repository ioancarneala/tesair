package com.shilapi.xcertplay.browser;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Validates AVC configuration and reads the actual cropped picture size from its SPS. */
final class H264Format {
    final byte[] annexB;
    final int width, height;
    private H264Format(byte[] annexB, int width, int height) { this.annexB = annexB; this.width = width; this.height = height; }

    static H264Format fromAvcC(byte[] config) {
        if (config.length < 7 || config.length > 65536 || config[0] != 1) throw new IllegalArgumentException("Invalid AVC configuration");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cursor = 6, spsCount = config[5] & 31;
        int[] dimensions = null;
        for (int i = 0; i < spsCount; i++) {
            int length = length(config, cursor); cursor += 2;
            if (length < 4 || cursor + length > config.length || (config[cursor] & 31) != 7)
                throw new IllegalArgumentException("Invalid AVC SPS");
            if (dimensions == null) dimensions = spsSize(Arrays.copyOfRange(config, cursor, cursor + length));
            writeNal(out, config, cursor, length); cursor += length;
        }
        if (dimensions == null || cursor >= config.length) throw new IllegalArgumentException("Missing AVC SPS/PPS");
        int ppsCount = config[cursor++] & 255;
        if (ppsCount == 0) throw new IllegalArgumentException("Missing AVC PPS");
        for (int i = 0; i < ppsCount; i++) {
            int length = length(config, cursor); cursor += 2;
            if (length < 1 || cursor + length > config.length || (config[cursor] & 31) != 8)
                throw new IllegalArgumentException("Invalid AVC PPS");
            writeNal(out, config, cursor, length); cursor += length;
        }
        return new H264Format(out.toByteArray(), dimensions[0], dimensions[1]);
    }

    static boolean isKeyframe(byte[] frame) {
        for (int i = 0; i + 3 < frame.length; i++) {
            if (frame[i] == 0 && frame[i + 1] == 0 && frame[i + 2] == 1 && (frame[i + 3] & 31) == 5) return true;
        }
        return false;
    }

    private static int length(byte[] data, int offset) {
        if (offset + 2 > data.length) throw new IllegalArgumentException("Truncated AVC configuration");
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }
    private static void writeNal(ByteArrayOutputStream out, byte[] data, int offset, int length) {
        out.write(0); out.write(0); out.write(0); out.write(1); out.write(data, offset, length);
    }

    static int[] spsSize(byte[] sps) {
        if (sps.length < 4 || (sps[0] & 31) != 7) throw new IllegalArgumentException("Invalid SPS");
        ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
        int zeros = 0;
        for (int i = 1; i < sps.length; i++) {
            int value = sps[i] & 255;
            if (zeros >= 2 && value == 3) { zeros = 0; continue; }
            rbsp.write(value); zeros = value == 0 ? zeros + 1 : 0;
        }
        Bits bits = new Bits(rbsp.toByteArray());
        int profile = bits.read(8); bits.read(8); bits.read(8); bits.ue();
        int chroma = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                || profile == 139 || profile == 134 || profile == 135) {
            chroma = bits.ue();
            if (chroma > 3) throw new IllegalArgumentException("Invalid chroma format");
            boolean separate = chroma == 3 && bits.read(1) != 0;
            bits.ue(); bits.ue(); bits.read(1);
            if (bits.read(1) != 0) {
                int lists = chroma == 3 ? 12 : 8;
                for (int i = 0; i < lists; i++) {
                    if (bits.read(1) == 0) continue;
                    int last = 8, next = 8;
                    for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                        if (next != 0) next = (last + bits.se() + 256) & 255;
                        if (next != 0) last = next;
                    }
                }
            }
            if (separate) chroma = 0;
        }
        bits.ue();
        int order = bits.ue();
        if (order == 0) bits.ue();
        else if (order == 1) {
            bits.read(1); bits.se(); bits.se();
            int cycle = bits.ue();
            if (cycle > 255) throw new IllegalArgumentException("Invalid SPS picture order");
            for (int i = 0; i < cycle; i++) bits.se();
        } else if (order != 2) throw new IllegalArgumentException("Invalid SPS picture order");
        bits.ue(); bits.read(1);
        int mbsWide = bits.ue() + 1, mapHeight = bits.ue() + 1;
        if (mbsWide > 512 || mapHeight > 512) throw new IllegalArgumentException("Unsupported video dimensions");
        int frameOnly = bits.read(1);
        if (frameOnly == 0) bits.read(1);
        bits.read(1);
        long left = 0, right = 0, top = 0, bottom = 0;
        if (bits.read(1) != 0) { left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue(); }
        int cropX = chroma == 1 || chroma == 2 ? 2 : 1;
        int cropY = (chroma == 1 ? 2 : 1) * (2 - frameOnly);
        long width = 16L * mbsWide - (left + right) * cropX;
        long height = 16L * mapHeight * (2 - frameOnly) - (top + bottom) * cropY;
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("Invalid cropped video dimensions");
        return new int[]{(int) width, (int) height};
    }

    private static final class Bits {
        private final byte[] data; private int bit;
        Bits(byte[] data) { this.data = data; }
        int read(int count) {
            if (count < 0 || count > 30 || bit + count > data.length * 8) throw new IllegalArgumentException("Truncated SPS");
            int value = 0;
            while (count-- > 0) { value = (value << 1) | ((data[bit / 8] >>> (7 - bit % 8)) & 1); bit++; }
            return value;
        }
        int ue() {
            int zeros = 0;
            while (read(1) == 0) if (++zeros > 29) throw new IllegalArgumentException("Invalid SPS integer");
            return (1 << zeros) - 1 + read(zeros);
        }
        int se() { int code = ue(); return (code & 1) == 0 ? -(code / 2) : (code + 1) / 2; }
    }
}
