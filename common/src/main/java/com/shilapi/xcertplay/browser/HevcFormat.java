package com.shilapi.xcertplay.browser;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** HEVC configuration helpers for the browser relay (Annex B chunks, no decoder description). */
final class HevcFormat {
    static final String DEFAULT_CODEC = "hvc1.1.6.L123.B0";

    private HevcFormat() {}

    /** True when the Annex B access unit contains an HEVC IRAP picture (IDR/CRA/BLA). */
    static boolean isKeyframe(byte[] data) {
        if (data == null) return false;
        int offset = 0;
        while (offset + 3 <= data.length) {
            int start;
            if (data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
                start = offset + 3;
            } else if (offset + 4 <= data.length && data[offset] == 0 && data[offset + 1] == 0 &&
                    data[offset + 2] == 0 && data[offset + 3] == 1) {
                start = offset + 4;
            } else { offset++; continue; }
            if (start + 1 < data.length) {
                int type = (data[start] >>> 1) & 0x3f;
                if (type >= 16 && type <= 23) return true;
            }
            offset = start + 2;
        }
        return false;
    }

    /** Parameter sets in Annex B form, prepended to every key frame. */
    static byte[] parameterSets(byte[] config) {
        if (config == null || config.length < 4) throw new IllegalArgumentException("Invalid HEVC configuration");
        if (startsWithStartCode(config)) return config.clone();
        if (isHvcC(config)) return fromHvcC(config);
        return lengthPrefixedToAnnexB(config);
    }

    /** WebCodecs codec string derived from the hvcC record; safe default when fields are unknown. */
    static String codec(byte[] config) {
        if (config == null || config.length < 23 || !isHvcC(config)) return DEFAULT_CODEC;
        try {
            int profileByte = config[1] & 0xff;
            int space = (profileByte >>> 6) & 3;
            int tier = (profileByte >>> 5) & 1;
            int profile = profileByte & 0x1f;
            int level = config[12] & 0xff;
            long compatibility = Integer.reverse((int) readU32(config, 2)) & 0xffff_ffffL;
            StringBuilder name = new StringBuilder("hvc1.");
            if (space != 0) name.append((char) ('A' + space - 1));
            name.append(profile).append('.').append(Long.toHexString(compatibility).toUpperCase(Locale.ROOT));
            name.append('.').append(tier != 0 ? 'H' : 'L').append(level == 0 ? 123 : level);
            name.append('.').append(String.format(Locale.ROOT, "%02X", config[6] & 0xff));
            return name.toString();
        } catch (RuntimeException ignored) {
            return DEFAULT_CODEC;
        }
    }

    private static boolean isHvcC(byte[] config) {
        return config.length >= 23 && config[0] == 1;
    }

    private static boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 &&
                (data[2] == 1 || (data[2] == 0 && data[3] == 1));
    }

    private static byte[] fromHvcC(byte[] config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int arrays = config[22] & 0xff;
        int cursor = 23;
        for (int array = 0; array < arrays; array++) {
            if (cursor + 3 > config.length) throw new IllegalArgumentException("Invalid hvcC array");
            int type = config[cursor] & 0x3f;
            int count = readU16(config, cursor + 1);
            cursor += 3;
            for (int index = 0; index < count; index++) {
                if (cursor + 2 > config.length) throw new IllegalArgumentException("Invalid hvcC NAL");
                int length = readU16(config, cursor);
                cursor += 2;
                if (length < 2 || cursor + length > config.length)
                    throw new IllegalArgumentException("Invalid hvcC NAL length");
                if (type == 32 || type == 33 || type == 34) writeNal(out, config, cursor, length);
                cursor += length;
            }
        }
        return out.toByteArray();
    }

    private static byte[] lengthPrefixedToAnnexB(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset + 4 <= data.length) {
            long length = readU32(data, offset);
            offset += 4;
            if (length < 2 || length > data.length - offset)
                throw new IllegalArgumentException("Invalid length-prefixed HEVC NAL");
            writeNal(out, data, offset, (int) length);
            offset += (int) length;
        }
        if (offset != data.length) throw new IllegalArgumentException("Trailing HEVC bytes");
        return out.toByteArray();
    }

    private static void writeNal(ByteArrayOutputStream out, byte[] data, int offset, int length) {
        out.write(0); out.write(0); out.write(0); out.write(1);
        out.write(data, offset, length);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long readU32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) |
                ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }
}
