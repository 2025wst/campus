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
    private boolean finished = true;
    private long startedAt = 0;
    private String lastAction = "";
    private String lastClickedText = "";
    private long lastClickAt = 0;
    private int clickCount = 0;
    private int tickCount = 0;
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

        // 华为在应用退后台时会解绑本服务；重连后要把没跑完的那一轮接着跑完，
        // 否则表现就是"卡在已等 N 秒"。
        if (CheckinStatus.isRunInFlight()) {
            running = true;
            finished = false;
            startedAt = CheckinStatus.getRunStartedAt();
            stageReset();
            handler.removeCallbacks(loop);
            handler.post(loop);
            CheckinStatus.appendLog("服务重连，继续未完成的打卡");
        }
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
        // 不需要事件驱动：主循环自己按固定节奏跑。
        // 之前用"事件来了就重置定时"的写法，一旦页面持续有动画就会把 tick 无限推迟；
        // 更糟的是华为在应用退后台时会解绑服务，重连后定时器全丢。
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
        svc.startedAt = System.currentTimeMillis();
        svc.stageReset();
        svc.result = "";
        CheckinStatus.markRunStart();
        CheckinStatus.setRunning(true);
        CheckinStatus.setLastResult("开始执行…");
        CheckinStatus.appendLog("开始执行查寝打卡");
        svc.handler.removeCallbacks(svc.loop);
        svc.handler.postDelayed(svc.loop, 500);
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
        tickCount = 0;
    }

    // ------------------------------------------------------------------ 状态机

    /**
     * 自循环：不依赖无障碍事件，也不依赖外部调度。
     * 每轮 tick 结束自己排下一轮，只要 running 还在。
     * （旧写法用"事件驱动 + removeCallbacks"，华为解绑重连时定时器会一起丢，
     *   表现为界面永远停在"已等 N 秒"。）
     */
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "tick 异常", t);
            }
            if (running) {
                handler.removeCallbacks(this);
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private void tick() {
        if (!running) {
            return;
        }
        tickCount++;
        long waited = System.currentTimeMillis() - startedAt;
        if (waited > DEADLINE_MS) {
            finish("超时：4 分钟没等到结果", false);
            return;
        }

        // 只在学习通里动作。顺手把"当前前台是谁"记下来，
        // 否则出问题时日志是空的，根本不知道卡在哪一步。
        AccessibilityNodeInfo probe = getRootInActiveWindow();
        String pkgName = null;
        if (probe != null) {
            CharSequence p = probe.getPackageName();
            pkgName = p == null ? null : p.toString();
            recycle(probe);
        }
        if (!PKG_CHAOXING.contentEquals(pkgName)) {
            CheckinStatus.setLastResult("等待学习通到前台…（当前 " + pkgName + "）");
            if (tickCount % 15 == 0) {
                CheckinStatus.appendLog("第 " + tickCount + " 次检查：前台是 " + pkgName
                        + "，已等 " + (waited / 1000) + " 秒");
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (tickCount % 15 == 0) {
                CheckinStatus.appendLog("第 " + tickCount + " 次检查：拿不到界面内容");
            }
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

            CheckinStatus.setLastResult("页面加载中…（已等 " + (waited / 1000) + " 秒）");
            // 每 15 次（约 12 秒）记一次现场：看到多少条文字、有没有「定位」相关的
            if (tickCount % 15 == 0) {
                StringBuilder sb = new StringBuilder();
                for (String t : texts) {
                    if (t.contains("打卡") || t.contains("签到") || t.contains("定位")) {
                        sb.append("[").append(t).append("]");
                        if (sb.length() > 120) {
                            break;
                        }
                    }
                }
                CheckinStatus.appendLog("第 " + tickCount + " 次：共 " + texts.size()
                        + " 条文字，相关=" + (sb.length() == 0 ? "无" : sb.toString()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "tick 出错", t);
        } finally {
            recycle(root);
        }
        schedule();
    }

    /** 旧调用点保留：循环由 loop 自己驱动，这里不需要做事。 */
    private void schedule() {
    }

    private void finish(String message, boolean success) {
        if (finished) {
            return;
        }
        finished = true;
        running = false;
        handler.removeCallbacks(loop);
        result = message;
        CheckinStatus.setRunning(false);
        CheckinStatus.markRunEnd();
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
