# `:web` — StreetComplete PWA

The **Compose Multiplatform for Web (Kotlin/Wasm)** target of StreetComplete. It started as
the milestone **M0** walking skeleton — proving the web toolchain end to end — grew the **M1**
platform services that the shared `:app` code plugs into (DI, settings, HTTP, and a synchronous
**`Database`** via sql.js + IndexedDB), and has an **M2** map (maplibre-gl-js) rendering with
pan/zoom and current-location. Most recently, the **first real slice of `:app`'s shared
`commonMain` compiles and runs on wasm** — the demo downloads a live OSM area and parses it with the
**real shared `MapDataApiParser`** into the real shared model (see "Shared-source bridge" below).
See the [PWA port roadmap](../docs/pwa-port/ROADMAP.md).

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
| `build.gradle.kts` | wasmJs target + Compose for Web + M1 service deps + the **shared-source bridge** (§ below) |
| `src/wasmJsMain/kotlin/.../Main.kt` | entry point; starts Koin, then mounts Compose |
| `src/wasmJsMain/kotlin/.../di/WebModule.kt` | Koin module: settings + HTTP client (M1) |
| `src/wasmJsMain/kotlin/.../data/Database.kt` | verbatim copy of the shared `Database` interface (mirror; still present) |
| `src/wasmJsMain/kotlin/.../data/WebDatabase.kt` | web `Database`: sql.js + IndexedDB interop boundary + impl (M1) |
| `src/wasmJsMain/kotlin/.../WebDatabaseHolder.kt` | bridges the async DB bootstrap to the Compose UI |
| `src/wasmJsMain/kotlin/.../map/WebMap.kt` | maplibre-gl-js interop boundary + `WebMap` wrapper (M2); uses the **real** shared `LatLon` |
| `src/wasmJsMain/kotlin/.../App.kt` | overlay UI: M1 services, the M2 map, and the shared-parser demo |
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

### The web `Database` (sql.js + IndexedDB)

The web `Database` now lands (`data/WebDatabase.kt`), resolving
[`docs/pwa-port/adr/0001-web-database.md`](../docs/pwa-port/adr/0001-web-database.md).

- **Synchronous SQLite, satisfying the shared interface unchanged.** The shared `Database`
  interface is synchronous (`query` returns a `List`, `insert` a `Long`). This is implemented with
  [sql.js](https://sql.js.org) — SQLite compiled to WebAssembly — run **in memory on the main
  thread**. Once `initSqlJs()` has loaded (awaited once at startup), every sql.js call is genuinely
  synchronous, so the whole `Database` surface and all its callers are reused verbatim. `data/Database.kt`
  is still a mirror of the shared interface (kept identical); it is deleted once the shared DAO slice
  reaches the source bridge (§ "Shared-source bridge"), at which point `WebDatabase` binds the real
  `Database` unchanged — exactly as the `map/LatLon.kt` mirror was already replaced by the real `LatLon`.
- **Durable, off the hot path.** After each mutation the in-memory image is exported and written to
  **IndexedDB** (debounced), and reloaded on startup — so the database survives reloads without the
  main thread ever blocking on storage. `main()` bootstraps it asynchronously and binds it into Koin
  (mirroring `AndroidModule`'s `single { AndroidDatabase(...) }`); the demo screen exercises it with a
  representative slice of the real schema (NoteTable + spatial index, the blob-bearing WayGeometryTable),
  a typed-row round-trip, a **blob** round-trip, and a reload-surviving launch counter.
- **Interop boundary + graceful degradation.** As with `WebMap`, all sql.js / IndexedDB JavaScript
  stays behind `data/WebDatabase.kt`; values cross as JSON (blobs as base64). sql.js loads from a CDN
  (like maplibre) and is not part of the offline shell yet, so if it is absent the app comes up
  **without** persistence rather than crashing.

This is the ADR's **Option 3** (in-memory + async IndexedDB flush). Its ceiling: the whole database
lives in memory and is flushed as one image — right for the preview and moderate data, **not** for the
full downloaded OSM dataset. The durable-at-scale path — the data layer in a Web Worker over an OPFS
sync-access VFS (ADR **Option 1**) — is the follow-up and slots in behind the same `Database` interface.

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

## Shared-source bridge — real `:app` code on wasm

`:web` now compiles a **curated slice of `:app`'s real `commonMain` source** for wasmJs — the payoff of
the whole port (reuse, not reimplementation). It is not yet a `:web → :app` module dependency, because
adding a `wasmJs` target to `:app` is blocked: two of `:app`'s own dependencies have no wasmJs target
(`osmfeatures` — no js/wasm at all; `countryboundaries` — js but no wasm), and a couple of files use
`runBlocking`/`Dispatchers.IO` (absent on wasm). Full analysis + decision:
[`docs/pwa-port/adr/0002-shared-source-on-wasm.md`](../docs/pwa-port/adr/0002-shared-source-on-wasm.md).

Instead, `build.gradle.kts` adds `:app/src/commonMain/kotlin` as a source directory and uses a Gradle
`include(...)` filter to compile **exactly** the wasm-ready files — no copies (the real files are
compiled in place), no change to `:app`'s build, no risk to the Android/iOS targets. The `include` list
**is** the manifest of "shared code proven on wasm"; grow it as more of `commonMain` becomes wasm-safe.
When the surface is large/stable enough it graduates to a real shared module (with a `wasmJs` target)
and this bridge is deleted.

The first slice is the **OSM data core**: the element model
(`Element`/`Node`/`Way`/`Relation`/`LatLon`…), `BoundingBox`, `MapData`/`MutableMapData`, the element
geometry model, the spherical-earth math, and the **`MapDataApiParser`**. The demo (`App.kt`,
"Download & parse area") runs it end-to-end: the real `BoundingBox.toOsmApiString()` builds an OSM
`bbox` query, a live `/api/0.6/map` download is parsed by the real shared parser into the real
`MutableMapData` — ~900 nodes / ~90 ways / ~100 relations from a ~150 m Berlin area, multi-byte names
("Straße", "Dom-Aquarée") intact. With the real `LatLon` now compiled, the `map/LatLon.kt` mirror is
deleted; `WebMap` uses the shared type.

> **One shared-code change was needed** (additive): on wasm, xmlutil's byte-`Source` XML reader aborts
> at the first multi-byte UTF-8 character, so `MapDataApiParser` gained a `CharSequence` overload that
> reads the already-decoded string. Android/iOS keep using the byte-`Source` overload unchanged.

## Next

1. **Unblock the quest/feature layer** — get a `js`/`wasmJs` target into `osmfeatures` (and
   `countryboundaries`) upstream, or vendor them, so the feature dictionary and quest forms can compile
   for web (gates M3/M6).
2. **Grow the bridge toward the download flow** — add the wasm-friendly parts of the map-data controller
   / DAOs and bind them to the web `Database`, and rewrite the two `runBlocking`/`Dispatchers.IO`
   download-orchestration files, working toward the headless "download a small area into the DB" flow.
3. **Durable-at-scale storage (ADR 0001 Option 1)** — move the data layer into a Web Worker over an
   OPFS sync-access VFS so the full downloaded OSM dataset fits, replacing `WebDatabase`'s in-memory
   store behind the same `Database` interface.

See [`docs/pwa-port/ROADMAP.md`](../docs/pwa-port/ROADMAP.md).
