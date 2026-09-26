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
    private EditText etAppPkg, etFp, etUri, etMod, etInfoSub;
    private TextView tvLog;
    private ScrollView logSv;
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

        // ───────── 设备信息 / 健康 ─────────
        root.addView(label("设备信息 / 健康（module / sub，用官方读接口）"));
        LinearLayout rowInfo = new LinearLayout(this);
        etMod = input("2"); etMod.setHint("module");
        etInfoSub = input("1"); etInfoSub.setHint("sub");
        rowInfo.addView(etMod); rowInfo.addView(etInfoSub);
        Button bProbeInfo = new Button(this); bProbeInfo.setText("读信息");
        rowInfo.addView(bProbeInfo);
        Button bInfoAll = new Button(this); bInfoAll.setText("电量+设备信息");
        rowInfo.addView(bInfoAll);
        root.addView(rowInfo);

        LinearLayout rowInfo2 = new LinearLayout(this);
        Button bStor = new Button(this); bStor.setText("存储");
        Button bHr = new Button(this); bHr.setText("心率设置");
        Button bSpo2 = new Button(this); bSpo2.setText("血氧设置");
        Button bPress = new Button(this); bPress.setText("压力设置");
        Button bSit = new Button(this); bSit.setText("久坐提醒");
        Button bSleep = new Button(this); bSleep.setText("睡眠模式");
        Button bAod = new Button(this); bAod.setText("息屏显示");
        rowInfo2.addView(bStor); rowInfo2.addView(bHr); rowInfo2.addView(bSpo2);
        rowInfo2.addView(bPress); rowInfo2.addView(bSit); rowInfo2.addView(bSleep); rowInfo2.addView(bAod);
        root.addView(rowInfo2);

        // ───────── 快应用（rpk）管理 ─────────
        root.addView(label("快应用管理：包名 / 指纹(可空) / 启动 URI(可空)"));
        LinearLayout rowApp = new LinearLayout(this);
        etAppPkg = input("com.miwear.demo");
        etFp = input(""); etFp.setHint("指纹 hex");
        etUri = input(""); etUri.setHint("uri");
        rowApp.addView(etAppPkg); rowApp.addView(etFp); rowApp.addView(etUri);
        root.addView(rowApp);

        LinearLayout rowApp2 = new LinearLayout(this);
        Button bList = new Button(this);  bList.setText("列出应用");
        Button bQuery = new Button(this); bQuery.setText("查询");
        Button bUninst = new Button(this); bUninst.setText("卸载");
        Button bLaunch = new Button(this); bLaunch.setText("启动");
        Button bInstall = new Button(this); bInstall.setText("安装 rpk");
        rowApp2.addView(bList); rowApp2.addView(bQuery); rowApp2.addView(bUninst);
        rowApp2.addView(bLaunch); rowApp2.addView(bInstall);
        root.addView(rowApp2);

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

        // ───────── 睡眠监测 ─────────
        root.addView(label("睡眠监测（常驻，入睡/起床弹通知；不依赖 Termux）"));
        LinearLayout rowS = new LinearLayout(this);
        Button bSleepOn = new Button(this);  bSleepOn.setText("开启监测");
        Button bSleepOff = new Button(this); bSleepOff.setText("关闭");
        Button bSleepSt = new Button(this);  bSleepSt.setText("状态");
        Button bSleepLog = new Button(this); bSleepLog.setText("看日志");
        Button bSleepTest = new Button(this);bSleepTest.setText("测试通知");
        final android.widget.EditText etSleepIv = new android.widget.EditText(this);
        etSleepIv.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etSleepIv.setText("60");
        etSleepIv.setHint("间隔秒");
        LinearLayout.LayoutParams lpIv = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rowS.addView(bSleepOn); rowS.addView(bSleepOff); rowS.addView(bSleepSt);
        rowS.addView(bSleepLog); rowS.addView(bSleepTest); rowS.addView(etSleepIv, lpIv);
        root.addView(rowS);

        // ───────── 日志（可翻页）─────────
        LinearLayout rowLog = new LinearLayout(this);
        rowLog.addView(label("日志"));
        Button bLogUp = new Button(this);    bLogUp.setText("▲ 上翻");
        Button bLogDown = new Button(this);  bLogDown.setText("▼ 下翻");
        Button bLogTop = new Button(this);   bLogTop.setText("⤒ 最旧");
        Button bLogEnd = new Button(this);   bLogEnd.setText("⤓ 最新");
        Button bLogCopy = new Button(this);  bLogCopy.setText("复制");
        Button bLogClear = new Button(this); bLogClear.setText("清空");
        rowLog.addView(bLogUp); rowLog.addView(bLogDown);
        rowLog.addView(bLogTop); rowLog.addView(bLogEnd);
        rowLog.addView(bLogCopy); rowLog.addView(bLogClear);
        root.addView(rowLog);

        tvLog = new TextView(this);
        // ⚠ 不要用 ScrollingMovementMethod、也不要 setTextIsSelectable：
        //   两者都会把拖动事件吃掉，导致日志区根本滑不动（要复制就用「复制」按钮）
        tvLog.setTextSize(11);
        logSv = new ScrollView(this);
        logSv.setFillViewport(true);
        logSv.setVerticalScrollBarEnabled(true);
        logSv.addView(tvLog);
        root.addView(logSv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (260 * getResources().getDisplayMetrics().density)));

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
                byte[] key = hex2(etKey.getText().toString().trim());
                if (key.length != 16) { log("auth key 必须是 32 位 hex (16 字节)"); return; }
                ensureConnected().authenticate(key);
                savePrefs();
            } catch (Exception e) { log("❌ " + e); }
        }));
        bClose.setOnClickListener(v -> { if (link != null) link.close(); });
        bSave.setOnClickListener(v -> { savePrefs(); log("已保存 MAC/KEY 到本机配置"); });

        // ───────── 日志翻页按钮 ─────────
        bLogUp.setOnClickListener(v -> logSv.smoothScrollBy(0, -Math.max(80, logSv.getHeight() * 8 / 10)));
        bLogDown.setOnClickListener(v -> logSv.smoothScrollBy(0, Math.max(80, logSv.getHeight() * 8 / 10)));
        bLogTop.setOnClickListener(v -> logSv.fullScroll(View.FOCUS_UP));
        bLogEnd.setOnClickListener(v -> logSv.fullScroll(View.FOCUS_DOWN));
        bLogCopy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("miwear log", sb.toString()));
                log("已复制日志到剪贴板（" + sb.length() + " 字节）");
            } catch (Exception e) { log("复制失败: " + e); }
        });
        bLogClear.setOnClickListener(v -> { sb.setLength(0); tvLog.setText(""); });

        // ───────── 睡眠监测按钮 ─────────
        bSleepOn.setOnClickListener(v -> {
            int iv = 60;
            try { iv = Integer.parseInt(etSleepIv.getText().toString().trim()); } catch (Exception ignored) {}
            if (iv < 10) iv = 10;
            SleepMonitor.start(this, iv);
            ensureServiceStarted("睡眠监测");
            log("✅ 睡眠监测已开启（每 " + iv + "s 一次）；入睡/起床会弹通知，常驻通知显示「睡眠监测中」");
        });
        bSleepOff.setOnClickListener(v -> {
            SleepMonitor.stop(this);
            log("🛑 睡眠监测已关闭" + (CmdServer.wantsCliService(this)
                    ? "（CLI 服务仍开着，常驻通知改为「CLI 服务运行中」）"
                    : "（后台服务已停，常驻通知已撤掉）"));
        });
        bSleepSt.setOnClickListener(v -> log("睡眠监测: " + (SleepMonitor.isRunning() ? "运行中" : "未运行")
                + "  " + SleepMonitor.statusJson(this)));
        bSleepLog.setOnClickListener(v -> {
            String l = SleepMonitor.savedLog(this);
            log("── 睡眠监测日志 ──\n" + (l == null || l.isEmpty() ? "（暂无）" : l));
        });
        bSleepTest.setOnClickListener(v -> bg(() -> {
            SleepMonitor.notify(this, "😴 睡眠监测测试",
                    "如果你看到这条通知，说明提醒通道通了。\n时间 " + new java.util.Date());
            log("已发测试通知");
        }));
        bReadKey.setOnClickListener(v -> bg(() -> {
            try { readOfficialKey(); } catch (Exception e) { log("❌ 读官方 key 失败: " + e); }
        }));
        bNotify.setOnClickListener(v -> bg(() -> {
            try {
                ensureConnected().pushNotification(etPkg.getText().toString().trim(),
                                      etTitle.getText().toString().trim(),
                                      etText.getText().toString().trim(),
                                      "MiWear", 1);
            } catch (Exception e) { log("❌ " + e); }
        }));
        bNet.setOnClickListener(v -> bg(() -> {
            try {
                WearLink l = ensureConnected();
                if (l.netProxyRunning()) l.stopNetProxy(); else l.startNetProxy();
            } catch (Exception e) { log("❌ " + e); }
        }));
        bSend.setOnClickListener(v -> bg(() -> {
            try {
                int api = Integer.parseInt(etApi.getText().toString().trim());
                byte[] sub = hex2(etSub.getText().toString().trim());
                ensureConnected().sendApi(api, sub);
            } catch (Exception e) { log("❌ " + e); }
        }));

        // ── 快应用管理 ──
        bList.setOnClickListener(v -> bg(() -> {
            try { ensureConnected().listApps(); } catch (Exception e) { log("❌ " + e); }
        }));
        bQuery.setOnClickListener(v -> bg(() -> {
            try { ensureConnected().appStatus(etAppPkg.getText().toString().trim()); }
            catch (Exception e) { log("❌ " + e); }
        }));
        bUninst.setOnClickListener(v -> confirm("卸载快应用",
                "卸载 " + etAppPkg.getText().toString().trim() + "？\n（设备对卸载不回执，看手表界面）",
                () -> {
                    try {
                        String fp = etFp.getText().toString().trim();
                        ensureConnected().uninstall(etAppPkg.getText().toString().trim(),
                                fp.isEmpty() ? null : hex2(fp));
                    } catch (Exception e) { log("❌ " + e); }
                }));
        bLaunch.setOnClickListener(v -> bg(() -> {
            try {
                String u = etUri.getText().toString().trim();
                ensureConnected().launchApp(etAppPkg.getText().toString().trim(), u.isEmpty() ? null : u);
            } catch (Exception e) { log("❌ " + e); }
        }));
        bInstall.setOnClickListener(v -> pickRpk());

        // ── 设备信息 / 健康 ──
        bProbeInfo.setOnClickListener(v -> probe(etMod.getText().toString().trim(), etInfoSub.getText().toString().trim()));
        bInfoAll.setOnClickListener(v -> bg(() -> {
            try { ensureConnected().deviceInfoAll(); } catch (Exception e) { log("❌ " + e); }
        }));
        bStor.setOnClickListener(v -> probe("2", "62"));
        bHr.setOnClickListener(v -> probe("8", "10"));
        bSpo2.setOnClickListener(v -> probe("8", "8"));
        bPress.setOnClickListener(v -> probe("8", "14"));
        bSit.setOnClickListener(v -> probe("8", "12"));
        bSleep.setOnClickListener(v -> probe("17", "8"));
        bAod.setOnClickListener(v -> probe("2", "65"));

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
                        runOnUiThread(() -> { etKey.setText(hex); savePrefs(); });
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
                        runOnUiThread(() -> { etKey.setText(hex); savePrefs(); });
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

    // ───────────────────────── rpk 文件选择 ─────────────────────────
    private static final int REQ_RPK = 42;

    private void pickRpk() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_RPK);
        } catch (Exception e) {
            log("❌ 打开文件选择器失败: " + e);
            log("   （或把 rpk 放到 /sdcard 后用 miwear install 装）");
        }
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_RPK || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        bg(() -> {
            try {
                byte[] bytes = readUri(uri);
                String name = uri.getLastPathSegment();
                if (name == null || !name.endsWith(".rpk")) name = "app.rpk";
                log("读取 rpk: " + name + " (" + bytes.length + " 字节)");
                ensureConnected().installRpk(bytes, name);
            } catch (Exception e) { log("❌ 安装失败: " + e); }
        });
    }

    private byte[] readUri(android.net.Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("打不开文件: " + uri);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 读任意 module/sub（官方读接口），结果以 protobuf 树打到日志 */
    private void probe(String mod, String sub) {
        bg(() -> {
            try {
                int m = Integer.parseInt(mod.trim());
                int s = Integer.parseInt(sub.trim());
                ensureConnected().probeInfo(m, s, 8000);
            } catch (Exception e) { log("❌ " + e); }
        });
    }

    /** 确保有一条已连接（已握手）的链路；断了会自动重连 */
    private WearLink ensureConnected() throws Exception {
        if (link != null && link.isConnected()) return link;
        String mac = etMac.getText().toString().trim();
        if (mac.isEmpty()) throw new IllegalStateException("先填手表 MAC");
        if (link != null) { try { link.close(); } catch (Exception ignored) {} }
        link = new WearLink(this);
        link.connect(mac);          // connect() 内部已经做了 L1 握手
        return link;
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

    /** 从官方 App（com.mi.health）读 encrypt_key / mac / phone_id。
     *
     * 注意：很多设备（比如 KernelSU-Next）只在部分 mount namespace 里暴露 su，
     * App 进程里 /system/bin/su 可能根本不存在（exec 会 ENOENT），所以优先读
     * Termux 侧（miwear）导出的文件：
     *   <files>/official_key.txt   第 1 行 mac、第 2 行 key、第 3 行 phone_id
     *   <files>/device_db.official 官方 device_db 原件（正则兜底）
     */
    private void readOfficialKey() throws Exception {
        // ① Termux 侧导出的 key
        File kf = new File(getFilesDir(), "official_key.txt");
        if (kf.exists()) {
            String[] lines = readLines(kf);
            if (lines.length >= 2 && lines[1].trim().matches("[0-9a-fA-F]{32}")) {
                final String mac = lines[0].trim();
                final String key = lines[1].trim().toLowerCase();
                final String pid = lines.length > 2 ? lines[2].trim() : "";
                log("🔑 读到官方 key（Termux 导出）= " + key);
                if (!mac.isEmpty()) log("   MAC = " + mac);
                if (!pid.isEmpty()) log("   phone_id = " + pid);
                runOnUiThread(() -> {
                    etKey.setText(key);
                    if (!mac.isEmpty()) etMac.setText(mac);
                    if (!pid.isEmpty()) etPhone.setText(pid);
                    savePrefs();
                });
                return;
            }
            log("⚠ official_key.txt 存在但内容不对，改试其他方式");
        }

        // ② 直接解析导出的 device_db 原件
        File db = new File(getFilesDir(), "device_db.official");
        if (!db.exists()) db = new File(getFilesDir(), "device_db");
        if (db.exists()) {
            if (parseDbForKey(db)) return;
        }

        // ③ 自己 su 去拷（部分设备 App 里能看到 su）
        String dir = getFilesDir().getAbsolutePath();
        String cmd = "cp -f /data/data/com.mi.health/databases/device_db* " + dir + "/ && chmod 666 " + dir + "/device_db*";
        String err = null;
        for (String su : new String[]{"su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
                                      "/data/adb/ksu/bin/su", "/data/adb/magisk/su"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                int rc = p.waitFor();
                if (rc == 0) {
                    File f = new File(dir, "device_db");
                    if (f.exists() && parseDbForKey(f)) return;
                }
                err = su + " exit=" + rc;
            } catch (Exception e) {
                err = su + ": " + e.getMessage();
            }
        }
        log("❌ 读不到官方 App 的 device_db（" + err + "）");
        log("   → 先在 Termux 里跑：miwear key   （会自动把 key 导出到本 App）");
        log("   → 或直接把 32 位 hex 填进上面的 auth key 框");
    }

    /** 从 sqlite 文件里正则抓 encrypt_key / mac / phone_id（JSON 是明文存的） */
    private boolean parseDbForKey(File db) throws Exception {
        byte[] b = new byte[(int) db.length()];
        try (FileInputStream in = new FileInputStream(db)) { in.read(b); }
        String s = new String(b, "ISO-8859-1");
        Matcher mk = Pattern.compile("\"encrypt_key\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").matcher(s);
        if (!mk.find()) { log("⚠ " + db.getName() + " 里没找到 encrypt_key"); return false; }
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
            if (fmac != null) etMac.setText(fmac);
            if (fpid != null) etPhone.setText(fpid);
            savePrefs();
        });
        return true;
    }

    private static String[] readLines(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) { in.read(b); }
        return new String(b, "UTF-8").split("\\r?\\n");
    }

    private static String firstMatch(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // ───────────────────────── 配置持久化 ─────────────────────────

    /** 确保前台服务已启动（睡眠监测需要它常驻，才能持续收 ch5 记录） */
    private void ensureServiceStarted(String tag) {
        try {
            android.content.Intent si = new android.content.Intent(this, GatewayService.class)
                    .putExtra("serve", true)
                    .putExtra("port", CmdServer.savedPort(this))
                    .putExtra("mac", etMac.getText().toString().trim())
                    .putExtra("key", etKey.getText().toString().trim())
                    .putExtra("sleep_monitor", true)
                    .putExtra("sleep_interval", SleepMonitor.savedInterval(this));
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
            log("（已拉前台服务，保证 " + tag + " 不被冻结）");
        } catch (Exception e) { log("❌ 启动前台服务失败: " + e); }
    }

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

        // ── 读官方 App 的 auth key（供脚本/界面用；读的是 Termux 导出的文件）──
        if (it.getBooleanExtra("read_key", false)) {
            bg(() -> { try { readOfficialKey(); } catch (Exception e) { log("❌ 读官方 key 失败: " + e); } });
            return;
        }

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
        // ── 睡眠监测（App 内常驻，不依赖 Termux）──
        if (it.hasExtra("sleep_monitor")) {
            int iv = it.getIntExtra("sleep_interval", 60);
            if (it.getBooleanExtra("sleep_monitor", false)) {
                SleepMonitor.start(this, iv);
                ensureServiceStarted("睡眠监测");
                log("✅ 睡眠监测已开启（每 " + iv + "s）");
            } else {
                SleepMonitor.stop(this);
                log("🛑 睡眠监测已关闭");
            }
            return;
        }
        if (it.getBooleanExtra("sleep_test", false)) {
            SleepMonitor.notify(this, "😴 睡眠监测测试",
                    "如果你看到这条通知，说明提醒通道通了。\n时间 " + new java.util.Date());
            log("已发测试通知");
            return;
        }
        if (it.getBooleanExtra("sleep_status", false)) {
            log("睡眠监测: " + (SleepMonitor.isRunning() ? "运行中" : "未运行") + "\n" + SleepMonitor.statusJson(this)
                + "\n── 日志 ──\n" + SleepMonitor.savedLog(this));
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
                        if (it.getBooleanExtra("battery_only", false)) { link.batteryInfo(); return; }
                        if (it.hasExtra("probe_mod")) {
                            link.probeInfo(it.getIntExtra("probe_mod", 0), it.getIntExtra("probe_sub", 0), 8000);
                            return;
                        }
                        if (it.getBooleanExtra("fit_ids", false)) {
                            link.fitnessIds(it.getIntExtra("fit_sub", 1));
                            return;
                        }
                        String fid = it.getStringExtra("fit_fetch_id");
                        if (fid != null) { link.fitnessFetchToFile(hex2(fid), 60000); return; }
                        if (it.getBooleanExtra("query_status", false)) { link.deviceInfoAll(); return; }
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
        final String line = s + "\n";
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(
                new File(getFilesDir(), "log.txt"), true)) {
            f.write(line.getBytes("UTF-8"));
        } catch (Exception ignored) {}
        runOnUiThread(() -> {
            // 只有本来就贴着底部时才自动跟到最新，避免正翻历史时被拽走
            boolean atBottom = true;
            try {
                if (logSv != null && tvLog != null)
                    atBottom = logSv.getScrollY() + logSv.getHeight() >= tvLog.getHeight() - 24;
            } catch (Exception ignored) {}
            sb.append(line);
            // 只保留最近 600 行，否则 TextView 会越滚越卡
            int nl = 0, cut = -1;
            for (int i = sb.length() - 1; i >= 0; i--) {
                if (sb.charAt(i) == '\n' && ++nl > 600) { cut = i + 1; break; }
            }
            if (cut > 0) sb.delete(0, cut);
            tvLog.setText(sb.toString());
            if (atBottom && logSv != null) logSv.post(() -> logSv.fullScroll(View.FOCUS_DOWN));
        });
    }
}
