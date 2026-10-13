package cc.haoran.dydl;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.io.File;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * 无障碍剪贴板监控: Android 10+ 后台读剪贴板的唯一系统通道。
 * 用户在系统无障碍设置开启一次后常驻; 监听窗口内容变化事件, 节流后读剪贴板,
 * 发现新的抖音链接 → DownloadService.enqueueParse (服务端解析+自动下载)。
 * 同时持续缓存最近一次剪贴板文本 (lastClipboard), 供悬浮球在无焦点场景兜底读取。
 */
public class ClipboardWatcherService extends AccessibilityService {

    /** 最近一次剪贴板全文 (悬浮球兜底用) */
    public static volatile String lastClipboard = "";

    private static final long READ_COOLDOWN_MS = 2000;   // 触发读冷却
    private static final long SWEEP_COOLDOWN_MS = 12000; // 抖音前台静默扫描冷却
    private static final int SEEN_MAX = 20;
    private final java.util.ArrayDeque<String> seen = new java.util.ArrayDeque<>();

    /** 上次已处理剪贴板内容的指纹 (跨进程重启持久化): 静默扫描据此判断"内容是否真被重新复制过" */
    private static final String PREFS = "dydl";
    private static final String PREF_LAST_FP = "last_clip_fp";
    private String lastHandledFp = null;
    private final SimpleDateFormat logFmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastReadAt = 0;
    private long lastSweepAt = 0;
    private long lastLogWrite = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d("DyDl", "watcher connected");
        writeLog("watcher connected");
        synchronized (seen) { seen.clear(); }
        // 恢复上次已处理内容指纹: 重启后剪贴板里残留的旧链接不会被静默扫描当成新链接
        try {
            lastHandledFp = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_FP, null);
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (pkg.equals(getPackageName())) return;

        StringBuilder et = new StringBuilder();
        if (event.getText() != null) {
            for (int i = 0; i < event.getText().size(); i++) et.append(event.getText().get(i)).append(' ');
        }
        String etext = et.toString();
        long now = System.currentTimeMillis();

        boolean trigger = etext.contains("复制") || etext.contains("拷贝");
        // 前台静默扫描兜底: 抖音与小红书 App 在前台时周期性读一次剪贴板
        boolean sweep = pkg.contains("douyin") || pkg.contains("ss.android")
                || pkg.contains("xingin") || pkg.contains("xiaohongshu");

        if (trigger && now - lastReadAt >= READ_COOLDOWN_MS) {
            lastReadAt = now;
            writeLog("trigger " + pkg + ": " + etext.substring(0, Math.min(60, etext.length())));
            stealFocusAndRead(false);
        } else if (sweep && now - lastSweepAt >= SWEEP_COOLDOWN_MS) {
            lastSweepAt = now;
            stealFocusAndRead(true);   // 抖音前台静默扫描兜底
        }
    }

    /** 隐形 1px 可聚焦悬浮窗抢系统焦点 150ms → 读剪贴板 → 移除 */
    private void stealFocusAndRead(boolean fromSweep) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            View v = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSPARENT);
            lp.gravity = Gravity.START | Gravity.TOP;
            try { wm.addView(v, lp); } catch (Exception e) { writeLog("overlay fail " + e); return; }
            v.post(() -> v.requestFocus());
            handler.postDelayed(() -> {
                String text = "";
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0
                            && cm.getPrimaryClip().getItemAt(0) != null) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                        if (t != null) text = t.toString();
                    }
                } catch (Exception ignored) {}
                try { wm.removeView(v); } catch (Exception ignored) {}
                processText(text, fromSweep);
            }, 150);
        } catch (Exception e) {
            writeLog("steal fail: " + e);
        }
    }

    private void processText(String text, boolean fromSweep) {
        if (text == null) text = "";
        lastClipboard = text;
        writeLog("read: " + text.substring(0, Math.min(80, text.length())));
        // 内容指纹: 先判断是否为新内容, 再记录本次
        String fp = text.length() + ":" + Integer.toHexString(text.hashCode());
        boolean hasBaseline = lastHandledFp != null;
        boolean isNew = !fp.equals(lastHandledFp);
        lastHandledFp = fp;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_FP, fp).apply();
        } catch (Exception ignored) {}
        // 平台不限: 抖音链接走服务端解析, 小红书链接走 App 本地解析 (DownloadService 内分流)
        if (!DownloadService.isSupportedUrl(DownloadService.extractFirstUrl(text))) return;
        // 静默扫描只是兜底 (复制事件的窗口文本未必抓得到): 内容与上次相同说明剪贴板没被重新
        // 复制过, 直接跳过 — 否则 App 重启后会把残留的旧链接当新链接重新入队。
        // 首次 (无基线, 含升级后) 只把当前剪贴板记为基线, 不自动入队, 避免把历史残留当成新链接。
        if (fromSweep && (!hasBaseline || !isNew)) { writeLog("sweep 无基线或内容未变, 跳过"); return; }

        Matcher m = Pattern.compile("https?://[^\\s\"'<>）)】，。]+").matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,;]+$", "");
            String key = DownloadService.keyOf(url);
            synchronized (seen) {
                if (seen.contains(key)) { writeLog("dup skip " + key); continue; }
                seen.addLast(key);
                while (seen.size() > SEEN_MAX) seen.removeFirst();
            }
            boolean monitorOn = getSharedPreferences("dydl", MODE_PRIVATE)
                    .getBoolean("clip_monitor_enabled", true);
            if (!monitorOn) { writeLog("monitor off, skip"); return; }
            writeLog("ENQUEUE " + url);
            DownloadService.enqueueParse(this, url);
            Toast.makeText(this, "检测到链接，已自动加入下载队列", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeLog(String msg) {
        // 诊断日志已关闭 (v2.16)。如需重新开启, 恢复本方法写 Download/dy-dl-clip.log
    }

    /** 是否已开启: 读系统设置字符串 + 已启用无障碍服务列表 */
    static boolean isEnabled(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null && enabled.contains(ctx.getPackageName())
                    && enabled.contains(ClipboardWatcherService.class.getSimpleName())) {
                return true;
            }
        } catch (Exception ignored) {}
        AccessibilityManager am = (AccessibilityManager)
                ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            String id = info.getId();
            if (id != null && id.contains(ctx.getPackageName())
                    && id.endsWith(ClipboardWatcherService.class.getName())) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() { }
}

