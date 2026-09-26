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

    private static GatewayService INSTANCE_SVC;

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE_SVC = this;
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
            SleepMonitor.stop(this, "serve_stop");
            CmdServer.stop();
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean serve, userWanted = false, quiet = false;
        String mac, key;
        int port;
        if (intent != null) {
            serve = intent.getBooleanExtra("serve", false);
            userWanted = intent.getBooleanExtra("serve_user", false);
            mac = intent.getStringExtra("mac");
            key = intent.getStringExtra("key");
            port = intent.getIntExtra("port", CmdServer.DEFAULT_PORT);
            quiet = intent.getBooleanExtra("quiet", false);
        } else {
            // 进程被杀后 START_STICKY 重启会是 null intent → 从 SharedPreferences 恢复
            userWanted = CmdServer.wantsCliService(this);
            serve = CmdServer.wasServing(this) || userWanted;
            mac = CmdServer.savedMacAny(this);
            key = CmdServer.savedKeyAny(this);
            port = CmdServer.savedPort(this);
        }
        WearLink.APP = getApplicationContext();
        if (serve) CmdServer.start(this, mac, key, port, userWanted);

        // 睡眠监测开关
        if (intent != null && intent.hasExtra("sleep_monitor")) {
            if (intent.getBooleanExtra("sleep_monitor", false)) {
                SleepMonitor.start(this, intent.getIntExtra("sleep_interval", 60));
            } else {
                SleepMonitor.stop(this, "intent sleep_monitor=false");
            }
        } else {
            autoStartMonitors();
        }

        if (quiet && CmdServer.isRunning()) return START_STICKY;   // 静默模式：不挂常驻通知

        // 没任何东西需要常驻（监测关了 / 用户没要 CLI / 本次也没要求 CLI / 网关没跑）
        // 注意要带上 serve：否则“为监测而拉服务”的启动会在监测还没就绪时把自己停掉（竞态）
        boolean wanted = SleepMonitor.isRunning() || CmdServer.wantsCliService(this)
                       || netproxyActive() || serve;
        if (!wanted) {
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        showForeground(serve);
        return START_STICKY;
    }

    /** 建/更新前台通知（标题与副标题反映当前实际状态） */
    private void showForeground(boolean serve) {
        boolean sm = SleepMonitor.isRunning();
        boolean cli = CmdServer.isRunning();
        String title = sm ? "miwear 睡眠监测中"
                      : (cli ? "miwear CLI 服务运行中" : "miwear 网关运行中");
        StringBuilder t = new StringBuilder();
        if (sm) t.append("入睡 / 起床会弹通知");
        if (cli) t.append(t.length() > 0 ? " · " : "").append("CLI 127.0.0.1:").append(CmdServer.runningPort());
        if (t.length() == 0) t.append("手表的网络请求正通过手机转发");

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(t.toString())
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .build();
        try { startForeground(NOTIFY_ID, n); } catch (Exception ignored) {}
    }

    /** 任何模块（睡眠监测 / CLI 服务）启停后调用：刷新通知；没人需要了就停掉自己（通知消失） */
    private static boolean refreshing;

    public static void refresh() { refresh("state-change"); }

    /** @param why 谁触发的刷新（写日志，便于排查“通知怎么自己变了/没了”） */
    public static void refresh(String why) {
        GatewayService s = INSTANCE_SVC;
        if (s == null) return;
        if (refreshing) return;              // CmdServer.stop() 会回调刷新，防递归
        refreshing = true;
        try {
            boolean busy = SleepMonitor.isRunning() || CmdServer.wantsCliService(s)
                        || (CmdServer.isRunning() && s.netproxyActive());
            s.appendSvcLog("refresh(" + why + "): monitor=" + SleepMonitor.isRunning()
                    + " userCli=" + CmdServer.wantsCliService(s) + " netproxy=" + s.netproxyActive()
                    + " → " + (busy ? "保留服务/刷新通知" : "停服务撤通知"));
            if (!busy) {
                // 连本地 socket 一起关掉：否则会留下「服务没了但 socket 还活着」的错乱状态，
                // 之后 miwear serve start 会误判成“已在运行”而不再拉起前台服务（通知栏就永远不出现）
                if (CmdServer.isRunning()) CmdServer.stop();
                try { s.stopForeground(true); } catch (Exception ignored) {}
                s.stopSelf();
                return;
            }
            s.showForeground(CmdServer.wantsCliService(s));
        } finally {
            refreshing = false;
        }
    }

    /** 只写文件，不弹 Toast；供诊断用 */
    private void appendSvcLog(String line) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "log.txt");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                fo.write(("[\u670d\u52a1] " + line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    private boolean netproxyActive() {
        try {
            WearLink l = CmdServer.currentLink();
            return l != null && l.netProxyRunning();
        } catch (Throwable t) { return false; }
    }

    @Override public void onDestroy() {
        if (INSTANCE_SVC == this) INSTANCE_SVC = null;
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}