package com.shilapi.xcertplay.browser;

import java.nio.ByteBuffer;
import java.util.Locale;

final class VideoFormat {
    static String codec(byte[] config) {
        for (int i = 0; i + 7 < config.length; i++) {
            int start = -1;
            if (config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 1) start = i + 3;
            else if (config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 0 && config[i + 3] == 1) start = i + 4;
            if (start >= 0 && start + 3 < config.length && (config[start] & 31) == 7)
                return String.format(Locale.ROOT, "avc1.%02X%02X%02X", config[start + 1] & 255,
                        config[start + 2] & 255, config[start + 3] & 255);
        }
        throw new IllegalArgumentException("Encoder did not supply an Annex B H.264 SPS");
    }
    static byte[] packet(byte[] bytes, byte[] config, boolean key, long pts) {
        ByteBuffer out = ByteBuffer.allocate(9 + bytes.length + (key ? config.length : 0));
        out.put((byte) (key ? 1 : 0)).putLong(pts);
        if (key) out.put(config);
        out.put(bytes);
        return out.array();
    }
}
