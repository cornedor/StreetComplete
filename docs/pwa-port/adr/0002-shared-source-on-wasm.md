# ADR 0002 — Compiling shared `commonMain` code for wasmJs: the dependency boundary and how we cross it

> Status: **Accepted** · Milestone: M1 → M3 · Last updated: 2026-07-07
>
> First real slice of `:app`'s shared `commonMain` now compiles **and runs** on wasm: the web demo
> downloads a live OSM area and parses it with the **real shared `MapDataApiParser`** into the real
> shared model. See "Decision" and "Consequences".

## Context

The whole premise of the port (ROADMAP §2) is **reuse `:app`'s `commonMain`, don't reimplement it**.
Until now `:web` only stood up web-side *platform services* (DI, settings, HTTP, `Database`, map) that
*mirror* the shapes the shared code expects — it never compiled any actual `:app` domain code. The
open question that gates M3 (quests) and the whole thesis is therefore: **does `commonMain` actually
compile for wasmJs, and what stops it?**

The end state is for `:web` to depend on `:app` with a `wasmJs` target. That is **not possible today**,
and the reason is worth recording precisely, because it shapes the sequencing of the rest of the port.

### Finding 1 — two of `:app`'s own dependencies have no wasmJs target

`:app`'s `commonMain` dependencies were audited against Maven Central for a published `*-wasm-js`
variant. The overwhelming majority already ship wasmJs (kotlinx-coroutines / -serialization / -io /
-datetime, atomicfu, Koin, Ktor, multiplatform-settings, **xmlutil**, kaml, kotlincrypto sha2,
bignum, qrose, compose-webview-multiplatform, reorderable, and Compose itself). The blockers:

| Dependency | Targets it publishes | wasmJs? | Used in `commonMain` |
|---|---|---|---|
| `de.westnordost:osmfeatures:7.1.0` | common, jvm, android, ios | ❌ no js **or** wasm | 16 files (feature dictionary, a few quest forms) |
| `de.westnordost:countryboundaries:3.0.0` | common, jvm, **js**, ios | ❌ no wasm (has js) | **0** source references (declared but unused) |
| `com.cheonjaeung.compose.grid:grid:2.7.4` | android, jvm, js, ios | ❌ no wasm | UI widget (non-lazy grid) |

Both hard blockers are **westnordost's own libraries** (same author as StreetComplete), so wasmJs
support is plausibly attainable upstream — `countryboundaries` already has a `js` target, so adding
`wasmJs` is small; `osmfeatures` would need a `js`/`wasmJs` target added from scratch. `grid` is a UI
widget already flagged in `app/build.gradle.kts` as replaceable and is not needed for non-UI slices.

### Finding 2 — a few files use coroutine APIs absent on wasmJs

`runBlocking` and `Dispatchers.IO` do not exist on Kotlin/Wasm (they are JVM/native only). In the map
data package only `MapDataDownloader.kt` and `RemoteMapDataRepository.kt` use them — the download
*orchestration*, not the model or the parser. They are simply excluded from the wasm-ready slice.

### Consequence of the findings

Adding `wasmJs { }` directly to `:app` (ROADMAP §5's eventual step) forces **all** of `commonMain`
and **every** dependency to compile for wasm at once — which cannot succeed while Findings 1–2 hold.
That is exactly the "big bang" the `:web` isolation was created to avoid. We need a way to compile the
**wasm-ready subset** of the *real* shared source now, and grow it, without forking it into copies and
without breaking the Android/iOS build.

## Options

1. **Add `wasmJs` to `:app` now.** Rejected: blocked by Findings 1–2; also risks the Android/iOS build,
   which cannot be verified from this (Linux/aarch64) environment.
2. **Extract a new shared Gradle module** (`:core-model` …) with android+ios+wasmJs targets, and move
   the wasm-ready files into it. The clean long-term structure, but a large, invasive refactor of `:app`
   that touches the Android/iOS build — premature, and unverifiable here.
3. **Duplicate the needed files into `:web`.** Rejected: duplication is the exact thing the port exists
   to avoid; copies silently drift from the originals.
4. **Compile a curated slice of `:app`'s *real* source for wasmJs from `:web`.** Add `:app`'s
   `commonMain/kotlin` as a source directory of `:web`'s `wasmJsMain` and use a Gradle `include(...)`
   filter to select exactly which real files compile. No copies (the originals are compiled in place),
   no change to `:app`'s build, no risk to Android/iOS, and the include list doubles as the manifest of
   "shared code proven on wasm".

## Decision

**Take Option 4 — a curated source bridge — as the incremental mechanism, keeping Option 2 (a real
shared module) as the eventual structure once the wasm-ready surface is large enough to be worth
extracting.**

The bridge lives in `web/build.gradle.kts`:

```kotlin
kotlin.srcDir(rootProject.projectDir.resolve("app/src/commonMain/kotlin"))
kotlin.include(
    "de/westnordost/streetcomplete/web/**",          // this module's own sources
    "de/westnordost/streetcomplete/data/osm/mapdata/Element.kt",
    …                                                // the wasm-ready slice, file by file
)
```

The first slice compiled is the dependency-light **OSM data core**: the element model
(`Element`/`Node`/`Way`/`Relation`/`RelationMember`/`ElementType`/`LatLon`), `BoundingBox`,
`MapData`/`MutableMapData`, `ElementKey`/`ElementUpdate`, the element geometry model, the spherical-earth
math, a handful of pure-Kotlin `util/ktx` helpers, and the **`MapDataApiParser`**. Its whole transitive
closure stays inside wasm-ready libraries (kotlinx-*, xmlutil) — deliberately avoiding `osmfeatures`.

Because the bridge compiles the real files, `:web`'s temporary *mirrors* can be deleted as the real
type arrives: `map/LatLon.kt` is gone (the map now uses the real
`data.osm.mapdata.LatLon`). The `data/Database.kt` mirror stays for now — the DAOs that would consume
the real `Database` pull in the `edits`/`quest` subsystems and `osmfeatures`, so that slice waits.

### One shared-code change was required (additive)

Running the real parser surfaced a genuine, previously-undiscovered bug: **on Kotlin/Wasm, xmlutil's
byte-`Source` XML reader aborts at the first multi-byte UTF-8 character** with a spurious
"Unexpected EOF" (a 3.1 MB Berlin extract died at the first "ß"). This parser had never run on JS/wasm
before — `:app` targets only Android/iOS. The fix is an **additive** `CharSequence` overload on
`MapDataApiParser.parseMapData(...)` that reads the already-decoded string via
`xmlStreaming.newReader(CharSequence)`; Android/iOS keep using the byte-`Source` overload unchanged, so
there is no behavioral change on those platforms. The web target already holds the response as a decoded
string, so it just calls the new overload.

## Consequences

- **The reuse thesis is validated for the domain core.** The real shared `BoundingBox` builds the OSM
  `bbox` query, a live `/api/0.6/map` download (CORS works) is parsed by the real shared
  `MapDataApiParser` into the real shared `MutableMapData` — 916 nodes / 89 ways / 107 relations from a
  ~150 m Berlin area, multi-byte names ("Straße", "Dom-Aquarée") intact — all running as wasm. Verified
  in-browser, no console errors.
- **The wasm-ready surface grows by editing one `include` list**, each addition compile-checked. When it
  is large/stable enough, it graduates to Option 2 (a real shared module with a `wasmJs` target) and the
  bridge is deleted.
- **`osmfeatures` (and, if ever used, `countryboundaries`) must gain a `js`/`wasmJs` target** before any
  slice that needs them — notably the feature dictionary and most quest forms (M3/M6) — can compile.
  This is now the critical upstream dependency for quest work on web and should be raised with the
  westnordost libraries.
- **The two `runBlocking`/`Dispatchers.IO` files** (download orchestration) need a wasm-friendly
  rewrite (suspend + `Dispatchers.Default`) before the headless "download a small area into the DB" flow
  can be shared; the parse+model half already works.
- **The `MapDataApiParser` `CharSequence` overload is a real (if tiny) change to shared `:app` code** and
  should be carried upstream with the rest of the multiplatform work. The underlying xmlutil wasm bug is
  worth reporting upstream too.
- Large-integer note from ADR 0001 is unrelated here (the parser uses `String.toLong()`, full precision).

## References

- ROADMAP §2 (decision), §4/§5 (workstreams, build setup), §5.4 (quest-form migration), §8 (risks).
- `web/build.gradle.kts` — the source bridge + `include` manifest.
- `app/.../data/osm/mapdata/MapDataApiParser.kt` — the parser + the added `CharSequence` overload.
- [ADR 0001](0001-web-database.md) — the web `Database` (the other half of the M1 seam).
