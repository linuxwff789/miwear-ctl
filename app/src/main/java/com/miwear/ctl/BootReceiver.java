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
            CmdServer.stop();
            try { app.stopService(new Intent(app, GatewayService.class)); } catch (Exception ignored) {}
            return;
        }
        boolean explicitStart = "com.termux.miwear.START".equals(a);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!explicitStart && !boot) return;
        // 开机自启只在用户显式开过服务时才做，不擅自常驻
        if (boot && !CmdServer.wasServing(app)) return;

        WearLink.APP = app;
        Intent si = new Intent(app, GatewayService.class)
                .putExtra("serve", true)
                .putExtra("port", CmdServer.savedPort(app))
                .putExtra("mac", explicitStart && it.getStringExtra("mac") != null
                        ? it.getStringExtra("mac") : CmdServer.savedMac(app))
                .putExtra("key", explicitStart && it.getStringExtra("key") != null
                        ? it.getStringExtra("key") : CmdServer.savedKey(app))
                .putExtra("quiet", it.getBooleanExtra("quiet", false));
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(si); else app.startService(si);
        } catch (Exception ignored) {}
    }
}