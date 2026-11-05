package com.zzu.checkin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 查寝打卡的无障碍服务。
 *
 * 它是整个 App 的"手"：只能看见屏幕上有什么、点一下。
 * 不伪造数据、不改定位、不碰账号密码。
 *
 * 工作方式：每 800ms 看一次当前界面，按状态机推进
 *   首页（看到「定位打卡」）→ 点它
 *   打卡页（看到「打卡 / 立即打卡」）→ 点它
 *   看到「已完成 / 已签到」→ 成功
 *   超过 4 分钟还没结果 → 失败
 *
 * 关键防呆：
 *   - 标题「查寝定位打卡」里也含"打卡"两个字，所以按钮匹配必须"完全相等"，
 *     否则会去点标题，白白浪费一次机会；
 *   - 同一个按钮 6 秒内不重复点，避免连点把页面点乱；
 *   - 只有学习通在前台时才动作，别的 App 里绝不乱点。
 */
public class CheckinService extends AccessibilityService {

    private static final String TAG = "CheckinService";
    private static final String PKG_CHAOXING = "com.chaoxing.mobile";

    /** 首页入口的文字（完全相等匹配） */
    private static final String[] ENTRY_TEXTS = { "定位打卡" };

    /** 打卡按钮可能的文字（完全相等匹配）。今天已签到时页面没有按钮，所以只会匹配到按钮本身。 */
    private static final String[] BUTTON_TEXTS = { "打卡", "立即打卡", "签到", "立即签到", "确认打卡", "确定打卡" };

    /** 出现这些文字就算已经签上了 */
    private static final String[] DONE_TEXTS = { "已完成", "已签到", "签到成功", "打卡成功" };

    private static final long TICK_MS = 800;
    private static final long CLICK_COOLDOWN_MS = 6000;
    private static final long DEADLINE_MS = 4 * 60 * 1000;

    private static CheckinService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean tickScheduled = false;
    private boolean finished = true;
    private long startedAt = 0;
    private String lastAction = "";
    private String lastClickedText = "";
    private long lastClickAt = 0;
    private int clickCount = 0;
    private final java.util.Map<String, Integer> clickCounts = new java.util.HashMap<>();
    private long lastBackAt = 0;
    private int backTries = 0;
    private String result = "";

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 200;
            setServiceInfo(info);
        }
        Log.i(TAG, "无障碍服务已连接");
        CheckinStatus.setAccessibilityEnabled(true);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        CheckinStatus.setAccessibilityEnabled(false);
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        CheckinStatus.setAccessibilityEnabled(false);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 有界面变化时提前看一次；但用 tickScheduled 去重，
        // 否则页面里一直有动画/时钟时事件不断，会把 tick 无限往后推（饿死）。
        if (running && !tickScheduled) {
            tickScheduled = true;
            handler.removeCallbacks(tickRunnable);
            handler.postDelayed(tickRunnable, 250);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    // ------------------------------------------------------------------ 对外接口

    public static boolean isReady() {
        return instance != null;
    }

    /** 开始一轮打卡；已在进行中则直接返回。 */
    public static boolean start(Context context) {
        CheckinService svc = instance;
        if (svc == null) {
            CheckinStatus.setLastResult("失败：无障碍服务没在运行");
            return false;
        }
        if (svc.running) {
            return true;
        }
        svc.running = true;
        svc.finished = false;
        svc.tickScheduled = true;
        svc.startedAt = System.currentTimeMillis();
        svc.stageReset();
        svc.result = "";
        CheckinStatus.setRunning(true);
        CheckinStatus.setLastResult("开始执行…");
        CheckinStatus.appendLog("开始执行查寝打卡");
        svc.handler.removeCallbacks(svc.tickRunnable);
        svc.handler.postDelayed(svc.tickRunnable, 500);
        return true;
    }

    public static void stop() {
        CheckinService svc = instance;
        if (svc == null) {
            return;
        }
        svc.finish("已手动停止", false);
    }

    private void stageReset() {
        lastAction = "";
        lastClickedText = "";
        lastClickAt = 0;
        clickCount = 0;
        clickCounts.clear();
        lastBackAt = 0;
        backTries = 0;
    }

    // ------------------------------------------------------------------ 状态机

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private void tick() {
        tickScheduled = false;
        if (!running) {
            return;
        }
        if (System.currentTimeMillis() - startedAt > DEADLINE_MS) {
            finish("超时：4 分钟没等到结果", false);
            return;
        }

        // 只在学习通里动作
        if (!isChaoxingForeground()) {
            CheckinStatus.setLastResult("等待学习通到前台…");
            schedule();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            schedule();
            return;
        }

        try {
            List<String> texts = collectTexts(root, 0);

            // 1) 已经签上了
            String done = firstMatch(texts, DONE_TEXTS, false);
            if (done != null) {
                finish("成功：页面出现「" + done + "」", true);
                return;
            }

            // 2) 打卡页：点按钮
            AccessibilityNodeInfo btn = findExact(root, BUTTON_TEXTS);
            if (btn != null) {
                String label = safeText(btn);
                if (canClick(label)) {
                    boolean ok = clickNode(btn);
                    CheckinStatus.appendLog("点「" + label + "」 " + (ok ? "成功" : "失败"));
                    markClicked(label);
                    CheckinStatus.setLastResult(ok ? "已点击「" + label + "」，等待结果…" : "点击失败，重试中…");
                }
                recycle(btn);
                schedule();
                return;
            }

            // 3) 首页：点「定位打卡」
            AccessibilityNodeInfo entry = findExact(root, ENTRY_TEXTS);
            if (entry != null) {
                String label = safeText(entry);
                if (canClick(label)) {
                    boolean ok = clickNode(entry);
                    CheckinStatus.appendLog("点「" + label + "」 " + (ok ? "成功" : "失败"));
                    markClicked(label);
                    CheckinStatus.setLastResult(ok ? "已打开打卡页，等待加载…" : "打开失败，重试中…");
                }
                recycle(entry);
                schedule();
                return;
            }

            // 兜底：45 秒还没看到任何入口，按两次返回，回到首页再找
            long waited = System.currentTimeMillis() - startedAt;
            if (waited > 45000 && backTries < 2 && System.currentTimeMillis() - lastBackAt > 10000) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                lastBackAt = System.currentTimeMillis();
                backTries++;
                CheckinStatus.appendLog("没看到入口，按返回（第 " + backTries + " 次）");
                schedule();
                return;
            }

            CheckinStatus.setLastResult("页面加载中…（已等 "
                    + ((System.currentTimeMillis() - startedAt) / 1000) + " 秒）");
        } catch (Throwable t) {
            Log.w(TAG, "tick 出错", t);
        } finally {
            recycle(root);
        }
        schedule();
    }

    private void schedule() {
        if (!running) {
            return;
        }
        tickScheduled = true;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void finish(String message, boolean success) {
        if (finished) {
            return;
        }
        finished = true;
        running = false;
        tickScheduled = false;
        handler.removeCallbacks(tickRunnable);
        result = message;
        CheckinStatus.setRunning(false);
        CheckinStatus.setLastResult(message);
        CheckinStatus.appendLog(message);
        CheckinStatus.recordResult(success, message);
        // 失败一定要让人看见（静默失败是这套方案最大的风险）
        try {
            Notify.show(getApplicationContext(), success, message);
        } catch (Throwable ignored) {
        }
        if (success) {
            CheckinStatus.closeActivitySoon();
        }
    }

    // ------------------------------------------------------------------ 节点工具

    private boolean isChaoxingForeground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            CharSequence pkg = root.getPackageName();
            boolean ok = pkg != null && PKG_CHAOXING.contentEquals(pkg);
            recycle(root);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收集当前窗口所有可见文字（最多 400 条，够用且防卡死）。 */
    private List<String> collectTexts(AccessibilityNodeInfo node, int depth) {
        List<String> out = new ArrayList<>();
        try {
            walk(node, out, depth, 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void walk(AccessibilityNodeInfo node, List<String> out, int depth, int count) {
        if (node == null || depth > 25 || count > 400) {
            return;
        }
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            out.add(t.toString().trim());
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) {
            out.add(d.toString().trim());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                walk(child, out, depth + 1, count + i);
            } finally {
                recycle(child);
            }
        }
    }

    private String firstMatch(List<String> texts, String[] candidates, boolean exact) {
        for (String text : texts) {
            for (String c : candidates) {
                boolean hit = exact ? text.equals(c) : text.contains(c);
                if (hit) {
                    return c;
                }
            }
        }
        return null;
    }

    /** 按"文字完全相等"找节点，并向上找可点击的祖先。 */
    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String[] candidates) {
        for (String c : candidates) {
            List<AccessibilityNodeInfo> nodes;
            try {
                nodes = root.findAccessibilityNodeInfosByText(c);
            } catch (Throwable t) {
                continue;
            }
            if (nodes == null) {
                continue;
            }
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null) {
                    continue;
                }
                CharSequence t = n.getText();
                boolean exact = t != null && c.contentEquals(t.toString().trim());
                if (exact) {
                    AccessibilityNodeInfo clickable = clickableAncestor(n);
                    recycle(n);
                    if (clickable != null) {
                        return clickable;
                    }
                } else {
                    recycle(n);
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) {
                recycle(cur);
                return null;
            }
            recycle(cur);
            cur = parent;
        }
        recycle(cur);
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        try {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    private String safeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) {
            t = n.getContentDescription();
        }
        return t == null ? "" : t.toString().trim();
    }

    /** 冷却判断：同一下按钮 6 秒内不重复点；每个按钮最多点 4 次。 */
    private boolean canClick(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        Integer n = clickCounts.get(label);
        if (n != null && n >= 4) {
            return false;
        }
        boolean same = label.equals(lastClickedText);
        return !(same && System.currentTimeMillis() - lastClickAt < CLICK_COOLDOWN_MS);
    }

    private void markClicked(String label) {
        Integer n = clickCounts.get(label);
        clickCounts.put(label, n == null ? 1 : n + 1);
        lastClickedText = label;
        lastClickAt = System.currentTimeMillis();
        clickCount++;
    }

    private void recycle(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
        }
    }
}
