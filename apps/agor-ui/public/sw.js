/**
 * Agor service worker.
 *
 * The UI may be hosted at the origin root (`/`) or under a sub-path
 * (typically `/ui/` when the daemon serves the production bundle). We
 * derive the scope dynamically from `self.registration.scope` so the
 * same SW works in both setups.
 *
 * Strategy:
 *  - Pre-cache the app shell (index.html + manifest + icon) so cold launches
 *    in standalone PWA mode never see a blank screen when offline.
 *  - Navigation requests use **network-first** with a cached-shell fallback,
 *    so users always pick up the latest deploy when online.
 *  - Static assets use stale-while-revalidate so they load instantly while
 *    a fresh copy is fetched in the background.
 *  - API and live data (sessions/tasks/messages/worktrees/auth) bypass the
 *    cache entirely so we never serve stale data.
 *  - WebSocket and EventSource traffic is never touched.
 */

const SW_VERSION = 'agor-v3';
const APP_SHELL_CACHE = `${SW_VERSION}-shell`;
const ASSET_CACHE = `${SW_VERSION}-assets`;

// Derive the base path from the SW scope. When the SW is registered at
// `/ui/sw.js` the scope is `https://host/ui/`, so basePath becomes `/ui/`.
const scopeUrl = new URL(self.registration.scope);
const basePath = scopeUrl.pathname.endsWith('/') ? scopeUrl.pathname : `${scopeUrl.pathname}/`;

const APP_SHELL_FILES = [
  basePath,
  `${basePath}index.html`,
  `${basePath}manifest.webmanifest`,
  `${basePath}favicon.png`,
  `${basePath}icons/agor-icon.svg`,
];

// API/data prefixes that must never be cached.
const NO_CACHE_PREFIXES = [
  'sessions',
  'tasks',
  'messages',
  'worktrees',
  'boards',
  'board-comments',
  'repos',
  'users',
  'mcp-servers',
  'gateway-channels',
  'artifacts',
  'cards',
  'card-types',
  'authentication',
  'authentication-refresh',
  'config',
  'instance-config',
  'auth-config',
  'permissions',
  'reports',
  'logs',
  'health',
  'socket.io',
  'mcp',
  'static',
  'api',
  'opencode',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(APP_SHELL_CACHE).then((cache) =>
      // Best-effort: don't fail the entire install if a single asset is missing
      Promise.all(
        APP_SHELL_FILES.map((url) =>
          cache.add(new Request(url, { cache: 'reload' })).catch(() => undefined)
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

/**
 * Detect API/data requests that should always go to the network.
 */
function isNoCacheRequest(url) {
  if (url.origin !== self.location.origin) return true;
  const pathname = url.pathname;
  // strip basePath if present so rules apply uniformly in `/ui/` and `/`
  const stripped = pathname.startsWith(basePath)
    ? pathname.slice(basePath.length)
    : pathname.slice(1);
  const firstSegment = stripped.split('/')[0];
  if (!firstSegment) return false;
  return NO_CACHE_PREFIXES.includes(firstSegment);
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  let url;
  try {
    url = new URL(request.url);
  } catch {
    return;
  }

  // Never touch WS upgrade or EventSource
  if (request.headers.get('upgrade') === 'websocket') return;
  if (request.headers.get('accept') === 'text/event-stream') return;

  // API/data: bypass cache entirely
  if (isNoCacheRequest(url)) {
    event.respondWith(fetch(request));
    return;
  }

  // Navigation: network-first with cached shell fallback
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          // Cache a copy of the latest shell for offline fallback
          if (response.ok) {
            const copy = response.clone();
            caches
              .open(APP_SHELL_CACHE)
              .then((cache) => cache.put(`${basePath}index.html`, copy))
              .catch(() => undefined);
          }
          return response;
        })
        .catch(() =>
          caches
            .match(`${basePath}index.html`)
            .then((cached) => cached || caches.match(basePath))
            .then((cached) => cached || Response.error())
        )
    );
    return;
  }

  // Static assets: stale-while-revalidate
  if (url.origin === self.location.origin) {
    event.respondWith(
      caches.match(request).then((cached) => {
        const networkFetch = fetch(request)
          .then((response) => {
            if (response.ok) {
              const copy = response.clone();
              caches
                .open(ASSET_CACHE)
                .then((cache) => cache.put(request, copy))
                .catch(() => undefined);
            }
            return response;
          })
          .catch(() => cached || Response.error());
        return cached || networkFetch;
      })
    );
  }
});

// Allow the page to trigger an immediate update when a new SW is waiting.
self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
