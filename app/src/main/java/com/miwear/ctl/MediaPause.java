package com.miwear.ctl;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 暂停正在播放的媒体（听书 / 音乐）。
 *
 * 首选路子：通知使用权（{@link MediaListener}）→ MediaSessionManager.getActiveSessions()
 * 拿到每个会话的控制器，只对「正在播放」的调 {@code pause()} —— 精准，不会打错目标。
 *
 * 兜底：没有通知使用权时，用 root 盲发一次媒体键（{@code cmd media_session dispatch pause}）。
 * ⚠ 盲发会打到系统认定的「最上层会话」，机器上还有 QQ音乐 MiPush 造的假会话时可能打偏，
 * 所以只是尽力而为，日志里会标明是哪种方式。
 */
public final class MediaPause {

    private MediaPause() {}

    /** 通知使用权服务组件（manifest 里注册的） */
    public static final String LISTENER = "com.miwear.ctl/com.miwear.ctl.MediaListener";

    private static final String[] SU_PATHS = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/magisk/su"
    };

    /** 有通知使用权吗（能列出媒体会话就说明有） */
    public static boolean canControl(Context ctx) {
        try {
            sessions(ctx);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<MediaController> sessions(Context ctx) {
        MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (m == null) throw new IllegalStateException("没有 MediaSessionManager");
        return m.getActiveSessions(new ComponentName(ctx, MediaListener.class));
    }

    /**
     * 暂停所有正在播放的媒体会话。
     * @return 人类可读的结果（供日志 / 通知）
     */
    public static String pauseAllPlaying(Context ctx) {
        List<MediaController> list;
        try {
            list = sessions(ctx);
        } catch (Throwable t) {
            // 没有通知使用权 → 兜底盲发
            String fallback = rootDispatchPause();
            return fallback != null ? fallback
                    : "暂停失败：没有通知使用权（" + t + "）";
        }
        Set<String> hit = new LinkedHashSet<>();
        Set<String> others = new LinkedHashSet<>();
        for (MediaController c : list) {
            try {
                PlaybackState st = c.getPlaybackState();
                int s = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                    c.getTransportControls().pause();
                    hit.add(c.getPackageName());
                } else {
                    others.add(c.getPackageName());
                }
            } catch (Throwable ignored) {}
        }
        if (!hit.isEmpty()) return "已暂停正在播放的： " + String.join("、", hit);
        if (!others.isEmpty()) return "没有正在播放的媒体（会话都在暂停/停下： " + String.join("、", others) + " ）";
        return "没有可控制的媒体会话";
    }

    /**
     * best-effort 打开通知使用权（需要 root）。
     * 成功或本来就已授权返回 true。
     */
    public static boolean ensureListener(Context ctx) {
        if (canControl(ctx)) return true;
        for (String su : SU_PATHS) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c",
                        "cmd notification allow_listener " + LISTENER});
                if (p.waitFor() == 0 && canControl(ctx)) return true;
            } catch (Throwable ignored) {}
        }
        return canControl(ctx);
    }

    /** root 盲发媒体键（没有通知使用权时的兜底）。失败返回 null。 */
    private static String rootDispatchPause() {
        for (String su : SU_PATHS) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c",
                        "cmd media_session dispatch pause"});
                if (p.waitFor() == 0) return "已盲发暂停键（无通知使用权，可能不准）";
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 给状态查询用的 JSON 小摘要 */
    public static String statusJson(Context ctx) {
        List<MediaController> list;
        try {
            list = sessions(ctx);
        } catch (Throwable t) {
            return "{\"listener\":false}";
        }
        StringBuilder sb = new StringBuilder("{\"listener\":true,\"playing\":[");
        boolean first = true;
        for (MediaController c : list) {
            try {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    if (!first) sb.append(',');
                    sb.append(org.json.JSONObject.quote(c.getPackageName()));
                    first = false;
                }
            } catch (Throwable ignored) {}
        }
        return sb.append("]}").toString();
    }
}
