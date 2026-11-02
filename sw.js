/* 校园通 service worker —— 离线缓存应用外壳。
 * 改了代码记得把 VERSION 加一，否则手机上还是旧版本。
 */
const VERSION = 'campus-v1';
const SHELL = [
  './',
  './index.html',
  './app.css',
  './manifest.webmanifest',
  './assets/icon-192.png',
  './assets/icon-512.png',
  './src/main.js',
  './src/util/date.js',
  './src/util/store.js',
  './src/util/format.js',
  './src/features/timetable/data.js',
  './src/features/timetable/view.js',
  './src/features/expense/view.js',
  './src/features/expense/import.js',
  './src/features/todo/view.js',
  './src/features/settings/view.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.match(req, { ignoreSearch: true }).then((hit) => {
      if (hit) return hit;
      return fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(VERSION).then((cache) => cache.put(req, copy)).catch(() => {});
        return res;
      }).catch(() => caches.match('./index.html'));
    })
  );
});
