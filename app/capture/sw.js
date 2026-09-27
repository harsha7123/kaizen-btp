// Page: network first (so an expired BTP login redirects to the sign-in page), cached copy when offline.
// Other shell files: served from cache instantly, refreshed in the background (stale-while-revalidate).
// API calls are never cached here: offline data lives in the app's IndexedDB queue and localStorage.
const CACHE = 'kaizen-capture-v7'
const SHELL = ['./', 'index.html', 'app.js', 'theme.css', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'vendor/jsQR.js']

self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())))
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())
))

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url), scope = new URL('./', location)
  if (e.request.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(scope.pathname)) return
  if (e.request.mode === 'navigate') { // ?eq=P-1042 label links open the same page
    e.respondWith(caches.open(CACHE).then(cache => fetch(e.request)
      .then(res => { if (res.ok && res.type === 'basic') cache.put(scope.href, res.clone()); return res })
      .catch(() => cache.match(scope.href))))
    return
  }
  e.respondWith(caches.open(CACHE).then(async cache => {
    const cached = await cache.match(e.request, { ignoreSearch: true })
    const fresh = fetch(e.request).then(res => { if (res.ok) cache.put(e.request, res.clone()); return res })
    if (cached) { e.waitUntil(fresh.catch(() => {})); return cached }
    return fresh
  }))
})
