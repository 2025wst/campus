package com.zzu.checkin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局状态：无障碍是否可用、上一次结果、历史记录、运行日志。
 *
 * 为什么单独一个类：UI 和 服务 需要共享状态，但服务和界面随时可能被销毁，
 * 所以状态放静态字段 + SharedPreferences，界面只做读取和展示。
 */
public final class CheckinStatus {

    private static final String PREF = "checkin";
    private static final String KEY_LOG = "log";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_LAST = "last_result";
    private static final String KEY_ENABLED = "accessibility_enabled";
    private static final int MAX_LOG = 120;
    private static final int MAX_HISTORY = 30;

    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private static volatile boolean accessibilityEnabled = false;
    private static volatile boolean running = false;
    private static volatile String lastResult = "";
    private static volatile String currentStage = "";
    private static volatile long runStartedAt = 0;
    private static volatile boolean runFinished = true;

    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private CheckinStatus() {
    }

    /* ---------------- 基础状态 ---------------- */

    public static boolean isAccessibilityEnabled() {
        return accessibilityEnabled;
    }

    public static void setAccessibilityEnabled(boolean v) {
        accessibilityEnabled = v;
        MainActivity a = activityRef.get();
        if (a != null) {
            a.runOnUiThread(a::refreshUi);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    /* ---------------- 一轮执行的上下文（服务被重连后要靠它续跑） ---------------- */

    public static void markRunStart() {
        runStartedAt = System.currentTimeMillis();
        runFinished = false;
    }

    public static void markRunEnd() {
        runFinished = true;
    }

    /** 有一轮已经开始但还没结束：服务被解绑重连时需要续跑。 */
    public static boolean isRunInFlight() {
        return !runFinished;
    }

    public static long getRunStartedAt() {
        return runStartedAt == 0 ? System.currentTimeMillis() : runStartedAt;
    }

    public static void setRunning(boolean v) {
        running = v;
        notifyUi();
    }

    public static String getLastResult() {
        return lastResult;
    }

    public static void setLastResult(String s) {
        String v = s == null ? "" : s;
        if (v.equals(lastResult)) {
            return;
        }
        lastResult = v;
        // 落盘一份：排障时可以直接从文件读，不用把 App 切到前台（切前台会改变被检测的目标）
        Context ctx = App.get();
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("cur", v).apply();
            } catch (Throwable ignored) {
            }
        }
        notifyUi();
    }

    public static String getCurrentStage() {
        return currentStage;
    }

    public static void setCurrentStage(String s) {
        currentStage = s == null ? "" : s;
        notifyUi();
    }

    /* ---------------- 界面绑定 ---------------- */

    public static void bindActivity(MainActivity a) {
        activityRef = new WeakReference<>(a);
    }

    public static void unbindActivity() {
        activityRef = new WeakReference<>(null);
    }

    private static void notifyUi() {
        MainActivity a = activityRef.get();
        if (a == null) {
            return;
        }
        UI.post(a::refreshUi);
    }

    /** 成功后延迟关闭界面，让屏幕可以熄灭。 */
    public static void closeActivitySoon() {
        UI.postDelayed(() -> {
            MainActivity a = activityRef.get();
            if (a != null) {
                a.finish();
            }
        }, 4000);
    }

    /* ---------------- 日志与历史 ---------------- */

    public static void appendLog(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        Context ctx = App.get();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_LOG, "[]"));
            JSONObject o = new JSONObject();
            o.put("t", TS.format(new Date()));
            o.put("m", line);
            arr.put(o);
            while (arr.length() > MAX_LOG) {
                arr.remove(0);
            }
            sp.edit().putString(KEY_LOG, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
        notifyUi();
    }

    public static List<String> readLog() {
        List<String> out = new ArrayList<>();
        Context ctx = App.get();
        if (ctx == null) {
            return out;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_LOG, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                out.add(o.optString("t") + "  " + o.optString("m"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 记录一次执行结果（历史里保留最近 30 条）。 */
    public static void recordResult(boolean success, String message) {
        Context ctx = App.get();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            JSONObject o = new JSONObject();
            o.put("at", TS.format(new Date()));
            o.put("ok", success);
            o.put("msg", message == null ? "" : message);
            arr.put(o);
            while (arr.length() > MAX_HISTORY) {
                arr.remove(0);
            }
            sp.edit().putString(KEY_HISTORY, arr.toString())
                    .putString(KEY_LAST, TS.format(new Date()) + "  " + message)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    public static List<String> readHistory() {
        List<String> out = new ArrayList<>();
        Context ctx = App.get();
        if (ctx == null) {
            return out;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_HISTORY, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                out.add((o.optBoolean("ok") ? "✅ " : "❌ ") + o.optString("at") + "  " + o.optString("msg"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String getLastRecord() {
        Context ctx = App.get();
        if (ctx == null) {
            return "";
        }
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST, "");
    }

    public static void clearLog() {
        Context ctx = App.get();
        if (ctx == null) {
            return;
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_LOG, "[]").apply();
        notifyUi();
    }
}
