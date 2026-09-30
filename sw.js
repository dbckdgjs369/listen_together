/**
 * 오프라인 대비. 호스트 핫스팟에만 붙어 있으면 인터넷이 없어서 이 페이지를 못 받는다.
 * 한 번이라도 열어둔 기기는 캐시에서 띄운다.
 *
 * 네트워크 우선이다 — 인터넷이 되면 항상 새 버전을 받고, 안 될 때만 캐시를 쓴다.
 * 캐시 우선으로 두면 roomcode.js 를 고쳐도 옛 규칙으로 계속 풀어서 엉뚱한 주소로 보낸다.
 */
var CACHE = 'lt-site-v3';
var FILES = ['./', './index.html', './roomcode.js?v=2', './manifest.webmanifest'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(FILES); }));
  self.skipWaiting();
});

self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE; })
      .map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (e) {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(
    // no-cache: 브라우저 HTTP 캐시(Pages 는 10분)를 건너뛰고 서버에 새 버전이 있는지 매번 묻는다
    fetch(e.request, { cache: 'no-cache' }).then(function (res) {
      var copy = res.clone();
      caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
      return res;
    }).catch(function () {
      return caches.match(e.request, { ignoreSearch: true });
    })
  );
});
