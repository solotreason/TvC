/**
 * TvC - Timecard Viewer Service Worker (sw.js)
 * Provides 100% offline capability for iPhone (iOS PWA), Android, and desktop browsers.
 * Caches core application shell, styles, scripts, brand icons, and recent API responses.
 */
const CACHE_NAME = 'tvc-timecard-v1';

const STATIC_PRECACHE = [
  '/',
  '/static/manifest.json',
  '/static/styles.css?v=12',
  '/static/tailwind.min.js',
  '/static/app.js?v=14',
  '/static/android_bridge.js?v=14',
  '/static/favicon.svg',
  '/static/favicon.png',
  '/static/icon-192.png',
  '/static/icon-512.png',
  '/static/logo.svg'
];

// Install: Pre-cache core shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(STATIC_PRECACHE).catch((err) => {
        console.warn('[SW] Pre-cache partial warning:', err);
      });
    }).then(() => self.skipWaiting())
  );
});

// Activate: Clean up outdated caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME) {
            return caches.delete(key);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch: Strategy depending on request type
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // Ignore non-GET requests (e.g. POST /api/sync)
  if (req.method !== 'GET') {
    return;
  }

  // 1. Navigation requests (Opening the app / page refresh) -> Network-first with cached index fallback
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((response) => {
          if (response && response.status === 200) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put('/', clone));
          }
          return response;
        })
        .catch(() => {
          return caches.match('/') || caches.match('/index.html');
        })
    );
    return;
  }

  // 2. API requests -> Network-first with Cache fallback for seamless offline viewing
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req)
        .then((response) => {
          if (response && response.status === 200) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, clone));
          }
          return response;
        })
        .catch(() => {
          return caches.match(req).then((cached) => {
            if (cached) return cached;
            return new Response(
              JSON.stringify({ offline: true, message: 'Offline mode active. Using cached records.' }),
              { headers: { 'Content-Type': 'application/json' } }
            );
          });
        })
    );
    return;
  }

  // 3. Static assets (CSS, JS, Fonts, Images) -> Cache-first with network fallback
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) {
        // Fetch in background to update cache for next time (Stale-While-Revalidate)
        fetch(req).then((networkRes) => {
          if (networkRes && networkRes.status === 200) {
            caches.open(CACHE_NAME).then((cache) => cache.put(req, networkRes));
          }
        }).catch(() => {});
        return cached;
      }
      return fetch(req).then((networkRes) => {
        if (networkRes && networkRes.status === 200) {
          const clone = networkRes.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, clone));
        }
        return networkRes;
      });
    })
  );
});

