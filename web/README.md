# `:web` — StreetComplete PWA

The **Compose Multiplatform for Web (Kotlin/Wasm)** target of StreetComplete. It started as
the milestone **M0** walking skeleton — proving the web toolchain end to end — grew the **M1**
platform services that the shared `:app` code will plug into, and now has an **M2** map
(maplibre-gl-js) rendering with pan/zoom and current-location. See the
[PWA port roadmap](../docs/pwa-port/ROADMAP.md).

## Why it's isolated from `:app`

The module deliberately does **not** depend on `:app` yet. Adding a `wasmJs` target directly
to `:app` would force the entire shared `commonMain` codebase — and every one of its
dependencies and `expect`/`actual` declarations — to compile for Wasm at once. Keeping this
module separate lets the web platform services come online **one at a time**, each verified
against the browser, without touching (or risking) the Android/iOS build.

The versions of the shared libraries used here (Koin, Ktor, multiplatform-settings, kotlinx)
are pinned to match `app/build.gradle.kts`, so nothing has to change when the shared modules
start compiling for wasmJs.

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
| `build.gradle.kts` | wasmJs target + Compose for Web + M1 service deps |
| `src/wasmJsMain/kotlin/.../Main.kt` | entry point; starts Koin, then mounts Compose |
| `src/wasmJsMain/kotlin/.../di/WebModule.kt` | Koin module: settings + HTTP client (M1) |
| `src/wasmJsMain/kotlin/.../map/WebMap.kt` | maplibre-gl-js interop boundary + `WebMap` wrapper (M2) |
| `src/wasmJsMain/kotlin/.../map/LatLon.kt` | local coordinate type mirroring the shared `LatLon` |
| `src/wasmJsMain/kotlin/.../App.kt` | overlay UI exercising the M1 services + driving the M2 map |
| `src/wasmJsMain/resources/index.html` | host page: loads maplibre + the Wasm bundle, registers the service worker |
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

## M1 — web platform services

The module now brings up the web-side services the shared `:app` code depends on, each
verified in the browser by the demo screen (`App.kt`):

- **Koin DI** (`di/WebModule.kt`) — the web analogue of `appModule`/`AndroidModule`. Started
  in `Main.kt` before the UI, exactly like the Android app starts Koin in its `Application`.
- **Settings over `localStorage`** — multiplatform-settings' `StorageSettings`, wrapped with
  `makeObservable()` to provide the `ObservableSettings` the shared `Preferences` class takes.
  The demo persists a launch counter that survives page reloads.
- **Ktor HTTP client on the JS engine** — configured to match `appModule`'s client (user
  agent + gzip content-encoding). The demo issues a live request to the OSM API, which also
  surfaces the browser CORS reality early.

### Deferred: the web `Database`

The web `Database` (Wasm-SQLite + OPFS) is intentionally **not** part of this step. The
shared `Database` interface is synchronous, which clashes with the browser's async /
Worker-only OPFS access. The trade-offs and the recommended path are captured in
[`docs/pwa-port/adr/0001-web-database.md`](../docs/pwa-port/adr/0001-web-database.md).

> **Local build note.** Compiling `:web` needs Compose's transitive `androidx.*` artifacts
> from Google's Maven. In network-restricted environments where that host is blocked, the
> build can't resolve them locally — rely on the `web-build.yml` CI job (unrestricted
> network) to build and verify the wasmJs bundle.

## M2 — the map

`maplibre-gl-js` (the web sibling of the Android MapLibre SDK) renders a full-screen vector map,
with the Compose UI in a small overlay card on top of it.

- **Interop boundary** (`map/WebMap.kt`) — the roadmap (§5.1) asks that `commonMain` code never
  touch JS types. All maplibre calls live behind `WebMap`: the `js(...)` interop functions are the
  only code that references the `maplibregl` global, and object handles cross the boundary as
  opaque `JsAny`. Everything else talks to `WebMap` in plain Kotlin types (`LatLon`, `Double`,
  `String`). The Compose overlay's "fly to" buttons drive the map through this wrapper.
- **What works** — pan/zoom, maplibre's navigation (zoom) control, and its geolocate control
  (current location via the browser Geolocation API), plus a demo pin. The base style is
  [OpenFreeMap](https://openfreemap.org/) Liberty (keyless, sign-up-free, OpenStreetMap-based); it
  is a single swappable constant in `Main.kt`.
- **Loading + offline** — maplibre loads from a CDN (`unpkg`) to keep the repo lean, so it is not
  part of the offline shell yet (map tiles need network anyway until offline data / OPFS lands). If
  it can't load, the app detects that and comes up **without** a map rather than crashing, so the
  offline shell launch does not regress.

Still to port (with the quest work in M3/M6 and the shared-UI migration): the real
`screens/main/map/components/*` layers (styleable overlay, quest / selected pins, tracks,
downloaded-area, focus geometry) against the JS map, and StreetComplete's own map style.

## Next

Resolve ADR 0001 and implement the web `Database` (Wasm-SQLite + OPFS), then wire the first shared
`:app` services against these bindings. See [`docs/pwa-port/ROADMAP.md`](../docs/pwa-port/ROADMAP.md).
