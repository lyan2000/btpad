package com.tauslop.btpad;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class HidTest {
    /** Report sizes in bits per "type:id", computed by walking the descriptor items. */
    private static Map<String, Integer> reportBits() {
        byte[] d = Hid.DESCRIPTOR;
        Map<String, Integer> bits = new HashMap<>();
        int size = 0, count = 0, id = 0, depth = 0;
        int i = 0;
        while (i < d.length) {
            int prefix = d[i] & 0xFF;
            assertTrue("long items unsupported", prefix != 0xFE);
            int len = prefix & 3;
            if (len == 3) len = 4;
            assertTrue("item overruns descriptor at " + i, i + 1 + len <= d.length);
            long value = 0;
            for (int k = 0; k < len; k++) value |= (long) (d[i + 1 + k] & 0xFF) << (8 * k);
            int tag = prefix & 0xFC;
            switch (tag) {
                case 0x74: size = (int) value; break;            // Report Size
                case 0x94: count = (int) value; break;           // Report Count
                case 0x84: id = (int) value; break;              // Report ID
                case 0xA0: depth++; break;                        // Collection
                case 0xC0: depth--; assertTrue(depth >= 0); break; // End Collection
                case 0x80: bits.merge("in:" + id, size * count, Integer::sum); break;
                case 0x90: bits.merge("out:" + id, size * count, Integer::sum); break;
                case 0xB0: bits.merge("feat:" + id, size * count, Integer::sum); break;
                default: break;
            }
            i += 1 + len;
        }
        assertEquals("unbalanced collections", 0, depth);
        return bits;
    }

    @Test
    public void descriptorReportSizesMatchEncoders() {
        Map<String, Integer> b = reportBits();
        assertEquals(Hid.KEYBOARD_LEN * 8, (int) b.get("in:1"));
        assertEquals(8, (int) b.get("out:1"));
        assertEquals(Hid.MOUSE_LEN * 8, (int) b.get("in:2"));
        assertEquals(Hid.CONSUMER_LEN * 8, (int) b.get("in:3"));
        assertEquals(Hid.TOUCHPAD_LEN * 8, (int) b.get("in:4"));
        assertEquals(8, (int) b.get("feat:5"));
        assertEquals(Hid.PTPHQA_LEN * 8, (int) b.get("feat:6"));
        assertEquals(8, (int) b.get("feat:7"));
        assertEquals(8, (int) b.get("feat:8"));
        assertEquals(9, b.size());  // nothing else, and no data before the first report ID
        assertNull(b.get("in:0"));
    }

    @Test
    public void descriptorIsStable() {
        // Hosts cache the descriptor: any change here forces users to re-pair.
        assertEquals(588, Hid.DESCRIPTOR.length);
        long h = 0;
        for (byte x : Hid.DESCRIPTOR) h = h * 31 + (x & 0xFF);
        assertEquals(DESCRIPTOR_HASH, h);
    }

    private static final long DESCRIPTOR_HASH = 2657142527738071708L;

    @Test
    public void keyboard() {
        assertArrayEquals(new byte[]{0x02, 0, 0x04, 0x05, 0, 0, 0, 0}, Hid.keyboard(0x02, 0x04, 0x05));
        assertEquals(8, Hid.keyboard(0, 1, 2, 3, 4, 5, 6, 7).length); // extra keys ignored
    }

    @Test
    public void mouse() {
        assertArrayEquals(new byte[]{1, 10, 0, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, 1},
                Hid.mouse(1, 10, -2, -1, 1));
        byte[] r = Hid.mouse(0xFF, 100000, -100000, 500, -500);
        assertEquals(0x1F, r[0]);
        assertEquals(32767, (short) ((r[1] & 0xFF) | r[2] << 8));
        assertEquals(-32767, (short) ((r[3] & 0xFF) | r[4] << 8));
        assertEquals(127, r[5]);
        assertEquals(-127, r[6]);
    }

    @Test
    public void consumer() {
        assertArrayEquals(new byte[]{(byte) 0xCD, 0}, Hid.consumer(0xCD));
        assertArrayEquals(new byte[]{0x38, 0x02}, Hid.consumer(0x238));
    }

    @Test
    public void touchpad() {
        byte[] r = Hid.touchpad(2, new int[]{0, 5}, new int[]{0x123, 5000}, new int[]{0x456, -1},
                new boolean[]{true, false}, 0xBEEF, true);
        assertEquals(Hid.TOUCHPAD_LEN, r.length);
        assertEquals(0x03, r[0]);                    // confidence + tip, id 0
        assertEquals(0x23, r[1]);
        assertEquals(0x01, r[2]);
        assertEquals(0x56, r[3]);
        assertEquals(0x04, r[4]);
        assertEquals(1 | 5 << 2, r[5]);              // confidence, lifted, id 5
        assertEquals(Hid.PAD_MAX, (r[6] & 0xFF) | (r[7] & 0xFF) << 8); // clamped
        assertEquals(0, r[8] | r[9]);
        for (int i = 10; i < 25; i++) assertEquals(0, r[i]); // unused slots
        assertEquals((byte) 0xEF, r[25]);
        assertEquals((byte) 0xBE, r[26]);
        assertEquals(2, r[27]);
        assertEquals(1, r[28]);
    }

    @Test
    public void features() {
        assertArrayEquals(new byte[]{0x05}, Hid.feature(Hid.ID_MAX_CONTACTS, 0));
        byte[] blob = Hid.feature(Hid.ID_PTPHQA, 0);
        assertEquals(256, blob.length);
        assertEquals((byte) 0xFC, blob[0]);
        assertEquals((byte) 0xC2, blob[255]);
        assertArrayEquals(new byte[]{3}, Hid.feature(Hid.ID_INPUT_MODE, 3));
        assertNull(Hid.feature(Hid.ID_KEYBOARD, 0));
        assertNull(Hid.feature(99, 0));
    }

    @Test
    public void oneByteUnwrapsReportId() {
        assertEquals(3, Hid.oneByte(Hid.ID_INPUT_MODE, new byte[]{3}));
        assertEquals(3, Hid.oneByte(Hid.ID_INPUT_MODE, new byte[]{7, 3}));
        assertEquals(Hid.LED_CAPS_LOCK, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{1, 2}));
        assertEquals(Hid.LED_CAPS_LOCK, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{2}));
        assertEquals(-1, Hid.oneByte(1, new byte[0]));
        assertEquals(-1, Hid.oneByte(1, null));
    }
}
