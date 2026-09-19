package com.miwear.ctl;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** REDMI Watch 5 直连控制器 —— 完全绕过小米运动健康 */
public class MainActivity extends Activity implements WearLink.Log {

    private EditText etMac, etKey, etApi, etSub, etTitle, etText, etPkg;
    private TextView tvLog;
    private WearLink link;
    private final StringBuilder sb = new StringBuilder();
    private static boolean LOG_STARTED = false;

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        root.addView(label("手表 MAC"));
        etMac = input("D4:A3:65:C0:BE:EA");
        root.addView(etMac);

        root.addView(label("auth key (encrypt_key, 32 位 hex)"));
        etKey = input("");
        root.addView(etKey);

        LinearLayout row = new LinearLayout(this);
        Button bConn = new Button(this); bConn.setText("连接");
        Button bAuth = new Button(this); bAuth.setText("认证");
        Button bClose = new Button(this); bClose.setText("断开");
        row.addView(bConn); row.addView(bAuth); row.addView(bClose);
        root.addView(row);

        root.addView(label("apiCode / field3 子消息 hex"));
        LinearLayout row2 = new LinearLayout(this);
        etApi = input("26");
        etSub = input("");
        row2.addView(etApi); row2.addView(etSub);
        Button bSend = new Button(this); bSend.setText("发送");
        row2.addView(bSend);
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

        tvLog = new TextView(this);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setTextSize(11);
        ScrollView sv = new ScrollView(this);
        sv.addView(tvLog);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        bConn.setOnClickListener(v -> bg(() -> {
            try { link = new WearLink(this); link.connect(etMac.getText().toString().trim()); }
            catch (Exception e) { log("❌ " + e); }
        }));
        bAuth.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                byte[] key = hex2(etKey.getText().toString().trim());
                if (key.length != 16) { log("auth key 必须是 32 位 hex (16 字节)"); return; }
                link.authenticate(key);
            } catch (Exception e) { log("❌ " + e); }
        }));
        bClose.setOnClickListener(v -> { if (link != null) link.close(); });
        bNotify.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                link.pushNotification(etPkg.getText().toString().trim(),
                                      etTitle.getText().toString().trim(),
                                      etText.getText().toString().trim(),
                                      "MiWear", 1);
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

        if (!LOG_STARTED) {
            LOG_STARTED = true;
            try { new java.io.File(getFilesDir(), "log.txt").delete(); } catch (Exception ignored) {}
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

        setContentView(root);
    }

    /** 处理启动参数；singleTask 下 am start 走 onNewIntent，必须也走这里 */
    private void handleIntent(android.content.Intent it) {
        if (it == null) return;
        String m = it.getStringExtra("mac");
        String k = it.getStringExtra("key");
        if (m != null) etMac.setText(m);
        if (k != null) etKey.setText(k);
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
                    if (autoAuth) {
                        byte[] key = hex2(kk);
                        if (key.length != 16) { log("auth key 长度错误: " + key.length + " 字节"); return; }
                        link.authenticate(key);
                        String rpkPath = it.getStringExtra("install_rpk");
                        if (rpkPath != null) {
                            java.io.File f = new java.io.File(rpkPath);
                            log("读取 rpk: " + rpkPath + " 存在=" + f.exists() + " 大小=" + f.length());
                            byte[] data = new byte[(int) f.length()];
                            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                                int p2 = 0; while (p2 < data.length) { int r = in.read(data, p2, data.length - p2); if (r < 0) break; p2 += r; }
                            }
                            link.installRpk(data, f.getName());
                            return;
                        }
                        String nt = it.getStringExtra("notify_title");
                        if (nt != null) {
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
                new java.io.File(getFilesDir(), "log.txt"), true)) {
            f.write(line.getBytes("UTF-8"));
        } catch (Exception ignored) {}
        runOnUiThread(() -> { sb.append(line); tvLog.setText(sb.toString()); });
    }
}
