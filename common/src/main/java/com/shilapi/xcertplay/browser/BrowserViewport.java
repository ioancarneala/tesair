package com.shilapi.xcertplay.browser;

final class BrowserViewport {
    static int[] fit(int width, int height) {
        if (width < 128 || height < 128 || width > 16384 || height > 16384)
            throw new IllegalArgumentException("Invalid browser display size");
        double scale = Math.min(1.0, 2560.0 / Math.max(width, height));
        scale = Math.min(scale, Math.sqrt((2560.0 * 1440) / ((double) width * height)));
        return new int[]{Math.max(128, (int) (width * scale) / 2 * 2),
                Math.max(128, (int) (height * scale) / 2 * 2)};
    }
}
