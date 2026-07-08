# StreetComplete PWA — Port Roadmap

> Status: **In progress (M1 · M2)**  ·  Owner: _TBD_  ·  Last updated: 2026-07-07
>
> _Latest: the first real slice of `:app`'s shared `commonMain` now compiles **and runs** on wasm —
> the web demo downloads a live OSM area and parses it with the **real shared `MapDataApiParser`** into
> the real shared model. See M1 and [`adr/0002-shared-source-on-wasm.md`](adr/0002-shared-source-on-wasm.md)._
>
> This document plans a Progressive Web App (PWA) build of StreetComplete. It is a
> living document — update the milestone checkboxes and open questions as work
> progresses.

## 1. Goal

Ship StreetComplete as an installable, offline-capable PWA that runs in modern
browsers, **reusing the existing Kotlin Multiplatform codebase** rather than
maintaining a separate reimplementation.

Success is defined by the MVP in §7.

## 2. Decision: Compose Multiplatform for Web (Kotlin/Wasm)

We add a **`wasmJs` target** to the existing Gradle module and render the UI with
**Compose Multiplatform for Web** (Skia/`skiko` → canvas). The PWA is a third
platform target alongside Android and iOS — not a new project.

### Why this fits the project

StreetComplete is already a Kotlin Multiplatform + Compose Multiplatform app,
mid-migration to run on iOS (funded by the NLnet 2025 grant). The web target is
the natural continuation of that work, not a detour.

- **Business logic is already shared.** `commonMain` holds ~825 Kotlin files
  (data, osm, quests, overlays) that run unchanged on web.
- **The core libraries already target Wasm/JS:** Ktor client, Koin, kotlinx
  (serialization / coroutines / datetime), multiplatform-settings.
- **The UI is already unifying on Compose.** `screens/` and `ui/theme` are
  moving into `commonMain`; the web target consumes that same UI.
- **Clean platform seams already exist** — e.g. the `Database` interface in
  `commonMain` (Android supplies `AndroidDatabase` via Koin), and OAuth logic
  (`data/user/oauth`) is already common.

### Why against the two stated criteria

- **Fast** — Kotlin/Wasm executes near-native in the browser; the heavy OSM data
  processing (already Kotlin) runs as-is. Compose renders via Skia to a canvas.
- **Easy to read** — one language across the entire app and the team's existing
  idioms; no parallel TypeScript reimplementation that silently drifts from the
  native apps.

### Alternatives considered (for the record)

| Option | Verdict | Reason |
|---|---|---|
| **Compose for Web (Wasm)** — *chosen* | ✅ | Max reuse of logic **and** UI; aligned with iOS migration. |
| Kotlin/JS + web-native DOM UI (React/Svelte) | ⚠️ Rejected for now | Reuses logic but forks the UI away from Compose; better a11y/SEO but doubles UI maintenance. Revisit only if Compose/canvas a11y proves blocking (§8). |
| Full TypeScript/React rewrite | ❌ Rejected | Reimplements ~800 files of domain logic + every quest; perpetually lags the native apps; discards the funded multiplatform investment. |

## 3. Current architecture snapshot

| Concern | Where it lives today | Web implication |
|---|---|---|
| Domain logic (data, osm, quests, overlays) | `commonMain` (~825 files) | Reuse as-is |
| UI | Compose, migrating `androidMain` → `commonMain` | Reuse shared parts; finish migration for the rest |
| Quest **forms** | ~383 still in `androidMain` | Must reach `commonMain` to appear on web (also benefits iOS) |
| HTTP | Ktor client (Android/Darwin engines) | Add `ktor-client-js` engine |
| DI | Koin | Add a `webMain`/`wasmJsMain` Koin module |
| Persistence | `Database` interface; `AndroidDatabase` over SQLite | New web `Database` impl (§5.2) |
| Key–value settings | multiplatform-settings | `StorageSettings` (localStorage) or OPFS-backed |
| Map | MapLibre Android OpenGL SDK | `maplibre-gl-js` behind the existing map abstraction (§5.1) |
| Location | `data/location` (common) + Android provider | Browser Geolocation API provider |
| Auth | `data/user/oauth` (common) | Browser redirect-based OAuth (§5.3) |

## 4. Workstreams & approach

### 5.1 Map rendering — *biggest chunk*
- Target `maplibre-gl-js` (the web sibling of the Android MapLibre SDK already in
  use), loaded via JS interop.
- Reimplement the map component abstractions under `screens/main/map/components/*`
  (pins, selected pins, tracks, current location, downloaded area, focus geometry,
  styleable overlay) against the JS map instead of the Android `MapView`.
- Reuse the existing map **style/scene** assets and tile config where possible.
- Wrap map interop behind an `expect`/`actual` or DI boundary so `commonMain`
  code never touches JS types directly.

### 5.2 Persistence
- Implement the `Database` interface (`commonMain/.../data/Database.kt`) for web.
- Recommended engine: **SQLite compiled to Wasm** (e.g. `wa-sqlite`) with
  **OPFS** (Origin Private File System) for durable, high-volume storage — the
  downloaded OSM data is far too large for `localStorage`/IndexedDB-as-blobs.
- Provide the impl through a web Koin module, mirroring `AndroidModule`'s
  `AndroidDatabase(sqLite.writableDatabase)` wiring.
- Reuse the shared `DatabaseInitializer` / schema.
- Settings: back multiplatform-settings with `StorageSettings` (small config) —
  keep bulk data in the SQLite/OPFS store.

### 5.3 Platform APIs
- **Geolocation** — implement the location provider over the browser Geolocation
  API + `watchPosition`; feed the existing `data/location` model.
- **Camera / photos** — `<input type="file" capture>` / `getUserMedia` for quests
  that attach images; map to the existing photo/attachment flow.
- **OAuth login** — browser redirect flow to OSM; handle the redirect callback
  and hand the token to the existing `UserLoginController`. Confirm the OSM app
  registration allows a web redirect URI.
- **Background work** — Android uses WorkManager; web has no true background
  execution. Scope downloads/uploads to foreground + a Service Worker where
  feasible; document the reduced guarantees.

### 5.4 Quest-form Compose migration
- ~383 quest/overlay forms remain in `androidMain`. Each must move to
  `commonMain` Compose to be usable on web.
- This is shared effort with the iOS migration — coordinate so it is done once.
- Sequence by usage: migrate the most common quests first to reach a usable MVP
  before full coverage.

### 5.5 PWA shell & offline
- Web app manifest (name, icons, theme, display: standalone) for installability.
- Service Worker to cache the app shell + Wasm/Skia bundle for offline launch.
- Verify Kotlin/Wasm GC support on target browsers (modern evergreen: OK).
- Track and budget bundle size (Wasm + skiko can be large) — measure early.

## 5. Build & project setup
- Add `wasmJs { browser() }` target to `app/build.gradle.kts`; create
  `wasmJsMain` (and `webMain` if sharing with a future JS target).
- Add web engine deps: `ktor-client-js`, Compose web/`skiko`, Koin web module.
- Set up a dev server + production bundling task; wire a CI job that builds the
  web target.
- Provide `expect`/`actual` or DI actuals for every current Android-only binding
  (Database, location, files, HTTP engine, platform flags in `BuildConfig`).

## 6. Milestones

Check off as completed. Each milestone should be independently demoable.

- [x] **M0 — Spike / walking skeleton.** _Done._ Isolated `:web` module added
      (Compose Multiplatform for Web / Kotlin/Wasm) with a trivial interactive screen,
      plus a `web-build.yml` CI job that builds a green browser distribution. Kept
      separate from `:app` on purpose (see below).
- [~] **M5 (partial, brought forward) — PWA shell.** _In progress._ The walking
      skeleton is now an installable, offline-capable PWA: web app manifest, app icons
      (reused from the Android launcher icon), and a service worker that pre-caches the
      app shell and runtime-caches the Wasm/Skia bundle so it launches offline after the
      first load. Brought forward from M5 because it is low-risk, self-contained in
      `:web`, and independent of the core-services work. Remaining M5 items (geolocation,
      photo capture) still depend on M1/M3.
- [~] **M1 — Core services on web.** _In progress._ Koin web module, `localStorage`-backed
      settings (multiplatform-settings), and a Ktor JS-engine `HttpClient` are wired in `:web`
      and exercised end-to-end by the demo screen (DI-resolved services, a persisted launch
      counter, and a live OSM API request). The web **`Database`** now also lands
      (`web/.../data/WebDatabase.kt`): a synchronous SQLite via [sql.js](https://sql.js.org)
      (Wasm) with the image persisted to IndexedDB, satisfying the shared, synchronous `Database`
      interface **unchanged** and bound into Koin like `AndroidModule`'s `AndroidDatabase`. The demo
      creates a representative slice of the real schema (NoteTable + spatial index, the blob-bearing
      WayGeometryTable) and round-trips typed rows, a blob, and a reload-surviving counter. This
      resolves [`adr/0001-web-database.md`](adr/0001-web-database.md) via its Option 3 (in-memory +
      async IndexedDB flush); the durable-at-scale path (data layer in a Worker over an OPFS
      sync-access VFS — ADR Option 1) is the follow-up and slots in behind the same interface. The
      headless "download a small area" flow at full OSM data volume waits on that Option 1 step.

      **Shared `commonMain` now compiles & runs on wasm (first real slice).** `:web` no longer only
      *mirrors* the shared types — it compiles the **real** `:app` domain source for wasmJs via a
      curated source bridge (`web/build.gradle.kts`: `:app`'s `commonMain` added as a source dir with an
      `include(...)` filter selecting the wasm-ready files). The first slice is the OSM data core — the
      element model, geometry, spherical math, `BoundingBox`, and the **`MapDataApiParser`** — and the
      demo exercises it end-to-end: the real `BoundingBox.toOsmApiString()` builds the query, a live
      `/api/0.6/map` download (CORS OK) is parsed by the real shared parser into the real
      `MutableMapData` (916 nodes / 89 ways / 107 relations from a ~150 m Berlin area, multi-byte names
      intact). The `map/LatLon` mirror is deleted (the map uses the real `LatLon` now). This surfaced —
      and fixed with an additive `CharSequence` overload — a real xmlutil-on-wasm UTF-8 bug, and the
      dependency wall for the rest: `osmfeatures` (and `countryboundaries`) have no wasmJs target. See
      [`adr/0002-shared-source-on-wasm.md`](adr/0002-shared-source-on-wasm.md).
- [~] **M2 — Map MVP.** _In progress._ `maplibre-gl-js` is wired into `:web` behind a Kotlin
      interop boundary (`web/.../map/WebMap.kt`): a full-screen vector map ([OpenFreeMap
      Liberty](https://openfreemap.org/), keyless/OSM-based) renders with pan/zoom, maplibre's
      navigation (zoom) and geolocate (current-location, over the browser Geolocation API) controls,
      and a demo pin. All JS/maplibre types stay behind `WebMap`; the rest of the Kotlin code uses
      plain types (`LatLon`), per §5.1. The Compose overlay drives the map (fly-to buttons) across
      that boundary, proving Compose ↔ map control without touching JS types. Maplibre loads from a
      CDN (not yet part of the offline shell — the map degrades gracefully to "no map" if it can't
      load, so an offline launch still comes up). **Still to port:** the real
      `screens/main/map/components/*` layers (styleable overlay, quest/selected pins, tracks,
      downloaded-area, focus geometry) against the JS map, and StreetComplete's own map style — these
      arrive with the quest work (M3/M6) and the shared-UI migration.
- [ ] **M3 — First quests end-to-end.** A handful of high-frequency quest forms
      migrated to common Compose; view quest → answer → local edit recorded.
      _Groundwork landed: the real shared OSM model + `MapDataApiParser` now compile & run on wasm and
      parse live OSM data (see M1). **Blocked next step:** the quest/feature layer depends on
      `osmfeatures`, which has no js/wasmJs target — that must be resolved upstream (or the module
      vendored) before quest forms can compile for web. See [`adr/0002`](adr/0002-shared-source-on-wasm.md)._
- [ ] **M4 — Auth + upload.** OSM OAuth redirect login; changeset upload from web.
- [~] **M5 — PWA shell.** Manifest + Service Worker; installable; offline app
      launch — _done (walking-skeleton shell, see M0/M5-partial above)_. **Geolocation** now works
      via the map's geolocate control (browser Geolocation API, landed with M2); a dedicated
      location provider feeding the shared `data/location` model still follows with the shared-code
      wiring. **Photo capture** still pending (depends on M3).
- [ ] **M6 — Quest coverage.** Remaining quest/overlay forms migrated (shared with
      iOS effort); overlays working.
- [ ] **M7 — Hardening.** Bundle-size budget, performance pass, cross-browser
      testing, accessibility review, beta release.

## 7. MVP definition (exit criteria for "usable beta")

A logged-in user can, in a modern desktop or mobile browser:
1. Load the installed PWA and see the map centered on their location.
2. Download quests for the visible area (persisted offline via OPFS).
3. Open and answer several common quest types.
4. Upload the resulting edits to OSM under their account.
5. Relaunch offline and still see previously downloaded data.

## 8. Risks & open questions

- **Shared dependencies without a wasmJs target.** `de.westnordost:osmfeatures` (no js **or** wasm
  target) and `de.westnordost:countryboundaries` (js but no wasm) block compiling the parts of
  `commonMain` that use them — notably the feature dictionary and most quest forms. Both are
  westnordost's own libraries, so wasmJs support is attainable upstream; until then those slices can't
  reach web. `com.cheonjaeung.compose.grid` (no wasm) similarly blocks some quest-form UI. This is now
  the gating dependency for M3/M6. See [`adr/0002-shared-source-on-wasm.md`](adr/0002-shared-source-on-wasm.md).
- **Compose/canvas accessibility.** Canvas-rendered UI has weaker a11y/SEO than
  DOM. Needs an early review; if blocking, the Kotlin/JS + DOM-UI alternative
  (§2) is the fallback for at least the shell. (Confirmed concretely: the Compose overlay's buttons are
  not exposed in the DOM accessibility tree — they render only to the canvas.)
- **Bundle size / cold start.** Wasm + skiko payload; measure at M0 and budget.
- **OPFS / Wasm-SQLite maturity** across target browsers (esp. iOS Safari).
- **No real background execution** on web — downloads/uploads are foreground-only;
  set user expectations.
- **OSM OAuth web redirect URI** — confirm app registration + CORS for OSM API
  from the browser origin.
- **Map feature parity** — AR measuring and some Android-specific map features may
  be out of scope for web v1.
- **Ownership & coordination** with upstream StreetComplete: is this an upstream
  target or a fork? The quest-form migration overlaps heavily with the iOS effort
  and should be coordinated to avoid duplicate work.

## 9. References
- Existing multiplatform work: iOS target (`iosApp/`), NLnet 2025 multiplatform grant.
- Key seams: `app/src/commonMain/.../data/Database.kt`,
  `app/src/androidMain/.../AndroidModule.kt`, `data/user/oauth/`,
  `screens/main/map/`.
