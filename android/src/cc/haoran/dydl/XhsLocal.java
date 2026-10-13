package cc.haoran.dydl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
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

    /** 页面内取数脚本: 在 __INITIAL_STATE__ 等根对象上做 BFS, 命中目标笔记后原样交回。
     *  返回对象而非字符串, 避免 evaluateJavascript 回调里的二次转义。 */
    private static final String EXTRACT_JS =
            "(function(){"
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
            + "return {note:d.note,comments:d.comments||null,url:location.href};}"
            + "if(v.noteData&&v.noteData.data&&v.noteData.data.noteData"
            + "&&v.noteData.data.noteData.noteId===want)"
            + "return {note:v.noteData.data.noteData,comments:v.noteData.data.commentData||null,url:location.href};"
            + "if(v.noteId===want&&(v.imageList||v.video||v.desc!==undefined))"
            + "return {note:v,url:location.href};"
            + "for(var k in v){try{var c=v[k];if(c&&typeof c==='object')queue.push(c);}catch(e){}}"
            + "}"
            + "return {error:'note_state_unavailable'};"
            + "})();";

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

    /** 是否已有小红书登录会话 (供设置页显示状态)。 */
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

        // 短链展开: 失败也无妨, WebView 自己会跟着跳转
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
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];

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

                final boolean[] fired = {false};
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        if (fired[0]) return;
                        fired[0] = true;
                        if (u != null && u.contains("/login")) {
                            err[0] = "小红书需要先登录 (设置 → 小红书登录)";
                            latch.countDown();
                            return;
                        }
                        // 首帧后再等一会, 确保页面把 __INITIAL_STATE__ 填好
                        main.postDelayed(() -> {
                            try {
                                view.evaluateJavascript(EXTRACT_JS, value -> {
                                    out[0] = value;
                                    latch.countDown();
                                });
                            } catch (Throwable t) {
                                err[0] = "小红书页面脚本执行失败: " + t.getMessage();
                                latch.countDown();
                            }
                        }, 1200);
                    }
                });
                wv.loadUrl(pageUrl);
            } catch (Throwable t) {
                err[0] = "小红书解析初始化失败: " + t.getMessage();
                latch.countDown();
            }
        });

        boolean ok;
        try {
            ok = latch.await(35, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        main.post(() -> {
            try {
                if (holder[0] != null) {
                    holder[0].stopLoading();
                    holder[0].destroy();
                }
            } catch (Exception ignored) {
            }
        });

        if (!ok) throw new Exception("小红书解析超时, 请检查网络后重试");
        if (err[0] != null) throw new Exception(err[0]);
        String raw = out[0];
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            throw new Exception("小红书页面没有返回数据 (笔记可能已删除或受限)");
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new Exception("小红书数据解析失败: " + e.getMessage());
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

        String id = note.optString("noteId", "");
        if (id == null || id.isEmpty()) id = fallbackId == null ? "" : fallbackId;

        JSONArray imageList = note.optJSONArray("imageList");
        if (imageList == null) imageList = note.optJSONArray("images");

        JSONArray images = new JSONArray();
        JSONArray lives = new JSONArray();
        boolean hasLive = false;
        for (int i = 0; imageList != null && i < imageList.length(); i++) {
            JSONObject item = imageList.optJSONObject(i);
            if (item == null) continue;
            String img = pickImageUrl(item);
            if (img == null || img.isEmpty()) continue;
            images.put(img);
            String live = pickLiveVideo(item);
            if (live != null && !live.isEmpty()) {
                hasLive = true;
                lives.put(live);
            } else {
                lives.put(JSONObject.NULL);
            }
        }

        String videoUrl = pickVideo(note.optJSONObject("video"));

        String title = note.optString("title", "");
        if (title == null || title.isEmpty()) title = note.optString("desc", "");
        String author = "";
        JSONObject user = note.optJSONObject("user");
        if (user != null) {
            author = user.optString("nickname", "");
            if (author.isEmpty()) author = user.optString("nickName", "");
        }

        boolean isImagePost = images.length() > 0 && (videoUrl == null || videoUrl.isEmpty());

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
        if (videoUrl != null && !videoUrl.isEmpty()) vids.put(videoUrl);
        du.put("video", vids);
        du.put("audio", new JSONArray());
        r.put("direct_urls", du);
        return r;
    }

    /** 图片直链: infoList 最后一个非空 url > urlDefault/url > url_list 最后一个。 */
    private static String pickImageUrl(JSONObject item) {
        JSONArray infoList = item.optJSONArray("infoList");
        String best = null;
        for (int i = 0; infoList != null && i < infoList.length(); i++) {
            JSONObject o = infoList.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            if (!u.isEmpty()) best = u;
        }
        if (best != null) return best;
        String d = item.optString("urlDefault", "");
        if (!d.isEmpty()) return d;
        String u2 = item.optString("url", "");
        if (!u2.isEmpty()) return u2;
        return lastInUrlList(item.optJSONArray("url_list"));
    }

    /** 实况照片的视频部分: video.media.video_stream.master_url > media.stream > play_addr。 */
    private static String pickLiveVideo(JSONObject item) {
        JSONObject v = item.optJSONObject("video");
        if (v == null) return null;
        String u = pickVideo(v);
        return u;
    }

    /** 视频直链: 优先 H.264 (手机兼容), 再 h265/av1/h266; 最后回退 play_addr。 */
    private static String pickVideo(JSONObject video) {
        if (video == null) return null;
        JSONObject media = video.optJSONObject("media");
        if (media != null) {
            JSONObject vs = media.optJSONObject("video_stream");
            if (vs != null) {
                String u = vs.optString("master_url", "");
                if (!u.isEmpty()) return u;
            }
            JSONObject stream = media.optJSONObject("stream");
            if (stream != null) {
                String[] order = {"h264", "h265", "av1", "h266"};
                for (String codec : order) {
                    String u = firstMasterUrl(stream.optJSONArray(codec));
                    if (u != null) return u;
                }
            }
        }
        String pa = firstUrlList(video.optJSONObject("play_addr"));
        if (pa != null) return pa;
        return video.optString("master_url", "");
    }

    private static String firstMasterUrl(JSONArray arr) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("master_url", "");
            if (!u.isEmpty()) return u;
            String bu = firstInUrlList(o.optJSONArray("backup_urls"));
            if (bu != null) return bu;
            String pa = firstUrlList(o.optJSONObject("play_addr"));
            if (pa != null) return pa;
        }
        return null;
    }

    private static String firstUrlList(JSONObject o) {
        if (o == null) return null;
        return firstInUrlList(o.optJSONArray("url_list"));
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
