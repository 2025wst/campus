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
 * 通知监听：把微信/支付宝的通知原文交给 App 的 JS 解析。
 *
 * 可靠性设计（都是踩过的坑）：
 * 1. 每条通知先落进 SharedPreferences 队列，再尝试实时推送 —— App 被杀也不丢；
 * 2. 队列由 JS 主动拉取（pendingExpenses）并确认清空（clearExpenses），
 *    避免"页面还没加载完就推送"导致丢事件；
 * 3. 去重靠通知的 key，由 JS 完成（同一笔重复推送会命中同一个 key）。
 */
public class ExpenseListener extends NotificationListenerService {

    /** 目前只关心这两个包（微信、支付宝）。 */
    private static final String[] PKGS = {
            "com.tencent.mm",              // 微信（含微信支付）
            "com.eg.android.AlipayGphone", // 支付宝
            "com.unionpay",                // 云闪付
            "com.sankuai.meituan",         // 美团
            "me.ele",                      // 饿了么
    };

    private static final String PREFS = "campus_pending";
    private static final String KEY = "queue";
    private static final int MAX_QUEUE = 300;

    private static WeakReference<WebView> webViewRef = new WeakReference<>(null);

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    /**
     * 服务重新连上时，把通知栏里"还挂着"的通知补抓一遍。
     * 这样即使 App 之前被系统杀掉、错过了推送，只要通知还没被用户划掉，就能补回来。
     */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            StatusBarNotification[] actives = getActiveNotifications();
            if (actives == null) return;
            for (StatusBarNotification s : actives) {
                handle(s);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handle(StatusBarNotification sbn) {
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
            o.put("key", sbn.getKey());
            String json = o.toString();

            // 先入队（保证不丢），再尝试实时推给页面
            enqueue(getApplicationContext(), json);
            WebView wv = webViewRef.get();
            if (wv != null) eval(wv, json);
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

    public static void attach(WebView wv) {
        webViewRef = new WeakReference<>(wv);
    }

    /** 页面进后台时不再推（队列照常收），回来再拉取。 */
    public static void detach() {
        webViewRef = new WeakReference<>(null);
    }

    private static void eval(WebView wv, String json) {
        try {
            final String js = "window.__campusExpense && window.__campusExpense(" + JSONObject.quote(json) + ")";
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 队列（JS 主动拉取） ---------------- */

    public static String[] pending(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.getString(i);
            return out;
        } catch (Throwable e) {
            return new String[0];
        }
    }

    public static void clear(Context ctx, java.util.Set<String> handledKeys) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (handledKeys == null || handledKeys.isEmpty()) {
                sp.edit().putString(KEY, "[]").apply();
                return;
            }
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                String item = arr.getString(i);
                String key = "";
                try {
                    key = new JSONObject(item).optString("key", "");
                } catch (Throwable ignored) {
                }
                if (!handledKeys.contains(key)) keep.put(item);
            }
            sp.edit().putString(KEY, keep.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static void enqueue(Context ctx, String json) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            arr.put(json);
            while (arr.length() > MAX_QUEUE) arr.remove(0);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
