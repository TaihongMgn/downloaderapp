# dy-dl 视频解析下载

个人自用的一站式视频解析下载工具：**Web 前端 + 轻量后端 + 安卓 App** 三端同源。

## 功能

- **抖音解析**：自研 a_bogus 签名通道（免 cookie 免登录），无水印直链、实况照片（live photo）支持
- **动态照片合成**：App 内将 静态图 + 实况 MP4 合成为单文件动态 JPG（小米/谷歌相册原生识别）
- **队列下载**：粘贴整段分享文案自动提取链接入队，串行解析下载，三重去重
- **剪贴板自动监控**（App）：无障碍检测复制动作 + 隐形悬浮窗抢焦点读取，复制后自动入队
- **悬浮球**：桌面小球显示队列角标，单击读剪贴板入队，可拖拽贴边
- **yt-dlp 通用通道**：TikTok 等其他站点解析（YouTube/B 站等有 IP 风控，需注入 cookies）

## 三端架构

```
Web 前端 (Vite 风格单文件)  ─┐
安卓 App (WebView 壳+原生)  ─┼──→ 后端 app.py (Python 标准库, 127.0.0.1:8788)
                            │      ├─ /api/parse   抖音签名通道 / yt-dlp
悬浮球/剪贴板 (App 原生)    ─┘      └─ /api/health
```

- **服务端**：`app.py` 单文件（a_bogus 签名 + ttwid 自动铸造 + yt-dlp 调用），Nginx/Caddy 反代 + HTTPS
- **App**：WebView 壳 + 原生前台服务（统一下载队列）+ 悬浮球 + 无障碍剪贴板监控 + 动态照片合成（XMP 注入）
- **下载方式**：直链由客户端直连 CDN（抖音 CDN 不绑 IP），服务器零文件流量

## 目录结构

```
app.py              服务端（含抖音 DouyinClient 签名通道）
static/index.html   Web 前端（队列/结果卡/图集/动态图提示）
static/sw.js        Service Worker（浏览器端系统下载兜底）
API.md              API 对接文档（含 App 桥接口说明）
deploy.sh           服务端部署脚本
android/            安卓 App（无 Gradle 裸构建，aapt2/javac/d8/apksigner）
  build.sh          一键构建出 APK
  src/...           MainActivity/SettingsActivity/DownloadService/
                    FloatBallService/ClipboardWatcherService
```

## 快速开始

服务端：

```bash
pip3 install gmssl pillow
python3 app.py          # 监听 127.0.0.1:8788, 用 Caddy/Nginx 反代 + HTTPS
```

安卓 App：

```bash
cd android
bash build.sh           # 需要 Android SDK + JDK17, 产物 dy-dl.apk
```

Web 前端直接静态托管 `static/`（需 HTTPS 以启用剪贴板 API 与 SW）。

## 已知限制

- YouTube/Bilibili 等站点在数据中心 IP 下有登录墙/IP 风控（可在 `/admin/cookies` 注入 cookies 解决）
- TikTok 直链绑解析服务器 IP，客户端直连不可用（需代理下载，本服务按设计不提供）
- 苹果 Live Photo 为双文件标准（HEIC+MOV 靠 UUID 配对），单文件动态 JPG 仅安卓相册（小米/谷歌）原生识别
- 安卓 10+ 后台 App 无法直接读剪贴板（系统限制），App 采用 无障碍事件 + 隐形悬浮窗抢焦点 方案

## 签名

仓库不含签名密钥。`android/build.sh` 首次构建会自动生成 `debug.keystore`（密码 dydl2025，仅测试用），正式分发请替换为自有密钥。
