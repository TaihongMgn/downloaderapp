#!/usr/bin/env python3
"""v3.2 服务端:
1) DouyinClient 优先使用 browser_cookies.txt (App 上传的完整浏览器 cookie, 含 uifid/msToken)
2) 新增 POST /api/sync_cookie (App 专用, 频率限制, 无 token — cookie 只被用作解析源)
3) /admin/cookies 兼容 browser_cookies 字段
"""
import io
import re

p = r'E:\Zcode\web\dy-dl\app.py'
s = io.open(p, encoding='utf-8').read()

# 1) DouyinClient 加 browser cookie 加载
old = '''class DouyinClient:
    _ttwid = None
    _ttwid_at = 0.0
    _lock = threading.Lock()

    @classmethod
    def _cookie(cls):
        with cls._lock:
            if not cls._ttwid or time.time() - cls._ttwid_at > 20 * 86400:
                cls._ttwid = _get_ttwid()
                cls._ttwid_at = time.time()
            return cls._ttwid'''
new = '''class DouyinClient:
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
            return cls._ttwid'''
assert old in s, 'cookie anchor'
s = s.replace(old, new)

# _signed_get_once: Cookie 头改为优先完整浏览器 cookie
old2 = """            'Cookie': f'ttwid={cls._cookie()}; s_v_web_id={_gen_verify_fp()}',"""
new2 = '''            'Cookie': cls._build_cookie_header(),'''
assert old2 in s, 'cookie header anchor'
s = s.replace(old2, new2)

# 新增 _build_cookie_header
old3 = '''    @classmethod
    def _signed_get_once(cls, path, extra):'''
new3 = '''    @classmethod
    def _build_cookie_header(cls):
        """完整浏览器 cookie (App 上传) 优先; 否则 ttwid+verify 兜底"""
        ck = cls._cookie()
        if cls._browser_cookie and ck == cls._browser_cookie:
            return ck   # 完整 cookie 直接用
        return f'ttwid={ck}; s_v_web_id={_gen_verify_fp()}'

    @classmethod
    def _signed_get_once(cls, path, extra):'''
assert old3 in s, 'build_cookie anchor'
s = s.replace(old3, new3)

# 新增 /api/sync_cookie 端点 (App 专用, 频率限制 1/次/10s/IP)
old4 = '''            elif path == '/api/health':'''
new4 = '''            elif path == '/api/sync_cookie':
                self._api_sync_cookie()
            elif path == '/api/health':'''
assert old4 in s, 'route anchor'
s = s.replace(old4, new4)

# sync_cookie 处理方法 (POST body: {"browser_cookies": "a=1; b=2;..."})
old5 = '''    def _admin_cookies(self):'''
new5 = '''    def _api_sync_cookie(self):
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

    def _admin_cookies(self):'''
assert old5 in s, 'sync cookie anchor'
s = s.replace(old5, new5)

io.open(p, 'w', encoding='utf-8').write(s)
print('app.py patched')
