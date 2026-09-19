#!/usr/bin/env python3
"""dy-dl: 通用视频解析服务 (dy.haoran.cc)

双通道:
- douyin.com / iesdouyin.com → 内置 a_bogus 签名直调抖音 web API (免 cookie 免登录)
- 其他 URL → yt-dlp (yt-dlp -J 解析 / yt-dlp -o - 代下载)

纯 Python 标准库, 无 web 框架依赖。abogus.py 需要 gmssl (pip3 install gmssl)。
"""
import html
import ipaddress
import json
import os
import random
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from abogus import ABogus  # noqa: E402

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
YTDLP = os.path.join(BASE_DIR, 'bin', 'yt-dlp')
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')

PARSE_TTL = 600          # 解析结果缓存 10 分钟
PARSE_TIMEOUT = 60       # yt-dlp -J 超时
RATE_LIMIT = (12, 300)   # 每 IP 每 300 秒最多 12 次解析
_parse_cache = {}
_parse_cache_lock = threading.Lock()
_rate_lock = threading.Lock()
_rate_map = {}


def _rate_ok(ip):
    now = time.time()
    limit, window = RATE_LIMIT
    with _rate_lock:
        # 防内存无限增长: 表过大时清掉过期 IP
        if len(_rate_map) > 1024:
            for k in [k for k, q in _rate_map.items() if not q or now - q[-1] > window]:
                _rate_map.pop(k, None)
        q = [t for t in _rate_map.get(ip, []) if now - t < window]
        if len(q) >= limit:
            _rate_map[ip] = q
            return False
        q.append(now)
        _rate_map[ip] = q
        return True


def _public_target(url):
    """SSRF 防护: 只允许公网 http/https 目标"""
    try:
        p = urllib.parse.urlsplit(url)
        if p.scheme not in ('http', 'https') or not p.hostname:
            return False
        infos = socket.getaddrinfo(p.hostname, p.port or (443 if p.scheme == 'https' else 80),
                                   proto=socket.IPPROTO_TCP)
        for info in infos:
            if ipaddress.ip_address(info[4][0]).is_private or \
               ipaddress.ip_address(info[4][0]).is_loopback or \
               ipaddress.ip_address(info[4][0]).is_link_local or \
               ipaddress.ip_address(info[4][0]).is_reserved:
                return False
        return True
    except Exception:
        return False


def _gen_verify_fp():
    return 'verify_' + ''.join(random.choice('0123456789abcdefghijklmnopqrstuvwxyz')
                               for _ in range(36))


def _get_ttwid():
    """向 bytedance 注册设备铸 ttwid; 失败回退读 cookies.txt"""
    body = json.dumps({
        'region': 'cn', 'aid': 1768, 'needFid': False,
        'service': 'www.ixigua.com',
        'migrate_info': {'ticket': '', 'source': 'node'},
        'cbUrlProtocol': 'https', 'union': True,
    }, separators=(',', ':')).encode()
    req = urllib.request.Request(
        'https://ttwid.bytedance.com/ttwid/union/register/', data=body,
        headers={'Content-Type': 'application/json', 'User-Agent': UA})
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        for c in (resp.headers.get_all('Set-Cookie') or []):
            if c.startswith('ttwid='):
                return c.split(';', 1)[0][len('ttwid='):]
    except Exception:
        pass
    try:
        with open(os.path.join(BASE_DIR, 'cookies.txt'), encoding='utf-8') as f:
            m = re.search(r'ttwid\t([^\s]+)', f.read())
            if m:
                return m.group(1)
    except Exception:
        pass
    return ''


class DouyinClient:
    _ttwid = None
    _ttwid_at = 0.0
    _lock = threading.Lock()
    _browser_cookie = None       # App 上传的完整浏览器 cookie (含 uifid/msToken)
    _browser_ck_at = 0.0

    @classmethod
    def _cookie(cls):
        with cls._lock:
            # 优先: App 上传的完整浏览器 cookie (解决 Argus uifid 403)
            ck_file = os.path.join(BASE_DIR, 'browser_cookies.txt')
            try:
                mt = os.path.getmtime(ck_file)
                if cls._browser_cookie is None or mt != cls._browser_cookie:
                    with open(ck_file, encoding='utf-8') as f:
                        txt = f.read().strip()
                    if txt and 'ttwid' in txt:
                        cls._browser_cookie = txt
                        cls._browser_cookie = txt
                        cls._ttwid = None
                        cls._ttwid_at = 0
                cls._browser_mtime = mt
            except FileNotFoundError:
                cls._browser_cookie = None
            if cls._browser_cookie:
                return cls._browser_cookie
            if not cls._ttwid or time.time() - cls._ttwid_at > 20 * 86400:
                cls._ttwid = _get_ttwid()
                cls._ttwid_at = time.time()
            return cls._ttwid

    WEB_PARAMS = {
        'device_platform': 'webapp', 'aid': '6383', 'channel': 'channel_pc_web',
        'pc_client_type': '1', 'version_code': '170400', 'version_name': '17.4.0',
        'cookie_enabled': 'true', 'screen_width': '1920', 'screen_height': '1080',
        'browser_language': 'zh-CN', 'browser_platform': 'Win32',
        'browser_name': 'Chrome', 'browser_version': '126.0.0.0',
        'browser_online': 'true', 'engine_name': 'Blink', 'engine_version': '126.0.0.0',
        'os_name': 'Windows', 'os_version': '10', 'cpu_core_num': '12',
        'device_memory': '8', 'platform': 'PC', 'downlink': '10',
        'effective_type': '4g', 'round_trip_time': '50',
    }

    @classmethod
    def _signed_get(cls, path, extra, _retries=4):
        last_err = None
        for attempt in range(_retries):
            try:
                return cls._signed_get_once(path, extra)
            except urllib.error.HTTPError as e:
                body = ''
                try:
                    body = e.read().decode(errors='ignore')[:120]
                except Exception:
                    pass
                last_err = e
                # Argus 偶发风控: 换签名+指纹, 拉大间隔重试
                if e.code == 403 and attempt < _retries - 1:
                    time.sleep(1.2 * (attempt + 1))
                    continue
                raise
        raise last_err

    @classmethod
    def _build_cookie_header(cls):
        """完整浏览器 cookie (App 上传) 优先; 否则 ttwid+verify 兜底"""
        ck = cls._cookie()
        if cls._browser_cookie and ck == cls._browser_cookie:
            return ck   # 完整 cookie 直接用
        return f'ttwid={ck}; s_v_web_id={_gen_verify_fp()}'

    @classmethod
    def _signed_get_once(cls, path, extra):
        params = {**cls.WEB_PARAMS, **extra}
        ab = ABogus()
        # 签名输入与请求 URL 必须逐字节一致: 统一用 quote
        qs = '&'.join(f'{k}={urllib.parse.quote(str(v), safe="")}' for k, v in params.items())
        a_bogus = ab.get_value(qs)
        url = 'https://www.douyin.com' + path + '?' + qs + '&a_bogus=' + urllib.parse.quote(a_bogus, safe='')
        req = urllib.request.Request(url, headers={
            'User-Agent': UA,
            'Referer': 'https://www.douyin.com/',
            'Cookie': cls._build_cookie_header(),
        })
        return json.loads(urllib.request.urlopen(req, timeout=20).read().decode())

    @classmethod
    def resolve_id(cls, aweme_id):
        data = cls._signed_get('/aweme/v1/web/aweme/detail/', {'aweme_id': aweme_id})
        detail = data.get('aweme_detail')
        if not detail:
            reason = (data.get('filter_detail') or {}).get('filter_reason') or '视频不存在或已删除'
            raise ValueError(reason)
        return cls._normalize(detail)

    @classmethod
    def _normalize(cls, d):
        video = d.get('video') or {}
        music = d.get('music') or {}
        author = d.get('author') or {}
        stats = d.get('statistics') or {}
        images = []
        live_videos = []          # 与 images 对齐: 实况照片(live photo)的 mp4 直链, 静态图为 None
        for im in d.get('images') or []:
            url = ''
            for cand in sorted(im.get('url_list') or [], key=len, reverse=True):
                url = cand
                break
            if url:
                images.append(url)
                # live photo: image 对象内嵌 video 子对象 (实况 mp4)
                lv = ''
                imv = im.get('video') or {}
                for cand in ((imv.get('play_addr') or {}).get('url_list') or []):
                    lv = cand
                    break
                live_videos.append(lv or None)
            else:
                live_videos.append(None)
        has_live = any(x for x in live_videos)

        # 直链 (play_addr 无水印; bit_rate 里有带码率的多档)
        direct = []
        for u in (video.get('play_addr') or {}).get('url_list') or []:
            if u not in direct:
                direct.append(u)

        variants = []
        seen_gears = set()
        dur = (video.get('duration') or 0) / 1000  # API 是毫秒

        def _gear_label(ga, br_rate, h265):
            codec = 'H.265' if h265 else 'H.264'
            if not ga:
                return f'{br_rate // 1000}kbps {codec}' if br_rate else f'默认 {codec}'
            s, prefix = ga, ''
            for p, cn in (('adapt', '自适应'), ('higher', '高'), ('low', '低码率')):
                if s.startswith(p):
                    prefix = cn
                    s = s[len(p):].lstrip('_')
                    break
            m = re.search(r'\d+', s)
            base = prefix + m.group(0) + 'P' if m else (prefix + s).strip()
            return f'{base} · {br_rate // 1000}kbps · {codec}' if br_rate else f'{base} · {codec}'

        for br in video.get('bit_rate') or []:
            ga = br.get('gear_name') or ''
            br_rate = br.get('bit_rate') or 0  # bps
            h265 = bool(br.get('is_h265'))
            label = _gear_label(ga, br_rate, h265)
            if label in seen_gears:
                continue
            urls = ((br.get('play_addr') or {}).get('url_list') or [])[:1]
            if urls:
                seen_gears.add(label)
                variants.append({
                    'note': label,
                    'ext': 'mp4',
                    'bit_rate': br_rate,
                    'h265': h265,
                    'filesize': int(br_rate * dur / 8) if br_rate and dur else None,
                    'urls': urls,
                })
        # 兼容性优先: H.265 档很多播放器没解码器(下载后只有声音没画面), 主按钮固定 H.264
        # 策略: variants 排序 = 先 H.264 后 H.265, 各自按码率降序; direct_urls.video[0] = H.264 最高码率档
        variants.sort(key=lambda v: (v['h265'], -(v['bit_rate'] or 0)))
        if variants:
            h264_best = next((v for v in variants if not v['h265']), variants[0])
            best_urls = h264_best['urls']
            direct = best_urls + [u for u in direct if u not in best_urls]

        is_note = bool(images)
        return {
            'source': 'douyin',
            'id': d.get('aweme_id'),
            'title': d.get('desc') or '(无标题)',
            'author': author.get('nickname') or '',
            'author_id': author.get('unique_id') or author.get('sec_uid') or '',
            'duration': round((video.get('duration') or 0) / 1000, 1),
            # 图文帖的视频封面是黑屏占位帧, 用第一张图
            'cover': images[0] if is_note and images else ((video.get('cover') or {}).get('url_list') or [''])[0],
            'images': images,               # 图文帖的图片直链
            'live_videos': live_videos,     # 与 images 对齐; 非 None = 该图为 live photo 的 mp4
            'has_live_photo': has_live,
            'is_image_post': is_note,
            # 图文帖的 play_addr 是黑屏占位视频, 不放进 direct_urls.video
            'direct_urls': {
                'video': [] if is_note else direct,
                'audio': [u for u in (music.get('play_url') or {}).get('url_list') or []],
            },
            'slideshow_video_urls': direct if is_note else [],
            'variants': variants,
            'stats': {
                'likes': stats.get('digg_count'), 'comments': stats.get('comment_count'),
                'shares': stats.get('share_count'), 'views': stats.get('play_count'),
            },
            'web_url': f"https://www.douyin.com/video/{d.get('aweme_id')}",
        }


DOUYIN_ID_RE = re.compile(r'(?:douyin\.com/(?:video/|note/)|iesdouyin\.com/share/(?:video|note)/|douyin\.com/share/(?:video|note)/|modal_id=)(\d{15,})')


def mobile_feed_parse(aweme_id):
    """移动端 Feed 通道: 免 Argus / 免 cookie / 免签名, 直出 JSON (~200ms)
    伪装抖音 Android App 的 Cronet UA, 走 App 端推荐流协议"""
    url = ('https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=' + aweme_id +
           '&aid=1128&version_code=290101&app_language=zh&channel=googleplay'
           '&device_type=Pixel+4&os_version=10&device_brand=Google&device_model=Pixel+4'
           '&device_platform=android&resolution=1080*1920&os_api=29&ssmix=a'
           '&manifest_version_code=290101&aweme_type=0&openudid=null')
    ua = ('com.ss.android.ugc.aweme/290101 (Linux; U; Android 10; zh_CN; Pixel 4; '
          'Build/QQ3A.200805.001; Cronet/TTNetVersion:5f9037be 2023-01-13)')
    req = urllib.request.Request(url, headers={'User-Agent': ua})
    resp = urllib.request.urlopen(req, timeout=20)
    data = json.loads(resp.read().decode())
    al = data.get('aweme_list') or data.get('aweme_details') or []
    if not al:
        raise ValueError('移动端 Feed 无结果: ' + str(data.get('status_code', '')))
    return al[0]


def extract_douyin_id(raw):
    m = DOUYIN_ID_RE.search(raw)
    return m.group(1) if m else None


def resolve_shortlink(url):
    """v.douyin.com 短链 → 302 后的 aweme_id (抖音对 HEAD 返回 403, 必须 GET)"""
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        final = resp.geturl()
        resp.close()
    except urllib.error.HTTPError as e:
        final = e.geturl() if hasattr(e, 'geturl') else url
    return final


def _ytcookies_arg():
    """cookies.txt 存在则传给 yt-dlp (cookie 按 domain 隔离, 不会串站)"""
    p = os.path.join(BASE_DIR, 'cookies.txt')
    return ['--cookies', p] if os.path.isfile(p) else []


def ytdlp_json(url):
    if not _public_target(url):
        raise ValueError('目标地址不允许')
    cmd = [YTDLP, '-J', '--no-warnings', '--no-playlist', '--socket-timeout', '20',
           *_ytcookies_arg(), url]
    proc = subprocess.run(cmd, capture_output=True, timeout=PARSE_TIMEOUT,
                          env={**os.environ, 'LC_ALL': 'C.UTF-8'})
    try:
        info = json.loads(proc.stdout.decode('utf-8', errors='replace'))
    except json.JSONDecodeError:
        info = None
    if proc.returncode != 0 or not isinstance(info, dict):
        err = proc.stderr.decode('utf-8', errors='replace').strip()
        raise ValueError(_clean_err(err) or 'yt-dlp 无输出')
    return normalize_ytdlp(info)


def _clean_err(err):
    m = re.search(r'ERROR:\s*(.+)', err)
    msg = m.group(1) if m else err
    return msg[:400].strip()


def normalize_ytdlp(info):
    fmts = info.get('formats') or []
    fmts.sort(key=lambda f: (f.get('height') or 0, f.get('fps') or 0, f.get('abr') or 0))
    variants = []
    direct = {'video': [], 'audio': []}
    for f in fmts:
        if not f.get('url') or f.get('vcodec') == 'none' and not f.get('acodec') or f.get('format_id') in {v['id'] for v in variants}:
            continue
        entry = {
            'id': f.get('format_id'), 'ext': f.get('ext'), 'height': f.get('height'),
            'fps': f.get('fps'), 'abr': f.get('abr'), 'filesize': f.get('filesize') or f.get('filesize_approx'),
            'note': f.get('format_note'), 'url': f['url'], 'vcodec': f.get('vcodec'), 'acodec': f.get('acodec'),
        }
        variants.append(entry)
        if f.get('vcodec') != 'none' and f.get('acodec') != 'none':
            direct['video'].append(f['url'])
        elif f.get('vcodec') == 'none' and f.get('acodec') != 'none':
            direct['audio'].append(f['url'])
    best = variants[-5:]  # 只留最高 5 档
    best.reverse()
    return {
        'source': 'yt-dlp',
        'extractor': info.get('extractor_key'),
        'id': info.get('id'), 'title': info.get('title') or '(无标题)',
        'author': info.get('uploader') or info.get('channel') or '',
        'duration': info.get('duration') or 0,
        'cover': info.get('thumbnail') or '',
        'web_url': info.get('webpage_url') or '',
        'variants': best,
        'direct_urls': direct,
        'images': [], 'is_image_post': False, 'stats': {},
        'live': bool(info.get('is_live')),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = 'dy-dl/1.0'
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        sys.stderr.write('[%s] %s\n' % (self.log_date_time_string(), fmt % args))

    def _send(self, code, body, ctype='application/json; charset=utf-8', extra=None, cors=True):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        if cors:
            self.send_header('Access-Control-Allow-Origin', '*')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _client_ip(self):
        xff = self.headers.get('X-Forwarded-For')
        if xff:
            return xff.split(',')[0].strip()
        return self.client_address[0]

    # ---------- routes ----------

    STATIC_FILES = {
        '/sw.js': 'application/javascript; charset=utf-8',
        '/manifest.webmanifest': 'application/manifest+json',
        '/icon-192.png': 'image/png',
        '/icon-512.png': 'image/png',
        '/apple-touch-icon.png': 'image/png',
    }

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        try:
            if path == '/' or path == '/index.html':
                self._serve_index()
            elif path in self.STATIC_FILES:
                self._serve_static(path)
            elif path == '/api/parse':
                self._api_parse(parsed)
            elif path == '/api/sync_cookie':
                self._api_sync_cookie()
            elif path == '/api/health':
                self._send(200, {'ok': True, 'ts': int(time.time())})
            else:
                self._send(404, {'error': 'not found'})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self._send(500, {'error': f'服务器内部错误: {e}'})

    def _serve_static(self, path):
        # 固定白名单, 无路径拼接, 无穿越风险
        try:
            with open(os.path.join(BASE_DIR, 'static', path.lstrip('/')), 'rb') as f:
                cc = 'no-store' if path == '/sw.js' else 'public, max-age=3600'
                self._send(200, f.read(), self.STATIC_FILES[path], {'Cache-Control': cc})
        except FileNotFoundError:
            self._send(404, {'error': 'not found'})

    def _serve_index(self):
        try:
            with open(os.path.join(BASE_DIR, 'static', 'index.html'), 'rb') as f:
                self._send(200, f.read(), 'text/html; charset=utf-8')
        except FileNotFoundError:
            self._send(500, {'error': 'static/index.html missing'})

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == '/admin/cookies':
            self._admin_cookies()
        else:
            self._send(404, {'error': 'not found'}, cors=False)

    def do_OPTIONS(self):
        # CORS 预检: 未来 App/第三方前端可直接跨域调用 /api/*
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def _api_parse(self, parsed):
        ip = self._client_ip()
        if not _rate_ok(ip):
            self._send(429, {'error': '请求太频繁，稍后再试'})
            return
        qs = urllib.parse.parse_qs(parsed.query)
        raw = (qs.get('url') or [''])[0].strip()
        if not raw:
            self._send(400, {'error': '缺少 url 参数'})
            return
        if len(raw) > 2048:
            self._send(400, {'error': '链接过长'})
            return
        # 支持直接粘贴分享文本
        m = re.search(r'https?://[^\s"\'<>）)】，。]+', raw)
        if not m:
            self._send(400, {'error': '未找到链接'})
            return
        url = m.group(0).rstrip('.,;')

        cache_key = url
        with _parse_cache_lock:
            hit = _parse_cache.get(cache_key)
            if hit and time.time() - hit[0] < PARSE_TTL:
                self._send(200, {**hit[1], 'cached': True})
                return

        try:
            result, cache_key = self._resolve_any(url)
        except ValueError as e:
            self._send(422, {'error': str(e)})
            return
        except subprocess.TimeoutExpired:
            self._send(504, {'error': '解析超时'})
            return
        except urllib.error.HTTPError as e:
            try:
                body = json.loads(e.read().decode(errors='ignore'))
                msg = body.get('status_msg') or body.get('filter_reason') or f'上游 HTTP {e.code}'
            except Exception:
                msg = f'上游 HTTP {e.code}'
            self._send(502, {'error': f'抖音接口错误: {msg}'})
            return
        except Exception as e:
            self._send(502, {'error': f'解析失败: {e}'})
            return

        with _parse_cache_lock:
            _parse_cache[cache_key] = (time.time(), result)
            if len(_parse_cache) > 256:
                for k in sorted(_parse_cache, key=lambda k: _parse_cache[k][0])[:64]:
                    _parse_cache.pop(k, None)
        self._send(200, result)

    def _resolve_any(self, url):
        """入口: 短链展开 → 路由到抖音通道或 yt-dlp; 返回 (result, cache_key)"""
        if 'douyin.com' in url or 'iesdouyin.com' in url:
            if 'v.douyin.com' in url:
                url = resolve_shortlink(url)
            aweme_id = extract_douyin_id(url)
            if not aweme_id:
                raise ValueError('无法从链接中识别出抖音视频 ID')
            # 缓存按 aweme_id: 同一视频的长链/短链/分享文本命中同一份, 不重复请求抖音
            ck = f'dy:{aweme_id}'
            with _parse_cache_lock:
                hit = _parse_cache.get(ck)
                if hit and time.time() - hit[0] < PARSE_TTL:
                    return {**hit[1], 'cached': True}, ck
            # 通道 1: 移动端 Feed (免 Argus / 免 cookie / 免签名, ~200ms)
            try:
                aweme = mobile_feed_parse(aweme_id)
                return DouyinClient._normalize(aweme), ck
            except Exception:
                pass
            # 通道 2: Web Detail API (需要签名+cookie)
            return DouyinClient.resolve_id(aweme_id), ck
        return ytdlp_json(url), f'u:{url}'

    # ---------- admin ----------

    def _api_sync_cookie(self):
        """App 端上传的完整浏览器 cookie (含 uifid/msToken) → browser_cookies.txt
        频率限制: 每 IP 每小时 2 次; 只覆盖写入, 不回读"""
        ip = self._client_ip()
        if not _rate_ok(ip):
            self._send(429, {'error': '请求太频繁'}, cors=False)
            return
        length = int(self.headers.get('Content-Length') or 0)
        if not length or length > 512 * 1024:
            self._send(400, {'error': 'bad body'}, cors=False)
            return
        body = self.rfile.read(length).decode('utf-8', errors='replace')
        try:
            data = json.loads(body)
            ck = data['browser_cookies']
            if not ck or 'ttwid' not in ck:
                self._send(400, {'error': 'missing ttwid'}, cors=False)
                return
            ck_file = os.path.join(BASE_DIR, 'browser_cookies.txt')
            open(ck_file, 'w', encoding='utf-8').write(ck)
            DouyinClient._ttwid = None
            DouyinClient._browser_cookie = None   # 强制重载
            self._send(200, {'ok': True}, cors=False)
        except Exception as e:
            self._send(400, {'error': str(e)}, cors=False)

    def _admin_cookies(self):
        tok_path = os.path.join(BASE_DIR, 'admin_token.txt')
        try:
            with open(tok_path, encoding='utf-8') as f:
                expect = f.read().strip()
        except FileNotFoundError:
            self._send(404, {'error': 'admin disabled'})
            return
        auth = self.headers.get('Authorization') or ''
        if auth != f'Bearer {expect}' or not expect:
            self._send(401, {'error': 'unauthorized'})
            return
        length = int(self.headers.get('Content-Length') or 0)
        if not length or length > 256 * 1024:
            self._send(400, {'error': 'bad body size'})
            return
        body = self.rfile.read(length).decode('utf-8', errors='replace')
        try:
            data = json.loads(body)
            cookie_text = data['cookies']  # 期望 Netscape 格式全文
            open(os.path.join(BASE_DIR, 'cookies.txt'), 'w', encoding='utf-8').write(cookie_text)
            DouyinClient._ttwid = None  # 强制下次重建
            self._send(200, {'ok': True}, cors=False)  # 管理端点不开跨域
        except Exception as e:
            self._send(400, {'error': str(e)}, cors=False)


def main():
    addr = ('127.0.0.1', int(os.environ.get('DYDL_PORT', '8788')))
    srv = ThreadingHTTPServer(addr, Handler)
    srv.daemon_threads = True
    sys.stderr.write(f'dy-dl listening on {addr[0]}:{addr[1]}\n')
    srv.serve_forever()


if __name__ == '__main__':
    main()
