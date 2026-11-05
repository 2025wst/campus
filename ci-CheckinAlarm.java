package com.zzu.checkin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

/**
 * 定时器：每天固定时间（默认 21:05）触发一次。
 *
 * 用 setExactAndAllowWhileIdle：Doze 省电模式下也能准时（需要精确闹钟权限）。
 * 触发后由 CheckinReceiver 拉起界面 → 点亮屏幕 → 交给 CheckinService 干活。
 */
public final class CheckinAlarm {

    private static final String PREF = "checkin_cfg";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MIN = "minute";
    private static final int REQ = 1001;

    private CheckinAlarm() {
    }

    public static int getHour(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_HOUR, 21);
    }

    public static int getMinute(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MIN, 5);
    }

    public static void setTime(Context ctx, int hour, int minute) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MIN, minute)
                .apply();
        schedule(ctx);
    }

    public static String describe(Context ctx) {
        return String.format(Locale.CHINA, "%02d:%02d", getHour(ctx), getMinute(ctx));
    }

    /** 计算下一次触发时间（毫秒时间戳）。 */
    public static long nextTriggerAt(Context ctx) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, getHour(ctx));
        c.set(Calendar.MINUTE, getMinute(ctx));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long at = c.getTimeInMillis();
        if (at <= System.currentTimeMillis()) {
            at += 24L * 60 * 60 * 1000;
        }
        return at;
    }

    public static boolean canExact(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }

    /** 安排下一次；失败也返回 false，让界面提示用户。 */
    public static boolean schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return false;
        }
        PendingIntent pi = pending(ctx);
        am.cancel(pi);

        long at = nextTriggerAt(ctx);
        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                // 没有精确闹钟权限时退化为不精确（可能晚几十分钟）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            CheckinStatus.appendLog("下次定时：" + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new java.util.Date(at)));
            return true;
        } catch (Throwable t) {
            CheckinStatus.appendLog("定时设置失败：" + t.getMessage());
            return false;
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pending(ctx));
        }
    }

    /** App 启动时确保有安排（防止重装/重启后丢）。 */
    public static void ensureScheduled(Context ctx) {
        schedule(ctx);
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, CheckinReceiver.class);
        i.setAction("com.zzu.checkin.TRIGGER");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 闹钟到点：拉起界面（界面负责点亮屏幕），并安排明天。 */
    public static class CheckinReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            CheckinStatus.appendLog("定时到点，准备打卡");
            schedule(context);
            try {
                Intent ui = new Intent(context, MainActivity.class);
                ui.putExtra(MainActivity.EXTRA_AUTO, true);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(ui);
            } catch (Throwable t) {
                CheckinStatus.appendLog("拉起界面失败：" + t.getMessage());
            }
        }
    }
}
