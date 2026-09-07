/* dy-dl service worker
 * 核心能力: /dl?u=<cdn直链>&n=<文件名> → 附件化流式响应
 * 浏览器把它交给系统下载管理器: 带文件名、通知栏进度、存到下载目录、支持后台/杀进程不中断
 */
const EXT_MAP = {
  'image/webp': 'webp', 'image/jpeg': 'jpg', 'image/png': 'png',
  'image/heic': 'heic', 'image/heif': 'heif', 'image/avif': 'avif',
  'audio/mpeg': 'mp3', 'audio/mp4': 'm4a', 'audio/aac': 'aac', 'audio/ogg': 'ogg',
  'video/mp4': 'mp4', 'video/webm': 'webm',
};

self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (u.origin !== self.location.origin) return;   // 只拦同源
  if (u.pathname === '/dl') e.respondWith(handleDl(u));
  // 其它请求一律直连网络 (刻意不缓存页面/接口, 避免版本陈旧)
});

async function handleDl(u) {
  const target = u.searchParams.get('u') || '';
  const name = (u.searchParams.get('n') || 'download').slice(0, 100);
  if (!/^https:\/\//i.test(target)) return new Response('bad target', { status: 400 });

  let up;
  try {
    // 抖音 CDN 拒第三方 Referer, 必须显式 no-referrer
    up = await fetch(target, { redirect: 'follow', referrerPolicy: 'no-referrer' });
  } catch (err) {
    return new Response('upstream fetch failed: ' + err, { status: 502 });
  }
  if (!(up.ok || up.status === 206)) return new Response('upstream HTTP ' + up.status, { status: 502 });

  const ct = (up.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  const ext = EXT_MAP[ct] || 'mp4';
  const h = new Headers();
  h.set('Content-Type', 'application/octet-stream');
  h.set('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(name)}.${ext}`);
  const cl = up.headers.get('content-length');
  if (cl) h.set('Content-Length', cl);
  // 流式透传: 不在内存攒整个文件, 下载管理器边拉边写盘
  return new Response(up.body, { status: 200, headers: h });
}
