package cc.haoran.dydl;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 小红书账号登录页。
 *
 * 只为把登录会话放进 App 自己的 CookieManager: 本地解析用的 WebView 与本页共用同一个
 * cookie jar, 登录一次后解析自动带上会话, 无需搬运或复制任何 cookie。
 * 登录态是本地解析能否绕开登录墙的关键。
 */
public class XhsLoginActivity extends Activity {

    static final String HOME = "https://www.xiaohongshu.com/";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF171A21);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(14);
        bar.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("小红书登录");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(0xFF7FD1A0);
        done.setTextSize(16);
        done.setPadding(dp(10), 0, 0, 0);
        done.setOnClickListener(v -> finish());
        bar.addView(done, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, -1, 1f));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 桌面 UA: 网页版登录入口与后续解析取数用同一套页面结构
        s.setUserAgentString(XhsLocal.DESKTOP_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        // 登录跳转全部留在页内完成
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }
        });

        web.loadUrl(HOME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        if (web != null) {
            try {
                ViewGroup vp = (ViewGroup) web.getParent();
                if (vp != null) vp.removeView(web);
                web.stopLoading();
                web.destroy();
            } catch (Exception ignored) {}
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        boolean ok = XhsLocal.hasSession(this);
        Toast.makeText(this, ok ? "✓ 小红书登录态已保存" : "未检测到登录态, 可稍后重试",
                Toast.LENGTH_LONG).show();
        super.finish();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
