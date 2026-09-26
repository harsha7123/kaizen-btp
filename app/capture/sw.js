// App shell: served from cache instantly, refreshed in the background (stale-while-revalidate).
// API calls are never cached here: offline data lives in the app's IndexedDB queue and localStorage.
const CACHE = 'kaizen-capture-v3'
const SHELL = ['./', 'index.html', 'app.js', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'vendor/jsQR.js']

self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())))
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())
))

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url)
  if (e.request.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(new URL('./', location).pathname)) return
  const key = e.request.mode === 'navigate' ? new URL('./', location).href : e.request // ?eq=P-1042 links open the cached shell
  e.respondWith(caches.open(CACHE).then(async cache => {
    const cached = await cache.match(key, { ignoreSearch: true })
    const fresh = fetch(e.request).then(res => { if (res.ok) cache.put(key, res.clone()); return res })
    if (cached) { e.waitUntil(fresh.catch(() => {})); return cached }
    return fresh
  }))
})
