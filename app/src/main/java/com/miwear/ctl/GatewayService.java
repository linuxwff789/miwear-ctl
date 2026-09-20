package com.miwear.ctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：App 退到后台后会被 Android 的 app freezer 冻结（进程状态 do_freezer_trap），
 * 冻结后收不到手表的包 → 手表 fetch 报 error 300。常驻前台服务可避免被冻结。
 */
public class GatewayService extends Service {

    public static final String CHANNEL = "miwear-gateway";
    public static final int NOTIFY_ID = 4321;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "miwear 网关", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 进程被杀后 START_STICKY 重启会是 null intent → 从 SharedPreferences 恢复
        boolean serve;
        String mac, key;
        int port;
        if (intent != null) {
            serve = intent.getBooleanExtra("serve", false);
            mac = intent.getStringExtra("mac");
            key = intent.getStringExtra("key");
            port = intent.getIntExtra("port", CmdServer.DEFAULT_PORT);
        } else {
            serve = CmdServer.wasServing(this);
            mac = CmdServer.savedMac(this);
            key = CmdServer.savedKey(this);
            port = CmdServer.savedPort(this);
        }
        if (serve) {
            WearLink.APP = getApplicationContext();
            CmdServer.start(this, mac, key, port);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(serve ? "miwear CLI 服务运行中" : "miwear 网关运行中")
                .setContentText(serve ? "127.0.0.1:" + CmdServer.runningPort() + " · 认证已常驻"
                                      : "手表的网络请求正通过手机转发")
                .setOngoing(true)
                .build();
        try { startForeground(NOTIFY_ID, n); } catch (Exception ignored) {}
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
