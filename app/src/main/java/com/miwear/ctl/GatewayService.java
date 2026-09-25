package com.miwear.ctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻服务，两种用途：
 *  ① 联网网关（netproxy）：App 退到后台后会被 Android 的 app freezer 冻结
 *     （进程状态 do_freezer_trap），冻结后收不到手表的包 → 手表 fetch 报 error 300。
 *  ② CLI 服务（CmdServer）：把 App 当蓝牙后端，Termux 侧 miwear 命令走本地 socket。
 *
 * 静默启动（界面完全不出现）：
 *   am start-foreground-service -n com.miwear.ctl/.GatewayService --ez serve true ...
 * 静默启动且不要常驻通知（可能被系统回收，建议先加白名单）：
 *   am start-service            -n com.miwear.ctl/.GatewayService --ez serve true --ez quiet true ...
 */
public class GatewayService extends Service {

    public static final String CHANNEL = "miwear-gateway";
    public static final String ALERT_CHANNEL = "miwear-alert";
    public static final int NOTIFY_ID = 4321;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                // MIN：不出声、不震动、不弹横幅（前台服务必须有通知，这是最低调的一档）
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "miwear 后台服务", NotificationManager.IMPORTANCE_MIN));
            }
            if (nm != null && nm.getNotificationChannel(ALERT_CHANNEL) == null) {
                NotificationChannel ac = new NotificationChannel(
                        ALERT_CHANNEL, "miwear 提醒", NotificationManager.IMPORTANCE_HIGH);
                ac.enableVibration(true);
                nm.createNotificationChannel(ac);
            }
        }
        autoStartMonitors();
    }

    /** 进程起来后（含开机/被杀重启）按已保存的开关自动恢复睡眠监测 */
    private void autoStartMonitors() {
        try {
            WearLink.APP = getApplicationContext();
            if (!SleepMonitor.enabled(this)) return;
            // 睡眠监测需要一条常驻手表连接（ch5 记录靠它收）——没服务就拉一个
            if (!CmdServer.isRunning()) {
                String mac = CmdServer.savedMacAny(this), key = CmdServer.savedKeyAny(this);
                if (!mac.isEmpty() && !key.isEmpty()) {
                    CmdServer.start(this, mac, key, CmdServer.savedPort(this));
                }
            }
            if (!SleepMonitor.isRunning()) {
                SleepMonitor.start(this, SleepMonitor.savedInterval(this));
            }
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean stop = intent != null && intent.getBooleanExtra("serve_stop", false);
        if (stop) {
            SleepMonitor.stop(this);
            CmdServer.stop();
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        // 睡眠监测（可与 CLI 服务独立运行）
        if (intent != null && intent.hasExtra("sleep_monitor")) {
            if (intent.getBooleanExtra("sleep_monitor", false)) {
                SleepMonitor.start(this, intent.getIntExtra("sleep_interval", 60));
            } else {
                SleepMonitor.stop(this);
            }
        } else {
            autoStartMonitors();
        }

        // 进程被杀后 START_STICKY 重启会是 null intent → 从 SharedPreferences 恢复
        boolean serve;
        String mac, key;
        int port;
        boolean quiet = false;
        if (intent != null) {
            serve = intent.getBooleanExtra("serve", false);
            mac = intent.getStringExtra("mac");
            key = intent.getStringExtra("key");
            port = intent.getIntExtra("port", CmdServer.DEFAULT_PORT);
            quiet = intent.getBooleanExtra("quiet", false);
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

        if (quiet && CmdServer.isRunning()) {
            // 静默模式：不挂常驻通知（用 am start-service 启动时才能这么干）
            return START_STICKY;
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean sm = SleepMonitor.isRunning();
        String title = serve ? "miwear CLI 服务运行中"
                      : (sm ? "miwear 睡眠监测中" : "miwear 网关运行中");
        String text = serve ? ("127.0.0.1:" + CmdServer.runningPort() + " · 认证已常驻"
                              + (sm ? " · 睡眠监测开" : ""))
                : (sm ? "入睡 / 起床会弹通知（后台常驻）" : "手表的网络请求正通过手机转发");
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
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