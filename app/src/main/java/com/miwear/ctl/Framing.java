package com.miwear.ctl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** MiWear L1/L2 帧编解码（逆向自 com.xiaomi.wearable.transport） */
public final class Framing {
    private Framing() {}

    public static final short MAGIC = (short) 0xA5A5;
    public static final byte TYPE_NAK = 0, TYPE_ACK = 1, TYPE_CMD = 2, TYPE_DATA = 3;

    public static final byte CH_PB = 1, CH_MASS = 2, CH_VOICE = 3, CH_FILE_SENSOR = 4,
                             CH_FILE_FITNESS = 5, CH_OTA = 6, CH_NETWORK = 7, CH_LYRA = 8;
    public static final byte OP_WRITE = 1, OP_WRITE_ENC = 2, OP_READ = 3;

    /** CRC-16/ARC（多项式 0x8005 反射 0xA001，初值 0） */
    public static int crc16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++)
                crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
        }
        return crc & 0xFFFF;
    }

    /** 组一个 L1 帧；payload 已含 L2 头 */
    public static byte[] build(byte type, byte seq, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(MAGIC);
        bb.put((byte) (type & 0x0F));          // frx=0 (NRX)
        bb.put(seq);
        bb.putShort((short) payload.length);
        bb.putShort((short) crc16(payload));
        bb.put(payload);
        return bb.array();
    }

    /** 组 L2 载荷 */
    public static byte[] l2(byte channel, byte opCode, byte[] data) {
        byte[] r = new byte[2 + data.length];
        r[0] = channel; r[1] = opCode;
        System.arraycopy(data, 0, r, 2, data.length);
        return r;
    }

    public static final class Frame {
        public byte type, seq;
        public byte[] payload;
        public Frame(byte t, byte s, byte[] p) { type = t; seq = s; payload = p; }
        public byte channel() { return payload.length > 0 ? payload[0] : -1; }
        public byte opCode()  { return payload.length > 1 ? payload[1] : -1; }
        public byte[] data()  { return payload.length > 2 ? java.util.Arrays.copyOfRange(payload, 2, payload.length) : new byte[0]; }
        @Override public String toString() {
            String tn = type == TYPE_ACK ? "ACK" : type == TYPE_CMD ? "CMD" : type == TYPE_DATA ? "DATA" : "NAK";
            return tn + " seq=" + (seq & 0xFF) + " ch=" + channel() + " op=" + opCode()
                 + " len=" + payload.length + " " + Crypto.hex(payload);
        }
    }

    /** 从缓冲区里切出所有完整 L1 帧，返回消耗的字节数写入 consumed[0] */
    public static List<Frame> parse(byte[] buf, int len, int[] consumed) {
        List<Frame> out = new ArrayList<>();
        int i = 0;
        while (i + 8 <= len) {
            if (!(buf[i] == (byte) 0xA5 && buf[i + 1] == (byte) 0xA5)) { i++; continue; }
            int dlen = (buf[i + 4] & 0xFF) | ((buf[i + 5] & 0xFF) << 8);
            int tot = 8 + dlen;
            if (i + tot > len) break;
            byte[] pl = new byte[dlen];
            System.arraycopy(buf, i + 8, pl, 0, dlen);
            int crc = (buf[i + 6] & 0xFF) | ((buf[i + 7] & 0xFF) << 8);
            out.add(new Frame((byte) (buf[i + 2] & 0x0F), buf[i + 3], pl));
            if (crc16(pl) != crc) out.get(out.size() - 1).seq = (byte) 0xEE; // 标记 CRC 错
            i += tot;
        }
        if (consumed != null && consumed.length > 0) consumed[0] = i;
        return out;
    }
}
