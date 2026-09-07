package cc.haoran.dydl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一下载队列。任务两类: parse(分享链接→服务端解析) 与 download(直链→落盘)。
 * 入口: WebView 桥 saveFile / 悬浮球单击 / 剪贴板监控。
 *
 * 安卓12+ 后台不允许启动前台服务: start() 失败时降级为应用内线程直跑
 * (进程存活期间完成下载), 不再抛异常。
 */
public class DownloadService extends Service {

    static final String TAG = "DyDl";
    static final String CH_DL = "dydl_dl";
    static final String CH_FG = "dydl_fg";
    static final String SITE = "https://dy.haoran.cc";
    static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36";
    static final String SUBDIR = "Hrdl";
    static final int DEDUP_MAX = 50;
    private static final Object MOTION_LOG_LOCK = new Object();

    static final Pattern DOUYIN_ID_RE = Pattern.compile(
            "(?:douyin\\.com/(?:video/|note/)|iesdouyin\\.com/share/(?:video|note)/|douyin\\.com/share/(?:video|note)/|modal_id=)(\\d{15,})");
    static final Pattern URL_RE = Pattern.compile("https?://[^\\s\"'<>）)】，。]+");
    static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]");

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final ArrayDeque<String> recentKeys = new ArrayDeque<>();
    private static volatile DownloadService instance;
    private NotificationManager nm;
    private boolean foreground = false;

    // ---------------- 入口 ----------------

    static void enqueueDownload(Context ctx, String url, String name) {
        submit(ctx, url, name, false);
    }

    static void enqueueParse(Context ctx, String raw) {
        submit(ctx, raw, null, true);
    }

    /** 启动服务; 安卓12+ 后台受限抛异常时静默降级 (调用方走应用内线程兜底) */
    private static void start(Context ctx) {
        Intent i = new Intent(ctx, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "fgs start blocked: " + e);
        }
    }

    private static void submit(Context ctx, String a, String b, boolean isParse) {
        // 重复判断: 队列活着时查已入队列表, 重复直接提示并跳过 (不抛异常)
        DownloadService s0 = instance;
        if (s0 != null) {
            boolean dupe = false;
            synchronized (s0.recentKeys) {
                String key = keyOf(a);
                if (s0.recentKeys.contains(key)) dupe = true;
                else {
                    s0.recentKeys.addLast(key);
                    while (s0.recentKeys.size() > DEDUP_MAX) s0.recentKeys.removeFirst();
                }
            }
            if (dupe) {
                Toast.makeText(ctx, "该链接已在下载队列中", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        start(ctx);
        DownloadService s = instance;
        if (s != null) {
            if (isParse) s.submitParse(a);
            else s.submitDownload(a, b);
            return;
        }
        // 服务没起来 (后台启动被系统限制): 应用内线程直跑, 进程存活期间完成
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                if (isParse) parseAndFanout(app, a);
                else doDownload(app, a, sanitize(b));
            } catch (Exception e) {
                Log.e(TAG, "bg task fail", e);
                resultNotify(app, "下载失败", b == null ? a : b,
                        e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            }
        });
        t.start();
    }

    /** 悬浮球角标用 */
    /** 供 WebView 桥调用的动态照片合成下载入口 */
    static void doMotionDownloadPublic(Context app, String imgUrl, String liveUrl, String base) {
        start(app);
        DownloadService s = instance;
        if (s != null) s.doMotionDownload(app, System.nanoTime() % 100000L, imgUrl, liveUrl, base);
        else doMotionDownload(app, 1, imgUrl, liveUrl, base);
    }

    static int activeCount() {
        DownloadService s = instance;
        return s != null ? s.pending.get() : 0;
    }

    // ---------------- 服务生命周期 ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel(this);
        Log.d(TAG, "DownloadService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteForeground(pending.get());
        if (intent != null && intent.hasExtra("url")) {
            if (intent.getBooleanExtra("parse", false))
                submitParse(intent.getStringExtra("url"));
            else
                submitDownload(intent.getStringExtra("url"), intent.getStringExtra("name"));
        }
        return START_NOT_STICKY;
    }

    private void promoteForeground(int queueLen) {
        try {
            Notification n = fgNotify(queueLen);
            startForeground(1, n);
            foreground = true;
        } catch (Exception e) {
            // 安卓12+ 后台启动受限: 降级为普通服务继续跑
            foreground = false;
            Log.w(TAG, "startForeground blocked: " + e);
        }
    }

    private void refreshFg() {
        if (foreground) nm.notify(1, fgNotify(pending.get()));
    }

    private Notification fgNotify(int queueLen) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_FG)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("视频解析下载")
                .setContentText(queueLen > 0 ? "队列 " + queueLen + " 条待处理" : "空闲")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    // ---------------- 任务提交 (服务内) ----------------

    private void submitParse(String raw) {
        pending.incrementAndGet();
        promoteForeground(pending.get());
        exec.execute(() -> {
            try {
                parseAndFanout(getApplicationContext(), raw);
            } catch (Exception e) {
                Log.e(TAG, "parse task fail: " + raw, e);
                resultNotify(getApplicationContext(), "解析失败",
                        raw.length() > 30 ? raw.substring(0, 30) : raw,
                        e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            } finally {
                taskDone();
            }
        });
    }

    private void submitDownload(String url, String name) {
        pending.incrementAndGet();
        promoteForeground(pending.get());
        exec.execute(() -> {
            try {
                doDownload(getApplicationContext(), url, sanitize(name));
            } catch (Exception e) {
                Log.e(TAG, "download task fail: " + url, e);
                resultNotify(getApplicationContext(), "下载失败", name,
                        e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            } finally {
                taskDone();
            }
        });
    }

    private void taskDone() {
        if (pending.decrementAndGet() <= 0) {
            synchronized (recentKeys) { recentKeys.clear(); }
            foreground = false;
            stopForeground(true);
            stopSelf();
            instance = null;
        } else {
            refreshFg();
        }
    }

    // ---------------- 静态任务机 (服务与兜底线程共用) ----------------


    private static void parseAndFanout(Context app, String raw) throws Exception {
        // 直接把整段分享文本交给服务端 (服务端自行提取链接/展开短链/aweme_id 缓存去重)。
        // 不要在 App 端预解析: 手机网络下 followRedirect 结果与服务端不一致, 曾致解析失败。
        if (extractFirstUrl(raw) == null) throw new Exception("文本中没有链接");
        String text = raw.length() > 2000 ? raw.substring(0, 2000) : raw;
        String parseUrl = SITE + "/api/parse?url=" + URLEncoder.encode(text, "UTF-8");
        Log.d(TAG, "parse: " + parseUrl);
        JSONObject info = httpJson(app, parseUrl);
        if (info.has("error")) throw new Exception(info.optString("error", "解析失败"));
        String title = info.optString("title", "video");
        String safeTitle = sanitize(title.isEmpty() ? "video" : title);
        if (info.optBoolean("is_image_post")) {
            JSONArray imgs = info.optJSONArray("images");
            JSONArray lives = info.optJSONArray("live_videos");   // 与 images 对齐, 非 null = live photo mp4
            int n = imgs == null ? 0 : imgs.length();
            for (int i = 0; i < n; i++) {
                String liveUrl = (lives != null && i < lives.length() && !lives.isNull(i))
                        ? lives.getString(i) : null;
                if (liveUrl != null) {
                    // 动态照片: JPEG(注入 GCamera XMP) + 尾部 MP4 → 单文件, 小米/谷歌相册原生识别
                    doMotionDownload(app, (int) (System.nanoTime() % 100000L), imgUrl0(imgs, i),
                            liveUrl, safeTitle + "_" + (i + 1));
                } else {
                    doDownload(app, imgs.getString(i), safeTitle + "_" + (i + 1) + ".jpg");
                }
            }
        } else {
            // direct_urls 是对象 {video:[], audio:[]}, video[0] 恒为最高 H.264 档
            JSONObject du = info.optJSONObject("direct_urls");
            JSONArray vids = du != null ? du.optJSONArray("video") : null;
            String play = (vids != null && vids.length() > 0) ? vids.getString(0) : null;
            if (play == null) throw new Exception("解析结果没有视频直链");
            doDownload(app, play, safeTitle + ".mp4");
        }
    }

    private static String imgUrl0(JSONArray imgs, int i) {
        try { return imgs.getString(i); } catch (Exception e) { return ""; }
    }

    /** 动态照片合成下载: 图(webp/png 自动转 JPEG) + 实况 mp4 → 单文件动态 JPG */
    static void doMotionDownload(Context app, long seq, String imgUrl, String liveUrl, String base) {
        MotionLog log = new MotionLog(app, base);
        try {
            log.put("START", "base=" + base + "\nimgUrl=" + imgUrl + "\nliveUrl=" + liveUrl
                    + "\nandroid=" + android.os.Build.VERSION.RELEASE
                    + "\nmodel=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            notifyProgress(app, (int) seq, base, 0, 0);
            byte[] imgB = fetchBytes(imgUrl);
            log.put("IMAGE_FETCH", "bytes=" + imgB.length + " head=" + hex(imgB, 16));
            byte[] vidB = fetchBytes(liveUrl);
            log.put("VIDEO_FETCH", "bytes=" + vidB.length + " head=" + hex(vidB, 32)
                    + " ftyp=" + indexOf(vidB, "ftyp".getBytes("UTF-8")));
            byte[] jpeg = toJpegBytes(imgB);
            log.put("JPEG", "bytes=" + jpeg.length + " soi=" + hex(jpeg, 2)
                    + " eoi=" + lastIndexOf(jpeg, new byte[]{(byte)0xff, (byte)0xd9}));
            byte[] motion = buildMotionPhoto(jpeg, vidB);
            log.put("MOTION_BUILT", "bytes=" + motion.length
                    + " xmp=" + indexOf(motion, "x:xmpmeta".getBytes("UTF-8"))
                    + " camera=" + indexOf(motion, "GCamera:MotionPhoto".getBytes("UTF-8"))
                    + " ftyp=" + indexOf(motion, "ftyp".getBytes("UTF-8"))
                    + " motion_marker=" + indexOf(motion, "MotionPhoto_Data".getBytes("UTF-8"))
                    + " tail_ftyp=" + tailHasFtyp(motion, vidB.length));
            // 硬校验：没有 XMP/视频盒就绝不把伪动态图标记为成功
            if (indexOf(motion, "GCamera:MotionPhoto".getBytes("UTF-8")) < 0
                    || indexOf(motion, "x:xmpmeta".getBytes("UTF-8")) < 0
                    || indexOf(motion, "ftyp".getBytes("UTF-8")) < 0) {
                throw new Exception("动态照片校验失败: XMP 或 MP4 缺失");
            }
            String name = sanitize(base) + ".jpg";
            boolean ok;
            if (Build.VERSION.SDK_INT >= 29) {
                ok = writeViaMediaStore(app, (int) seq, name, "image/jpeg",
                        new ByteArrayInputStream(motion), motion.length);
            } else {
                ok = writeLegacyFile(app, (int) seq, name, "image/jpeg",
                        new ByteArrayInputStream(motion));
            }
            log.put("STORE", "ok=" + ok + " name=" + name);
            log.put("DONE", ok ? "SUCCESS" : "FAILED");
            log.close();
            resultNotify(app, ok ? "✓ 已保存动态照片 DCIM/" + SUBDIR : "动态照片保存失败",
                    name, ok ? null : "写入失败");
        } catch (Exception e) {
            log.put("EXCEPTION", stack(e));
            log.put("DONE", "FAILED");
            log.close();
            Log.e(TAG, "motion build fail", e);
            // 合成失败兜底: 静态图与实况 mp4 分开保存
            try {
                doDownload(app, imgUrl, sanitize(base) + ".jpg");
                doDownload(app, liveUrl, sanitize(base) + "_live.mp4");
                resultNotify(app, "动态图合成失败, 已分开保存", base, e.getMessage());
            } catch (Exception e2) {
                resultNotify(app, "下载失败", base, e2.getMessage());
            }
        }
    }

    /** 诊断日志已关闭 (v2.16)。如需重新开启, 恢复本类写 Download/dy-dl-motion-*.log */
    static class MotionLog {
        MotionLog(Context app, String base) { }
        void put(String tag, String text) { }
        void close() { }
    }

    static String stack(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02x", b[i] & 255));
        return s.toString();
    }
    static int lastIndexOf(byte[] data, byte[] needle) {
        for (int i = data.length - needle.length; i >= 0; i--) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) if (data[i+j] != needle[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }
    static boolean tailHasFtyp(byte[] data, int videoLen) {
        return videoLen > 0 && videoLen <= data.length && indexOf(java.util.Arrays.copyOfRange(data, data.length-videoLen, data.length), "ftyp".getBytes()) >= 0;
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        InputStream in = c.getInputStream();
        try { return readAll(in, done -> { }); } finally { in.close(); c.disconnect(); }
    }

    /** 无超限保护 (用户明确要求): 全尺寸合成 */
    private static byte[] toJpegBytes(byte[] img) throws Exception {
        if (img.length >= 2 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8) return img;
        Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
        if (bmp == null) throw new Exception("图片解码失败");
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, jpg);
        bmp.recycle();
        return jpg.toByteArray();
    }

    /** 动态照片字节结构: 完全对齐小米真实文件 (MVIMG 样本逐字节照抄)
     *  [JPEG 截至最后 FFD9][实况 MP4 原样紧贴 (自带 ftyp)]
     *  XMP: 仅 MotionPhoto/MotionPhotoVersion/MotionPhotoPresentationTimestampUs 三属性
     *       (无 MicroVideo 系、无 MotionPhoto_Data 标记 — 与小米一致)
     *       Container: Primary 无 Length; MotionPhoto 带 Length+Padding=0 */
    private static int indexOf(byte[] data, byte[] needle) {
        outer: for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] buildMotionPhoto(byte[] jpeg, byte[] video) {
        int eoi = lastIndexOf(jpeg, new byte[]{(byte) 0xff, (byte) 0xd9});
        if (eoi < 0) throw new IllegalArgumentException("JPEG EOI missing");
        jpeg = java.util.Arrays.copyOf(jpeg, eoi + 2);
        StringBuilder x = new StringBuilder(4096);
        x.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>");
        x.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">");
        x.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">");
        x.append("<rdf:Description rdf:about=\"\"");
        x.append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"");
        x.append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"");
        x.append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"");
        x.append(" GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"");
        x.append(" GCamera:MotionPhotoPresentationTimestampUs=\"0\">");
        x.append("<Container:Directory><rdf:Seq>");
        x.append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\"/></rdf:li>");
        x.append("<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"").append(video.length);
        x.append("\" Item:Padding=\"0\"/></rdf:li></rdf:Seq></Container:Directory>");
        x.append("</rdf:Description></rdf:RDF></x:xmpmeta>");
        String xmp = x.toString();
        try {
            byte[] payload = ("http://ns.adobe.com/xap/1.0/" + (char) 0 + xmp).getBytes("UTF-8");
            if (payload.length + 2 > 65535) throw new IllegalArgumentException("XMP too large");
            byte[] seg = new byte[payload.length + 4];
            seg[0] = (byte) 0xff; seg[1] = (byte) 0xe1;
            int sl = payload.length + 2;
            seg[2] = (byte) (sl >> 8); seg[3] = (byte) sl;
            System.arraycopy(payload, 0, seg, 4, payload.length);
            int pos = 2;
            while (pos + 4 <= jpeg.length && (jpeg[pos] & 255) == 255) {
                int marker = jpeg[pos + 1] & 255;
                if (marker == 0xda || (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc)) break;
                int l = ((jpeg[pos + 2] & 255) << 8) | (jpeg[pos + 3] & 255);
                if (l < 2) break;
                pos += 2 + l;
            }
            byte[] out = new byte[jpeg.length + seg.length + video.length];
            System.arraycopy(jpeg, 0, out, 0, pos);
            System.arraycopy(seg, 0, out, pos, seg.length);
            System.arraycopy(jpeg, pos, out, pos + seg.length, jpeg.length - pos);
            System.arraycopy(video, 0, out, jpeg.length + seg.length, video.length);
            if (indexOf(out, "x:xmpmeta".getBytes("UTF-8")) < 0 || indexOf(out, "GCamera:MotionPhoto".getBytes("UTF-8")) < 0)
                throw new IllegalArgumentException("XMP injection verification failed");
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void doDownload(Context app, String url, String name) {
        HttpURLConnection conn = null;
        boolean saved = false;
        String err = null;
        int seq = (int) (System.currentTimeMillis() % 100000000L) + 100;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                err = "HTTP " + code + (code == 403 ? " (直链过期, 请重新解析)" : "");
            } else {
                final long len = conn.getContentLengthLong();
                InputStream in = conn.getInputStream();
                if (mimeOf(name).startsWith("image/")) {
                    final String nmAtParse = name;
                    ImageResult ir = saveImage(app, seq, name, readAll(in, done ->
                            notifyProgress(app, (int) seq, nmAtParse, done, len)));
                    saved = ir.ok;
                    name = ir.name;
                } else if (Build.VERSION.SDK_INT >= 29) {
                    saved = writeViaMediaStore(app, (int) seq, name, mimeOf(name), in, len);
                } else {
                    saved = writeLegacyFile(app, (int) seq, name, mimeOf(name), in);
                }
                in.close();
                if (!saved) err = "存储写入失败";
            }
        } catch (Exception e) {
            Log.e(TAG, "download fail: " + url, e);
            err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            if (conn != null) conn.disconnect();
        }
        resultNotify(app, saved ? "✓ 已保存 DCIM/" + SUBDIR : "下载失败",
                name, saved ? null : err);
    }

    interface ProgressCb { void accept(long done); }

    static byte[] readAll(InputStream in, ProgressCb cb) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[65536];
        long done = 0;
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
            done += n;
            cb.accept(done);
        }
        return buf.toByteArray();
    }

    static class ImageResult { boolean ok; String name; }

    private static ImageResult saveImage(Context app, int seq, String rawName, byte[] data) {
        ImageResult ir = new ImageResult();
        String type = sniff(data);
        String stem = rawName.lastIndexOf('.') > 0
                ? rawName.substring(0, rawName.lastIndexOf('.')) : rawName;
        if ("jpeg".equals(type)) {
            ir.ok = writeImage(app, seq, stem + ".jpg", "image/jpeg",
                    new ByteArrayInputStream(data), data.length);
        } else if ("webp".equals(type) || "png".equals(type)) {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp != null) {
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                Bitmap out = bmp;
                if ("png".equals(type)) {
                    Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
                    flat.eraseColor(Color.WHITE);
                    Canvas c = new Canvas(flat);
                    c.drawBitmap(bmp, 0, 0, null);
                    out = flat;
                }
                out.compress(Bitmap.CompressFormat.JPEG, 95, jpg);
                bmp.recycle();
                ir.ok = writeImage(app, seq, stem + ".jpg", "image/jpeg",
                        new ByteArrayInputStream(jpg.toByteArray()), jpg.size());
            } else {
                ir.ok = writeImage(app, seq, stem + "." + type, "image/" + type,
                        new ByteArrayInputStream(data), data.length);
            }
        } else {
            ir.ok = writeImage(app, seq, stem + ".bin", "application/octet-stream",
                    new ByteArrayInputStream(data), data.length);
        }
        ir.name = rawName;
        return ir;
    }

    private static boolean writeImage(Context app, int seq, String name, String mime, InputStream in, long len) {
        if (Build.VERSION.SDK_INT >= 29) return writeViaMediaStore(app, seq, name, mime, in, len);
        return writeLegacyFile(app, seq, name, mime, in);
    }

    private static boolean writeViaMediaStore(Context app, int seq, String name, String mime, InputStream in, long len) {
        boolean isVideo = mime.startsWith("video/");
        boolean isAudio = mime.startsWith("audio/");
        Uri collection;
        if (isVideo) collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        else if (isAudio) collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        else if (mime.startsWith("image/")) collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        else collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = isVideo ? Environment.DIRECTORY_DCIM + "/" + SUBDIR
                : (isAudio ? Environment.DIRECTORY_MUSIC + "/" + SUBDIR
                : (mime.startsWith("image/") ? Environment.DIRECTORY_DCIM + "/" + SUBDIR
                : Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR));
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = app.getContentResolver().insert(collection, cv);
        if (uri == null) { Log.e(TAG, "insert null " + rel); return false; }
        boolean ok;
        try (OutputStream os = app.getContentResolver().openOutputStream(uri)) {
            ok = pump(in, os, done -> notifyProgress(app, seq, name, done, len));
        } catch (Exception e) {
            Log.e(TAG, "media store write", e);
            ok = false;
        }
        if (ok) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            app.getContentResolver().update(uri, done, null, null);
        } else {
            try { app.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
        }
        return ok;
    }

    private static boolean writeLegacyFile(Context app, int seq, String name, String mime, InputStream in) {
        boolean isImg = mime.startsWith("image/");
        File base = isImg
                ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base, SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) return false;
        File f = new File(dir, name);
        int n = 1;
        while (f.exists()) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            f = new File(dir, stem + " (" + (n++) + ")" + ext);
        }
        boolean ok;
        try (OutputStream os = new FileOutputStream(f)) {
            ok = pump(in, os, done -> notifyProgress(app, seq, name, done, 0));
            if (ok) {
                Intent i = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                i.setData(Uri.fromFile(f));
                app.sendBroadcast(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "legacy write", e);
            f.delete();
            ok = false;
        }
        return ok;
    }

    private static boolean pump(InputStream in, OutputStream os, ProgressCb cb) throws Exception {
        byte[] buf = new byte[65536];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            done += n;
            cb.accept(done);
        }
        os.flush();
        return done > 0;
    }

    // ---------------- 网络/解析工具 ----------------

    private static JSONObject httpJson(Context app, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            return new JSONObject(buf.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }


    static String extractFirstUrl(String text) {
        if (text == null) return null;
        if (text.startsWith("http")) return text.trim();
        Matcher m = URL_RE.matcher(text);
        return m.find() ? m.group().replaceAll("[.,;]+$", "") : null;
    }

    static String extractDouyinId(String url) {
        Matcher m = DOUYIN_ID_RE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String keyOf(String raw) {
        String id = extractDouyinId(raw == null ? "" : raw);
        if (id != null) return "dy:" + id;
        String u = raw == null ? "" : raw.replaceAll("[?&](utm_[^&]+|spm=[^&]+)", "").replaceAll("/+$", "");
        return "u:" + u;
    }

    static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "video.mp4";
        String n = ILLEGAL.matcher(name.trim()).replaceAll("_");
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = (dot > 0 && n.length() - dot <= 8) ? n.substring(dot) : ".mp4";
            n = n.substring(0, 110) + ext;
        }
        return n;
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    static String sniff(byte[] b) {
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        if (b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8) return "jpeg";
        return "unknown";
    }

    // ---------------- 通知 ----------------

    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_FG) == null) {
            NotificationChannel fg = new NotificationChannel(CH_FG, "下载队列",
                    NotificationManager.IMPORTANCE_MIN);
            fg.setShowBadge(false);
            nm.createNotificationChannel(fg);
        }
        if (nm.getNotificationChannel(CH_DL) == null) {
            NotificationChannel dl = new NotificationChannel(CH_DL, "下载结果",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(dl);
        }
    }

    static void notifyProgress(Context app, int id, String name, long done, long total) {
        try {
            ensureChannel(app);
            NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CH_DL)
                    : new Notification.Builder(app);
            b.setSmallIcon(android.R.drawable.stat_sys_download)
             .setContentTitle(name)
             .setOnlyAlertOnce(true)
             .setAutoCancel(true);
            if (total > 0) {
                b.setProgress(100, (int) Math.min(100, done * 100 / total), false)
                 .setContentText((done / 1048576) + " / " + (total / 1048576) + " MB");
            } else {
                b.setProgress(0, 0, true).setContentText((done / 1048576) + " MB");
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {}
    }

    static void resultNotify(Context app, String title, String name, String err) {
        try {
            ensureChannel(app);
            NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CH_DL)
                    : new Notification.Builder(app);
            b.setSmallIcon(err == null ? android.R.drawable.stat_sys_download_done
                                       : android.R.drawable.stat_notify_error)
             .setContentTitle(title)
             .setContentText(err == null ? name : name + " — " + err)
             .setAutoCancel(true)
             .setContentIntent(PendingIntent.getActivity(app, 0,
                     new Intent(app, MainActivity.class),
                     PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            nm.notify((int) (System.currentTimeMillis() % 100000000L), b.build());
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
