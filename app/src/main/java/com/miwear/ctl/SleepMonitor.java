package com.miwear.ctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 睡眠监测（常驻 App 内，不依赖 Termux / 外部脚本）。
 *
 * 原理：手表把健身记录（含「全天睡眠」AllDaySleep 段）从 ch5 推给 App，WearLink 自动落盘到
 *   files/fitness/&lt;7B id&gt;.bin
 * 本类定期扫描这些文件，以**最新那一段**为准：
 *   AllDaySleep.isSleepFinish = 0 ⇒ 正在睡；= 1 ⇒ 已醒
 * 状态机 awake ↔ asleep，入睡/起床时写日志并弹本机通知（channel miwear-alert）。
 *
 * 记录布局（详见 tools/fitness_decode.py）：
 *   &lt;7B id&gt; 0x00 &lt;dataValid N&gt; &lt;body&gt;；AllDaySleep v1~4 的 dataValid = 1 字节，
 *   body = isSleepFinish(1) bedTime(4) wakeupTime(4) [sleepQuality(1) if ver≥4] …
 */
public final class SleepMonitor implements WearLink.Log {

    public static final String ACTION = "com.miwear.ctl.SLEEPMON";
    public static final String CHANNEL = "miwear-alert";
    public static final int NOTIFY_ID = 7788;
    private static final String PREF = "miwear-sleep";

    private static SleepMonitor INSTANCE;
    public static synchronized SleepMonitor get() { return INSTANCE; }
    public static synchronized boolean isRunning() { return INSTANCE != null && INSTANCE.running; }

    public static synchronized boolean enabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    public static synchronized int savedInterval(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("interval", 60);
    }

    public static synchronized String savedLog(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("log", "");
    }

    public static synchronized void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }

    public static synchronized void setInterval(Context c, int sec) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("interval", sec).apply();
    }

    /** 清空状态 / 日志（状态机回到 awake；用于消掉误判、重新开始记录） */
    public static synchronized void resetState(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .remove("state").remove("lastId").remove("bedTime")
                .remove("wakeupTime").remove("detectedAt").remove("log").apply();
        SleepMonitor m = INSTANCE;
        if (m != null) {
            m.state = "awake"; m.lastId = null;
            m.bedTime = 0; m.wakeupTime = 0; m.detectedAt = 0;
        }
    }

    /** 加一行到内存日志（供 App 界面显示） */
    private static synchronized void pushLog(Context c, String line) {
        String old = savedLog(c);
        String all = line + "\n" + (old == null ? "" : old);
        if (all.length() > 12000) all = all.substring(0, 12000);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("log", all).apply();
    }

    /** 外部（CmdServer / 脚本）可以直接让 App 弹一条通知 */
    public static void notify(Context ctx, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "miwear 提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
            Notification n = (Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────── 实例 ───────────────────────────

    private final Context ctx;
    private volatile boolean running;
    private Thread thread;
    private int interval;
    private String state = "awake";
    private String lastId = null;
    private long bedTime, wakeupTime, detectedAt;
    private final Object lock = new Object();
    private boolean dirty;

    /** 收到新记录时“踢”一下，立即判定（不用等到下一个轮询） */
    public static void kick() {
        SleepMonitor m = INSTANCE;
        if (m == null) return;
        synchronized (m.lock) { m.dirty = true; m.lock.notifyAll(); }
    }

    private SleepMonitor(Context c) { this.ctx = c.getApplicationContext(); }

    public static synchronized void start(Context c, int intervalSec) {
        if (INSTANCE != null && INSTANCE.running) {
            INSTANCE.interval = intervalSec;
            setInterval(c, intervalSec);
            INSTANCE.log("睡眠监测已在运行，间隔改为 " + intervalSec + "s");
            return;
        }
        SleepMonitor m = new SleepMonitor(c);
        INSTANCE = m;
        m.interval = intervalSec <= 0 ? 60 : intervalSec;
        setEnabled(c, true);
        setInterval(c, m.interval);
        m.restoreState();
        m.running = true;
        m.thread = new Thread(m::loop, "miwear-sleep");
        m.thread.setDaemon(true);
        m.thread.start();
        GatewayService.refresh("monitor-start");   // 通知栏要跟着变
    }

    public static synchronized void stop(Context c) { stop(c, "unknown"); }

    /** @param why 谁触发的停止（便于日后查“怎么自己关了”） */
    public static synchronized void stop(Context c, String why) {
        SleepMonitor m = INSTANCE;
        INSTANCE = null;
        if (m != null) {
            m.running = false;
            m.log("🛑 睡眠监测已停止（触发：" + why + "）");
            synchronized (m.lock) { m.lock.notifyAll(); }
        }
        setEnabled(c, false);
        GatewayService.refresh("monitor-stop:" + why);   // 关监测后：不要 CLI 服务的话就把常驻通知一起撤掉
    }

    /** 进程重启后恢复：读回状态（重启不重复报「已入睡」） */
    private void restoreState() {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            state = sp.getString("state", "awake");
            lastId = sp.getString("lastId", null);
            bedTime = sp.getLong("bedTime", 0);
            wakeupTime = sp.getLong("wakeupTime", 0);
            detectedAt = sp.getLong("detectedAt", 0);
        } catch (Exception ignored) {}
    }

    private void persistState() {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString("state", state)
                    .putString("lastId", lastId)
                    .putLong("bedTime", bedTime)
                    .putLong("wakeupTime", wakeupTime)
                    .putLong("detectedAt", detectedAt)
                    .apply();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────── 主循环 ───────────────────────────

    private void loop() {
        log("👀 睡眠监测启动（间隔 " + interval + "s，当前状态 " + ("asleep".equals(state) ? "睡眠中" : "清醒") + "）");
        int miss = 0;
        while (running) {
            try {
                SleepSeg seg = newestSleep();
                if (seg == null) {
                    miss++;
                    // 长时间没有推送 → 主动问一次手表（也会促使它把积压记录推过来）
                    if (miss % 5 == 1) pokeWatch();
                } else {
                    miss = 0;
                    step(seg);
                }
            } catch (Throwable t) {
                log("⚠ 监测异常: " + t);
            }
            synchronized (lock) {
                if (!dirty) {
                    try { lock.wait(Math.max(5, interval) * 1000L); }
                    catch (InterruptedException e) { break; }
                }
                dirty = false;
            }
        }
        running = false;
    }

    /** 让 CmdServer 去问一次 8/1（手表会顺带把积压记录推下来） */
    private void pokeWatch() { CmdServer.pokeFitnessIds(); }

    private void step(SleepSeg seg) {
        boolean fin = seg.isSleepFinish;
        if (!fin && !"asleep".equals(state)) {
            long now = System.currentTimeMillis() / 1000L;
            state = "asleep"; lastId = seg.id; bedTime = seg.bedTime; detectedAt = now;
            persistState();
            String msg = "入睡时间 " + ts(seg.bedTime) + "（表记录）\n"
                       + "检测到 " + ts(now) + "\n记录 id " + seg.id;
            log("😴 入睡  bedTime=" + ts(seg.bedTime) + "  detected=" + ts(now) + "  id=" + seg.id);
            notify(ctx, "😴 已入睡", msg);
        } else if (fin && "asleep".equals(state)) {
            long now = System.currentTimeMillis() / 1000L;
            long bed = bedTime > 0 ? bedTime : seg.bedTime;
            double dur = seg.wakeupTime > 0 ? (seg.wakeupTime - bed) / 3600.0 : 0;
            state = "awake"; lastId = seg.id; wakeupTime = seg.wakeupTime; detectedAt = now;
            persistState();
            String msg = "起床时间 " + ts(seg.wakeupTime) + "\n"
                       + "睡了 " + String.format("%.1f", dur) + " 小时\n"
                       + "检测到 " + ts(now) + "\n记录 id " + seg.id;
            log("☀️ 起床  wakeup=" + ts(seg.wakeupTime) + "  slept=" + String.format("%.1f", dur)
                + "h  detected=" + ts(now) + "  id=" + seg.id);
            notify(ctx, "☀️ 已起床", msg);
        } else if (!seg.id.equals(lastId)) {
            lastId = seg.id;
            persistState();
            log("· 新睡眠段 id=" + seg.id + "  isSleepFinish=" + fin
                + "  bed=" + ts(seg.bedTime) + " wake=" + ts(seg.wakeupTime));
        }
    }

    // ─────────────────────── 记录扫描 / 解析 ───────────────────────

    private static final class SleepSeg {
        String id;
        boolean isSleepFinish;
        long bedTime, wakeupTime;
        long sortKey;
    }

    private File fitnessDir() { return new File(ctx.getFilesDir(), "fitness"); }

    private SleepSeg newestSleep() {
        File dir = fitnessDir();
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<File> cand = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".bin")) continue;
            byte[] idb = MainActivity.hex2(n.substring(0, n.length() - 4));
            if (idb.length != 7) continue;
            int type = idb[6] & 0xFF;
            int dt = (type >> 7) & 1;
            int daily = dt == 1 ? 0 : ((type & 0x7F) >> 2);
            int file = type & 3;
            if ((daily == 8 && file == 1) || daily == 2 || daily == 3) cand.add(f);
        }
        if (cand.isEmpty()) return null;
        cand.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(cand.size(), 4); i++) {
            SleepSeg s = parseAllDaySleep(cand.get(i));
            if (s != null) return s;
        }
        return null;
    }

    private SleepSeg parseAllDaySleep(File f) {
        try {
            byte[] b = readAll(f);
            if (b == null || b.length < 9 + 12) return null;
            int type = b[6] & 0xFF;
            int daily = ((type & 0x7F) >> 2);
            int file = type & 3;
            if (daily != 8 || file != 1) return null;
            int ver = b[5] & 0xFF;
            int validLen = allDaySleepValidLen(ver);
            if (validLen <= 0) return null;
            int off = 7 + 1 + validLen;
            if (b.length < off + 9) return null;
            SleepSeg s = new SleepSeg();
            s.id = f.getName().substring(0, 14);
            s.isSleepFinish = b[off] == 1;
            s.bedTime = u32(b, off + 1);
            s.wakeupTime = u32(b, off + 5);
            s.sortKey = s.bedTime;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** FitnessDataValidity.getAllDaySleepValidityLen */
    private static int allDaySleepValidLen(int version) {
        if (version >= 1 && version <= 4) return 1;
        if (version == 5) return 2;
        if (version == 6) return 3;
        return -1;
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8)
             | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }

    private static byte[] readAll(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int p = 0;
            while (p < b.length) {
                int r = in.read(b, p, b.length - p);
                if (r < 0) break;
                p += r;
            }
            return b;
        } catch (Exception e) { return null; }
    }

    // ─────────────────────────── 杂项 ───────────────────────────

    private static String ts(long sec) {
        if (sec <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(sec * 1000L));
    }

    /** 写日志：App 界面（SharedPreferences）+ log.txt（终端 miwear log 也能看） */
    @Override public void log(String s) {
        String line = "[" + ts(System.currentTimeMillis() / 1000) + "] " + s;
        pushLog(ctx, line);
        try {
            File lf = new File(ctx.getFilesDir(), "sleep.log");
            try (FileOutputStream fo = new FileOutputStream(lf, true)) {
                fo.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
        CmdServer.sendLog(line);
    }

    /** 给 CmdServer status/sleep 查询用的 JSON 摘要 */
    public static String statusJson(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject();
            o.put("running", isRunning());
            o.put("enabled", sp.getBoolean("enabled", false));
            o.put("interval", sp.getInt("interval", 60));
            o.put("state", sp.getString("state", "awake"));
            o.put("lastId", sp.getString("lastId", ""));
            o.put("bedTime", sp.getLong("bedTime", 0));
            o.put("wakeupTime", sp.getLong("wakeupTime", 0));
            o.put("detectedAt", sp.getLong("detectedAt", 0));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}
