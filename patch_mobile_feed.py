#!/usr/bin/env python3
"""v3.2: 移动端 Feed 通道 (免 Argus / 免 cookie / 免签名)"""
import io

p = r'E:\Zcode\web\dy-dl\app.py'
s = io.open(p, encoding='utf-8').read()

# 1) 加 mobile_feed_parse 函数 (插在 extract_douyin_id 前)
mobile_feed = '''def mobile_feed_parse(aweme_id):
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


def extract_douyin_id(raw):'''
old1 = 'def extract_douyin_id(raw):'
if 'mobile_feed_parse' not in s:
    assert old1 in s, 'extract anchor'
    s = s.replace(old1, mobile_feed, 1)

# 2) _resolve_any: 优先移动端 Feed
old2 = '''            # 缓存按 aweme_id: 同一视频的长链/短链/分享文本命中同一份, 不重复请求抖音
            ck = f'dy:{aweme_id}'
            with _parse_cache_lock:
                hit = _parse_cache.get(ck)
                if hit and time.time() - hit[0] < PARSE_TTL:
                    return {**hit[1], 'cached': True}, ck
            return DouyinClient.resolve_id(aweme_id), ck'''
new2 = '''            # 缓存按 aweme_id: 同一视频的长链/短链/分享文本命中同一份, 不重复请求抖音
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
            return DouyinClient.resolve_id(aweme_id), ck'''
assert old2 in s, '_resolve_any anchor'
s = s.replace(old2, new2)

io.open(p, 'w', encoding='utf-8').write(s)
print('mobile feed channel added')

# 版本 v3.2/30
p3 = r'E:\Zcode\web\dy-dl\android\AndroidManifest.xml'
s3 = io.open(p3, encoding='utf-8').read()
s3 = s3.replace('android:versionCode="31"', 'android:versionCode="32"').replace('android:versionName="3.3"', 'android:versionName="3.2"')
io.open(p3, 'w', encoding='utf-8').write(s3)
p4 = r'E:\Zcode\web\dy-dl\android\build.sh'
s4 = io.open(p4, encoding='utf-8').read()
s4 = s4.replace('--version-code 31 --version-name 3.3', '--version-code 32 --version-name 3.2')
io.open(p4, 'w', encoding='utf-8').write(s4)
print('v3.2 patched')
