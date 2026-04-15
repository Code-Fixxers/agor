const SW_VERSION = 'agor-v2';
const APP_SHELL_CACHE = `${SW_VERSION}-shell`;

// Derive the base path from the SW's own URL so this works whether the UI is
// served from "/" (dev) or "/ui/" (production daemon static mount).
const BASE = new URL('./', self.location.href).pathname;
const APP_SHELL_FILES = [BASE, `${BASE}index.html`, `${BASE}manifest.webmanifest`];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(APP_SHELL_CACHE).then((cache) =>
      // Best-effort: if one asset 404s (e.g. a renamed file after an upgrade)
      // we don't want the whole install to fail and leave users on a stale SW.
      Promise.all(
        APP_SHELL_FILES.map((file) =>
          cache.add(file).catch((err) => {
            console.warn('[sw] Failed to precache', file, err);
          })
        )
      )
    )
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((key) => !key.startsWith(SW_VERSION)).map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Only handle same-origin requests that live under our base path (the UI bundle).
  // Everything else — cross-origin, daemon API (/sessions, /tasks, ...), and
  // crucially Socket.IO polling/upgrade traffic at /socket.io/* — is left
  // entirely to the browser. Intercepting those would break realtime fallback.
  if (url.origin !== self.location.origin || !url.pathname.startsWith(BASE)) {
    return;
  }

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match(`${BASE}index.html`)));
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(APP_SHELL_CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      });
    })
  );
});
