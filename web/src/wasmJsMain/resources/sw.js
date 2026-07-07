/*
 * StreetComplete Web — service worker.
 *
 * Milestone M5 groundwork of docs/pwa-port/ROADMAP.md: caches the app shell and the
 * Wasm/Skia bundle so the installed PWA launches offline after its first successful load.
 *
 * Strategy:
 *  - install:   pre-cache the small, stable app shell (host page, manifest, icons).
 *  - activate:  drop caches from older versions.
 *  - fetch:
 *      * navigations   -> network-first, fall back to the cached shell when offline.
 *      * same-origin GET (the hashed .js/.wasm bundle, icons, …) -> stale-while-revalidate,
 *        so the bundle is served instantly and refreshed in the background. Because the
 *        bundle filenames are content-hashed, caching them at runtime (rather than by name)
 *        keeps this worker correct across rebuilds.
 *      * everything else -> straight to the network.
 *
 * Bump CACHE_VERSION whenever the shell files below change so old caches are evicted.
 */
const CACHE_VERSION = "streetcomplete-web-v1";

// Small, stable files that are safe to pre-cache by name.
const APP_SHELL = [
  "./",
  "./index.html",
  "./manifest.webmanifest",
  "./icons/icon.svg",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => cache.addAll(APP_SHELL))
  );
  // Activate this worker as soon as it has finished installing.
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((key) => key !== CACHE_VERSION).map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;

  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // let cross-origin requests pass through

  // Navigation requests: try the network first so updates are picked up, but fall back to
  // the cached shell so the app still launches offline.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(() =>
        caches.match("./index.html").then((cached) => cached || caches.match("./"))
      )
    );
    return;
  }

  // Static assets (bundle, icons, …): stale-while-revalidate.
  event.respondWith(
    caches.open(CACHE_VERSION).then((cache) =>
      cache.match(request).then((cached) => {
        const network = fetch(request)
          .then((response) => {
            if (response && response.ok) cache.put(request, response.clone());
            return response;
          })
          .catch(() => cached);
        return cached || network;
      })
    )
  );
});
