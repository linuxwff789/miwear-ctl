package com.miwear.ctl;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 极简 protobuf 读写 */
public final class PB {
    private PB() {}

    public static final class F {
        public int field, wire;
        public long varint;
        public byte[] bytes;
    }

    public static List<F> parse(byte[] b) {
        List<F> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            long key = 0; int sh = 0;
            while (i < b.length) { int x = b[i++] & 0xFF; key |= (long) (x & 0x7F) << sh; sh += 7; if ((x & 0x80) == 0) break; }
            F f = new F();
            f.field = (int) (key >> 3); f.wire = (int) (key & 7);
            if (f.wire == 0) {
                long v = 0; sh = 0;
                while (i < b.length) { int x = b[i++] & 0xFF; v |= (long) (x & 0x7F) << sh; sh += 7; if ((x & 0x80) == 0) break; }
                f.varint = v;
            } else if (f.wire == 2) {
                long ln = 0; sh = 0;
                while (i < b.length) { int x = b[i++] & 0xFF; ln |= (long) (x & 0x7F) << sh; sh += 7; if ((x & 0x80) == 0) break; }
                f.bytes = new byte[(int) ln];
                System.arraycopy(b, i, f.bytes, 0, (int) ln);
                i += (int) ln;
            } else break;
            out.add(f);
        }
        return out;
    }

    public static void varint(ByteArrayOutputStream o, long v) {
        while (true) {
            int x = (int) (v & 0x7F); v >>>= 7;
            if (v != 0) o.write(x | 0x80); else { o.write(x); break; }
        }
    }

    /** 写字段 key：field<<3 | wire。⚠️ field ≥ 16 时必须用 varint（不能只写一个字节） */
    private static void key(ByteArrayOutputStream o, int field, int wire) {
        varint(o, ((long) field << 3) | wire);
    }

    public static void str(ByteArrayOutputStream o, int field, String s) {
        try { byte[] b = s.getBytes("UTF-8"); key(o, field, 2); varint(o, b.length); o.write(b, 0, b.length); }
        catch (Exception ignored) {}
    }

    public static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        key(o, field, 2); varint(o, b.length); o.write(b, 0, b.length);
    }

    /** 写 float（wire type 5，小端 fixed32）—— 绑定时 id0.f2 = SDK_INT */
    public static void f32(ByteArrayOutputStream o, int field, float v) {
        key(o, field, 5);
        int bits = Float.floatToIntBits(v);
        o.write(bits & 0xFF); o.write((bits >>> 8) & 0xFF);
        o.write((bits >>> 16) & 0xFF); o.write((bits >>> 24) & 0xFF);
    }

    /** 写 varint 字段 */
    public static void num(ByteArrayOutputStream o, int field, long v) {
        key(o, field, 0); varint(o, v);
    }

    /** 写嵌套消息（等价于 bytes） */
    public static void msg(ByteArrayOutputStream o, int field, byte[] b) { bytes(o, field, b); }
}
