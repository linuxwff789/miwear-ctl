package com.miwear.ctl;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 常驻本地 socket 服务 —— 让 App 变成手表的「CLI 后端」。
 *
 * 为什么需要它：Android 的蓝牙 HCI 是用户态实现的（HAL 独占 /dev/ttyHS0），
 * 内核根本没有 hci_dev，所以 Termux 里无法用内核 RFCOMM 直接连手表
 * （hci_get_route() == NULL → EHOSTUNREACH）。只有 App 里的 BluetoothSocket 能连。
 * 于是把 App 做成常驻服务，CLI 通过本地 socket 命令它 —— 认证只做一次，输出实时流式。
 *
 * 协议：一行一个 JSON
 *   客户端 → {"cmd":"notify","title":"..","text":".."}
 *   服务端 → {"ev":"log","msg":".."}    实时流（App 的所有日志）
 *            {"ev":"done","ok":true}    一条命令结束（keep=true 表示连接保持）
 *
 * 手动试：  (echo '{"cmd":"info"}'; sleep 25) | nc 127.0.0.1 38787
 */
public class CmdServer implements WearLink.Log {

    public static final int DEFAULT_PORT = 38787;

    private static CmdServer INSTANCE;

    public static synchronized CmdServer get() { return INSTANCE; }

    public static synchronized boolean isRunning() { return INSTANCE != null && INSTANCE.running; }

    public static synchronized int runningPort() { return INSTANCE == null ? 0 : INSTANCE.port; }

    public static synchronized WearLink currentLink() { return INSTANCE == null ? null : INSTANCE.link; }

    /** 启动服务（已在跑则只更新 mac/key）。绑定失败会立刻返回并记日志。 */
    public static synchronized boolean start(Context ctx, String mac, String key, int port) {
        if (INSTANCE != null) {
            if (mac != null && !mac.isEmpty()) INSTANCE.mac = mac;
            if (key != null && !key.isEmpty()) INSTANCE.key = key;
            INSTANCE.log("服务已在运行（端口 " + INSTANCE.port + "）");
            return true;
        }
        CmdServer s = new CmdServer();
        s.ctx = ctx.getApplicationContext();
        s.mac = mac == null ? "" : mac;
        s.key = key == null ? "" : key;
        s.port = port;
        s.logFile = new File(ctx.getFilesDir(), "log.txt");
        try {
            s.server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            s.appendFile("❌ 服务绑定 127.0.0.1:" + port + " 失败: " + e);
            return false;
        }
        INSTANCE = s;
        s.running = true;
        s.savePrefs(true);
        s.log("✅ miwear 服务已启动：127.0.0.1:" + port + "（认证只需一次，命令走常驻连接）");
        Thread t = new Thread(s::acceptLoop, "miwear-serve");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** 进程被杀后由 START_STICKY 重启时用：从 SharedPreferences 恢复上次的设置 */
    public static boolean wasServing(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("serve", false);
    }

    public static int savedPort(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("port", DEFAULT_PORT);
    }

    public static String savedMac(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("mac", "");
    }

    public static String savedKey(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("key", "");
    }

    public static synchronized void stop() {
        CmdServer s = INSTANCE;
        if (s == null) return;
        INSTANCE = null;
        s.running = false;
        s.savePrefs(false);
        s.log("服务停止");
        s.broadcast("{\"ev\":\"done\",\"ok\":true,\"bye\":true}\n");
        for (Client c : s.clients) c.close();
        try { s.server.close(); } catch (Exception ignored) {}
        s.closeLink();
    }

    // ──────────────────────── 实例状态 ────────────────────────
    private static final String PREF = "miwear-serve";

    private Context ctx;
    private ServerSocket server;
    private volatile boolean running;
    private int port = DEFAULT_PORT;
    private String mac = "", key = "";
    private WearLink link;
    private File logFile;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    /** 手表是单连接，所有命令串行执行 */
    private final Object lock = new Object();

    private void savePrefs(boolean serve) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
               .putBoolean("serve", serve).putInt("port", port)
               .putString("mac", mac).putString("key", key).apply();
        } catch (Exception ignored) {}
    }

    // ──────────────────────── 日志 ────────────────────────
    @Override public void log(String s) {
        appendFile(s);
        broadcast("{\"ev\":\"log\",\"msg\":" + JSONObject.quote(s) + "}\n");
    }

    private void appendFile(String s) {
        if (logFile == null) return;
        try (FileOutputStream f = new FileOutputStream(logFile, true)) {
            f.write((s + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    private void broadcast(String line) {
        for (Client c : clients) c.write(line);
    }

    // ──────────────────────── 连接管理 ────────────────────────
    private void acceptLoop() {
        while (running) {
            Socket sock;
            try { sock = server.accept(); }
            catch (Exception e) { if (running) appendFile("accept 失败: " + e); break; }
            try {
                Client c = new Client(sock);
                clients.add(c);
                Thread t = new Thread(() -> serve(c), "miwear-cli-" + sock.getPort());
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void serve(Client c) {
        c.write("{\"ev\":\"hello\",\"port\":" + port + ",\"link\":" + linkState() + "}\n");
        try {
            String line;
            while (running && (line = c.in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject j;
                try { j = new JSONObject(line); }
                catch (Exception e) { c.write(err("JSON 解析失败: " + e)); continue; }
                if ("ping".equals(j.optString("cmd"))) { c.write("{\"ev\":\"pong\"}\n"); continue; }
                boolean keep = dispatch(j, c);
                if (!keep) break;
            }
        } catch (Exception ignored) {
        } finally {
            clients.remove(c);
            c.close();
        }
    }

    private static String err(String msg) {
        return "{\"ev\":\"done\",\"ok\":false,\"error\":" + JSONObject.quote(msg) + "}\n";
    }

    private String linkState() {
        if (link == null) return "\"none\"";
        if (!link.isConnected()) return "\"down\"";
        return link.isAuthenticated() ? "\"ready\"" : "\"linked\"";
    }

    private void closeLink() {
        try { if (link != null) link.close(); } catch (Exception ignored) {}
        link = null;
    }

    /**
     * 保证「已连接 + 已认证」，失败重试一次（SPP 连接偶发失败）。
     * 认证结果常驻，后续命令几乎零延迟。
     */
    private WearLink ensureLink() throws Exception {
        if (link != null && link.isConnected() && link.isAuthenticated()) return link;
        if (mac == null || mac.isEmpty()) throw new IllegalStateException("未配置手表 MAC");
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                closeLink();
                WearLink l = new WearLink(this);
                l.connect(mac);
                l.handshake();
                byte[] k = MainActivity.hex2(key);
                if (k.length != 16) throw new IllegalStateException("auth key 必须是 32 位 hex（16 字节）");
                l.authenticate(k);
                link = l;
                return link;
            } catch (Exception e) {
                last = e;
                log("⚠ 连接失败(" + (attempt + 1) + "/2): " + e);
                closeLink();
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            }
        }
        throw last == null ? new IllegalStateException("连接失败") : last;
    }

    // ──────────────────────── 命令分发 ────────────────────────
    /** @return false 表示让客户端断开（仅 bye） */
    private boolean dispatch(JSONObject j, Client c) {
        String cmd = j.optString("cmd", "");
        if ("bye".equals(cmd) || "quit".equals(cmd)) { c.write("{\"ev\":\"done\",\"ok\":true}\n"); return false; }
        if ("subscribe".equals(cmd)) {   // 只订阅日志流，不结束连接
            c.write("{\"ev\":\"done\",\"ok\":true,\"keep\":true}\n");
            return true;
        }
        if ("state".equals(cmd)) {
            c.write("{\"ev\":\"done\",\"ok\":true,\"link\":" + linkState()
                    + ",\"port\":" + port + ",\"mac\":" + JSONObject.quote(mac) + "}\n");
            return true;
        }
        if ("logfile".equals(cmd)) {
            c.write("{\"ev\":\"done\",\"ok\":true,\"data\":" + JSONObject.quote(readLog()) + "}\n");
            return true;
        }
        if ("reconnect".equals(cmd)) { closeLink(); }

        boolean keep = j.optBoolean("keep", false);
        Thread t = new Thread(() -> run(cmd, j, c, keep), "miwear-cmd");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(String cmd, JSONObject j, Client c, boolean keep) {
        String done;
        try {
            done = execute(cmd, j);
        } catch (Throwable e) {
            String m = e.toString();
            if (e instanceof NullPointerException) m = "空指针（可能未连接蓝牙）";
            log("❌ " + cmd + ": " + m);
            done = err(m);
            c.write(done);
            return;
        }
        c.write(done == null ? "{\"ev\":\"done\",\"ok\":true,\"keep\":" + keep + "}\n"
                             : "{\"ev\":\"done\",\"ok\":true,\"keep\":" + keep + ",\"data\":" + done + "}\n");
    }

    /** 执行一条命令；返回 null 或一段 JSON（作为 data） */
    private String execute(String cmd, JSONObject j) throws Exception {
        synchronized (lock) {
            switch (cmd) {
                case "link": {
                    ensureLink();
                    return null;
                }
                case "notify": {
                    WearLink l = ensureLink();
                    l.pushNotification(j.optString("pkg", "com.termux"),
                                       j.optString("title", "标题"),
                                       j.optString("text", "内容"),
                                       j.optString("app", "MiWear"),
                                       j.optInt("id", 1));
                    return null;
                }
                case "call": {
                    WearLink l = ensureLink();
                    l.incomingCall(j.optString("number", "10086"),
                                   j.optString("name", ""),
                                   j.optInt("type", 1));
                    return null;
                }
                case "install": {
                    String path = j.optString("path", "");
                    File f = new File(path);
                    if (!f.exists()) throw new IllegalStateException("文件不存在: " + path);
                    byte[] data = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int p = 0;
                        while (p < data.length) {
                            int r = in.read(data, p, data.length - p);
                            if (r < 0) break;
                            p += r;
                        }
                    }
                    log("读取 rpk: " + path + " (" + data.length + " 字节)");
                    ensureLink().installRpk(data, f.getName());
                    return null;
                }
                case "apps": {
                    ensureLink().listApps();
                    return null;
                }
                case "app": {
                    ensureLink().appStatus(j.optString("pkg"));
                    return null;
                }
                case "uninstall": {
                    String fp = j.optString("fp", "");
                    ensureLink().uninstall(j.optString("pkg"),
                            fp.isEmpty() ? null : MainActivity.hex2(fp));
                    return null;
                }
                case "launch": {
                    String uri = j.optString("uri", "");
                    ensureLink().launchApp(j.optString("pkg"), uri.isEmpty() ? null : uri);
                    return null;
                }
                case "raw": {
                    ensureLink().rawCall(MainActivity.hex2(j.optString("hex")),
                                         j.optInt("timeout", 8000));
                    return null;
                }
                case "info": {
                    ensureLink().deviceStatus();
                    return null;
                }
                case "find": {
                    ensureLink().findDevice();
                    return null;
                }
                case "msg": {
                    ensureLink().sendPhoneMessage(j.optString("pkg"),
                            j.optString("text", "").getBytes("UTF-8"), null);
                    return null;
                }
                case "sync": {
                    ensureLink().syncPhoneAppStatus(j.optString("pkg"), j.optInt("status", 1));
                    return null;
                }
                case "net": {
                    WearLink l = ensureLink();
                    if (!l.netProxyRunning()) {
                        l.startNetProxy();
                        CmdServer.this.log("netproxy 已启动");
                    } else {
                        CmdServer.this.log("netproxy 已在运行");
                    }
                    String lp = j.optString("launch_pkg", "");
                    if (!lp.isEmpty()) {
                        String lu = j.optString("launch_uri", "");
                        l.launchApp(lp, lu.isEmpty() ? null : lu);
                    }
                    return null;
                }
                case "netstop": {
                    if (link != null) link.stopNetProxy();
                    return null;
                }
                case "bindprobe": {
                    closeLink();
                    WearLink l = new WearLink(this);
                    try {
                        l.connect(mac);
                        l.handshake();
                        WearLink.BindInfo bi = l.getBindInfo(j.optString("userid", ""));
                        log("bindInfo: " + bi);
                        if (bi.error == 1) log("→ 设备已绑定（error=1），需先解绑/恢复出厂才能重新绑定");
                        else if (bi.error >= 0) log("→ 查询出错 error=" + bi.error);
                        else if (bi.verifyMode == 2) log("→ 支持本地 ECDH 绑定 ✅（可用 miwear bind --yes）");
                        else if (bi.verifyMode == 1) log("→ 只支持 PSK/服务器绑定 ❌");
                        else log("→ 未知 verifyMode=" + bi.verifyMode);
                    } finally {
                        l.close();
                    }
                    return null;
                }
                case "bind": {
                    String uid = j.optString("userid", "");
                    String phoneId = j.optString("phoneid", "");
                    if (phoneId.isEmpty()) {
                        try {
                            phoneId = android.provider.Settings.Secure.getString(
                                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                        } catch (Exception ignored) {}
                    }
                    if (phoneId == null) phoneId = "";
                    closeLink();
                    WearLink l = new WearLink(this);
                    try {
                        l.connect(mac);
                        l.handshake();
                        WearLink.BindInfo bi = l.getBindInfo(uid);
                        log("bindInfo: " + bi);
                        if (bi.error == 1) throw new IllegalStateException("设备已绑定，请先在官方 App 里解绑或恢复出厂后再试");
                        if (bi.error >= 0) throw new IllegalStateException("查询绑定信息失败 error=" + bi.error);
                        if (bi.verifyMode != 2) throw new IllegalStateException("设备不支持本地绑定 verifyMode=" + bi.verifyMode);
                        byte[] k = l.localBind(uid, phoneId, bi, 20000);
                        return "{\"key\":" + JSONObject.quote(Crypto.hex(k)) + "}";
                    } finally {
                        l.close();
                    }
                }
                default:
                    throw new IllegalStateException("未知命令: " + cmd);
            }
        }
    }

    private String readLog() {
        try {
            byte[] b = new byte[(int) logFile.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(logFile)) {
                int r = in.read(b);
                return r <= 0 ? "" : new String(b, 0, r, "UTF-8");
            }
        } catch (Exception e) { return ""; }
    }

    // ──────────────────────── 客户端 ────────────────────────
    private static final class Client {
        final Socket s;
        final BufferedReader in;
        final BufferedWriter out;

        Client(Socket s) throws Exception {
            this.s = s;
            s.setTcpNoDelay(true);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
        }

        synchronized void write(String line) {
            try { out.write(line); out.flush(); }
            catch (Exception ignored) {}
        }

        void close() { try { s.close(); } catch (Exception ignored) {} }
    }
}
