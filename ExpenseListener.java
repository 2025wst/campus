package com.zzu.campus;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 通知监听：只做一件事——把微信/支付宝的通知原文转给 App 的 JS。
 *
 * 金额、商户、分类这些解析逻辑全在 JS 里（src/features/expense/auto.js），
 * 这样以后改规则只要换 index.html，不用重写 Java。
 *
 * App 不在前台时，通知先存进 SharedPreferences，等 App 回到前台再补记，
 * 所以「后台付款、晚点再打开 App」也不会丢。
 */
public class ExpenseListener extends NotificationListenerService {

    /** 目前只关心这两个包（微信、支付宝）。 */
    private static final String[] PKGS = { "com.tencent.mm", "com.eg.android.AlipayGphone" };

    private static final String PREFS = "campus_pending";
    private static final String KEY = "queue";
    private static final int MAX_QUEUE = 200;

    private static WeakReference<WebView> webViewRef = new WeakReference<>(null);

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            String pkg = sbn.getPackageName();
            if (!tracked(pkg)) return;

            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            Bundle ex = n.extras;

            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String body = (text + " " + big).replaceAll("\\s+", " ").trim();
            if (title.isEmpty() && body.isEmpty()) return;

            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("title", title);
            o.put("text", body);
            o.put("at", sbn.getPostTime());
            deliver(o.toString());
        } catch (Throwable ignored) {
            // 监听服务里任何异常都不能往外抛
        }
    }

    private static boolean tracked(String pkg) {
        for (String p : PKGS) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /* ---------------- 与页面之间的通道 ---------------- */

    /** Activity 回到前台时挂上 WebView，并补发排队中的通知。 */
    public static void attach(WebView wv) {
        webViewRef = new WeakReference<>(wv);
        if (wv == null) return;
        Context ctx = wv.getContext();
        for (String item : drain(ctx)) {
            eval(wv, item);
        }
    }

    public static void detach() {
        webViewRef = new WeakReference<>(null);
    }

    private static void deliver(String json) {
        WebView wv = webViewRef.get();
        if (wv == null) {
            enqueue(wv == null ? null : wv.getContext(), json);
            return;
        }
        eval(wv, json);
    }

    private static void eval(WebView wv, String json) {
        final String js = "window.__campusExpense && window.__campusExpense(" + JSONObject.quote(json) + ")";
        wv.post(() -> wv.evaluateJavascript(js, null));
    }

    /* ---------------- 离线排队（SharedPreferences 里的 JSON 数组） ---------------- */

    private static void enqueue(Context ctx, String json) {
        if (ctx == null) {
            // 没有 Context 时用应用级 Context
            ctx = getAppContext();
            if (ctx == null) return;
        }
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            arr.put(json);
            while (arr.length() > MAX_QUEUE) arr.remove(0);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String[] drain(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.getString(i);
            sp.edit().putString(KEY, "[]").apply();
            return out;
        } catch (Throwable e) {
            return new String[0];
        }
    }

    private static Context appCtx;

    public static void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    private static Context getAppContext() {
        return appCtx;
    }
}
