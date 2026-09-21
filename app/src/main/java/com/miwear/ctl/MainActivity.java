package com.miwear.ctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REDMI Watch 5 直连控制器 —— 完全绕过小米运动健康。
 *
 * 界面上的按钮：
 *   连接/认证/断开     基本连接
 *   读官方 key         通过 root 把 com.mi.health 的 device_db 拷出来，正则抓 encrypt_key
 *   查绑定信息         apiCode 17 getBindInfo（手表未绑定时才会回 verifyMode=2）
 *   本地绑定           apiCode 18/19/25 本地 ECDH，生成全新 auth key（不依赖官方 App）
 *   解绑(恢复出厂)      module 2 sub 0 + shr{1}，官方叫 ERASE_ALL，会清空手表
 *   一键重绑           解绑 → 等手表重启 → 本地绑定
 */
public class MainActivity extends Activity implements WearLink.Log {

    private static final String PREF = "miwear-ui";

    private EditText etMac, etKey, etApi, etSub, etTitle, etText, etPkg, etUser, etPhone;
    private TextView tvLog;
    private WearLink link;
    private final StringBuilder sb = new StringBuilder();
    private static boolean LOG_STARTED = false;

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        WearLink.APP = getApplicationContext();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        // ───────── 连接参数 ─────────
        root.addView(label("手表 MAC"));
        etMac = input("D4:A3:65:C0:BE:EA");
        root.addView(etMac);

        root.addView(label("auth key（encrypt_key，32 位 hex）"));
        etKey = input("");
        root.addView(etKey);

        LinearLayout row0 = new LinearLayout(this);
        Button bReadKey = new Button(this); bReadKey.setText("读官方 key");
        Button bSave = new Button(this);    bSave.setText("保存配置");
        Button bConn = new Button(this);    bConn.setText("连接");
        Button bAuth = new Button(this);    bAuth.setText("认证");
        Button bClose = new Button(this);   bClose.setText("断开");
        row0.addView(bReadKey); row0.addView(bSave);
        row0.addView(bConn); row0.addView(bAuth); row0.addView(bClose);
        root.addView(row0);

        // ───────── 手动发帧 / 通知 ─────────
        root.addView(label("apiCode / field3 子消息 hex"));
        LinearLayout row2 = new LinearLayout(this);
        etApi = input("26");
        etSub = input("");
        row2.addView(etApi); row2.addView(etSub);
        Button bSend = new Button(this); bSend.setText("发送");
        row2.addView(bSend);
        Button bNet = new Button(this); bNet.setText("联网");
        row2.addView(bNet);
        root.addView(row2);

        root.addView(label("通知：标题 / 内容 / 包名"));
        LinearLayout row3 = new LinearLayout(this);
        etTitle = input("测试标题");
        etText  = input("测试内容");
        etPkg   = input("com.termux");
        row3.addView(etTitle); row3.addView(etText); row3.addView(etPkg);
        Button bNotify = new Button(this); bNotify.setText("推送");
        row3.addView(bNotify);
        root.addView(row3);

        // ───────── 绑定 / 重绑 ─────────
        root.addView(label("绑定 / 重绑（不依赖官方 App）—— userId / phoneId 可留空"));
        LinearLayout row4 = new LinearLayout(this);
        etUser = input("");
        etUser.setHint("userId");
        etPhone = input("");
        etPhone.setHint("phoneId");
        row4.addView(etUser); row4.addView(etPhone);
        root.addView(row4);

        LinearLayout row5 = new LinearLayout(this);
        Button bProbe = new Button(this); bProbe.setText("查绑定信息");
        Button bBind = new Button(this);  bBind.setText("本地绑定");
        Button bReset = new Button(this); bReset.setText("解绑(恢复出厂)");
        Button bRebind = new Button(this); bRebind.setText("一键重绑");
        row5.addView(bProbe); row5.addView(bBind); row5.addView(bReset); row5.addView(bRebind);
        root.addView(row5);

        // ───────── 日志 ─────────
        root.addView(label("日志"));
        tvLog = new TextView(this);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setTextSize(11);
        ScrollView logSv = new ScrollView(this);
        logSv.addView(tvLog);
        root.addView(logSv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (220 * getResources().getDisplayMetrics().density)));

        // 整页可滚动（按钮多，小屏不至于被裁）
        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        loadPrefs();

        bConn.setOnClickListener(v -> bg(() -> {
            try { link = new WearLink(this); link.connect(etMac.getText().toString().trim()); savePrefs(); }
            catch (Exception e) { log("❌ " + e); }
        }));
        bAuth.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                byte[] key = hex2(etKey.getText().toString().trim());
                if (key.length != 16) { log("auth key 必须是 32 位 hex (16 字节)"); return; }
                link.authenticate(key);
                savePrefs();
            } catch (Exception e) { log("❌ " + e); }
        }));
        bClose.setOnClickListener(v -> { if (link != null) link.close(); });
        bSave.setOnClickListener(v -> { savePrefs(); log("已保存 MAC/KEY 到本机配置"); });
        bReadKey.setOnClickListener(v -> bg(() -> {
            try { readOfficialKey(); } catch (Exception e) { log("❌ 读官方 key 失败: " + e); }
        }));
        bNotify.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                link.pushNotification(etPkg.getText().toString().trim(),
                                      etTitle.getText().toString().trim(),
                                      etText.getText().toString().trim(),
                                      "MiWear", 1);
            } catch (Exception e) { log("❌ " + e); }
        }));
        bNet.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                if (link.netProxyRunning()) link.stopNetProxy(); else link.startNetProxy();
            } catch (Exception e) { log("❌ " + e); }
        }));
        bSend.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                int api = Integer.parseInt(etApi.getText().toString().trim());
                byte[] sub = hex2(etSub.getText().toString().trim());
                link.sendApi(api, sub);
            } catch (Exception e) { log("❌ " + e); }
        }));

        bProbe.setOnClickListener(v -> confirm("查绑定信息",
                "会连手表并发 apiCode 17。注意：手表若仍绑着官方 App，会直接断开连接（固件拒绝重绑）。",
                () -> {
                    try {
                        WearLink l = freshLink();
                        WearLink.BindInfo bi = l.getBindInfo(etUser.getText().toString().trim());
                        log("bindInfo: " + bi);
                        if (bi.error == 1) log("⚠ 设备已绑定 → 先「解绑(恢复出厂)」");
                        else if (bi.error >= 0) log("❌ 查询失败 error=" + bi.error);
                        else if (bi.verifyMode == 2) log("✅ 支持本地 ECDH 绑定");
                        else if (bi.verifyMode == 1) log("⚠ 只支持 PSK/服务器绑定");
                    } catch (Exception e) { log("❌ " + e); }
                }));

        bBind.setOnClickListener(v -> confirm("本地绑定",
                "会与手表做本地 ECDH，给手表写入一个【全新的 auth key】。\n"
              + "之后官方 App 就用旧 key 连不上了。\n"
              + "前提：手表必须处于未绑定状态（先解绑/恢复出厂）。",
                () -> {
                    try {
                        WearLink l = freshLink();
                        WearLink.BindInfo bi = l.getBindInfo(etUser.getText().toString().trim());
                        log("bindInfo: " + bi);
                        if (bi.error == 1) { log("❌ 设备已绑定（先解绑/恢复出厂）"); return; }
                        if (bi.error >= 0) { log("❌ 查询失败 error=" + bi.error); return; }
                        if (bi.verifyMode != 2) { log("❌ 不支持本地绑定 verifyMode=" + bi.verifyMode); return; }
                        byte[] nk = l.localBind(etUser.getText().toString().trim(),
                                                etPhone.getText().toString().trim(), bi, 20000);
                        String hex = Crypto.hex(nk);
                        log("🔑 新 auth key = " + hex);
                        runOnUiThread(() -> etKey.setText(hex));
                        savePrefs();
                    } catch (Exception e) { log("❌ " + e); }
                }));

        bReset.setOnClickListener(v -> confirm("解绑 / 恢复出厂",
                "官方实现里这就是 ERASE_ALL —— 手表上的数据会被清空，并且与官方 App 解绑！\n"
              + "确认要发送吗？",
                () -> {
                    try { authLink().unbindReset(); }
                    catch (Exception e) { log("❌ " + e); }
                }));

        bRebind.setOnClickListener(v -> confirm("一键重绑",
                "步骤：解绑(恢复出厂、清空手表) → 等手表重启 → 本地 ECDH 绑定 → 保存新 key。\n"
              + "手表数据会被清空，确认继续？",
                () -> {
                    try {
                        authLink().unbindReset();
                        log("已发送解绑，等手表重启…");
                        WearLink.BindInfo bi = null;
                        for (int i = 0; i < 24; i++) {
                            Thread.sleep(5000);
                            try {
                                WearLink l = freshLink();
                                bi = l.getBindInfo(etUser.getText().toString().trim());
                                log("第 " + (i + 1) + " 次探测: " + bi);
                                if (bi.error < 0 && bi.verifyMode == 2) break;
                            } catch (Exception e) {
                                log("第 " + (i + 1) + " 次探测失败: " + e);
                            }
                            bi = null;
                        }
                        if (bi == null) { log("❌ 手表一直没进入可绑定状态（可能要在手表上确认恢复出厂）"); return; }
                        byte[] nk = link.localBind(etUser.getText().toString().trim(),
                                                   etPhone.getText().toString().trim(), bi, 20000);
                        String hex = Crypto.hex(nk);
                        log("🔑 重绑成功，新 auth key = " + hex);
                        runOnUiThread(() -> etKey.setText(hex));
                        savePrefs();
                    } catch (Exception e) { log("❌ " + e); }
                }));

        if (!LOG_STARTED) {
            LOG_STARTED = true;
            try { new File(getFilesDir(), "log.txt").delete(); } catch (Exception ignored) {}
            log("=== " + new java.util.Date() + " ===");
        }
        // 发一条本机通知，用于给官方 App 做抓包校准
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Exception ignored) {}
        }
        if (getIntent() != null && getIntent().getBooleanExtra("localnotify", false)) {
            try {
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                String ch = "miwear";
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(new android.app.NotificationChannel(ch, "miwear", android.app.NotificationManager.IMPORTANCE_DEFAULT));
                }
                android.app.Notification n = new android.app.Notification.Builder(this, ch)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("测试标题")
                        .setContentText("测试内容 hello")
                        .build();
                nm.notify(1234, n);
                log("已发本机通知");
            } catch (Exception e) { log("发通知失败: " + e); }
        }

        BluetoothAdapter ad0 = BluetoothAdapter.getDefaultAdapter();
        log(ad0 != null && ad0.isEnabled() ? "蓝牙已开启" : "⚠ 蓝牙未开启");

        handleIntent(getIntent());
    }

    // ───────────────────────── 界面辅助 ─────────────────────────

    private void confirm(String title, String msg, Runnable ok) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("确定", (d, w) -> bg(ok))
                .setNegativeButton("取消", null)
                .show());
    }

    /** 新建连接并做 L1 握手（不认证）—— 绑定探测用 */
    private WearLink freshLink() throws Exception {
        if (link != null) { try { link.close(); } catch (Exception ignored) {} link = null; }
        link = new WearLink(this);
        link.connect(etMac.getText().toString().trim());
        link.handshake();
        return link;
    }

    /** 新建连接 + 认证 —— 解绑等需要 key 的操作 */
    private WearLink authLink() throws Exception {
        WearLink l = freshLink();
        byte[] k = hex2(etKey.getText().toString().trim());
        if (k.length != 16) throw new IllegalStateException("auth key 必须是 32 位 hex（16 字节）");
        l.authenticate(k);
        return l;
    }

    /** 从官方 App（com.mi.health）的 device_db 里抓 encrypt_key / mac / phone_id（需要 root） */
    private void readOfficialKey() throws Exception {
        String dir = getFilesDir().getAbsolutePath();
        int rc = runSu("cp -f /data/data/com.mi.health/databases/device_db* " + dir + "/ && chmod 666 " + dir + "/device_db*");
        File db = new File(dir, "device_db");
        if (rc != 0 || !db.exists()) {
            log("❌ 读不到官方 App 的 device_db（需要 root，且装了小米运动健康）");
            return;
        }
        byte[] b = new byte[(int) db.length()];
        try (FileInputStream in = new FileInputStream(db)) { in.read(b); }
        String s = new String(b, "ISO-8859-1");      // JSON 是明文存在 sqlite blob 里的，直接正则即可

        Matcher mk = Pattern.compile("\"encrypt_key\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").matcher(s);
        if (!mk.find()) { log("❌ 数据库里没找到 encrypt_key"); return; }
        String key = mk.group(1).toLowerCase();
        int from = Math.max(0, mk.start() - 200);
        String ctx = s.substring(from, Math.min(s.length(), mk.end() + 500));
        String mac = firstMatch(ctx, "\"mac\"\\s*:\\s*\"([0-9A-Fa-f:]{17})\"");
        String pid = firstMatch(ctx, "\"phone_id\"\\s*:\\s*\"([^\"]+)\"");

        log("🔑 官方 App 里的 key = " + key);
        if (mac != null) log("   MAC = " + mac);
        if (pid != null) log("   phone_id = " + pid);
        final String fmac = mac, fpid = pid;
        runOnUiThread(() -> {
            etKey.setText(key);
            if (fmac != null && etMac.getText().toString().trim().isEmpty()) etMac.setText(fmac);
            if (fpid != null && etPhone.getText().toString().trim().isEmpty()) etPhone.setText(fpid);
        });
        savePrefs();
    }

    private static String firstMatch(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private int runSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor();
        } catch (Exception e) {
            log("su 执行失败: " + e);
            return -1;
        }
    }

    // ───────────────────────── 配置持久化 ─────────────────────────

    private SharedPreferences prefs() { return getSharedPreferences(PREF, MODE_PRIVATE); }

    private void loadPrefs() {
        try {
            SharedPreferences sp = prefs();
            etMac.setText(sp.getString("mac", etMac.getText().toString()));
            etKey.setText(sp.getString("key", ""));
            etPhone.setText(sp.getString("phone_id", ""));
            etUser.setText(sp.getString("user_id", ""));
        } catch (Exception ignored) {}
    }

    private void savePrefs() {
        try {
            prefs().edit()
                    .putString("mac", etMac.getText().toString().trim())
                    .putString("key", etKey.getText().toString().trim())
                    .putString("phone_id", etPhone.getText().toString().trim())
                    .putString("user_id", etUser.getText().toString().trim())
                    .apply();
        } catch (Exception ignored) {}
    }

    // ───────────────────────── Intent 入口 ─────────────────────────

    /** 处理启动参数；singleTask 下 am start 走 onNewIntent，必须也走这里 */
    private void handleIntent(android.content.Intent it) {
        if (it == null) return;
        log("Intent extras: " + it.getExtras());
        String runId = it.getStringExtra("run_id");
        if (runId != null) log("RUN " + runId);   // 供外部脚本识别「本次运行」
        String m = it.getStringExtra("mac");
        String k = it.getStringExtra("key");
        if (m != null) etMac.setText(m);
        if (k != null) etKey.setText(k);

        // ── 常驻 CLI 服务（App 作为蓝牙后端，认证只做一次）──
        if (it.getBooleanExtra("serve_stop", false)) {
            CmdServer.stop();
            try { stopService(new android.content.Intent(this, GatewayService.class)); } catch (Exception ignored) {}
            log("🛑 CLI 服务已停止");
            return;
        }
        if (it.getBooleanExtra("serve", false)) {
            int port = it.getIntExtra("port", CmdServer.DEFAULT_PORT);
            try {
                android.content.Intent si = new android.content.Intent(this, GatewayService.class)
                        .putExtra("serve", true)
                        .putExtra("port", port)
                        .putExtra("mac", etMac.getText().toString().trim())
                        .putExtra("key", etKey.getText().toString().trim());
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
                log("已请求启动 CLI 服务（127.0.0.1:" + port + "）");
            } catch (Exception e) { log("❌ 启动 CLI 服务失败: " + e); }
            return;
        }
        if (it.getBooleanExtra("autoconnect", false)) {
            final String mm = etMac.getText().toString().trim();
            final String kk = etKey.getText().toString().trim();
            final boolean autoAuth = it.getBooleanExtra("autoauth", false);
            bg(() -> {
                try {
                    if (link != null) { try { link.close(); } catch (Exception ignored) {} link = null; }
                    link = new WearLink(this);
                    link.connect(mm);
                    link.handshake();
                    if (it.getBooleanExtra("bind_probe", false) || it.getBooleanExtra("bind_now", false)) {
                        WearLink.BindInfo bi = link.getBindInfo(it.getStringExtra("bind_userid"));
                        log("bindInfo: " + bi);
                        if (it.getBooleanExtra("bind_now", false)) {
                            if (bi.error == 1) { log("❌ 设备已绑定（先解绑/恢复出厂）"); return; }
                            if (bi.error >= 0) { log("❌ 查询失败 error=" + bi.error); return; }
                            if (bi.verifyMode != 2) { log("❌ 不支持本地绑定 verifyMode=" + bi.verifyMode); return; }
                            byte[] nk = link.localBind(it.getStringExtra("bind_userid"),
                                    it.getStringExtra("bind_phoneid"), bi, 20000);
                            log("BINDKEY " + Crypto.hex(nk));
                        } else if (bi.verifyMode == 2) {
                            log("✅ 支持本地绑定");
                        } else if (bi.error == 1) {
                            log("⚠ 设备已绑定");
                        }
                        return;
                    }
                    if (autoAuth) {
                        byte[] key = hex2(kk);
                        if (key.length != 16) { log("auth key 长度错误: " + key.length + " 字节"); return; }
                        link.authenticate(key);
                        if (it.getBooleanExtra("unbind_reset", false)) { link.unbindReset(); return; }
                        // 联网网关不 return：可以接着做后面的动作（如拉起手表应用）
                        if (it.getBooleanExtra("netproxy", false)) {
                            link.startNetProxy();
                            try { startForegroundService(new android.content.Intent(this, GatewayService.class)); }
                            catch (Exception e) { log("⚠ 前台服务启动失败（App 可能被冻结）: " + e); }
                        }
                        String rpkPath = it.getStringExtra("install_rpk");
                        if (rpkPath != null) {
                            File f = new File(rpkPath);
                            log("读取 rpk: " + rpkPath + " 存在=" + f.exists() + " 大小=" + f.length());
                            byte[] data = new byte[(int) f.length()];
                            try (FileInputStream in = new FileInputStream(f)) {
                                int p2 = 0; while (p2 < data.length) { int r = in.read(data, p2, data.length - p2); if (r < 0) break; p2 += r; }
                            }
                            link.installRpk(data, f.getName());
                            return;
                        }
                        if (it.getBooleanExtra("list_apps", false)) { link.listApps(); return; }
                        String up = it.getStringExtra("uninstall_pkg");
                        if (up != null) {
                            String fpH = it.getStringExtra("uninstall_fp");
                            link.uninstall(up, fpH == null ? null : hex2(fpH));
                            return;
                        }
                        String lp = it.getStringExtra("launch_pkg");
                        if (lp != null) { link.launchApp(lp, it.getStringExtra("launch_uri")); return; }
                        String raw = it.getStringExtra("raw_hex");
                        if (raw != null) { link.rawCall(hex2(raw), 8000); return; }
                        String cn = it.getStringExtra("call_number");
                        if (cn != null) {
                            link.incomingCall(cn, it.getStringExtra("call_name"),
                                              it.getIntExtra("call_type", 1));
                            return;
                        }
                        String ap = it.getStringExtra("app_pkg");
                        if (ap != null) { link.appStatus(ap); return; }
                        if (it.getBooleanExtra("query_status", false)) { link.deviceStatus(); return; }
                        if (it.getBooleanExtra("find_device", false)) { link.findDevice(); return; }
                        String mp = it.getStringExtra("msg_pkg");
                        if (mp != null) {
                            String mt = it.getStringExtra("msg_text");
                            link.sendPhoneMessage(mp, mt == null ? new byte[0] : mt.getBytes("UTF-8"), null);
                            return;
                        }
                        String sp = it.getStringExtra("sync_pkg");
                        if (sp != null) {
                            link.syncPhoneAppStatus(sp, it.getIntExtra("sync_status", 1));
                            return;
                        }
                        String nt = it.getStringExtra("notify_title");                        if (nt != null) {
                            Thread.sleep(1500);
                            String np = it.getStringExtra("notify_pkg");
                            String nx = it.getStringExtra("notify_text");
                            link.pushNotification(np == null ? "com.termux" : np, nt,
                                                  nx == null ? "hello" : nx, "MiWear", 1);
                        }
                    }
                } catch (Exception e) { log("❌ " + e); }
            });
        }
    }

    @Override protected void onNewIntent(android.content.Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        log("--- onNewIntent ---");
        handleIntent(it);
    }

    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); return t; }
    private EditText input(String def) { EditText e = new EditText(this); e.setText(def); e.setTextSize(12); return e; }
    private void bg(Runnable r) { new Thread(r).start(); }

    static byte[] hex2(String s) {
        s = s.replaceAll("[^0-9a-fA-F]", "");
        if (s.isEmpty()) return new byte[0];
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    @Override public void log(String s) {
        String line = s + "\n";
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(
                new File(getFilesDir(), "log.txt"), true)) {
            f.write(line.getBytes("UTF-8"));
        } catch (Exception ignored) {}
        runOnUiThread(() -> { sb.append(line); tvLog.setText(sb.toString()); });
    }
}
