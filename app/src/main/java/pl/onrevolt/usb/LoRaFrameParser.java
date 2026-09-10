package pl.onrevolt.usb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

class LoRaFrameParser {

    private static final byte SYNC_0 = (byte) 0xA5;
    private static final byte SYNC_1 = (byte) 0x5A;
    private static final int MAX_BODY_LENGTH = 4096;
    private static final String WORD_ORDER = "hi_lo";

    private byte[] buffer = new byte[8192];
    private int bufferLen = 0;

    public List<String> feed(byte[] chunk) {
        append(chunk);
        ArrayList<String> out = new ArrayList<>();

        while (true) {
            int syncPos = findSync();
            if (syncPos < 0) {
                // zostaw max 1 ostatni bajt, gdyby był początkiem sync
                if (bufferLen > 1) {
                    buffer[0] = buffer[bufferLen - 1];
                    bufferLen = 1;
                }
                return out;
            }

            if (syncPos > 0) {
                discard(syncPos);
            }

            // sync(2) + len(2)
            if (bufferLen < 4) {
                return out;
            }

            int bodyLen = u16le(buffer, 2);
            if (bodyLen < 12 || bodyLen > MAX_BODY_LENGTH) {
                // zły frame, przesuń o 1 bajt i szukaj dalej
                discard(1);
                continue;
            }

            int totalLen = 2 + 2 + bodyLen;
            if (bufferLen < totalLen) {
                return out;
            }

            byte[] body = Arrays.copyOfRange(buffer, 4, totalLen);
            discard(totalLen);

            try {
                out.addAll(parseBody(body));
            } catch (Exception e) {
                out.add("ERR: " + e.getMessage());
            }
        }
    }

    private List<String> parseBody(byte[] body) {
        ArrayList<String> lines = new ArrayList<>();

        // VER(1) SLAVE(1) START(2LE) COUNT(2LE) UPTIME(4LE) REGS(count*2) CRC(2LE)
        if (body.length < 12) {
            throw new IllegalArgumentException("frame too short");
        }

        int ver = u8(body[0]);
        int slave = u8(body[1]);
        int start = u16le(body, 2);
        int count = u16le(body, 4);
        long uptime = u32le(body, 6);

        int regsOff = 10;
        int regsLen = count * 2;
        int crcOff = regsOff + regsLen;

        if (body.length != crcOff + 2) {
            throw new IllegalArgumentException("length mismatch");
        }

        int recvCrc = u16le(body, crcOff);
        int calcCrc = crc16Modbus(body, 0, crcOff);
        if (recvCrc != calcCrc) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "bad crc calc=0x%04X recv=0x%04X", calcCrc, recvCrc));
        }

        int[] regs = new int[count];
        for (int i = 0; i < count; i++) {
            regs[i] = u16le(body, regsOff + i * 2);
        }

        float VL1N = f32(regs, start, 0x00, WORD_ORDER);
        float VL2N = f32(regs, start, 0x02, WORD_ORDER);
        float VL3N = f32(regs, start, 0x04, WORD_ORDER);

        float AL1 = f32(regs, start, 0x0C, WORD_ORDER);
        float AL2 = f32(regs, start, 0x0E, WORD_ORDER);
        float AL3 = f32(regs, start, 0x10, WORD_ORDER);

        float WL1 = f32(regs, start, 0x12, WORD_ORDER);
        float WL2 = f32(regs, start, 0x14, WORD_ORDER);
        float WL3 = f32(regs, start, 0x16, WORD_ORDER);
        float WSYS = f32(regs, start, 0x28, WORD_ORDER);

        float HZ = f32(regs, start, 0x32, WORD_ORDER);
        float kWhPTOT = f32(regs, start, 0x34, WORD_ORDER);

        lines.add("-----");
        lines.add(String.format(Locale.US,
                "VER=%d SLAVE=%d start=0x%04X count=%d uptime_s=%d",
                ver, slave, start, count, uptime));
        lines.add(String.format(Locale.US,
                "U: L1N=%.2fV  L2N=%.2fV  L3N=%.2fV",
                VL1N, VL2N, VL3N));
        lines.add(String.format(Locale.US,
                "I: L1=%.3fA   L2=%.3fA   L3=%.3fA",
                AL1, AL2, AL3));
        lines.add(String.format(Locale.US,
                "P: L1=%.1fW   L2=%.1fW   L3=%.1fW   SYS=%.1fW",
                WL1, WL2, WL3, WSYS));
        lines.add(String.format(Locale.US,
                "F: %.2f Hz   E+: %.3f kWh",
                HZ, kWhPTOT));

        return lines;
    }

    private void append(byte[] chunk) {
        ensureCapacity(bufferLen + chunk.length);
        System.arraycopy(chunk, 0, buffer, bufferLen, chunk.length);
        bufferLen += chunk.length;
    }

    private void ensureCapacity(int wanted) {
        if (wanted <= buffer.length) return;
        int newSize = buffer.length;
        while (newSize < wanted) newSize *= 2;
        buffer = Arrays.copyOf(buffer, newSize);
    }

    private void discard(int n) {
        if (n <= 0) return;
        if (n >= bufferLen) {
            bufferLen = 0;
            return;
        }
        System.arraycopy(buffer, n, buffer, 0, bufferLen - n);
        bufferLen -= n;
    }

    private int findSync() {
        for (int i = 0; i < bufferLen - 1; i++) {
            if (buffer[i] == SYNC_0 && buffer[i + 1] == SYNC_1) {
                return i;
            }
        }
        return -1;
    }

    private static int u8(byte b) {
        return b & 0xFF;
    }

    private static int u16le(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] data, int off) {
        return ((long) (data[off] & 0xFF)) |
                (((long) (data[off + 1] & 0xFF)) << 8) |
                (((long) (data[off + 2] & 0xFF)) << 16) |
                (((long) (data[off + 3] & 0xFF)) << 24);
    }

    private static int crc16Modbus(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static float f32(int[] regs, int start, int addr, String wordOrder) {
        int i = addr - start;
        if (i < 0 || i + 1 >= regs.length) {
            throw new IllegalArgumentException("register out of range: 0x" + Integer.toHexString(addr));
        }

        int hi = regs[i] & 0xFFFF;
        int lo = regs[i + 1] & 0xFFFF;

        int bits;
        if ("hi_lo".equals(wordOrder)) {
            bits = (hi << 16) | lo;
        } else {
            bits = (lo << 16) | hi;
        }
        return Float.intBitsToFloat(bits);
    }
}