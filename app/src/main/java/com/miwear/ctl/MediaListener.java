package com.miwear.ctl;

import android.service.notification.NotificationListenerService;

/**
 * 空的「通知使用权」服务。
 *
 * 我们并不读任何通知，只是借这个权限拿到 {@code MediaSessionManager.getActiveSessions()}，
 * 从而精确控制媒体会话（入睡时暂停听书/音乐）。
 *
 * 授权方式（免手动点设置，root 一条命令）：
 *   su -c "cmd notification allow_listener com.miwear.ctl/com.miwear.ctl.MediaListener"
 * App 启动监测时也会自己 best-effort 试一次（见 {@link MediaPause#ensureListener}）。
 */
public class MediaListener extends NotificationListenerService {
}
