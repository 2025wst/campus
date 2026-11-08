package com.zzu.checkin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 定时器：每天在「打卡窗口」内安排多个闹钟（默认 21:00 / 21:05 / 21:15 / 21:30 / 22:00）。
 *
 * 为什么要多个：
 * 华为/鸿蒙的「应用启动管理」会把不常用的第三方 App 深度休眠，
 * 并把它登记的闹钟一起清掉。只排一个闹钟时，一旦被清就彻底哑火；
 * 排五个 + 一个 JobScheduler 巡检任务，只要有一个活下来就能打上。
 *
 * 每次触发都会先检查「今天是不是已经打过了」，打过就静默跳过。
 */
public final class CheckinAlarm {

    private static final String PREF = "checkin_cfg";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MIN = "minute";
    private static final int REQ_BASE = 1001;

    /** 每天的多个触发时刻：{时, 分}。第一个用"闹钟模式"，其余用精确闹钟。 */
    private static final int[][] TIMES = {
            {21, 0},
            {21, 5},
            {21, 15},
            {21, 30},
            {22, 0},
    };

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
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TIMES.length; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(String.format(Locale.CHINA, "%02d:%02d", TIMES[i][0], TIMES[i][1]));
        }
        return sb.toString();
    }

    /** 下一次触发时间：取所有时刻里最近的一个（今天过了就顺延到明天）。 */
    public static long nextTriggerAt(Context ctx) {
        long best = Long.MAX_VALUE;
        for (int[] t : TIMES) {
            long at = atFor(t[0], t[1]);
            if (at < best) {
                best = at;
            }
        }
        return best;
    }

    private static long atFor(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
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

    /** 重新安排全部闹钟（先全部取消，避免重复）。 */
    public static boolean schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return false;
        }
        for (int i = 0; i < TIMES.length; i++) {
            try {
                am.cancel(pending(ctx, i));
            } catch (Throwable ignored) {
            }
        }

        boolean any = false;
        long nearest = Long.MAX_VALUE;
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < TIMES.length; i++) {
            long at = atFor(TIMES[i][0], TIMES[i][1]);
            if (at < nearest) {
                nearest = at;
            }
            if (i > 0) {
                times.append(" / ");
            }
            times.append(String.format(Locale.CHINA, "%02d:%02d", TIMES[i][0], TIMES[i][1]));
            if (armAt(ctx, am, at, i)) {
                any = true;
            }
        }

        CheckinStatus.appendLog("已排 " + TIMES.length + " 个闹钟：" + times
                + "，最近一次 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(nearest)));
        return any;
    }

    /** 安排单个闹钟；第 0 个用闹钟模式，其余用精确闹钟。 */
    private static boolean armAt(Context ctx, AlarmManager am, long at, int slot) {
        PendingIntent pi = pending(ctx, slot);
        if (slot == 0) {
            try {
                Intent show = new Intent(ctx, MainActivity.class);
                show.putExtra(MainActivity.EXTRA_AUTO, true);
                show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int sflags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    sflags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent showPi = PendingIntent.getActivity(ctx, 2002, show, sflags);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
                return true;
            } catch (Throwable t) {
                CheckinStatus.appendLog("闹钟模式设置失败，退回精确闹钟：" + t.getMessage());
            }
        }
        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            return true;
        } catch (Throwable t) {
            CheckinStatus.appendLog("定时设置失败(" + slot + ")：" + t.getMessage());
            return false;
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int i = 0; i < TIMES.length; i++) {
            try {
                am.cancel(pending(ctx, i));
            } catch (Throwable ignored) {
            }
        }
    }

    /** App 启动时确保有安排（防止重装/重启后丢）。 */
    public static void ensureScheduled(Context ctx) {
        schedule(ctx);
        try {
            CheckinJob.schedule(ctx);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pending(Context ctx, int slot) {
        Intent i = new Intent(ctx, CheckinReceiver.class);
        i.setAction("com.zzu.checkin.TRIGGER");
        i.putExtra("slot", slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ_BASE + slot, i, flags);
    }

    /** 闹钟到点：先看今天打没打过，再决定要不要干活。 */
    public static class CheckinReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int slot = intent != null ? intent.getIntExtra("slot", 0) : 0;

            boolean doneToday;
            try {
                doneToday = CheckinStatus.isDoneToday(context);
            } catch (Throwable t) {
                doneToday = false;
            }
            if (doneToday) {
                CheckinStatus.appendLog("第 " + (slot + 1) + " 个闹钟到点：今天已完成，跳过");
                schedule(context);
                return;
            }
            if (CheckinStatus.isRunning()) {
                CheckinStatus.appendLog("第 " + (slot + 1) + " 个闹钟到点：上一轮还在跑，跳过");
                schedule(context);
                return;
            }

            CheckinStatus.appendLog("第 " + (slot + 1) + " 个闹钟到点，准备打卡");
            schedule(context);

            try {
                Notify.showWake(context);
            } catch (Throwable t) {
                CheckinStatus.appendLog("全屏通知失败：" + t.getMessage());
            }

            try {
                if (CheckinService.start(context)) {
                    CheckinStatus.appendLog("已交给无障碍服务执行（无需拉起界面）");
                    return;
                }
                CheckinStatus.appendLog("无障碍服务不可用，尝试拉起界面");
            } catch (Throwable t) {
                CheckinStatus.appendLog("启动服务失败：" + t.getMessage());
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
