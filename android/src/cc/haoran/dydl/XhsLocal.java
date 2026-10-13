package cc.haoran.dydl;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小红书本地解析 (App 内完成, 不经服务器)。
 *
 * 取数方式: 用 WebView 以桌面 UA 加载笔记页 (自动携带 App 内的小红书登录会话),
 * 页面加载完再把脚本注入页面自己的 JS 上下文, 从中取出该笔记的结构化状态。
 * 这样不依赖任何签名逆向, 也不需要自己解析 __INITIAL_STATE__ 字面量 —— 签名与状态
 * 都由页面自身的 JS 准备妥当。
 *
 * 输出与服务器 /api/parse 的小红书结果同构, 直接交给 DownloadService.fanout 消费。
 */
final class XhsLocal {

    static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Pattern XHS_ID_RE =
            Pattern.compile("/(?:explore|discovery/item|item)/([0-9a-fA-F]{24})");

    private static final String[] XHS_HOSTS =
            {"xiaohongshu.com", "rednote.com", "xhslink.com", "xhslink.cn"};

    /** H.264 优先 (部分手机放不了 HEVC/AV1) */
    private static final String[] CODEC_ORDER = {"h264", "h265", "av1", "h266"};

    /** 页面内取数脚本: 在 __INITIAL_STATE__ 等根对象上做 BFS 找到目标笔记, 然后
     *  在页面自己的 JS 里把「图 / 实况 / 视频」直接选好再返回。
     *
     *  实况字段位置在小红书历代版本里来回变 (livePhoto.media.stream / stream /
     *  livePhotoVideo / liveUrl ...), 所以最后再对整张图的子树做一次扫描, 只要出现
     *  像视频的链接就采用 —— 不再依赖具体字段名。
     *
     *  返回对象而非字符串, 避免 evaluateJavascript 回调里的二次转义。 */
    private static final String EXTRACT_JS =
            "(function(){"
            + "function str(u){return (typeof u==='string'&&u.indexOf('http')===0)?u:'';}"
            + "function pickStream(st){"
            + "if(!st||typeof st!=='object')return '';"
            + "var order=['h264','h265','av1','h266'],wm='';"
            + "for(var ci=0;ci<order.length;ci++){var arr=st[order[ci]];if(!arr||!arr.length)continue;"
            + "for(var i=0;i<arr.length;i++){var it=arr[i];if(!it)continue;"
            + "var u=(typeof it==='string')?str(it):(str(it.masterUrl)||str(it.master_url)||str(it.url)"
            + "||str((it.backupUrls||it.backup_urls||[])[0]));"
            + "if(!u)continue;"
            + "var bad=(it.streamType===259||it.streamType===309"
            + "||String(it.streamDesc||'').toUpperCase()==='WEB_LIVEPHOTO_19');"
            + "if(bad){if(!wm)wm=u;}else{return u;}}}"
            + "return wm;}"
            + "function originUrl(v){"
            + "if(!v||typeof v!=='object')return '';"
            + "var k=(v.consumer&&v.consumer.originVideoKey)||v.originVideoKey;"
            + "if(typeof k!=='string'||!k)return '';"
            + "return k.indexOf('http')===0?k:('https://sns-video-bd.xhscdn.com/'+k.replace(/^\\//,''));}"
            + "function sweep(root){"
            + "var seen=new Set(),q=[root],n=0;"
            + "while(q.length&&n++<4000){var v=q.shift();"
            + "if(!v||typeof v!=='object')continue;"
            + "if(seen.has(v))continue;seen.add(v);"
            + "for(var k in v){var c=v[k];"
            + "if(typeof c==='string'){"
            + "if(c.indexOf('http')===0&&(c.indexOf('sns-video')>=0||c.indexOf('sns-vod')>=0"
            + "||/\\.mp4(\\?|$)/.test(c)))return c;}"
            + "else if(c&&typeof c==='object'){q.push(c);}}}"
            + "return '';}"
            + "function liveOf(it){"
            + "if(!it||typeof it!=='object')return '';"
            + "var u=pickStream(it.livePhoto&&it.livePhoto.media&&it.livePhoto.media.stream)"
            + "||pickStream(it.livePhoto&&it.livePhoto.stream)"
            + "||pickStream(it.stream)"
            + "||str(it.livePhotoVideo)||str(it.liveUrl)"
            + "||originUrl(it.livePhoto)||originUrl(it);"
            + "if(u)return u;"
            + "return sweep(it);}"
            + "function imgOf(it){"
            + "if(!it||typeof it!=='object')return '';"
            + "var d=str(it.urlDefault);if(d)return d;"
            + "var il=it.infoList||[],any='';"
            + "for(var i=0;i<il.length;i++){var o=il[i]||{};var s=str(o.url);if(!s)continue;"
            + "if(String(o.imageScene||'').toUpperCase()==='WB_DFT')return s;if(!any)any=s;}"
            + "if(any)return any;"
            + "var u=str(it.url);if(u)return u;"
            + "var ul=it.url_list||[],last='';"
            + "for(var j=0;j<ul.length;j++){var s2=str(ul[j]);if(s2)last=s2;}"
            + "if(last)return last;"
            + "if(it.traceId)return 'https://sns-img-qc.xhscdn.com/'+it.traceId;"
            + "return '';}"
            + "function build(note){"
            + "var il=note.imageList||note.images||[];"
            + "var media=[],live=0;"
            + "for(var i=0;i<il.length;i++){var it=il[i];var img=imgOf(it);if(!img)continue;"
            + "var lv=liveOf(it);if(lv)live++;media.push({img:img,live:lv});}"
            + "var v=note.video||null;"
            + "var vurl=pickStream(v&&v.media&&v.media.stream)||pickStream(v&&v.video_stream)"
            + "||pickStream(v&&v.stream)||originUrl(v)||'';"
            + "var u0=note.user||{};"
            + "var k0=il.length&&il[0]&&typeof il[0]==='object'?Object.keys(il[0]).join(','):'-';"
            + "var lp0=il.length&&il[0]&&il[0].livePhoto?'lp:'+Object.keys(il[0].livePhoto).join(','):'lp:-';"
            + "var st0=il.length&&il[0]&&il[0].stream?'st:'+Object.keys(il[0].stream).join(','):'st:-';"
            + "return {media:media,video:vurl,title:note.title||note.desc||'',"
            + "author:u0.nickname||u0.nickName||'',"
            + "diag:'n='+il.length+';live='+live+';k0='+k0+';'+lp0+';'+st0};}"
            + "var m=location.pathname.match(/\\/(?:explore|discovery\\/item|item)\\/([0-9a-fA-F]{24})/);"
            + "var want=m?m[1]:null;"
            + "if(!want)return {error:'unsupported_page'};"
            + "var roots=[window.__INITIAL_STATE__,window.__NEXT_DATA__,window.__NUXT__];"
            + "var seen=new Set(),queue=[],i;"
            + "for(i=0;i<roots.length;i++){if(roots[i])queue.push(roots[i]);}"
            + "var visits=0;"
            + "while(queue.length&&visits++<20000){"
            + "var v=queue.shift();"
            + "if(!v||typeof v!=='object')continue;"
            + "if(seen.has(v))continue;seen.add(v);"
            + "if(v.noteDetailMap&&v.noteDetailMap[want]&&v.noteDetailMap[want].note){"
            + "var d=v.noteDetailMap[want];"
            + "if(!d.note.noteId||d.note.noteId===want)"
            + "return {note:build(d.note),noteId:d.note.noteId||want,url:location.href};}"
            + "if(v.noteData&&v.noteData.data&&v.noteData.data.noteData"
            + "&&v.noteData.data.noteData.noteId===want)"
            + "return {note:build(v.noteData.data.noteData),noteId:want,url:location.href};"
            + "if(v.noteId===want&&(v.imageList||v.video||v.desc!==undefined))"
            + "return {note:build(v),noteId:want,url:location.href};"
            + "for(var k in v){try{var c=v[k];if(c&&typeof c==='object')queue.push(c);}catch(e){}}"
            + "}"
            + "return {error:'note_state_unavailable'};"
            + "})();";;;

    /** 页面首帧后轮询取数的次数与间隔 (页面可能异步注水) */
    private static final int POLL_TRIES = 6;
    private static final long POLL_GAP_MS = 1500;
    private static final long TOTAL_TIMEOUT_S = 45;

    private XhsLocal() {}

    // ---------------- 识别 ----------------

    static boolean isXhs(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.toLowerCase();
        for (String h : XHS_HOSTS) if (u.contains(h)) return true;
        return false;
    }

    /** 从链接/分享文本中提取 24 位十六进制笔记 ID; 取不到返回 null。 */
    static String noteId(String raw) {
        if (raw == null) return null;
        Matcher m = XHS_ID_RE.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /** 是否已有小红书登录会话 (供设置页显示状态 / 失败时给出可操作提示)。 */
    static boolean hasSession(Context ctx) {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.xiaohongshu.com");
            return c != null && (c.contains("web_session=") || c.contains("customerClientId=")
                    || c.contains("a1="));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 主入口 ----------------

    static JSONObject parse(Context ctx, String raw) throws Exception {
        String url = DownloadService.extractFirstUrl(raw);
        if (url == null) throw new Exception("文本中没有链接");
        if (!isXhs(url)) throw new Exception("不是小红书链接");

        // 短链展开: 失败也无妨, WebView 自己会跟着跳转 (顺带保留 xsec_token 等 query)
        String page = resolveShortLink(url);
        String id = noteId(page);
        if (id == null) id = noteId(url);

        JSONObject payload = extractViaWebView(ctx, page, id);
        return buildResult(payload, id, page);
    }

    /** xhslink 短链 → 302 后的笔记页地址; 任何异常都退回原地址。 */
    static String resolveShortLink(String url) {
        String u = url.toLowerCase();
        boolean shortLink = u.contains("xhslink.com") || u.contains("xhslink.cn");
        if (!shortLink) return url;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", DESKTOP_UA);
            String cookie = CookieManager.getInstance().getCookie("https://www.xiaohongshu.com");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            String finalUrl = c.getURL() == null ? null : c.getURL().toString();
            if (code >= 200 && code < 400 && finalUrl != null && finalUrl.contains("xiaohongshu.com")) {
                return finalUrl;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
        return url;
    }

    // ---------------- WebView 取数 ----------------

    private static JSONObject extractViaWebView(Context ctx, String pageUrl, String noteId)
            throws Exception {
        final String[] out = new String[1];
        final String[] err = new String[1];
        final String[] finalUrl = new String[1];
        final boolean[] attached = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final int[] overlayType = {0};
        // 旁听网络: 视频笔记即使状态取不到, 嗅到 CDN 直链也能兜底下载
        final Set<String> sniffed = Collections.synchronizedSet(new LinkedHashSet<String>());

        main.post(() -> {
            try {
                WebView wv = new WebView(ctx);
                holder[0] = wv;
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                // 桌面 UA: PC 版页面才会把笔记写进 note.noteDetailMap
                s.setUserAgentString(DESKTOP_UA);
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(wv, true);

                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                     WebResourceRequest req) {
                        try {
                            String u = req.getUrl() == null ? null : req.getUrl().toString();
                            if (u != null && u.contains("xhscdn") && u.contains("sns-video")) {
                                sniffed.add(u);
                            }
                        } catch (Exception ignored) {}
                        return null;   // 只旁听, 不拦截
                    }

                    @Override
                    public void onPageFinished(WebView view, String u) {
                        finalUrl[0] = u;
                        startPolling(view, main, latch, out, err, noteId, sniffed);
                    }
                });

                // 挂到窗口上, 避免后台 WebView 被降频导致 JS 不执行/页面不注水
                attached[0] = tryAttach(ctx, wv, overlayType);
                wv.loadUrl(pageUrl);
            } catch (Throwable t) {
                err[0] = "小红书解析初始化失败[" + t + "]";
                latch.countDown();
            }
        });

        boolean ok;
        try {
            ok = latch.await(TOTAL_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        final int ot = overlayType[0];
        final WebView wvFinal = holder[0];
        final boolean attFinal = attached[0];
        main.post(() -> {
            try {
                if (wvFinal != null) {
                    if (attFinal) {
                        try {
                            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).removeView(wvFinal);
                        } catch (Exception ignored) {}
                    }
                    wvFinal.stopLoading();
                    wvFinal.destroy();
                }
                if (ot != 0) { /* 仅用于类型选择的记录, 无副作用 */ }
            } catch (Exception ignored) {
            }
        });

        if (!ok) {
            throw new Exception("小红书解析超时[" + (attFinal ? "已挂载" : "未挂载") + "], 请检查网络后重试");
        }
        if (err[0] != null) throw new Exception(err[0]);
        String raw = out[0];
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            throw new Exception(noDataReason(ctx, finalUrl[0], attFinal));
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new Exception("小红书数据解析失败: " + e.getMessage());
        }
    }

    /** 页面首帧后反复尝试取数: 有的笔记是异步注水的, 一次取不到不代表没有。 */
    private static void startPolling(final WebView view, final Handler main,
                                     final CountDownLatch latch, final String[] out,
                                     final String[] err, final String noteId,
                                     final Set<String> sniffed) {
        final int[] attempt = {0};
        final boolean[] finished = {false};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (finished[0]) return;
            if (attempt[0]++ >= POLL_TRIES) {
                finished[0] = true;
                String v = firstSniffedVideo(sniffed);
                if (v != null) {
                    out[0] = sniffedVideoResult(noteId, v);
                } else if (err[0] == null) {
                    err[0] = "";   // 交给外层按登录态/最终 URL 生成可操作提示
                }
                latch.countDown();
                return;
            }
            try {
                view.evaluateJavascript(EXTRACT_JS, value -> {
                    if (finished[0]) return;
                    try {
                        if (value != null && !value.isEmpty() && !"null".equals(value)) {
                            JSONObject o = new JSONObject(value);
                            if (o.has("note")) {
                                finished[0] = true;
                                out[0] = value;
                                latch.countDown();
                                return;
                            }
                            if ("unsupported_page".equals(o.optString("error", ""))) {
                                finished[0] = true;
                                err[0] = "无法从小红书链接中识别出笔记 ID";
                                latch.countDown();
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                    main.postDelayed(step[0], POLL_GAP_MS);
                });
            } catch (Throwable t) {
                if (!finished[0]) {
                    finished[0] = true;
                    err[0] = "小红书脚本注入失败[" + t + "]";
                    latch.countDown();
                }
            }
        };
        step[0].run();
    }

    /** 没有拿到笔记数据时, 依据登录态与最终 URL 给出最可操作的提示。 */
    private static String noDataReason(Context ctx, String finalUrl, boolean attached) {
        boolean loginPage = finalUrl != null && finalUrl.contains("/login");
        if (loginPage || !hasSession(ctx)) {
            return "小红书需要先登录 (设置 → 小红书账号登录)";
        }
        return "小红书页面取不到笔记数据[" + (attached ? "已挂载" : "未挂载")
                + "] (可能被风控或笔记受限)";
    }

    /** 把 WebView 挂成 1x1 隐形窗口: 避免后台被降频。无悬浮窗权限时退回不挂载。 */
    private static boolean tryAttach(Context ctx, WebView wv, int[] typeOut) {
        try {
            if (!Settings.canDrawOverlays(ctx)) return false;
            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.START | Gravity.TOP;
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).addView(wv, lp);
            typeOut[0] = type;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String firstSniffedVideo(Set<String> sniffed) {
        synchronized (sniffed) {
            for (String u : sniffed) if (u != null && !u.isEmpty()) return u;
        }
        return null;
    }

    /** 状态取不到但嗅到了视频直链时, 拼一个最小的视频笔记结果。 */
    private static String sniffedVideoResult(String noteId, String videoUrl) {
        try {
            JSONObject item = new JSONObject().put("master_url", DownloadService.httpsify(videoUrl));
            JSONArray arr = new JSONArray().put(item);
            JSONObject stream = new JSONObject().put("h264", arr);
            JSONObject media = new JSONObject().put("stream", stream);
            JSONObject video = new JSONObject().put("media", media);
            JSONObject note = new JSONObject()
                    .put("noteId", noteId == null ? "" : noteId)
                    .put("type", "video")
                    .put("video", video);
            return new JSONObject().put("note", note).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 结果规整 ----------------

    private static JSONObject buildResult(JSONObject payload, String fallbackId, String pageUrl)
            throws Exception {
        if (payload.has("error")) {
            String e = payload.optString("error", "");
            if ("unsupported_page".equals(e)) {
                throw new Exception("无法从小红书链接中识别出笔记 ID");
            }
            throw new Exception("小红书笔记取不到数据 (可能未登录或笔记已失效)");
        }
        JSONObject note = payload.optJSONObject("note");
        if (note == null) throw new Exception("小红书笔记数据为空");

        String id = payload.optString("noteId", "");
        if (id.isEmpty()) id = note.optString("noteId", "");
        if (id.isEmpty()) id = fallbackId == null ? "" : fallbackId;

        JSONArray images = new JSONArray();
        JSONArray lives = new JSONArray();
        boolean hasLive = false;

        JSONArray media = note.optJSONArray("media");
        if (media != null) {
            // 首选: 页面内 JS 已经按历代字段位置 + 子树扫描选好的 [图, 实况] 对
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.optJSONObject(i);
                if (m == null) continue;
                String img = m.optString("img", "");
                if (img.isEmpty()) continue;
                images.put(DownloadService.httpsify(img));
                String lv = m.optString("live", "");
                if (lv.isEmpty()) {
                    lives.put(JSONObject.NULL);
                } else {
                    hasLive = true;
                    lives.put(DownloadService.httpsify(lv));
                }
            }
        } else {
            // 兜底: 老结构 (payload 里没有 media 时)
            JSONArray imageList = note.optJSONArray("imageList");
            if (imageList == null) imageList = note.optJSONArray("images");
            for (int i = 0; imageList != null && i < imageList.length(); i++) {
                JSONObject item = imageList.optJSONObject(i);
                if (item == null) continue;
                String img = pickImageUrl(item);
                if (img == null || img.isEmpty()) continue;
                images.put(DownloadService.httpsify(img));
                String live = pickLiveVideo(item);
                if (live != null && !live.isEmpty()) {
                    hasLive = true;
                    lives.put(DownloadService.httpsify(live));
                } else {
                    lives.put(JSONObject.NULL);
                }
            }
        }

        String videoUrl = note.optString("video", "");
        if (videoUrl.isEmpty()) videoUrl = pickVideo(note.optJSONObject("video"));

        String title = note.optString("title", "");
        if (title.isEmpty()) title = note.optString("desc", "");
        String author = note.optString("author", "");
        if (author.isEmpty()) {
            JSONObject user = note.optJSONObject("user");
            if (user != null) {
                author = user.optString("nickname", "");
                if (author.isEmpty()) author = user.optString("nickName", "");
            }
        }

        boolean isImagePost = images.length() > 0 && (videoUrl == null || videoUrl.isEmpty());
        if (images.length() == 0 && (videoUrl == null || videoUrl.isEmpty())) {
            throw new Exception("小红书笔记没有可下载的媒体"
                    + (note.optString("diag", "").isEmpty() ? "" : " [" + note.optString("diag") + "]"));
        }

        JSONObject r = new JSONObject();
        r.put("source", "xhs");
        r.put("id", id);
        r.put("title", title);
        r.put("author", author);
        r.put("cover", images.length() > 0 ? images.optString(0) : "");
        r.put("web_url", "https://www.xiaohongshu.com/explore/" + id);
        r.put("images", images);
        r.put("live_videos", lives);
        r.put("has_live_photo", hasLive);
        r.put("is_image_post", isImagePost);
        JSONObject du = new JSONObject();
        JSONArray vids = new JSONArray();
        if (videoUrl != null && !videoUrl.isEmpty()) vids.put(DownloadService.httpsify(videoUrl));
        du.put("video", vids);
        du.put("audio", new JSONArray());
        r.put("direct_urls", du);
        // 字段清单: 便于排查"实况取不到"时到底页面里有什么 (设置里开关控制是否提示)
        r.put("_diag", note.optString("diag", ""));
        return r;
    }

    /** 图片直链。优先级参考两个独立实现 (RSSHub / NEORUAA):
     *  urlDefault (默认原图) > infoList 里 imageScene=WB_DFT 那档 > infoList 任一
     *  > url > url_list 末项 > traceId 拼接。 */
    private static String pickImageUrl(JSONObject item) {
        String d = item.optString("urlDefault", "");
        if (!d.isEmpty()) return d;

        JSONArray infoList = item.optJSONArray("infoList");
        String any = null;
        for (int i = 0; infoList != null && i < infoList.length(); i++) {
            JSONObject o = infoList.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            if (u.isEmpty()) continue;
            if ("WB_DFT".equalsIgnoreCase(o.optString("imageScene", ""))) return u;
            if (any == null) any = u;
        }
        if (any != null) return any;

        String u2 = item.optString("url", "");
        if (!u2.isEmpty()) return u2;

        String ul = lastInUrlList(item.optJSONArray("url_list"));
        if (ul != null) return ul;

        // 兜底: 有些图只有 traceId, 可拼出图床地址
        String tr = item.optString("traceId", "");
        if (!tr.isEmpty()) return "https://sns-img-qc.xhscdn.com/" + tr;
        return null;
    }

    /** 实况照片的视频部分: 挂在 imageList[i].livePhoto 上 (不是 imageList[i].video),
     *  少数结构直接挂在图上, 再兜一层。 */
    private static String pickLiveVideo(JSONObject item) {
        String u = pickVideo(item.optJSONObject("livePhoto"));
        if (u == null || u.isEmpty()) u = pickVideo(item);
        return u;
    }

    /** 视频直链。注意: 结尾只认 masterUrl/master_url, 不认裸 url ——
     *  否则对普通图片调用时会把图片地址当成"视频"。 */
    private static String pickVideo(JSONObject video) {
        if (video == null) return null;
        JSONObject media = video.optJSONObject("media");
        if (media != null) {
            String u = streamUrl(media.optJSONObject("stream"));
            if (u != null) return u;
            JSONObject vs = media.optJSONObject("video_stream");
            if (vs != null) {
                String m = firstNonEmpty(vs, "masterUrl", "master_url");
                if (m != null) return m;
            }
        }
        String u2 = streamUrl(video.optJSONObject("stream"));
        if (u2 != null) return u2;
        String key = originKey(video);
        if (key != null) return key;
        JSONObject pa = video.optJSONObject("play_addr");
        if (pa != null) {
            String u3 = firstInUrlList(pa.optJSONArray("url_list"));
            if (u3 != null) return u3;
        }
        return firstNonEmpty(video, "masterUrl", "master_url");
    }

    /** 从 media.stream 选直链: 优先 H.264 (手机兼容), 并跳过明确带水印的档位。 */
    private static String streamUrl(JSONObject stream) {
        if (stream == null) return null;
        String watermarked = null;
        for (String codec : CODEC_ORDER) {
            JSONArray arr = stream.optJSONArray(codec);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String raw = arr.optString(i, "");
                String u = itemUrl(o, raw);
                if (u == null) continue;
                if (isWatermarked(o)) {
                    if (watermarked == null) watermarked = u;
                } else {
                    return u;
                }
            }
        }
        return watermarked;
    }

    /** stream 里单个档位的直链: masterUrl/master_url > backupUrls/backup_urls > url。 */
    private static String itemUrl(JSONObject o, String raw) {
        if (o == null) return (raw != null && raw.startsWith("http")) ? raw : null;
        String u = firstNonEmpty(o, "masterUrl", "master_url", "url");
        if (u == null) u = firstInUrlList(o.optJSONArray("backupUrls"));
        if (u == null) u = firstInUrlList(o.optJSONArray("backup_urls"));
        return u;
    }

    /** 已知的水印档位标记 (streamType 259/309、streamDesc WEB_LIVEPHOTO_19)。 */
    private static boolean isWatermarked(JSONObject o) {
        if (o == null) return false;
        int st = o.optInt("streamType", 0);
        if (st == 259 || st == 309) return true;
        return "WEB_LIVEPHOTO_19".equalsIgnoreCase(o.optString("streamDesc", ""));
    }

    /** 原片 key → sns-video-bd 直链 (无水印原视频优先)。 */
    private static String originKey(JSONObject video) {
        if (video == null) return null;
        JSONObject consumer = video.optJSONObject("consumer");
        String key = consumer == null ? null : firstNonEmpty(consumer, "originVideoKey");
        if (key == null) key = firstNonEmpty(video, "originVideoKey");
        if (key == null || key.isEmpty()) return null;
        if (key.startsWith("http")) return key;
        return "https://sns-video-bd.xhscdn.com/" + (key.startsWith("/") ? key.substring(1) : key);
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return null;
    }

    private static String firstInUrlList(JSONArray a) {
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            String u = a.optString(i, "");
            if (!u.isEmpty()) return u;
        }
        return null;
    }

    private static String lastInUrlList(JSONArray a) {
        if (a == null) return null;
        String best = null;
        for (int i = 0; i < a.length(); i++) {
            String u = a.optString(i, "");
            if (!u.isEmpty()) best = u;
        }
        return best;
    }
}
