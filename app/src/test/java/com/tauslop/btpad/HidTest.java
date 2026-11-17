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
        long lmin = 0, lmax = 0;
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
            long signed = len == 0 ? 0 : value << (64 - 8 * len) >> (64 - 8 * len);
            if ((tag == 0x80 || tag == 0x90 || tag == 0xB0) && (value & 1) == 0) {
                // Data item: the logical range must fit the field (signed if min < 0).
                assertTrue("size at " + i, size > 0 && size <= 32 && count > 0);
                long hi = lmin < 0 ? (1L << (size - 1)) - 1 : (1L << size) - 1;
                long lo = lmin < 0 ? -(1L << (size - 1)) : 0;
                assertTrue("logical range vs size at " + i, lmin >= lo && lmax <= hi && lmin <= lmax);
            }
            switch (tag) {
                case 0x74: size = (int) value; break;            // Report Size
                case 0x94: count = (int) value; break;           // Report Count
                case 0x84: id = (int) value; break;              // Report ID
                case 0x14: lmin = signed; break;                 // Logical Minimum
                case 0x24: lmax = lmin < 0 ? signed : value; break; // Logical Maximum
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
        assertEquals(4, b.size());  // nothing else, and no data before the first report ID
        assertNull(b.get("in:0"));
    }

    @Test
    public void descriptorIsStable() {
        // Hosts cache the descriptor: any change here forces users to re-pair.
        assertEquals(153, Hid.DESCRIPTOR.length);
        long h = 0;
        for (byte x : Hid.DESCRIPTOR) h = h * 31 + (x & 0xFF);
        assertEquals(DESCRIPTOR_HASH, h);
    }

    private static final long DESCRIPTOR_HASH = -883425754859060617L;

    /**
     * Android 9-15 HID_DevAddRecord (system/bt stack/hid/hidd_api.cc) builds the
     * HIDDescriptorList attribute as 35 seq_len 08 22 25 desc_len descriptor, with
     * {@code uint8_t seq_len = 4 + desc_len}, and SDP_AddAttribute drops any attribute that
     * would overflow the record's SDP_MAX_PAD_LEN (600) byte pad shared by all attributes.
     */
    @Test
    public void descriptorFitsAndroidSdpRecord() {
        int desc = Hid.DESCRIPTOR.length;
        int seqLen = 4 + desc;                  // class type 08 22 + string header 25 len
        assertTrue("seq_len overflows uint8_t: " + seqLen, seqLen <= 255);
        assertTrue(desc <= Hid.SDP_MAX_DESCRIPTOR);
        int attr = 2 + seqLen;                  // 35 seq_len header
        assertTrue("SDP_MAX_ATTR_LEN", attr < 400); // sdp_server.cc partial-response limit

        // Record pad use, in HID_DevAddRecord order (value bytes only, as stored).
        int before = 4                          // service record handle (SDP_CreateRecord)
                + 3                             // service class ID list: one UUID16
                + 13                            // protocol list: L2CAP(PSM) + HIDP
                + 9                             // language base attribute ID list
                + 15                            // additional protocol list
                + Hid.SDP_NAME.length() + 1     // NUL-terminated strings
                + Hid.SDP_DESCRIPTION.length() + 1
                + Hid.SDP_PROVIDER.length() + 1
                + 8                             // profile descriptor list
                + 2 + 2 + 1 + 1 + 1 + 1;        // relnum, parser, subclass, country, 2 bools
        assertTrue("record pad", before + attr < 600); // sdp_db.cc: free_pad_ptr + len >= 600
        int after = 8 + 1 + 1 + 1 + 1 + 2 + 3;  // lang ID base, 4 bools, profile ver, browse
        assertTrue("record pad incl. trailing attributes", before + attr + after < 600);
    }

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
    public void oneByteUnwrapsReportId() {
        assertEquals(3, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{3}));
        assertEquals(3, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{1, 3}));
        assertEquals(Hid.LED_CAPS_LOCK, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{1, 2}));
        assertEquals(Hid.LED_CAPS_LOCK, Hid.oneByte(Hid.ID_KEYBOARD, new byte[]{2}));
        assertEquals(-1, Hid.oneByte(1, new byte[0]));
        assertEquals(-1, Hid.oneByte(1, null));
    }
}
