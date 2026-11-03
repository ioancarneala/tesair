package com.shilapi.xcertplay.browser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HevcFormatTest {
    private static byte[] hvcC() {
        byte[] data = new byte[32];
        data[0] = 1;
        data[1] = 1;                       // Main profile, space 0, tier 0
        data[2] = 0x60;                    // profile compatibility flags 0x60000000
        data[6] = (byte) 0xB0;             // constraint indicator flags
        data[12] = 93;                     // level 3.1
        data[13] = (byte) 0xF0;            // min spatial segmentation reserved bits
        data[15] = (byte) 0xFC;            // parallelismType reserved bits
        data[16] = (byte) 0xFD;            // chromaFormat reserved bits
        data[17] = (byte) 0xF8;            // bitDepthLuma reserved bits
        data[18] = (byte) 0xF8;            // bitDepthChroma reserved bits
        data[21] = 0x0F;
        data[22] = 1;                      // one array
        data[23] = (byte) 0xA1;            // completeness + NAL type 33 (SPS)
        data[24] = 0; data[25] = 1;        // one NAL
        data[26] = 0; data[27] = 4;        // four bytes
        data[28] = 0x42; data[29] = 0x01; data[30] = 0x01; data[31] = 0x60;
        return data;
    }

    @Test public void extractsAnnexBParameterSetsAndAWebCodecsCodecString() {
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x42, 0x01, 0x01, 0x60}, HevcFormat.parameterSets(hvcC()));
        assertEquals("hvc1.1.6.L93.B0", HevcFormat.codec(hvcC()));
    }

    @Test public void detectsIrapKeyframesAndRejectsDeltaPictures() {
        assertTrue(HevcFormat.isKeyframe(new byte[]{0, 0, 0, 1, 0x26, 0x01, 0x01}));
        assertFalse(HevcFormat.isKeyframe(new byte[]{0, 0, 0, 1, 0x02, 0x01, 0x01}));
    }

    @Test public void acceptsAnnexBAndLengthPrefixedConfigurations() {
        byte[] annexB = {0, 0, 0, 1, 0x40, 0x01, 0x0c, 0x01};
        assertArrayEquals(annexB, HevcFormat.parameterSets(annexB));
        byte[] lengthPrefixed = {0, 0, 0, 4, 0x40, 0x01, 0x0c, 0x01};
        assertArrayEquals(annexB, HevcFormat.parameterSets(lengthPrefixed));
    }
}
