#!/usr/bin/env python3
"""在服务器上复刻签名请求逐步调试 403"""
import sys, json, random, urllib.request, urllib.parse, urllib.error
sys.path.insert(0, '/opt/dy-dl')
from urllib.parse import quote
from abogus import ABogus
from app import _get_ttwid, _gen_verify_fp, UA

ttwid = _get_ttwid()
print('ttwid len:', len(ttwid), 'head:', ttwid[:30])
verify_fp = _gen_verify_fp()
params = {
    'aweme_id': '7655163834785981115',
    'device_platform': 'webapp', 'aid': '6383', 'channel': 'channel_pc_web',
    'pc_client_type': '1', 'version_code': '170400', 'version_name': '17.4.0',
    'cookie_enabled': 'true', 'screen_width': '1920', 'screen_height': '1080',
    'browser_language': 'zh-CN', 'browser_platform': 'Win32',
    'browser_name': 'Chrome', 'browser_version': '126.0.0.0',
    'browser_online': 'true', 'engine_name': 'Blink', 'engine_version': '126.0.0.0',
    'os_name': 'Windows', 'os_version': '10', 'cpu_core_num': '12',
    'device_memory': '8', 'platform': 'PC', 'downlink': '10', 'effective_type': '4g',
    'round_trip_time': '50',
}
ab = ABogus()
a_bogus = quote(ab.get_value(params), safe='')
url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?' + urllib.parse.urlencode(params) + '&a_bogus=' + a_bogus
req = urllib.request.Request(url, headers={
    'User-Agent': UA, 'Referer': 'https://www.douyin.com/',
    'Cookie': f'ttwid={ttwid}; s_v_web_id={verify_fp}',
})
try:
    resp = urllib.request.urlopen(req, timeout=20)
    print('STATUS:', resp.status)
    print('BODY:', resp.read().decode()[:200])
except urllib.error.HTTPError as e:
    print('HTTP:', e.code)
    print('HEADERS:', dict(e.headers))
    print('BODY:', e.read().decode(errors='ignore')[:300])
