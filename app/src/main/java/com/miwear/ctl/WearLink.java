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

    /** 由 MainActivity 注入，供需要系统服务的功能使用 */
    public static android.content.Context APP;

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
    private NetProxyBridge net;

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
                for (Framing.Frame f : fs) {
                    // 通道 7 = 手表联网：明文原始 IP 包，不需要 ACK
                    if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_NETWORK) {
                        byte[] ip = f.data();
                        if (ip.length > 0) {
                            log.log("← ch7 " + ip.length + "B  " + NetProxyBridge.ipSummary(ip));
                            if (net != null) net.upstream(ip);
                        }
                        continue;
                    }
                    // ⚠️ 除 ch7/ch10 外，所有 DATA 帧都必须立刻 ACK（官方 needSendAck）。
                    // 否则手表发送窗口被未确认帧塞满，之后什么都发不过来。
                    if (f.type == Framing.TYPE_DATA && f.channel() != 10) {
                        try { ack(f); } catch (Exception ignored) {}
                    }
                    // 认证后：解密 ch1 帧。module18/0 自动应答；设备心跳(2/2)丢弃；其余入队给 request()
                    if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_PB
                            && f.opCode() == Framing.OP_WRITE_ENC && keys != null) {
                        byte[] pt = Crypto.ctr(keys.deviceKey, f.data());
                        if (pt != null) {
                            long mod = -1, sub = -1;
                            for (PB.F g : PB.parse(pt)) {
                                if (g.field == 1) mod = g.varint;
                                else if (g.field == 2) sub = g.varint;
                            }
                            if (mod == 18 && sub == 0) {
                                if (net != null && net.isStarted()) {
                                    log.log("↩ 手表问联网能力，回 module18 sub1");
                                    sendNetCapability();
                                } else {
                                    log.log("↩ 手表问联网能力（网关未开，不应答）");
                                }
                                continue;
                            }
                            if (mod == 23 && sub == 0) {
                                log.log("↩ 手表问手机状态，回 module23 sub1");
                                sendPhoneStatus();
                                continue;
                            }
                            if (mod == 2 && sub == 2) {          // 设备信息心跳
                                continue;
                            }
                            log.log("← ch1 明文 " + Crypto.hex(pt));
                            synchronized (queue) { queue.add(f); queue.notifyAll(); }
                        }
                        continue;
                    }
                    synchronized (queue) {
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
            if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_PB) { resp = f; break; }
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
        byte[] nonce = Crypto.concat(keys.appIv, new byte[8]);   // 4+8 = 12B
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
            if (f.type == Framing.TYPE_DATA && f.channel() == Framing.CH_PB) { c = f; break; }
        }
        log.log("confirm 应答: " + (c == null ? "(无)" : Crypto.hex(c.data())));
        return keys;
    }

    /** 从 rpk 的 manifest.json 里取字段 */
    private static String parseManifest(byte[] rpk, String key) {
        try {
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(rpk));
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("manifest.json".equals(e.getName())) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[4096]; int n;
                    while ((n = zin.read(b)) > 0) bo.write(b, 0, n);
                    String js = new String(bo.toByteArray(), "UTF-8");
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]+)\"?").matcher(js);
                    if (m.find()) return m.group(1).trim();
                }
            }
        } catch (Exception ex) { }
        return null;
    }

    /** jd0Var：app 信息（字段号/类型逆向自抓包：f2 是 float、f4 是版本号） */
    private byte[] buildAppInfo() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08); o.write(0x00);                       // f1 = 0
        o.write(0x15);                                      // f2 = float(34.0)（官方是 fixed32 不是 varint！）
        o.write(0x00); o.write(0x00); o.write(0x08); o.write(0x42);
        PB.str(o, 3, android.os.Build.MODEL);               // f3 = 机型
        o.write(0x20); PB.varint(o, 25237220L);             // f4 = 版本号
        PB.str(o, 5, "CN");                                 // f5 = 区域
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

    /**
     * 加密发送（op=WRITE_ENC）：body = AES-CTR(AppKey, data)，IV = AppKey，无 MAC
     */
    public void sendEncrypted(byte channel, byte[] plain) throws Exception {
        if (keys == null) throw new IllegalStateException("未认证");
        byte[] enc = Crypto.ctr(keys.appKey, plain);
        if (enc == null) throw new IllegalStateException("AES-CTR 加密失败");
        sendData(channel, Framing.OP_WRITE_ENC, enc);
    }

    /** 解密收到的 op=2 帧 */
    public byte[] decryptIncoming(byte[] body) {
        if (keys == null) return null;
        return Crypto.ctr(keys.deviceKey, body);
    }

    // ───────────────────── 通知推送（module 7）─────────────────────
    /**
     * 报文结构（逆向自 BlueToothSender.addNotifications / oyt / kli / lli）：
     *   oyt{ f1=7(通知模块) f2=0(添加) f9=kli{ f3=lli.e{ f1 repeated=lli } } }
     *   lli{ f1=包名 f2=标题 f3=内容 f4=应用名 f7=id f12=key }
     */
    public void pushNotification(String pkg, String title, String text, String appName, int id) throws Exception {
        if (keys == null) throw new IllegalStateException("未认证");

        // 字段表逆向自 BaseNotifySyncService.handleNotificationPosted（权威）
        String key = "0|" + pkg + "|" + id + "|null|1000";
        String time = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                          .format(new java.util.Date());

        ByteArrayOutputStream lli = new ByteArrayOutputStream();
        PB.str(lli, 1, pkg);                      // f1  包名
        PB.str(lli, 2, appName);                  // f2  应用名
        PB.str(lli, 3, title);                    // f3  标题
        PB.str(lli, 4, "");                       // f4
        PB.str(lli, 5, text);                     // f5  内容
        PB.str(lli, 6, time);                     // f6  时间
        lli.write(0x38); PB.varint(lli, id);      // f7  id
        PB.str(lli, 9, "");                       // f9  appGroup
        PB.str(lli, 12, key);                     // f12 key
        lli.write((byte) 0x80); lli.write(0x01); lli.write(0x01);   // f16 = true

        ByteArrayOutputStream llie = new ByteArrayOutputStream();
        PB.bytes(llie, 1, lli.toByteArray());     // lli.e.f1 repeated

        ByteArrayOutputStream kli = new ByteArrayOutputStream();
        PB.bytes(kli, 3, llie.toByteArray());     // kli.f3

        ByteArrayOutputStream oyt = new ByteArrayOutputStream();
        oyt.write(0x08); PB.varint(oyt, 7);       // f1 模块=通知
        oyt.write(0x10); PB.varint(oyt, 0);       // f2 子命令=添加
        PB.bytes(oyt, 9, kli.toByteArray());      // f9

        log.log("通知 oyt = " + Crypto.hex(oyt.toByteArray()));
        sendEncrypted(Framing.CH_PB, oyt.toByteArray());
    }

    // ───────────────── 手表联网（L2 通道 7）─────────────────
    /**
     * 启动联网网关：手表自带 TCP/IP 栈，把原始 IP 包丢过来；
     * 这里用小米自己的 libnetproxy.so 做 NAT，再把回包发回手表。
     */
    public void startNetProxy() {
        if (net == null) net = new NetProxyBridge(log, this::sendNetData);
        net.start();
        if (!net.isStarted()) return;
        // init 只重放一次（里面含 2/14(4)/(2)，反复发会把网络状态又按回去）
        sendHexList(NET_INIT, 40);
        // 之后只周期维持「网络可用(2/14 f1=1) + 联网能力(18/1)」
        new Thread(() -> {
            for (int r = 0; r < 40; r++) {
                try {
                    Thread.sleep(2500);
                    if (!net.isStarted()) return;
                    sendHexList(new String[]{ "0802100e22059202020801", "08121001a201040a02080a" }, 250);
                } catch (InterruptedException ignored) { return; }
            }
        }, "netproxy-hello").start();
    }
    public void stopNetProxy() { if (net != null) net.stop(); }

    public boolean netProxyRunning() { return net != null && net.isStarted(); }

    /** 告诉手表「我支持联网」：module 18 sub 1（抓包复刻 oyt{f1=18,f2=1,f20={f1={f1=10}}}） */
    public void sendNetCapability() {
        try {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.write(0x08); PB.varint(inner, 10);                 // {f1: 10}
            ByteArrayOutputStream x = new ByteArrayOutputStream();
            PB.bytes(x, 1, inner.toByteArray());                     // x5h{f1: {f1:10}}
            byte[] body = oyt(18, 1, 20, x.toByteArray());
            log.log("→ 联网能力 " + Crypto.hex(body));
            sendEncrypted(Framing.CH_PB, body);
        } catch (Exception e) { log.log("❌ 回联网能力失败: " + e); }
    }

    /** 告诉手表网络状态（抓包：module 2 sub 14，oyt{f1=2,f2=14,f4={f34={f1=1}}}） */
    public void sendNetStatus() {
        try {
            ByteArrayOutputStream a = new ByteArrayOutputStream();
            a.write(0x08); PB.varint(a, 1);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            PB.bytes(b, 34, a.toByteArray());
            byte[] body = oyt(2, 14, 4, b.toByteArray());
            log.log("→ 网络状态 " + Crypto.hex(body));
            sendEncrypted(Framing.CH_PB, body);
        } catch (Exception e) { log.log("❌ 发网络状态失败: " + e); }
    }

    /** 把一个 IP 包发回手表（明文，通道 7） */
    private void sendNetData(byte[] ip) throws Exception {
        byte[] f = Framing.build(Framing.TYPE_DATA, seq, Framing.l2(Framing.CH_NETWORK, Framing.OP_WRITE, ip));
        log.log("→ ch7 " + ip.length + "B  " + NetProxyBridge.ipSummary(ip));
        write(f);
        seq++;
    }

    /** 抓包复刻：官方 App 连上后发的初始化序列（与官方抓包逐字节一致，含重复项） */
    private static final String[] NET_INIT = {
        "08021002",                          // 2/2
        "0802105c2205da03020801",            // 2/92  f58.f1=1
        "0808101d5200",                      // 8/29
        "0808101e5207ca010408011003",        // 8/30
        "0805100a3a00",                      // 5/10
        "0802100e22059202020804",            // 2/14  f34.f1=4
        "0802100e22059202020804",            // 2/14  f34.f1=4
        "08021002",                          // 2/2
        "08021002",                          // 2/2
        "0802102c2207aa02040a020800",        // 2/44
        "08021006220aa201070a057a685f636e",  // 2/6   locale zh_cn
        "08021002",                          // 2/2
        "0802100e22059202020802",            // 2/14  f34.f1=2
        "08141000",                          // 20/0  列应用
        "08121001a201040a02080a",            // 18/1  联网能力
        "08021002",                          // 2/2
        "0802100e22059202020801",            // 2/14  f34.f1=1
        "08121001a201040a02080a",            // 18/1  联网能力
    };

    /** 发一串明文 oyt（加密后发出） */
    public void sendHexList(String[] hexes, int gapMs) {
        new Thread(() -> {
            for (String h : hexes) {
                if (net == null || !net.isStarted()) return;
                try {
                    byte[] b = new byte[h.length() / 2];
                    for (int i = 0; i < b.length; i++)
                        b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                    sendEncrypted(Framing.CH_PB, b);
                    log.log("→ init " + h);
                    Thread.sleep(gapMs);
                } catch (InterruptedException ie) { return; }
                catch (Exception e) { log.log("❌ init 发送失败: " + e); }
            }
        }, "net-init").start();
    }

    // ───────────────── 应用管理（module 20）─────────────────

    /** 组一条 oyt：f1=module, f2=sub，可选 payload 放在 payloadField */
    private static byte[] oyt(int module, int sub, int payloadField, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08); PB.varint(o, module);
        o.write(0x10); PB.varint(o, sub);
        if (payload != null && payloadField > 0) PB.bytes(o, payloadField, payload);
        return o.toByteArray();
    }

    /** 发一条加密 oyt 并等**模块匹配**的应答，返回解密后的明文（超时返回 null） */
    public byte[] request(byte[] body, long timeoutMs) throws Exception {
        if (keys == null) throw new IllegalStateException("未认证");
        long wantMod = -1, wantSub = -1;
        for (PB.F g : PB.parse(body)) {
            if (g.field == 1) wantMod = g.varint;
            else if (g.field == 2) wantSub = g.varint;
        }
        sendEncrypted(Framing.CH_PB, body);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Framing.Frame f = await((byte) -1, 500);
            if (f == null) continue;
            if (f.type != Framing.TYPE_DATA || f.channel() != Framing.CH_PB) continue;
            byte[] pt = Crypto.ctr(keys.deviceKey, f.data());
            if (pt == null) continue;
            if (wantMod >= 0) {
                long m = -1, s = -1;
                for (PB.F g : PB.parse(pt)) {
                    if (g.field == 1) m = g.varint;
                    else if (g.field == 2) s = g.varint;
                }
                if (m != wantMod || (wantSub >= 0 && s != wantSub)) continue;  // 不是我们要的应答
            }
            return pt;
        }
        return null;
    }

    /** 发任意 oyt（hex 字节）并打印应答 —— 调试用 */
    public byte[] rawCall(byte[] body, long timeoutMs) throws Exception {
        byte[] pt = request(body, timeoutMs);
        log.log("应答明文: " + (pt == null ? "(无)" : Crypto.hex(pt)));
        return pt;
    }

    /** rxr{f1=包名, f2=指纹} */
    private static byte[] rxr(String pkg, byte[] fp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PB.str(o, 1, pkg);
        if (fp != null && fp.length > 0) PB.bytes(o, 2, fp);
        return o.toByteArray();
    }

    /** module 20 sub 0：列出已安装快应用。每项 = [包名, 版本, 指纹hex, 备注] */
    public List<String[]> listApps() throws Exception {
        byte[] pt = request(oyt(20, 0, 0, null), 25000);
        List<String[]> out = new ArrayList<>();
        if (pt == null) { log.log("应用列表：设备无应答"); return out; }
        log.log("应用列表明文: " + Crypto.hex(pt));
        for (PB.F f : PB.parse(pt)) {
            if (f.field != 22 || f.bytes == null) continue;          // oyt.f22 = yxr
            for (PB.F y : PB.parse(f.bytes)) {
                if (y.field != 1 || y.bytes == null) continue;       // yxr.f1 = pxr.a
                for (PB.F p : PB.parse(y.bytes)) {                   // pxr.a.f1 = repeated pxr
                    if (p.field != 1 || p.bytes == null) continue;
                    String pkg = "?", note = ""; int ver = 0; boolean on = false; byte[] fp = null;
                    for (PB.F g : PB.parse(p.bytes)) {
                        if (g.field == 1 && g.bytes != null) pkg = new String(g.bytes, "UTF-8");
                        else if (g.field == 2) fp = g.bytes;
                        else if (g.field == 3) ver = (int) g.varint;
                        else if (g.field == 4) on = g.varint != 0;
                        else if (g.field == 5 && g.bytes != null) note = new String(g.bytes, "UTF-8");
                    }
                    out.add(new String[]{ pkg, String.valueOf(ver),
                                          fp == null ? "" : Crypto.hex(fp), on ? "运行中" : "" });
                    log.log("  • " + pkg + "  v" + ver + (on ? "  [运行中]" : "")
                            + (fp == null ? "" : "  fp=" + Crypto.hex(fp))
                            + (note.isEmpty() ? "" : "  " + note));
                }
            }
        }
        log.log("共 " + out.size() + " 个应用");
        return out;
    }

    /** module 23 sub 1：回答手表的「手机状态」询问（oyt{f1=23,f2=1,f25={f1={f1=状态}}}）
     *  状态：0=熄屏 1=锁屏但亮屏 2=亮屏已解锁 */
    public void sendPhoneStatus() {
        try {
            int st = 0;
            try {
                android.app.KeyguardManager km = (android.app.KeyguardManager)
                        APP.getSystemService(android.content.Context.KEYGUARD_SERVICE);
                android.os.PowerManager pm = (android.os.PowerManager)
                        APP.getSystemService(android.content.Context.POWER_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                boolean interactive = pm != null && pm.isInteractive();
                st = interactive ? (locked ? 1 : 2) : 0;
            } catch (Throwable ignored) {}
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x08); PB.varint(d, st);
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            PB.bytes(v, 1, d.toByteArray());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0x08); PB.varint(o, 23);
            o.write(0x10); PB.varint(o, 1);
            PB.bytes(o, 25, v.toByteArray());
            sendEncrypted(Framing.CH_PB, o.toByteArray());
            log.log("→ 手机状态 " + st);
        } catch (Exception e) { log.log("❌ 回手机状态失败: " + e); }
    }

    /** module 8 sub 29：查询手表状态（电量等）。应答 oyt{f10={f24={f1,f2=时间戳,f3={f1=电量%},f7}}} */
    public byte[] deviceStatus() throws Exception {
        byte[] pt = request(hex("0808101d5200"), 8000);
        log.log("设备状态明文: " + (pt == null ? "(无应答)" : Crypto.hex(pt)));
        if (pt == null) return null;
        for (PB.F f10 : PB.parse(pt)) {
            if (f10.field != 10 || f10.bytes == null) continue;
            for (PB.F f24 : PB.parse(f10.bytes)) {
                if (f24.field != 24 || f24.bytes == null) continue;
                for (PB.F g : PB.parse(f24.bytes)) {
                    if (g.field == 3 && g.bytes != null) {
                        for (PB.F b : PB.parse(g.bytes))
                            if (b.field == 1) log.log("  🔋 电量 = " + b.varint + "%");
                    } else if (g.field == 2) {
                        log.log("  时间戳 = " + g.varint);
                    } else if (g.field == 7) {
                        log.log("  f7 = " + g.varint);
                    }
                }
            }
        }
        return pt;
    }

    /** module 2 sub 78：查询设备状态（旧接口，手表未必支持） */
    public byte[] queryStatus() throws Exception {
        byte[] pt = request(oyt(2, 78, 0, null), 8000);
        log.log("设备状态明文: " + (pt == null ? "(无应答)" : Crypto.hex(pt)));
        if (pt == null) return null;
        for (PB.F f4 : PB.parse(pt)) {
            if (f4.field != 4 || f4.bytes == null) continue;
            for (PB.F f48 : PB.parse(f4.bytes)) {
                if (f48.field != 48 || f48.bytes == null) continue;
                StringBuilder sb = new StringBuilder("  设备状态: ");
                for (PB.F g : PB.parse(f48.bytes)) {
                    if (g.field == 2) sb.append("值=").append(g.varint).append(' ');
                    else if (g.field == 1 || g.field == 3 || g.field == 4)
                        sb.append("f").append(g.field).append('=').append(g.varint != 0).append(' ');
                    else if (g.field == 5 && g.bytes != null)
                        sb.append("f5=").append(Crypto.hex(g.bytes)).append(' ');
                }
                log.log(sb.toString());
            }
        }
        return pt;
    }

    /** module 2 sub 18：让手表响铃/震动（找手表） */
    public void findDevice() throws Exception {
        sendEncrypted(Framing.CH_PB, oyt(2, 18, 0, null));
        log.log("已发送「找手表」（手表应会震动/响铃）");
    }

    /** module 20 sub 8：给手表快应用发消息（快应用侧 @system.interconnect 的 onmessage） */
    public void sendPhoneMessage(String pkg, byte[] payload, byte[] extra) throws Exception {
        ByteArrayOutputStream rxr = new ByteArrayOutputStream();
        PB.str(rxr, 1, pkg);
        if (payload != null && payload.length > 0) PB.bytes(rxr, 2, payload);
        ByteArrayOutputStream vxr = new ByteArrayOutputStream();
        PB.bytes(vxr, 1, rxr.toByteArray());          // vxr.f1 = rxr
        if (extra != null && extra.length > 0) PB.bytes(vxr, 2, extra);
        ByteArrayOutputStream y = new ByteArrayOutputStream();
        PB.bytes(y, 9, vxr.toByteArray());            // yxr.f9 = vxr
        byte[] pt = request(oyt(20, 8, 22, y.toByteArray()), 8000);
        log.log("发消息应答: " + (pt == null ? "(无回执)" : Crypto.hex(pt)));
    }

    /** module 20 sub 7：同步手机 App 安装状态（status: 1=已安装 2=未安装 3=手机未安装） */
    public void syncPhoneAppStatus(String pkg, int status) throws Exception {
        ByteArrayOutputStream rxr = new ByteArrayOutputStream();
        PB.str(rxr, 1, pkg);
        ByteArrayOutputStream qxr = new ByteArrayOutputStream();
        PB.bytes(qxr, 1, rxr.toByteArray());          // qxr.f1 = rxr
        qxr.write(0x10); PB.varint(qxr, status);      // qxr.f2 = status
        ByteArrayOutputStream y = new ByteArrayOutputStream();
        PB.bytes(y, 8, qxr.toByteArray());            // yxr.f8 = qxr
        byte[] pt = request(oyt(20, 7, 22, y.toByteArray()), 6000);
        log.log("同步应用状态应答: " + (pt == null ? "(无回执)" : Crypto.hex(pt)));
    }

    /** module 20 sub 21：查询某个应用在手表上的状态 */
    public byte[] appStatus(String pkg) throws Exception {
        ByteArrayOutputStream y = new ByteArrayOutputStream();
        PB.bytes(y, 5, rxr(pkg, null));                  // yxr.f5 = rxr
        byte[] pt = request(oyt(20, 21, 22, y.toByteArray()), 15000);
        log.log("应用状态应答: " + (pt == null ? "(无)" : Crypto.hex(pt)));
        return pt;
    }

    /** module 20 sub 3：卸载（fp 可为 null）。设备不回执，属正常 */
    public boolean uninstall(String pkg, byte[] fp) throws Exception {
        ByteArrayOutputStream y = new ByteArrayOutputStream();
        PB.bytes(y, 5, rxr(pkg, fp));                    // yxr.f5 = rxr
        byte[] pt = request(oyt(20, 3, 22, y.toByteArray()), 8000);
        log.log("卸载应答: " + (pt == null ? "(无)" : Crypto.hex(pt)));
        return pt != null;
    }

    /** module 20 sub 4：启动应用。设备不回执，属正常 */
    public boolean launchApp(String pkg, String uri) throws Exception {
        ByteArrayOutputStream uxr = new ByteArrayOutputStream();
        PB.bytes(uxr, 1, rxr(pkg, null));                // uxr.f1 = rxr
        if (uri != null && !uri.isEmpty()) PB.str(uxr, 2, uri);
        ByteArrayOutputStream y = new ByteArrayOutputStream();
        PB.bytes(y, 6, uxr.toByteArray());               // yxr.f6 = uxr
        byte[] pt = request(oyt(20, 4, 22, y.toByteArray()), 6000);
        log.log("启动应答: " + (pt == null ? "(无)" : Crypto.hex(pt)));
        return pt != null;
    }

    // ───────────────── 应用安装（.rpk 侧载）─────────────────
    /**
     * 协议逆向自 MassDataHandler / MassDataDispatcher / RunningMassManager：
     *  ① MassPrepare（PB 通道，加密）：oyt{f1=22,f2=0} + eqg{f1=iqg{ f1=dataType, f2=md5, f3=length }}
     *  ② 分片（MASS 通道 ch=2 op=1，明文）：
     *       [05 00][片序号:2B LE][仅首片: 00 | s | md5(16) | len(4B LE)] + 数据
     *  ③ 设备自动解包安装到 /data/quickapp/<包名>/
     */
    public void installRpk(byte[] rpk, String dataId) throws Exception {
        if (keys == null) throw new IllegalStateException("未认证");

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] md5 = md.digest(rpk);
        int total = rpk.length;
        log.log("安装 rpk: " + dataId + " " + total + " 字节 md5=" + Crypto.hex(md5));

        // ⓪ prepareInstallApp（module 20 sub 1）—— 告诉手表包名/版本号/大小，缺这步装不上！
        String pkg = parseManifest(rpk, "package");
        int ver = 0;
        try { ver = Integer.parseInt(parseManifest(rpk, "versionCode")); } catch (Exception ignored) {}
        if (pkg == null || pkg.isEmpty()) throw new IllegalStateException("rpk 里没找到 package");
        log.log("包名=" + pkg + " versionCode=" + ver + " size=" + total);

        java.io.ByteArrayOutputStream mxr = new java.io.ByteArrayOutputStream();
        PB.str(mxr, 1, pkg);                            // f1 包名
        mxr.write(0x10); PB.varint(mxr, ver);           // f2 versionCode
        mxr.write(0x18); PB.varint(mxr, total);         // f3 packageSize

        java.io.ByteArrayOutputStream yxr = new java.io.ByteArrayOutputStream();
        PB.bytes(yxr, 2, mxr.toByteArray());            // yxr.f2 = mxr

        java.io.ByteArrayOutputStream pi = new java.io.ByteArrayOutputStream();
        pi.write(0x08); PB.varint(pi, 20);              // f1 = 20
        pi.write(0x10); PB.varint(pi, 1);               // f2 = 1
        pi.write((byte) 0xB2); pi.write(0x01);          // f22
        PB.varint(pi, yxr.size()); pi.write(yxr.toByteArray(), 0, yxr.size());
        log.log("prepareInstallApp = " + Crypto.hex(pi.toByteArray()));
        sendEncrypted(Framing.CH_PB, pi.toByteArray());
        long e0 = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < e0) {
            Framing.Frame f = await((byte) -1, 800);
            if (f == null) continue;
            if (f.type == Framing.TYPE_DATA) {
                if (f.channel() == Framing.CH_PB) {
                    byte[] pt = Crypto.ctr(keys.deviceKey, f.data());
                    log.log("prepareInstallApp 应答: " + (pt == null ? "?" : Crypto.hex(pt)));
                    break;
                }
            }
        }

        // ① MassPrepare
        java.io.ByteArrayOutputStream iqg = new java.io.ByteArrayOutputStream();
        iqg.write(0x08); PB.varint(iqg, 64);            // f1 = dataType = 64
        PB.bytes(iqg, 2, md5);                          // f2 = md5
        iqg.write(0x18); PB.varint(iqg, total);         // f3 = 文件长度

        java.io.ByteArrayOutputStream eqg = new java.io.ByteArrayOutputStream();
        PB.bytes(eqg, 1, iqg.toByteArray());            // eqg.f1 = iqg

        java.io.ByteArrayOutputStream oyt = new java.io.ByteArrayOutputStream();
        oyt.write(0x08); PB.varint(oyt, 22);            // f1 = MASS 模块
        oyt.write(0x10); PB.varint(oyt, 0);             // f2 = prepare
        // f24 (key = 0xc2 0x01)
        oyt.write((byte) 0xC2); oyt.write(0x01);
        PB.varint(oyt, eqg.size()); oyt.write(eqg.toByteArray(), 0, eqg.size());
        log.log("MassPrepare = " + Crypto.hex(oyt.toByteArray()));

        sendEncrypted(Framing.CH_PB, oyt.toByteArray());
        // 等应答并 ACK（必须 ACK，否则设备会一直重传）
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end) {
            Framing.Frame f = await((byte) -1, 800);
            if (f == null) continue;
            if (f.type == Framing.TYPE_DATA) {
                if (f.channel() == Framing.CH_PB) {
                    byte[] pt = Crypto.ctr(keys.deviceKey, f.data());
                    log.log("MassPrepare 应答明文: " + (pt == null ? "(解密失败)" : Crypto.hex(pt)));
                    break;
                }
            }
        }

        // ② 分片发送（MASS 通道，明文）
        final int SEG = 16384;
        int off = 0, idx = 0;
        while (off < total) {
            idx++;
            int n = Math.min(SEG, total - off);
            java.io.ByteArrayOutputStream seg = new java.io.ByteArrayOutputStream();
            seg.write(0x05); seg.write(0x00);
            seg.write(idx & 0xFF); seg.write((idx >> 8) & 0xFF);   // 片序号 LE
            if (idx == 1) {                                        // 仅首片带 22B 头
                seg.write(0x00);
                seg.write(0x40);                                   // s = 64
                seg.write(md5, 0, 16);
                seg.write(total & 0xFF); seg.write((total >> 8) & 0xFF);
                seg.write((total >> 16) & 0xFF); seg.write((total >> 24) & 0xFF);
            }
            seg.write(rpk, off, n);
            sendData(Framing.CH_MASS, Framing.OP_WRITE, seg.toByteArray());
            off += n;
            log.log("  片 " + idx + " 已发 (" + n + "B)");
            // 收帧并 ACK（关键！）
            long e2 = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < e2) {
                Framing.Frame a = await((byte) -1, 400);
                if (a == null) continue;
                if (a.type == Framing.TYPE_DATA) {
                    byte[] pt = (a.channel() == Framing.CH_PB)
                              ? Crypto.ctr(keys.deviceKey, a.data()) : null;
                    log.log("    ← 设备 DATA ch=" + a.channel() + " op=" + a.opCode()
                            + (pt != null ? " 明文=" + Crypto.hex(pt) : ""));
                }
            }
        }
        log.log("✅ rpk 发送完成，共 " + idx + " 片，等待设备处理…");
        long e3 = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < e3) {
            Framing.Frame a = await((byte) -1, 1000);
            if (a == null) continue;
            if (a.type == Framing.TYPE_DATA) {
                byte[] pt = (a.channel() == Framing.CH_PB) ? Crypto.ctr(keys.deviceKey, a.data()) : null;
                log.log("    ← 事后 DATA ch=" + a.channel() + " op=" + a.opCode()
                        + (pt != null ? " 明文=" + Crypto.hex(pt) : ""));
            }
        }
    }
}