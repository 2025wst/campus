package com.zzu.checkin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 一条通知：打卡成功/失败都告诉用户。
 *
 * 为什么必须有：这套自动化的最大风险是"静默失败"（以为签了其实没签）。
 * 只要失败就发通知，用户点开能看到原因。
 */
public final class Notify {

    private static final String CHANNEL = "checkin_v2";
    // 用新通道 id：安卓不允许修改已存在通道的声音设置，只能换一个
    
    private static final int ID = 2001;
    private static final int ID_WAKE = 2002;

    private Notify() {
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "打卡结果", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("查寝打卡成功或失败的通知（静音，不打扰）");
        // 关键：保持高重要性（全屏唤醒需要），但关掉声音和震动
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    public static void show(Context ctx, boolean success, String message) {
        show(ctx, success ? "查寝打卡成功 ✅" : "查寝打卡失败 ❌", message);
    }

    /** 已执行但结果未知（坐标盲点）用这个，避免误报"失败"。 */
    public static void showInfo(Context ctx, String message) {
        show(ctx, "查寝打卡：已执行，请确认", message);
    }

    /**
     * 到点唤醒：用「全屏意图通知」让系统替我们拉起界面。
     * 普通 startActivity 在后台会被安卓 10+/华为拦掉（"Abort background activity starts"），
     * 全屏意图是闹钟类应用的标准豁免通道。
     */
    public static void showWake(Context ctx) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent i = new Intent(ctx, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_AUTO, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 3003, i, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("查寝打卡开始")
                    .setContentText("正在打开学习通…")
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis());
            nm.notify(ID_WAKE, b.build());
        } catch (Throwable ignored) {
        }
    }

    public static void show(Context ctx, String title, String message) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, i, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(message == null ? "" : message)
                    .setStyle(new Notification.BigTextStyle().bigText(message == null ? "" : message))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis());

            nm.notify(ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
