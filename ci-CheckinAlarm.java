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
            // 用 setAlarmClock：系统会把它当"闹钟应用"对待，
            // 触发时允许拉起界面（普通 setExact 会被安卓 10+ 的
            // "后台禁止启动 Activity" 拦掉，华为还会额外拦截一层）。
            Intent show = new Intent(ctx, MainActivity.class);
            show.putExtra(MainActivity.EXTRA_AUTO, true);
            show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sflags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent showPi = PendingIntent.getActivity(ctx, 2002, show, sflags);
            AlarmManager.AlarmClockInfo clock =
                    new AlarmManager.AlarmClockInfo(at, showPi);
            am.setAlarmClock(clock, pi);
            CheckinStatus.appendLog("下次定时（闹钟模式）：" + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new java.util.Date(at)));
            return true;
        } catch (Throwable t) {
            CheckinStatus.appendLog("闹钟模式设置失败，退回精确闹钟：" + t.getMessage());
        }

        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            CheckinStatus.appendLog("下次定时（精确模式）：" + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
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
            // 先发全屏意图通知：系统会替我们拉起界面（后台直接 startActivity 会被拦）
            try {
                Notify.showWake(context);
            } catch (Throwable t) {
                CheckinStatus.appendLog("全屏通知失败：" + t.getMessage());
            }
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
