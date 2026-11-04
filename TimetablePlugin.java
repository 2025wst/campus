package com.zzu.campus;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 教务平台课表抓取窗口。
 *
 * 流程：
 *   1. App 里调 TimetableImport.open({url}) → 打开 ImportActivity（我们自己的 WebView）；
 *   2. 页面加载完成后注入 inject.js（优先从站点拉最新版，失败用内置兜底）；
 *   3. inject.js 在右下角放一个绿色按钮，点了把课程原始行交给 CampusBridge；
 *   4. 原生带着 JSON 回到 MainActivity，由 window.__campusImport 解析入库。
 *
 * 账号密码只在学校页面里输入，我们不碰，也不需要复制粘贴。
 */
@CapacitorPlugin(name = "TimetableImport")
public class TimetablePlugin extends Plugin {

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url", "https://jwxt.zzu.edu.cn/manager/secondary-portal");
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("没有可用的 Activity");
            return;
        }
        Intent intent = new Intent(activity, ImportActivity.class);
        intent.putExtra("url", url);
        activity.startActivity(intent);
        call.resolve();
    }

    /* ---------------- 自动记账：通知监听相关 ---------------- */

    @PluginMethod
    public void notificationStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", notificationsEnabled());
        call.resolve(ret);
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("打不开通知设置：" + e.getMessage());
        }
    }

    private boolean notificationsEnabled() {
        try {
            String flat = android.provider.Settings.Secure.getString(
                getContext().getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getContext().getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static class ImportActivity extends Activity {

        /** 远程抓取脚本（改了不用重新打包）。 */
        private static final String INJECT_URL = "https://2025wst.github.io/campus/inject.js";

        /** 内置兜底脚本：只做最朴素的一件事，保证按钮一定出现。 */
        private static final String FALLBACK_JS =
            "(function(){if(window.__campusBtn)return;window.__campusBtn=true;" +
            "var b=document.createElement('div');b.textContent='导入到校园通';" +
            "b.style.cssText='position:fixed;right:16px;bottom:110px;z-index:2147483647;" +
            "background:#22c55e;color:#fff;font-size:16px;font-weight:700;padding:14px 20px;" +
            "border-radius:999px;box-shadow:0 6px 20px rgba(0,0,0,.3);font-family:sans-serif;';" +
            "b.onclick=function(){b.textContent='读取中…';" +
            "setTimeout(function(){var rows=[];" +
            "document.querySelectorAll('td.td-content').forEach(function(td){" +
            "var m=/td-content\\s+(\\d+)/.exec(td.className||'');if(!m)return;" +
            "var day=parseInt(m[1],10);" +
            "td.querySelectorAll('div.tdHtml').forEach(function(blk){" +
            "var n=blk.querySelector('.course-name');if(!n)return;" +
            "var info='';for(var i=0;i<blk.children.length;i++){var el=blk.children[i];" +
            "if((el.textContent||'').indexOf('周')>=0&&!el.classList.contains('course-name')" +
            "&&!el.classList.contains('lesson-name')){info=el.textContent;break;}}" +
            "if(!info)info=blk.textContent||'';" +
            "if(info.indexOf('周')<0)return;" +
            "rows.push({name:n.textContent.trim(),info:info.replace(/\\u00a0/g,' '),day:day});});});" +
            "if(!rows.length){b.textContent='没找到课表，请到「我的课表」再点';return;}" +
            "b.textContent='找到 '+rows.length+' 门，导入中…';" +
            "try{CampusBridge.emit(JSON.stringify(rows));}catch(e){b.textContent='发送失败：'+e.message;}},30);};" +
            "document.body.appendChild(b);})();";

        private static volatile String cachedJs = null;

        private WebView webView;

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            FrameLayout root = new FrameLayout(this);
            webView = new WebView(this);
            root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);

            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
            s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

            webView.addJavascriptInterface(new Bridge(), "CampusBridge");

            // 让页面里的 alert/confirm 能正常显示（否则会被静默丢弃）
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                    new AlertDialog.Builder(ImportActivity.this)
                        .setMessage(message)
                        .setPositiveButton("好", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                result.cancel();
                            }
                        })
                        .show();
                    return true;
                }

                @Override
                public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                    new AlertDialog.Builder(ImportActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        })
                        .show();
                    return true;
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (url != null && url.contains("zzu.edu.cn")) {
                        inject(view);
                    }
                }
            });

            String url = getIntent().getStringExtra("url");
            if (url == null) url = "https://jwxt.zzu.edu.cn/manager/secondary-portal";
            webView.loadUrl(url);
        }

        /** 优先用站点上的最新脚本，拿不到就用内置兜底。 */
        private void inject(final WebView view) {
            final String cached = cachedJs;
            if (cached != null) {
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        view.evaluateJavascript(cached, null);
                    }
                });
                return;
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    String js = fetchRemote();
                    final String use = (js != null && js.length() > 200) ? js : FALLBACK_JS;
                    if (js != null && js.length() > 200) cachedJs = js;
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.evaluateJavascript(use, null);
                        }
                    });
                }
            }).start();
        }

        private String fetchRemote() {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(INJECT_URL).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != 200) return null;
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return bos.toString("UTF-8");
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        /** 页面 JS 通过这里把数据交回来。 */
        private class Bridge {
            @JavascriptInterface
            public void emit(final String json) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent back = new Intent(ImportActivity.this, MainActivity.class);
                        back.putExtra("campusImport", json);
                        back.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(back);
                        finish();
                    }
                });
            }
        }
    }
}
