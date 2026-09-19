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

    private EditText etMac, etKey, etApi, etSub;
    private TextView tvLog;
    private WearLink link;
    private final StringBuilder sb = new StringBuilder();

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
        bSend.setOnClickListener(v -> bg(() -> {
            try {
                if (link == null) { log("先连接"); return; }
                int api = Integer.parseInt(etApi.getText().toString().trim());
                byte[] sub = hex2(etSub.getText().toString().trim());
                link.sendApi(api, sub);
            } catch (Exception e) { log("❌ " + e); }
        }));

        // 支持从 Intent 传入，避免在中文输入法下手打
        android.content.Intent it = getIntent();
        if (it != null) {
            String m = it.getStringExtra("mac");
            String k = it.getStringExtra("key");
            if (m != null) etMac.setText(m);
            if (k != null) etKey.setText(k);
            if (it.getBooleanExtra("autoconnect", false)) {
                String mm = etMac.getText().toString().trim();
                bg(() -> { try { link = new WearLink(this); link.connect(mm); } catch (Exception e) { log("❌ " + e); } });
            }
            if (it.getBooleanExtra("autoauth", false)) {
                bg(() -> {
                    try {
                        for (int i = 0; i < 40 && link == null; i++) Thread.sleep(250);
                        if (link == null) { log("未连接"); return; }
                        byte[] key = hex2(etKey.getText().toString().trim());
                        if (key.length != 16) { log("auth key 长度错误: " + key.length); return; }
                        link.authenticate(key);
                    } catch (Exception e) { log("❌ " + e); }
                });
            }
        }

        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        log(ad != null && ad.isEnabled() ? "蓝牙已开启" : "⚠ 蓝牙未开启");
        setContentView(root);
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
        runOnUiThread(() -> { sb.append(s).append('\n'); tvLog.setText(sb.toString()); });
    }
}
