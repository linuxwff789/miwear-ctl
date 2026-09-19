package com.miwear.ctl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REDMI Watch 5 直连客户端（不依赖小米运动健康）
 * 传输：经典蓝牙 RFCOMM/SPP  →  L1/L2 帧  →  认证握手  →  加密会话
 */
public class WearLink {

    public interface Log { void log(String s); }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** 标准 SPP UUID；手表 SDP 记录里注册了它，用它可让系统自动解析 RFCOMM 通道号 */
    public static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Log log;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean running;
    private final ArrayDeque<Framing.Frame> queue = new ArrayDeque<>();
    private byte seq = 0;
    private byte cmdSeq = 0;
    private Crypto.Keys keys;

    /** 能力协商帧（抓包复刻，必须在 apiCode 之前发，否则手表会重启） */
    private static final byte[] HELLO = hex("030001000002020000fc03020020000402001027");

    public WearLink(Log log) { this.log = log; }

    // ───────────────────────── 连接 ─────────────────────────
    public void connect(String mac) throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new IllegalStateException("无蓝牙适配器");
        if (!ad.isEnabled()) throw new IllegalStateException("蓝牙未开启");
        BluetoothDevice dev = ad.getRemoteDevice(mac);
        log.log("连接 " + mac + " …");
        socket = dev.createRfcommSocketToServiceRecord(SPP);
        ad.cancelDiscovery();
        socket.connect();
        in = socket.getInputStream();
        out = socket.getOutputStream();
        running = true;
        reader = new Thread(this::readLoop, "miwear-rx");
        reader.setDaemon(true);
        reader.start();
        log.log("✅ 已连接 (SPP/RFCOMM)");
    }

    public void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        log.log("已断开");
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        while (running) {
            try {
                int n = in.read(buf);
                if (n < 0) break;
                acc.write(buf, 0, n);
                byte[] all = acc.toByteArray();
                int[] consumed = new int[1];
                List<Framing.Frame> fs = Framing.parse(all, all.length, consumed);
                synchronized (queue) {
                    for (Framing.Frame f : fs) {
                        log.log("← " + f + (f.seq == (byte) 0xEE ? "   [CRC 错!]" : ""));
                        queue.add(f);
                        queue.notifyAll();
                    }
                }
                if (consumed[0] > 0) {
                    acc.reset();
                    if (consumed[0] < all.length) acc.write(all, consumed[0], all.length - consumed[0]);
                }
            } catch (Exception e) {
                if (running) log.log("读取异常: " + e);
                break;
            }
        }
    }

    private void write(byte[] b) throws Exception {
        out.write(b);
        out.flush();
    }

    /** 发送一个 DATA 帧，返回其 seq */
    public byte sendData(byte channel, byte op, byte[] data) throws Exception {
        byte[] f = Framing.build(Framing.TYPE_DATA, seq, Framing.l2(channel, op, data));
        log.log("→ " + Framing.parse(f, f.length, null).get(0));
        write(f);
        return seq++;
    }

    /** 等一帧（可按 type 过滤），超时毫秒 */
    public Framing.Frame await(byte type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (queue) {
            while (true) {
                for (Framing.Frame f : queue) {
                    if (type < 0 || f.type == type) { queue.remove(f); return f; }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                queue.wait(left);
            }
        }
    }

    private void ack(Framing.Frame f) throws Exception {
        write(Framing.build(Framing.TYPE_ACK, f.seq, new byte[0]));
    }

    // ───────────────────────── 握手 ─────────────────────────
    /** 连接后第一步：CMD 能力协商。返回设备应答。 */
    public byte[] handshake() throws Exception {
        byte[] payload = Crypto.concat(new byte[]{Framing.CH_PB, Framing.OP_WRITE}, HELLO);
        byte[] f = Framing.build(Framing.TYPE_CMD, cmdSeq++, payload);
        log.log("→ " + Framing.parse(f, f.length, null).get(0));
        write(f);
        Framing.Frame r = await(Framing.TYPE_CMD, 6000);
        if (r == null) { log.log("⚠ 未收到 CMD 应答"); return null; }
        log.log("握手应答: " + Crypto.hex(r.payload));
        return r.payload;
    }

    // ───────────────────────── 认证 ─────────────────────────
    /** 完整认证握手；成功后 keys 可用 */
    public Crypto.Keys authenticate(byte[] secret) throws Exception {
        byte[] randomApp = new byte[16];
        new SecureRandom().nextBytes(randomApp);
        log.log("randomApp = " + Crypto.hex(randomApp));

        // apiCode 26 = sendAppVerify, field3 -> field30{ field1: randomApp }
        byte[] inner30 = Crypto.concat(new byte[]{0x0A, 0x10}, randomApp);
        byte[] f30 = Crypto.concat(new byte[]{(byte) 0xF2, 0x01, (byte) inner30.length}, inner30);
        byte[] verify = Crypto.concat(new byte[]{0x08, 0x01, 0x10, 0x1A, 0x1A, (byte) f30.length}, f30);
        sendData(Framing.CH_PB, Framing.OP_WRITE, verify);

        Framing.Frame resp = null;
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            Framing.Frame f = await((byte) -1, 500);
            if (f == null) continue;
            if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_PB) { ack(f); resp = f; break; }
        }
        if (resp == null) throw new IllegalStateException("设备无应答(apiCode 26)");
        byte[] body = resp.data();
        log.log("verify 应答: " + Crypto.hex(body));

        byte[] randomDevice = null, deviceSign = null;
        for (PB.F f3 : PB.parse(body)) {
            if (f3.field == 3 && f3.bytes != null) {
                for (PB.F f31 : PB.parse(f3.bytes)) {
                    if (f31.field == 31 && f31.bytes != null) {
                        for (PB.F g : PB.parse(f31.bytes)) {
                            if (g.field == 1) randomDevice = g.bytes;
                            if (g.field == 2) deviceSign = g.bytes;
                        }
                    }
                }
            }
        }
        if (randomDevice == null || deviceSign == null) throw new IllegalStateException("应答解析失败");
        log.log("randomDevice = " + Crypto.hex(randomDevice));

        keys = Crypto.Keys.derive(secret, randomApp, randomDevice);
        log.log("DeviceKey = " + Crypto.hex(keys.deviceKey));
        log.log("AppKey    = " + Crypto.hex(keys.appKey));
        log.log("DeviceIV  = " + Crypto.hex(keys.deviceIv) + "  AppIV = " + Crypto.hex(keys.appIv));

        byte[] expect = Crypto.hmac(keys.deviceKey, Crypto.concat(randomDevice, randomApp));
        log.log("设备签名校验: " + (java.util.Arrays.equals(expect, deviceSign) ? "✅ 通过" : "❌ 不匹配"));
        if (!java.util.Arrays.equals(expect, deviceSign)) throw new IllegalStateException("设备身份校验失败");

        // apiCode 27 = sendAppConfirm, field3 -> field32{ field1: appSign, field2: enc }
        byte[] appSign = Crypto.hmac(keys.appKey, Crypto.concat(randomApp, randomDevice));
        byte[] plain = buildAppInfo();
        byte[] nonce = Crypto.concat(keys.appIv, new byte[12]);
        byte[] enc = Crypto.ccm(true, keys.appKey, nonce, plain, 32);
        if (enc == null) throw new IllegalStateException("AES-GCM 加密失败");
        byte[] f32 = Crypto.concat(new byte[]{(byte) 0x82, 0x02, (byte) (2 + appSign.length + 2 + enc.length)},
                Crypto.concat(Crypto.concat(new byte[]{0x0A, 0x20}, appSign),
                              Crypto.concat(new byte[]{0x12, (byte) enc.length}, enc)));
        byte[] confirm = Crypto.concat(new byte[]{0x08, 0x01, 0x10, 0x1B, 0x1A, (byte) f32.length}, f32);
        sendData(Framing.CH_PB, Framing.OP_WRITE, confirm);

        Framing.Frame c = null;
        end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            Framing.Frame f = await((byte) -1, 500);
            if (f == null) continue;
            if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_PB) { ack(f); c = f; break; }
        }
        log.log("confirm 应答: " + (c == null ? "(无)" : Crypto.hex(c.data())));
        return keys;
    }

    /** jd0Var：app 信息（字段号逆向自 WearAuthV2，值可后续校准） */
    private byte[] buildAppInfo() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08); o.write(0x00);                       // f1 = 0
        o.write(0x10); PB.varint(o, android.os.Build.VERSION.SDK_INT);   // f2 = SDK
        PB.str(o, 3, android.os.Build.MODEL);               // f3 = 机型
        PB.str(o, 4, "CN");                                 // f4 = 区域
        return o.toByteArray();
    }

    // ───────────────────────── 命令 ─────────────────────────
    /** 发一条自定义 apiCode（ch=PB, op=WRITE），sub 为 field3 的子消息字节 */
    public void sendApi(int apiCode, byte[] sub) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08); o.write(0x01);
        o.write(0x10); PB.varint(o, apiCode);
        if (sub != null && sub.length > 0) { o.write(0x1A); PB.varint(o, sub.length); o.write(sub, 0, sub.length); }
        sendData(Framing.CH_PB, Framing.OP_WRITE, o.toByteArray());
    }

    /** 加密会话数据（op=WRITE_ENC） */
    public void sendEncrypted(byte channel, byte[] plain) throws Exception {
        if (keys == null) throw new IllegalStateException("未认证");
        byte[] nonce = Crypto.concat(keys.appIv, new byte[12]);
        byte[] enc = Crypto.ccm(true, keys.appKey, nonce, plain, 32);
        sendData(channel, Framing.OP_WRITE_ENC, enc);
    }
}
