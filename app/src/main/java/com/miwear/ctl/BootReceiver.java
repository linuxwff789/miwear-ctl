package com.miwear.ctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 静默开机自启。
 *
 * 只要用户曾经 `miwear serve start` 过（SharedPreferences 里 serve=true），
 * 开机 / 系统重启 / 应用被覆盖安装后会自动把 CLI 服务拉起来 —— 界面完全不出现。
 *
 * 也支持命令控制（不需要 root，但需 App 未被冻结）：
 *   am broadcast -a com.termux.miwear.START -n com.miwear.ctl/.BootReceiver
 *   am broadcast -a com.termux.miwear.STOP  -n com.miwear.ctl/.BootReceiver
 *
 * 注意：小米/HyperOS 需要在「设置 → 应用 → miwear-ctl → 自启动」里允许，
 * 开机广播才会送到（否则系统会拦）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent it) {
        String a = it == null ? null : it.getAction();
        Context app = ctx.getApplicationContext();
        if (a == null) return;

        if ("com.termux.miwear.STOP".equals(a)) {
            SleepMonitor.stop(app, "boot receiver STOP");
            CmdServer.stop();
            try { app.stopService(new Intent(app, GatewayService.class)); } catch (Exception ignored) {}
            return;
        }
        boolean explicitStart = "com.termux.miwear.START".equals(a);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!explicitStart && !boot) return;
        // 开机自启只在用户显式开过服务 / 睡眠监测时才做，不擅自常驻
        boolean sleepWanted = SleepMonitor.enabled(app);
        if (boot && !CmdServer.wasServing(app) && !sleepWanted) return;

        WearLink.APP = app;
        boolean serve = CmdServer.wasServing(app) || explicitStart;
        Intent si = new Intent(app, GatewayService.class)
                .putExtra("serve", serve)
                .putExtra("port", CmdServer.savedPort(app))
                .putExtra("mac", explicitStart && it.getStringExtra("mac") != null
                        ? it.getStringExtra("mac") : CmdServer.savedMacAny(app))
                .putExtra("key", explicitStart && it.getStringExtra("key") != null
                        ? it.getStringExtra("key") : CmdServer.savedKeyAny(app))
                .putExtra("quiet", it.getBooleanExtra("quiet", false));
        if (sleepWanted) {
            si.putExtra("sleep_monitor", true)
              .putExtra("sleep_interval", SleepMonitor.savedInterval(app))
              .putExtra("serve", true)
              .putExtra("serve_user", CmdServer.wantsCliService(app))
              .putExtra("mac", CmdServer.savedMacAny(app))
              .putExtra("key", CmdServer.savedKeyAny(app));
        } else if (CmdServer.wantsCliService(app)) {
            si.putExtra("serve_user", true);
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(si); else app.startService(si);
        } catch (Exception ignored) {}
    }
}