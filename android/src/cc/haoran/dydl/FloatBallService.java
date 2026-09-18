package cc.haoran.dydl;

import android.app.Service;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.util.Log;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮球: 显示队列角标; 单击读剪贴板入队; 长按打开主界面; 可拖拽贴边。
 * 需要「显示在其他应用上层」权限 (Settings.canDrawOverlays)。
 */
public class FloatBallService extends Service {

    static volatile boolean running = false;
    private static volatile FloatBallService uiInstance = null;
    private static volatile String animState = "idle";   // idle | pulse | working

    /** DownloadService 回调: 队列变化 (任意线程)。kind 直接透传, 不经共享状态避免竞态 */
    static void onQueueChanged(String kind) {
        final FloatBallService svc = uiInstance;
        if (svc == null) return;
        final String k = kind;
        svc.handler.post(() -> {
            if ("pulse".equals(k)) {
                svc.playPulse();
                animState = "working";   // 入队后立即进入工作态
            } else if ("tick".equals(k)) {
                animState = DownloadService.activeCount() > 0 ? "working" : "idle";
            }
            svc.updateBadge();
        });
    }

    int pendingCount() {
        return DownloadService.activeCount();
    }

    private WindowManager wm;
    private View ball;
    private TextView badge;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        running = true;
        uiInstance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        int size = dp(52);
        FrameLayout box = new FrameLayout(this);
        BallView bv = new BallView(this);
        box.addView(bv, new FrameLayout.LayoutParams(size, size));
        badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(null);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(size, dp(16), Gravity.CENTER);
        blp.topMargin = dp(18);
        box.addView(badge, blp);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = dp(220);

        box.setOnTouchListener(new DragClick(lp, () -> updateBadge(), this::onBallClick, () -> {
            // 长按: 打开主界面
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }));
        wm.addView(box, lp);
        ball = box;
        updateBadge();
    }

    /** 单击悬浮球: 三级兜底读剪贴板 (直接读 → 无障碍缓存 → 临时抢焦点) */
    private void onBallClick() {
        // 抢焦点直读优先: 悬浮窗临时可聚焦 → 本 App 持有焦点 → 安卓10+ 也允许读, 内容必然最新。
        // 旧缓存在这里会读到陈旧内容, 故仅作最后的兜底。
        grabFocusAndRead();
    }

    private String readClipboardDirect() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return "";
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";   // 安卓10+ 后台无焦点读剪贴板会被拒
        }
    }

    private static boolean hasDouyinLink(String text) {
        String url = DownloadService.extractFirstUrl(text == null ? "" : text);
        return url != null && (url.contains("douyin.com") || url.contains("iesdouyin.com"));
    }

    private void enqueueFromText(String text) {
        try {
            String url = DownloadService.extractFirstUrl(text);
            if (url == null) { toast("剪贴板里没有抖音链接"); return; }
            DownloadService.enqueueParse(this, url);
            toast("已加入下载队列");
        } catch (Exception e) {
            Log.w("DyDl", "enqueue fail", e);
            toast("加入队列失败: " + e.getMessage());
        }
    }

    /** 悬浮窗临时变可聚焦抢系统焦点 → 读剪贴板 → 恢复不可聚焦 */
    private void grabFocusAndRead() {
        try {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(ball, lp);
            ball.requestFocus();
            handler.postDelayed(() -> {
                String text = readClipboardDirect();
                // 立刻恢复不可聚焦, 避免抢其他 App 的键盘
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                if (hasDouyinLink(text)) {
                    enqueueFromText(text);
                } else {
                    toast("读不到剪贴板(安卓10+限制)：请开启无障碍监控");
                }
            }, 150);
        } catch (Exception e) {
            toast("读不到剪贴板：请开启无障碍监控");
        }
    }

    private void toast(String s) {
        handler.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 入队脉冲: 波纹扩散 + 球体弹一下 */
    void playPulse() {
        if (ball instanceof BallView) ((BallView) ball).firePulse();
        if (ball != null) {
            android.view.animation.ScaleAnimation sa = new android.view.animation.ScaleAnimation(
                    1f, 1.25f, 1f, 1.25f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            sa.setDuration(140);
            sa.setRepeatMode(android.view.animation.Animation.REVERSE);
            sa.setRepeatCount(1);
            sa.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                public void onAnimationStart(android.view.animation.Animation a) {}
                public void onAnimationRepeat(android.view.animation.Animation a) {}
                public void onAnimationEnd(android.view.animation.Animation a) {
                    animState = DownloadService.activeCount() > 0 ? "working" : "idle";
                }
            });
            ball.startAnimation(sa);
        }
    }

    void updateBadge() {
        int n = DownloadService.activeCount();
        handler.post(() -> {
            if (badge == null) return;
            badge.setText(n > 0 ? String.valueOf(n) : "");
            if (ball instanceof BallView) {
                ((BallView) ball).setWorking(n > 0);
                ball.invalidate();
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        uiInstance = null;
        if (ball != null) {
            try { wm.removeView(ball); } catch (Exception ignored) {}
            ball = null;
        }
        super.onDestroy();
    }

    /** 拖拽 + 贴边 + 单击/长按判定 */
    class DragClick implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final Runnable onMoveEnd, onClick, onLongClick;
        private float downX, downY;
        private int startX, startY;
        private boolean moved = false, longFired = false;
        private final Runnable longTask;

        DragClick(WindowManager.LayoutParams lp, Runnable onMoveEnd, Runnable onClick, Runnable onLongClick) {
            this.lp = lp; this.onMoveEnd = onMoveEnd; this.onClick = onClick; this.onLongClick = onLongClick;
            this.longTask = () -> { longFired = true; onLongClick.run(); };
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = lp.x; startY = lp.y;
                    moved = false; longFired = false;
                    handler.postDelayed(longTask, 550);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > dp(8)) {
                        moved = true;
                        handler.removeCallbacks(longTask);
                    }
                    if (moved) {
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longTask);
                    if (longFired) return true;
                    if (moved) {
                        // 贴边吸附
                        int sw = getResources().getDisplayMetrics().widthPixels;
                        lp.x = (lp.x + dp(26) > sw / 2) ? sw - dp(80) : dp(16);
                        try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                        onMoveEnd.run();
                    } else {
                        onClick.run();
                    }
                    return true;
            }
            return false;
        }
    }

    class BallView extends View {
        private float pulseR = -1f;
        private boolean working = false;
        private final android.os.Handler animHandler = new android.os.Handler(Looper.getMainLooper());
        private final Runnable breathe = new Runnable() {
            @Override public void run() {
                invalidate();
                if (working) animHandler.postDelayed(this, 400);
            }
        };
        BallView(Context c) { super(c); }

        void firePulse() {
            pulseR = 0f;
            animPulse();
        }
        private void animPulse() {
            if (pulseR < 0) return;
            pulseR += getWidth() / 14f;
            if (pulseR > getWidth() * 1.1f) { pulseR = -1f; invalidate(); return; }
            invalidate();
            animHandler.postDelayed(this::animPulse, 16);
        }
        void setWorking(boolean w) {
            if (working != w) {
                working = w;
                if (working) animHandler.post(breathe); else animHandler.removeCallbacks(breathe);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (pulseR >= 0) {
                Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
                wave.setStyle(Paint.Style.STROKE);
                wave.setStrokeWidth(dp(2));
                float t = pulseR / (getWidth() * 1.1f);
                wave.setColor(Color.argb((int) (160 * (1 - t)), 79, 140, 255));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, pulseR, wave);
            }
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 2;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
            // 渐变底
            android.graphics.LinearGradient g = new android.graphics.LinearGradient(
                    cx - r, cy - r, cx + r, cy + r, 0xFF4F8CFF, 0xFF22C1DC, android.graphics.Shader.TileMode.CLAMP);
            p.setShader(g);
            canvas.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            // 下载箭头: 竖线 + 三角 + 底线
            float s = r * 0.42f;
            canvas.drawLine(cx, cy - s, cx, cy + s * 0.45f, p);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(cx - s * 0.62f, cy + s * 0.28f);
            tri.lineTo(cx + s * 0.62f, cy + s * 0.28f);
            tri.lineTo(cx, cy + s);
            tri.close();
            p.setStyle(Paint.Style.FILL);
            canvas.drawPath(tri, p);
            canvas.drawLine(cx - s * 0.7f, cy + s, cx + s * 0.7f, cy + s, p);
        }
    }
}
