package com.zzu.checkin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.util.DisplayMetrics;
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

    /** 打卡按钮可能的文字（完全相等匹配） */
    private static final String[] BUTTON_TEXTS = {
            "打卡", "立即打卡", "签到", "立即签到", "确认打卡", "确定打卡",
            "点击打卡", "我要打卡", "去打卡", "开始打卡", "现在打卡"
    };

    /** 出现这些文字说明当前不在打卡时间，没必要死等 */
    private static final String[] OUT_OF_WINDOW_TEXTS = { "未到查寝时间", "不在打卡时间", "未到打卡时间", "打卡时间已结束" };

    /** 出现这些文字就算已经签上了 */
    private static final String[] DONE_TEXTS = { "已完成", "已签到", "签到成功", "打卡成功" };

    private static final long TICK_MS = 800;
    private static final long CLICK_COOLDOWN_MS = 6000;
    private static final long DEADLINE_MS = 4 * 60 * 1000;
    /** 多个候选坐标之间的间隔 */
    private static final long TAP_GAP_MS = 2500;

    /** 打卡页左上角「返回」箭头的相对坐标（用于强制刷新页面） */
    private static final float BACK_X = 0.02f;
    private static final float BACK_Y = 0.065f;

    private static CheckinService instance;

    private Thread worker;
    private volatile int workerGeneration = 0;
    private volatile boolean running = false;
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
    private int tapIndex = 0;
    private long lastTapAt = 0;
    private boolean refreshed = false;
    private int bringStep = 0;
    private long lastBringAt = 0;
    private String result = "";

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // 故意不调用 setServiceInfo()：
        // 在 onServiceConnected 里改服务配置会让框架重连自己，
        // 连接一直处于"握手未完成"，随后 getRootInActiveWindow() 会永久阻塞。
        // 所有配置都写在 res/xml/accessibility_service_config.xml 里，够用了。
        Log.i(TAG, "无障碍服务已连接");
        CheckinStatus.appendLog("无障碍服务已连接");
        CheckinStatus.setAccessibilityEnabled(true);

        // 华为在应用退后台时会解绑本服务；重连后要把没跑完的那一轮接着跑完，
        // 否则表现就是"卡在已等 N 秒"。
        if (CheckinStatus.isRunInFlight()) {
            running = true;
            finished = false;
            startedAt = CheckinStatus.getRunStartedAt();
            stageReset();
            startWorker();
            CheckinStatus.appendLog("服务重连，继续未完成的打卡");
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CheckinStatus.appendLog("无障碍服务被解绑");
        instance = null;
        CheckinStatus.setAccessibilityEnabled(false);
       return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        CheckinStatus.appendLog("无障碍服务销毁");
        instance = null;
        CheckinStatus.setAccessibilityEnabled(false);
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
        svc.startWorker();
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
        tapIndex = 0;
        lastTapAt = 0;
        refreshed = false;
        bringStep = 0;
        lastBringAt = 0;
    }

    // ------------------------------------------------------------------ 状态机

    /**
     * 独立工作线程：不用 Handler/Looper。
     * Handler 方案在华为上不可靠（服务被解绑重建时 post 的回调会丢），
     * 线程只依赖一个 volatile 布尔量，简单可预测。
     */
    private void startWorker() {
        if (worker != null && worker.isAlive()) {
            CheckinStatus.appendLog("工作线程已在运行，不重复启动");
            return;
        }
        final int runId = ++workerGeneration;
        worker = new Thread(() -> {
            CheckinStatus.appendLog("工作线程启动 #" + runId);
            long lastBeat = 0;
            while (running && workerGeneration == runId) {
                // 心跳：每 20 秒记一次 tickCount。
                // 如果心跳在涨、tickCount 不涨，说明卡在 tick() 内部的系统调用上。
                long now = System.currentTimeMillis();
                if (now - lastBeat > 20000) {
                    lastBeat = now;
                    CheckinStatus.appendLog("心跳 #" + runId + "：tickCount=" + tickCount);
                }
                try {
                    tick();
                } catch (Throwable t) {
                    Log.w(TAG, "tick 异常", t);
                    CheckinStatus.appendLog("tick 异常：" + t.getClass().getSimpleName());
                }
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            CheckinStatus.appendLog("工作线程退出 #" + runId);
        }, "checkin-worker-" + runId);
        worker.setDaemon(true);
        worker.start();
    }

    private void tick() {
        if (!running) {
            return;
        }
        tickCount++;
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > DEADLINE_MS) {
            finish("超时：4 分钟没等到结果", false);
            return;
        }

        // 只在学习通里动作。顺手把"当前前台是谁"记下来，
        // 否则出问题时日志是空的，根本不知道卡在哪一步。
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkgName = null;
        if (root != null) {
            CharSequence p = root.getPackageName();
            pkgName = p == null ? null : p.toString();
        }
        if (!PKG_CHAOXING.contentEquals(pkgName)) {
            bringChaoxingToFront(root, elapsed, pkgName);
            recycle(root);
            return;
        }

        if (root == null) {
            if (tickCount % 15 == 0) {
                CheckinStatus.appendLog("第 " + tickCount + " 次检查：拿不到界面内容");
            }
            return;
        }

        try {
            List<String> texts = collectTexts(root, 0);

            // 0) 如果已经停在打卡页：先按返回退回首页，下一次再点进去，
            //    强制页面重新加载（打卡页不会自己刷新状态，21:00 之后还显示"未到时间"）。
            //    整个流程都发生在学习通内部，不需要启动任何界面，
            //    从而绕开华为"后台禁止拉起界面"的限制。
            if (!refreshed && containsText(texts, "查寝定位打卡")) {
                refreshed = true;
                boolean okBack = tapAt(BACK_X, BACK_Y);
                CheckinStatus.appendLog("已在打卡页，先返回以便重新加载（" + (okBack ? "已注入" : "注入失败") + "）");
                CheckinStatus.setLastResult("正在重新加载打卡页…");
                return;
            }

            // 1) 已经签上了
            String done = firstMatch(texts, DONE_TEXTS, false);
            if (done != null) {
                finish("成功：页面出现「" + done + "」", true);
                return;
            }

            // 1b) 还没到打卡时间：直接结束，别白等 4 分钟
            String early = firstMatch(texts, OUT_OF_WINDOW_TEXTS, false);
            if (early != null) {
                finish("当前不在打卡时间（页面提示「" + early + "」）", false);
                return;
            }

            // 2) 打卡页：点按钮（先精确匹配，再放宽：任何"含打卡、但不含查寝"的短文字）
            Hit btn = findExact(root, BUTTON_TEXTS);
            if (btn == null) {
                btn = findLooseButton(root);
            }
            if (btn != null) {
                if (canClick(btn.label)) {
                    boolean ok = clickNode(btn.node);
                    CheckinStatus.appendLog("点「" + btn.label + "」 " + (ok ? "成功" : "失败"));
                    markClicked(btn.label);
                    CheckinStatus.setLastResult(ok ? "已点击「" + btn.label + "」，等待结果…" : "点击失败，重试中…");
                } else if (tickCount % 30 == 0) {
                    CheckinStatus.appendLog("看到「" + btn.label + "」但暂不点（冷却中/次数上限）");
                }
                recycle(btn.node);
                return;
            }

            // 3) 首页：点「定位打卡」
            Hit entry = findExact(root, ENTRY_TEXTS);
            if (entry != null) {
                if (canClick(entry.label)) {
                    boolean ok = clickNode(entry.node);
                    CheckinStatus.appendLog("点「" + entry.label + "」 " + (ok ? "成功" : "失败"));
                    markClicked(entry.label);
                    CheckinStatus.setLastResult(ok ? "已打开打卡页，等待加载…" : "打开失败，重试中…");
                } else if (tickCount % 30 == 0) {
                    CheckinStatus.appendLog("看到「" + entry.label + "」但暂不点（冷却中/次数上限）");
                }
                recycle(entry.node);
                return;
            }

            // 4) 页面在、但内容读不到（学习通关掉了 WebView 无障碍）→ 坐标盲点
            //    支持连点多个候选位置（tap.txt 里用分号分隔），提高一次命中率
            java.util.List<float[]> pts = readTapPoints();
            if (elapsed > 10000 && tapIndex < pts.size()
                    && System.currentTimeMillis() - lastTapAt > TAP_GAP_MS) {
                float[] pt = pts.get(tapIndex);
                boolean okTap = tapAt(pt[0], pt[1]);
                lastTapAt = System.currentTimeMillis();
                tapIndex++;
                CheckinStatus.appendLog("盲点 " + tapIndex + "/" + pts.size() + " ("
                        + pt[0] + "," + pt[1] + ") " + (okTap ? "已注入" : "注入失败"));
                CheckinStatus.setLastResult("已按坐标点击 " + tapIndex + "/" + pts.size() + "，等待…");
                return;
            }

            // 5) 全部点完，静置几秒收尾
            if (tapIndex >= pts.size() && System.currentTimeMillis() - lastTapAt > 6000) {
                finishInfo("已按坐标连点 " + tapIndex + " 次，请在 App 里看结果");
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

    /**
     * 读 files/tap.txt（内容形如 "0.5,0.79"，表示屏幕宽高的比例）。
     * 用文件而不是硬编码：坐标调起来不用重新打包，adb 一条命令就能改。
     */
    private java.util.List<float[]> readTapPoints() {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        try {
            java.io.File f = new java.io.File(getFilesDir(), "tap.txt");
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    for (String seg : line.split(";")) {
                        String s = seg.trim();
                        if (s.contains(",")) {
                            String[] xy = s.split(",");
                            out.add(new float[] {
                                    Float.parseFloat(xy[0].trim()),
                                    Float.parseFloat(xy[1].trim())
                            });
                        }
                    }
                }
                r.close();
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            out.add(new float[] { 0.5f, 0.79f });
        }
        return out;
    }

    /** 在屏幕相对位置注入一次点击（比例 0~1 之间）。 */
    private boolean tapAt(float rx, float ry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = Math.max(1, Math.min(dm.widthPixels - 1, dm.widthPixels * rx));
            float y = Math.max(1, Math.min(dm.heightPixels - 1, dm.heightPixels * ry));
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 60);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(gd, null, null);
            Log.i(TAG, "tapAt " + x + "," + y + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            CheckinStatus.appendLog("注入点击失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 收尾但不算失败：坐标点击已执行，结果需要人确认。 */
    private void finishInfo(String message) {
        if (finished) {
            return;
        }
        finished = true;
        running = false;
        CheckinStatus.setRunning(false);
        CheckinStatus.markRunEnd();
        CheckinStatus.setLastResult(message);
        CheckinStatus.appendLog(message);
        CheckinStatus.recordResult(true, message);
        try {
            Notify.showInfo(getApplicationContext(), message);
        } catch (Throwable ignored) {
        }
        CheckinStatus.closeActivitySoon();
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

    /**
     * 放宽版按钮查找：任何"包含打卡、但不含查寝、长度不超过 6"的文字。
     * 用来兜住「立即打卡 / 签到打卡 / 去打卡」这类没枚举到的文案，
     * 同时避免把标题「查寝定位打卡」当成按钮。
     */
    private Hit findLooseButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes;
        try {
            nodes = root.findAccessibilityNodeInfosByText("打卡");
        } catch (Throwable t) {
            return null;
        }
        if (nodes == null) {
            return null;
        }
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) {
                continue;
            }
            CharSequence t = n.getText();
            String s = t == null ? "" : t.toString().trim();
            boolean ok = s.contains("打卡") && !s.contains("查寝") && !s.contains("时间") && s.length() <= 6;
            if (ok) {
                AccessibilityNodeInfo clickable = clickableAncestor(n);
                recycle(n);
                if (clickable != null) {
                    return new Hit(clickable, s);
                }
            } else {
                recycle(n);
            }
        }
        return null;
    }

    /**
     * 学习通不在前台时，主动把它切回来。
     *
     * 只能用"界面操作"（回桌面点图标 / 最近任务点卡片），
     * 不能用 startActivity —— 后者会被安卓/华为的"后台禁止拉起界面"拦掉。
     * 但点图标、点最近任务卡片都算用户级操作，系统放行。
     */
    private void bringChaoxingToFront(AccessibilityNodeInfo root, long elapsed, String pkgName) {
        long now = System.currentTimeMillis();
        if (now - lastBringAt < 5000) {
            setWaitStatus(pkgName, elapsed);
            return;
        }
        lastBringAt = now;
        bringStep++;
        switch (bringStep) {
            case 1:
                performGlobalAction(GLOBAL_ACTION_HOME);
                CheckinStatus.appendLog("学习通不在前台（当前 " + pkgName + "），先回桌面");
                CheckinStatus.setLastResult("正在切回学习通…");
                break;
            case 2:
            case 3:
            case 4: {
                boolean ok = clickTextOrDesc(root, "学习通");
                CheckinStatus.appendLog("在桌面找「学习通」图标并点击：" + (ok ? "成功" : "没找到") + "（第 " + (bringStep - 1) + " 次）");
                CheckinStatus.setLastResult(ok ? "已点学习通，等待启动…" : "桌面没找到学习通图标，换最近任务");
                break;
            }
            case 5:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                CheckinStatus.appendLog("改用最近任务切换");
                break;
            case 6:
            case 7:
            case 8: {
                boolean ok = clickTextOrDesc(root, "学习通");
                CheckinStatus.appendLog("在最近任务里点学习通：" + (ok ? "成功" : "没找到"));
                break;
            }
            default:
                setWaitStatus(pkgName, elapsed);
                break;
        }
    }

    private void setWaitStatus(String pkgName, long elapsed) {
        CheckinStatus.setLastResult("等待学习通到前台…（当前 " + pkgName + "）");
        if (tickCount % 15 == 0) {
            CheckinStatus.appendLog("第 " + tickCount + " 次检查：前台是 " + pkgName
                    + "，已等 " + (elapsed / 1000) + " 秒");
        }
    }

    /** 按文字或无障碍描述找元素并点击（桌面图标的名字在 content-desc 里）。 */
    private boolean clickTextOrDesc(AccessibilityNodeInfo node, String needle) {
        return clickSearch(node, needle, 0);
    }

    private boolean clickSearch(AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null || depth > 25) {
            return false;
        }
        try {
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            boolean hit = (t != null && t.toString().contains(needle))
                    || (d != null && d.toString().contains(needle));
            if (hit) {
                if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                AccessibilityNodeInfo p = node.getParent();
                int up = 0;
                while (p != null && up < 4) {
                    if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                    p = p.getParent();
                    up++;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (clickSearch(c, needle, depth + 1)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean containsText(List<String> texts, String needle) {
        for (String s : texts) {
            if (s != null && s.contains(needle)) {
                return true;
            }
        }
        return false;
    }
    /** 命中结果：既要有能点的节点，也要有"我们认出来的文字"。 */
    private static final class Hit {
        final AccessibilityNodeInfo node;
        final String label;

        Hit(AccessibilityNodeInfo node, String label) {
            this.node = node;
            this.label = label;
        }
    }

    /**
     * 按"文字完全相等"找节点，并向上找可点击的祖先。
     *
     * 关键：label 用的是**匹配到的候选文字**，不是祖先节点的文字。
     * 祖先通常是容器（RelativeLayout 之类），没有 text，
     * 早先版本用祖先的 text 当标签会得到空串，导致永远判定"不能点"而静默跳过。
     */
    private Hit findExact(AccessibilityNodeInfo root, String[] candidates) {
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
                        return new Hit(clickable, c);
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
