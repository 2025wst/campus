package com.zzu.campus;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

/**
 * 主 Activity：注册抓取插件、初始化通知监听、并在从「导入课表」窗口返回时
 * 把抓到的原始行交给页面里的 window.__campusImport 处理。
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TimetablePlugin.class);
        super.onCreate(savedInstanceState);
        deliver(getIntent());
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到前台：挂上 WebView，补发排队中的支付通知
        if (getBridge() != null) {
            ExpenseListener.attach(getBridge().getWebView());
        }
    }

    @Override
    public void onPause() {
        ExpenseListener.detach();
        super.onPause();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        deliver(intent);
    }

    private void deliver(Intent intent) {
        if (intent == null) return;
        final String json = intent.getStringExtra("campusImport");
        if (json == null) return;
        intent.removeExtra("campusImport");
        if (getBridge() == null || getBridge().getWebView() == null) return;
        final String js = "window.__campusImport && window.__campusImport(" + JSONObject.quote(json) + ")";
        getBridge().getWebView().post(new Runnable() {
            @Override
            public void run() {
                getBridge().getWebView().evaluateJavascript(js, null);
            }
        });
    }
}
