package cc.haoran.dydl;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 视频解析 App 壳: WebView 承载 https://dy.haoran.cc
 * 下载统一走 DownloadService (前台服务队列), 本类只做 UI 与权限引导。
 */
public class MainActivity extends Activity {

    static final String SITE = "https://dy.haoran.cc/";
    static final String TAG = "DyDl";

    private final java.util.concurrent.ExecutorService dlExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 原生顶栏(标题 + ⚙ 设置入口) + WebView
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);

        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF171A21);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int bp = dp(14);
        bar.setPadding(bp, bp, bp, bp);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("视频解析");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.TextView gear = new android.widget.TextView(this);
        gear.setText("⚙");
        gear.setTextColor(0xFF9AA3B2);
        gear.setTextSize(20);
        gear.setPadding(dp(10), 0, 0, 0);
        gear.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, SettingsActivity.class));
            } catch (Exception e) {
                Log.e(TAG, "open settings fail", e);
                Toast.makeText(this, "打开设置失败: " + e, Toast.LENGTH_LONG).show();
            }
        });
        bar.addView(gear, new android.widget.LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));

        web = new WebView(this);
        root.addView(web, new android.widget.LinearLayout.LayoutParams(-1, -1, 1f));
        setContentView(root);

        // 恢复用户设置: 悬浮球开关
        android.content.SharedPreferences prefs = getSharedPreferences("dydl", MODE_PRIVATE);
        if (prefs.getBoolean("ball_enabled", false) && Settings.canDrawOverlays(this)) {
            startService(new Intent(this, FloatBallService.class));
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " DyDlApp/1.0");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "AndroidDyDl");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if (host != null && host.endsWith("haoran.cc")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d(TAG, "web:" + cm.message() + " @" + cm.lineNumber());
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.HitTestResult r = view.getHitTestResult();
                String data = r != null ? r.getExtra() : null;
                if (data != null && data.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(data)));
                        return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });
        web.loadUrl(SITE);
        handleAutoParse(getIntent());

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (Exception ignored) {}
        }
    }

    void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // 通知权限: 拒绝也能用, 只是看不到通知
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAutoParse(intent);
    }

    /** App 获得焦点即自动读剪贴板 (安卓10+ 合法): 发现新抖音链接 → 原生队列自动解析下载。
     *  每次打开都检查; last_auto_key 去重; 无链接时静默。 */
    private void handleAutoParse(Intent intent) {
        boolean fromNotify = intent != null && intent.getBooleanExtra("auto_parse_clipboard", false);
        web.postDelayed(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
                    if (fromNotify) Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                String text = t == null ? "" : t.toString();
                String url = DownloadService.extractFirstUrl(text);
                if (url == null || !(url.contains("douyin.com") || url.contains("iesdouyin.com"))) {
                    if (fromNotify) Toast.makeText(this, "剪贴板中没有抖音链接", Toast.LENGTH_SHORT).show();
                    return;   // 普通打开: 无链接静默
                }
                String key = DownloadService.keyOf(url);
                android.content.SharedPreferences prefs =
                        getSharedPreferences("dydl", MODE_PRIVATE);
                if (key.equals(prefs.getString("last_auto_key", ""))) {
                    if (fromNotify) Toast.makeText(this, "该链接刚解析过", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("last_auto_key", key).apply();
                DownloadService.enqueueParse(this, url);
                Toast.makeText(this, "✓ 剪贴板发现抖音链接，已自动加入下载队列", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "auto parse fail", e);
                Toast.makeText(this, "自动解析失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 800);   // 等 WebView 加载与焦点就绪
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 注入页面的 JS 桥: window.AndroidDyDl */
    class Bridge {

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String getClipboard() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
            return t == null ? "" : t.toString();
        }

        /** 提交到原生前台服务队列 (解析+下载), 结果走通知栏 */
        @JavascriptInterface
        public boolean saveFile(String url, String name) {
            if (url == null || !url.startsWith("https://")) return false;
            try {
                DownloadService.enqueueDownload(MainActivity.this, url, name);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "enqueue fail", e);
                return false;
            }
        }

        /** 分享链接整条入队 (解析+下载), 悬浮球同款 */
        /** 动态照片: 服务端拉图+实况mp4 → 本地合成单文件 JPG */
        @JavascriptInterface
        public boolean saveMotion(String imgUrl, String liveUrl, String name) {
            if (imgUrl == null || liveUrl == null
                    || !imgUrl.startsWith("https://") || !liveUrl.startsWith("https://")) return false;
            try {
                dlExec.execute(() -> {
                    try {
                        DownloadService.doMotionDownloadPublic(MainActivity.this,
                                imgUrl, liveUrl, name);
                    } catch (Exception e) {
                        Log.e(TAG, "motion fail", e);
                    }
                });
                return true;
            } catch (Exception e) {
                Log.e(TAG, "saveMotion fail", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean enqueueParse(String raw) {
            if (raw == null) return false;
            try {
                DownloadService.enqueueParse(MainActivity.this, raw);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "enqueueParse fail", e);
                return false;
            }
        }

        @JavascriptInterface
        public boolean isA11yOn() {
            return ClipboardWatcherService.isEnabled(MainActivity.this);
        }

        @JavascriptInterface
        public boolean canDrawOverlays() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void openA11ySettings() { runOnUiThread(() -> openA11ySettings()); }

        @JavascriptInterface
        public void openOverlaySettings() { runOnUiThread(() -> MainActivity.this.openOverlaySettings()); }

        @JavascriptInterface
        public void startFloatBall() {
            runOnUiThread(() -> {
                if (!Settings.canDrawOverlays(MainActivity.this)) { openOverlaySettings(); return; }
                startService(new Intent(MainActivity.this, FloatBallService.class));
                Toast.makeText(MainActivity.this, "悬浮球已开启", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void stopFloatBall() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, FloatBallService.class)));
        }

        @JavascriptInterface
        public boolean isBallRunning() {
            return FloatBallService.running;
        }
    }
}
