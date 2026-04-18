const SW_VERSION = 'agor-v2';
const APP_SHELL_CACHE = `${SW_VERSION}-shell`;
const BASE_PATH = new URL(self.registration.scope).pathname.replace(/\/$/, '');

function withBase(path) {
  const normalized = path.startsWith('/') ? path : `/${path}`;
  return `${BASE_PATH}${normalized}` || '/';
}

const APP_SHELL_FILES = [
  withBase('/'),
  withBase('/index.html'),
  withBase('/manifest.webmanifest'),
  withBase('/favicon.png'),
  withBase('/icons/agor-icon.svg'),
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(APP_SHELL_CACHE).then((cache) => cache.addAll(APP_SHELL_FILES)));
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
  const url = new URL(request.url);

  if (request.method !== 'GET') return;

  if (
    url.pathname.startsWith('/sessions') ||
    url.pathname.startsWith('/tasks') ||
    url.pathname.startsWith('/messages') ||
    url.pathname.startsWith('/worktrees')
  ) {
    event.respondWith(fetch(request));
    return;
  }

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match(withBase('/index.html'))));
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response.ok && url.origin === self.location.origin) {
          const copy = response.clone();
          caches.open(APP_SHELL_CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      });
    })
  );
});
