package com.zzu.checkin;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 唯一的界面：看状态、开权限、改时间、手动测试。
 *
 * 定时到点时会带着 EXTRA_AUTO=true 被拉起，此时：
 *   点亮屏幕 → 解开锁屏（你没设密码）→ 打开学习通 → 交给无障碍服务去点。
 */
public class MainActivity extends Activity {

    public static final String EXTRA_AUTO = "auto";

    private static final int REQ_ACCESSIBILITY = 10;
    private static final int REQ_OVERLAY = 11;
    private static final int REQ_EXACT = 12;
    private static final int REQ_WRITE_SETTINGS = 13;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvAccessibility;
    private TextView tvAlarm;
    private TextView tvLast;
    private TextView tvStage;
    private TextView tvLog;
    private Button btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CheckinStatus.bindActivity(this);
        setContentView(buildUi());
        refreshUi();
        requestNotificationPermission();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO, false)) {
            getIntent().removeExtra(EXTRA_AUTO);
            ui.postDelayed(this::startAutoFlow, 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CheckinStatus.bindActivity(this);
        refreshUi();
        ui.postDelayed(refreshRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        CheckinStatus.unbindActivity();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("查寝打卡助手");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#16181D"));
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("每晚到点自动打开学习通完成定位打卡。只需授权一次。");
        tip.setTextSize(13);
        tip.setTextColor(Color.parseColor("#6B7280"));
        tip.setPadding(0, pad / 4, 0, pad);
        root.addView(tip);

        tvAccessibility = addRow(root, "无障碍服务", "检测中…");
        tvAlarm = addRow(root, "定时", "");
        tvLast = addRow(root, "上次结果", "");
        tvStage = addRow(root, "正在进行", "");

        addButton(root, "① 开启无障碍服务", v -> openAccessibility());
        addButton(root, "② 允许显示在其他应用上层", v -> openOverlay());
        addButton(root, "③ 允许精确闹钟", v -> openExactAlarm());
        addButton(root, "④ 关闭电池优化（防止被杀）", v -> openBattery());
        addButton(root, "设置打卡时间", v -> pickTime());

        btnTest = addButton(root, "立即测试一次", v -> startAutoFlow());
        addButton(root, "清除日志", v -> {
            CheckinStatus.clearLog();
            refreshUi();
        });

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(Color.parseColor("#4B5563"));
        tvLog.setPadding(0, pad, 0, 0);
        root.addView(tvLog);

        return scroll;
    }

    private TextView addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(Color.parseColor("#6B7280"));
        l.setWidth((int) (110 * getResources().getDisplayMetrics().density));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(Color.parseColor("#16181D"));
        v.setGravity(Gravity.START);
        row.addView(v);

        parent.addView(row);
        return v;
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * getResources().getDisplayMetrics().density));
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    // ------------------------------------------------------------------ 状态刷新

    public void refreshUi() {
        boolean acc = isAccessibilityEnabled();
        if (tvAccessibility != null) {
            tvAccessibility.setText(acc ? "✅ 已开启" : "❌ 未开启（点下面第 ① 个按钮）");
            tvAccessibility.setTextColor(acc ? Color.parseColor("#2F9E6E") : Color.parseColor("#D64545"));
        }
        if (tvAlarm != null) {
            boolean exact = CheckinAlarm.canExact(this);
            long at = CheckinAlarm.nextTriggerAt(this);
            String t = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(at));
            tvAlarm.setText(CheckinAlarm.describe(this) + "（下次 " + t + "）"
                    + (exact ? "" : " ⚠️未允许精确闹钟"));
        }
        if (tvLast != null) {
            String last = CheckinStatus.getLastResult();
            if (last == null || last.isEmpty()) {
                last = CheckinStatus.getLastRecord();
            }
            tvLast.setText(last == null || last.isEmpty() ? "还没有执行过" : last);
        }
        if (tvStage != null) {
            tvStage.setText(CheckinStatus.isRunning() ? "执行中…" : "空闲");
        }
        if (btnTest != null) {
            btnTest.setEnabled(!CheckinStatus.isRunning());
        }
        if (tvLog != null) {
            List<String> logs = CheckinStatus.readLog();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(30, logs.size()); i++) {
                sb.append(logs.get(i)).append('\n');
            }
            tvLog.setText(sb.length() == 0 ? "（暂无日志）" : sb.toString());
        }
    }

    private boolean isAccessibilityEnabled() {
        if (CheckinService.isReady()) {
            return true;
        }
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 执行流程

    /** 点亮屏幕 → 解锁 → 打开学习通 → 交给无障碍服务。 */
    private void startAutoFlow() {
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务（第 ① 个按钮）");
            return;
        }
        if (!CheckinService.isReady()) {
            toast("无障碍服务还没连上，稍等 2 秒再试");
            ui.postDelayed(this::startAutoFlow, 2000);
            return;
        }

        // 唤醒 + 解锁（你没设密码，这一步会直接成功）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
            }
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (Throwable t) {
            CheckinStatus.appendLog("解锁失败：" + t.getMessage());
        }

        // 打开学习通
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.chaoxing.mobile");
            if (launch == null) {
                toast("没装学习通？");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            CheckinStatus.appendLog("已打开学习通");
        } catch (Throwable t) {
            CheckinStatus.appendLog("打开学习通失败：" + t.getMessage());
            toast("打开学习通失败");
            return;
        }

        // 等服务开始点
        ui.postDelayed(() -> CheckinService.start(this), 1500);
        toast("已开始，别锁屏");
    }

    // ------------------------------------------------------------------ 权限入口

    private void openAccessibility() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityForResult(i, REQ_ACCESSIBILITY);
        } catch (Throwable t) {
            toast("打不开无障碍设置：" + t.getMessage());
        }
    }

    private void openOverlay() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } catch (Throwable t) {
            toast("打不开悬浮窗设置");
        }
    }

    private void openExactAlarm() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_EXACT);
            } else {
                toast("这个系统版本不需要单独授权");
            }
        } catch (Throwable t) {
            toast("打不开精确闹钟设置");
        }
    }

    private void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private void pickTime() {
        // 用系统自带的简单输入：弹出时间对话框
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(this,
                (view, hour, minute) -> {
                    CheckinAlarm.setTime(this, hour, minute);
                    toast("已设为 " + String.format(Locale.CHINA, "%02d:%02d", hour, minute));
                    refreshUi();
                },
                CheckinAlarm.getHour(this), CheckinAlarm.getMinute(this), true);
        dlg.show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        CheckinStatus.appendLog(s);
    }

    /** Android 13+ 要单独申请通知权限，否则失败通知看不到。 */
    private void requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 20);
            }
        } catch (Throwable ignored) {
        }
    }
}
