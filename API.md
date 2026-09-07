# dy-dl API 文档

视频解析服务，主站 **https://dy.haoran.cc**，服务器内网 `127.0.0.1:8788`。
所有响应带 `Access-Control-Allow-Origin: *`，**可直接从任何网页/App 跨域调用**。

## GET /api/parse?url=…

解析一条链接，返回元数据 + 无水印直链。

- `url`（必填）：支持
  - 抖音普通链接 `https://www.douyin.com/video/{id}`
  - 抖音笔记/图集 `/note/{id}`
  - 短链 `https://v.douyin.com/xxxx/`（自动 302 展开）
  - **整段分享文案**（自动正则抠出 `https?://…`）
  - 其他站点 URL → 转交 yt-dlp（受风控限制，见下）
- 限频：每 IP 12 次 / 300 秒（超限 429）
- 结果缓存 10 分钟（响应带 `"cached": true` 表示命中缓存）。**抖音缓存 key 是 aweme_id**：同一视频的长链/短链/分享文本共享一份缓存，不重复请求上游
- 队列去重是前端行为：粘贴多链接时前端按 aweme_id/归一化 URL 去重，无需后端支持

成功响应示例（抖音）：

```json
{
  "source": "douyin",
  "id": "6982497745948921092",
  "title": "这个夏日和小羊@杨超越 一起遇见白色幻想",
  "author": "杨超越工作室",
  "duration": 42.5,                      // 秒
  "cover": "https://p3-pc-sign.douyinpic.com/...",
  "images": [],                          // 图文帖的图片直链数组
  "is_image_post": false,                // true = 图文帖
  "direct_urls": {
    "video": ["https://v3-dy-o.zjcdn.com/...", "..."],   // [0] 恒为最高画质档(码率最高), 其后为 play_addr 兜底
    "audio":  ["https://...", "..."]                       // 原声 BGM
  },
  "slideshow_video_urls": [],            // 仅图文帖: 抖音合成的幻灯片视频(黑屏占位帧, 一般不用)
  // ⚠ 图文帖时 direct_urls.video 恒为 [] (那里的 play_addr 是黑屏占位视频), 取图片走 images[]
  "variants": [ {"quality": "default", "urls": ["..."]}],
  "stats": {"likes": 8009, "comments": 848, "shares": 293, "views": 0},
  "web_url": "https://www.douyin.com/video/...",
  "cached": false
}
```

错误响应：`{"error": "..."}`，HTTP 状态码 400(参数)/404/422(业务错误如视频不存在)/429(限频)/502(上游失败)。

## GET /api/health

`{"ok": true, "ts": 1788570601}`

## POST /admin/cookies

给 yt-dlp 通道喂 cookies（YouTube 等站点风控时用）。Header: `Authorization: Bearer <token>`，token 在服务器 `/opt/dy-dl/admin_token.txt`。Body: `{"cookies": "<Netscape 格式 cookies.txt 全文>"}`。

## 客户端直连下载须知（重要）

- **抖音直链不绑 IP**：浏览器/App 直接 GET `direct_urls.video[0]` 即可下载（需带浏览器 UA，纯 curl 默认 UA 会被 CDN 拒）。客户端应发 HEAD 或 Range 请求试探。
- **⚠ 抖音 CDN 拒绝带第三方 Referer 的请求**（403 "Powered by Byte-nginx"）：网页里点链接/fetch 时如果带 `Referer: 你的站点` 会 403。Web 前端用 `<meta name="referrer" content="no-referrer">`；App 原生请求不带 Referer 即可。
- **抖音全系 CDN（zjcdn 视频域 / douyinpic 图片域 / 音乐域）都回 `Access-Control-Allow-Origin: *`**：Web 端可以 `fetch(url)` → blob → `a.download` 强制保存（页面就是这么做的），Origin 头不触发风控。
- 图文帖（`is_image_post: true`）：图片走 `images[]`（同样不绑 IP，实测跨 IP 206）；封面已自动替换为第一张图（原视频封面是黑屏占位帧）。
- `variants[]`（抖音）：按 **H.264 优先、各自码率降序** 排列；`note` 形如 `540P · 1259kbps · H.264` / `1080P · 2605kbps · H.265`，`filesize` 是按码率×时长估算值，`h265` 布尔字段。
- **⚠ 编码陷阱**：抖音高分辨率档（1080P/720P 高码率）几乎都是 H.265/HEVC——文件本身声画齐全，但很多播放器没有 HEVC 解码器，播起来"只有声音没画面"。因此 `direct_urls.video[0]`（主下载按钮）恒为**码率最高的 H.264 档**，H.265 档放列表后面供高级用户自选。App 版如自带解码内核（如 ijkplayer/ExoPlayer + HEVC 模块）可直接按码率选档。

## 安卓 App（cc.haoran.dydl）

WebView 壳承载本站（UA 追加 `DyDlApp/1.0`），JS 桥 `window.AndroidDyDl`：

- `isApp(): boolean` — 是否在 App 内
- `getClipboard(): string` — 原生剪贴板（WebView clipboard API 权限受限，粘贴入队走这个）
- `saveFile(url, name): boolean` — 交给系统 DownloadManager：通知栏进度、存「下载」目录、**App 退后台/被杀下载不中断**；前端 `download()` 调度器自动选择 桥 → SW `/dl` → blob 三级路径

构建：`E:\Zcode\web\dy-dl\android\build.sh`（无 Gradle，aapt2/javac/d8/zipalign/apksigner 直调，SDK 在 `E:\Tools\android-sdk`，JDK17 Temurin），产物 `dy-dl.apk`（debug 自签 keystore `debug.keystore`，密码 dydl2025——正式分发前换正式签名）。前端检测到 App 环境自动禁用 SW/blob 分支。
- 直链**有时效**（数小时），过期后重新调 `/api/parse` 即可。
- **TikTok 等海外站直链绑 IP/有 CDN 防护**，客户端直连大概率 403——这些站点需要服务器代理下载（当前版本已按“服务器零文件流量”原则移除，未来 App 版可加回）。
- 图集帖 `is_image_post: true`，图片在 `images[]`。

## 未来 App 版预留

- 已有 CORS `*` + JSON API，App 直接 fetch `/api/parse` 即可
- 抖音签名算法在服务器端（`/opt/dy-dl/abogus.py` + `app.py DouyinClient`），客户端零签名逻辑
- 若要代理下载回补：恢复 app.py git 历史（本地 `E:\Zcode\web\dy-dl\`）或重写流式泵，同时补 `MAX_CONCURRENT_DL` 信号量
- cookies 保活：ttwid 自动铸造（每 3 天 cron）+ `/admin/cookies` 手动兜底

## 运维

```bash
ssh -i <your-key> <user>@<your-server>
systemctl status dy-dl                  # 服务状态 (journalctl -u dy-dl -f)
bash /opt/dy-dl/deploy.sh               # 本地部署 (E:\Zcode\web\dy-dl\deploy.sh)
tail /opt/dy-dl/tmp/update.log          # yt-dlp 自更新/ ttwid 保活 cron 日志
# Caddy 块: /opt/ai-daily-news-agent/Caddyfile 尾部 dy.haoran.cc { reverse_proxy 127.0.0.1:8788 }
```
