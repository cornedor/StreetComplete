# `:web` — StreetComplete PWA (walking skeleton)

This module is milestone **M0** of the [PWA port roadmap](../docs/pwa-port/ROADMAP.md):
a minimal **Compose Multiplatform for Web (Kotlin/Wasm)** app that proves the web
toolchain end to end before the larger port work begins.

## Why it's isolated from `:app`

M0 deliberately does **not** depend on `:app`. Adding a `wasmJs` target directly to
`:app` would force the entire shared `commonMain` codebase — and every one of its
dependencies and `expect`/`actual` declarations — to compile for Wasm, which is the
work of M1 onward, not a walking skeleton. Keeping this module separate lets the web
build succeed today without touching (or risking) the Android/iOS build.

Reuse of the shared `:app` code begins in **M1** (see the roadmap).

## Run it locally

Requires JDK 21 (same as the rest of the project). The first run downloads a Node.js
toolchain used by the Kotlin/Wasm browser tooling.

```bash
# Start a hot-reloading dev server (opens http://localhost:8080)
./gradlew :web:wasmJsBrowserDevelopmentRun

# Produce an optimized static bundle in web/build/dist/wasmJs/productionExecutable
./gradlew :web:wasmJsBrowserDistribution

# Just compile (no Node/browser tooling needed)
./gradlew :web:compileKotlinWasmJs
```

## What's here

| File | Purpose |
|---|---|
| `build.gradle.kts` | wasmJs target + Compose for Web wiring |
| `src/wasmJsMain/kotlin/.../Main.kt` | entry point; mounts Compose into the page |
| `src/wasmJsMain/kotlin/.../App.kt` | placeholder interactive screen |
| `src/wasmJsMain/resources/index.html` | host page that loads the Wasm bundle + registers the service worker |
| `src/wasmJsMain/resources/manifest.webmanifest` | web app manifest (name, icons, theme, `display: standalone`) |
| `src/wasmJsMain/resources/sw.js` | service worker; caches the app shell + bundle for offline launch |
| `src/wasmJsMain/resources/icons/` | app icons (reused from the Android launcher icon) |

## PWA shell (installable + offline)

Beyond the walking skeleton, the module now ships a minimal **PWA shell** so the
skeleton is a real, installable Progressive Web App (part of milestone M5, brought
forward because it is low-risk and self-contained):

- **`manifest.webmanifest`** — makes the app installable to the home screen /
  desktop, with a standalone display mode, the StreetComplete brand color
  (`#7DB6D8`) and the launcher icon in SVG + 192/512 px PNG.
- **`sw.js`** — a service worker that pre-caches the small app shell on install and
  runtime-caches the (content-hashed) Wasm/Skia bundle, so once the app has loaded
  successfully it launches offline. Bump `CACHE_VERSION` in `sw.js` when the shell
  files change.

Note that service workers only register over `https://` or `http://localhost`, so
test installability/offline via the dev server or a served production bundle (not by
opening `index.html` from disk).

## Next (M1)

Wire the shared services (web `Database` over Wasm-SQLite + OPFS, Ktor JS engine, Koin
web module) and start consuming shared code from `:app`. See
[`docs/pwa-port/ROADMAP.md`](../docs/pwa-port/ROADMAP.md).
