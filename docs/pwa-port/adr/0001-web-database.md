# ADR 0001 — Web persistence: the synchronous `Database` interface vs. the browser

> Status: **Accepted** · Milestone: M1 · Last updated: 2026-07-07
>
> **Update (implemented):** the first working web `Database` has landed in `:web`
> ([`web/.../data/WebDatabase.kt`](../../../web/src/wasmJsMain/kotlin/de/westnordost/streetcomplete/web/data/WebDatabase.kt))
> taking **Option 3** (below) as the pragmatic first step: synchronous in-memory Wasm-SQLite with
> asynchronous whole-image persistence to IndexedDB. **Option 1** (the data layer in a Worker over an
> OPFS sync-access VFS) remains the target for durable-at-scale storage and will replace the
> implementation behind the same `Database` interface. See the updated Decision and Consequences.

## Context

The shared data layer talks to storage through one seam:
`app/src/commonMain/.../data/Database.kt`. Every method on it is **synchronous** and
blocking — `query(...)` returns a `List<T>`, `insert(...)` returns a `Long`,
`transaction { }` runs a block and returns its result inline. Android satisfies this with
`AndroidDatabase` over SQLite; the call happens on a background thread and blocking is fine.

The roadmap (§5.2) picks **SQLite compiled to Wasm + OPFS** for the web store, because the
downloaded OSM data is far too large for `localStorage`/IndexedDB-as-blobs. The problem is
the interaction between that choice and the synchronous interface:

- **OPFS synchronous access** (`createSyncAccessHandle`, the fast path Wasm-SQLite VFSes
  use) is **only available inside a Web Worker**. It is not on the main thread.
- The **main/UI thread cannot block.** Kotlin/Wasm has no way to synchronously wait on an
  async result on the main thread; there is no equivalent of Android's "just block this
  background thread."

So a naive "implement `Database` with wa-sqlite on the main thread" does not work: either
the storage API is async (breaks the synchronous signature) or it needs a Worker (which
communicates asynchronously with the main thread).

## Options

1. **Run the whole data layer in a Web Worker.** The shared data/domain code runs inside a
   Worker where synchronous OPFS access handles exist, so `Database` stays synchronous and
   unchanged. The UI thread talks to the Worker over an async message channel. Cleanest for
   code reuse (the `Database` interface and all its callers are untouched), but requires a
   Worker boundary and a serialization/RPC layer between UI and data.
2. **Make `Database` suspend/async.** Change the shared interface to `suspend` functions and
   update every caller. Fits the browser naturally and would also suit other async backends,
   but it is a large, cross-cutting change to shared code that also affects Android/iOS, and
   must be coordinated with upstream + the iOS effort.
3. **Synchronous Wasm-SQLite in memory, async persistence to OPFS/IndexedDB.** Keep the DB
   in Wasm linear memory (synchronous, satisfies the interface) and periodically/async flush
   the whole image to OPFS or IndexedDB. Simple and keeps the interface, but the entire DB
   must fit in memory and flushes are coarse — risky for the large OSM datasets this store
   exists to hold.

## Decision

**Ship Option 3 now as the first working web `Database`; keep Option 1 as the durable-at-scale
target.**

The earlier revision of this ADR deferred the web `Database` entirely, so that settings/HTTP/DI
could ship first without being blocked on the persistence question. Those shipped. This revision
takes the next step and **implements** the seam.

The choice is **Option 3 — synchronous in-memory Wasm-SQLite with async persistence** — realised
with [sql.js](https://sql.js.org) on the main thread and the database image flushed to **IndexedDB**
(not OPFS) after each mutation. The reasoning:

- **It satisfies the synchronous `Database` contract today, unchanged.** Once `initSqlJs()` has
  loaded (awaited once at startup), every sql.js call is genuinely synchronous — `query` returns a
  `List`, `insert` returns a `Long` — so the whole shared interface and all its callers are reused
  verbatim, which is the point of the port. No Worker, no RPC boundary, no interface change.
- **It is self-contained in `:web` and verifiable now**, matching how every other web service came
  online one at a time. Option 1's Worker bootstrap + OPFS VFS + UI↔Worker RPC is a much larger,
  threading-sensitive piece that would have blocked any web persistence for far longer.
- **Its ceiling is understood.** The whole database lives in memory and is persisted as one image,
  so it is right for the preview and moderate data but **not** for the full downloaded OSM dataset
  (the very reason the roadmap picked OPFS). That is an explicit, documented limit — not a surprise.

**Option 1 (data layer in a Worker over an OPFS sync-access VFS) remains the target** for
durable-at-scale storage. Because Option 3 keeps the exact synchronous `Database` interface, Option 1
can later replace the implementation behind that same seam without touching any caller. Option 2
(async `Database`) stays the fallback if the Worker RPC boundary proves too heavy, to be coordinated
with the iOS migration.

## Consequences

- `webModule`-adjacent wiring now binds a `Database`: [`main`](../../../web/src/wasmJsMain/kotlin/de/westnordost/streetcomplete/web/Main.kt)
  bootstraps `WebDatabase` asynchronously and, once ready, loads it into Koin (mirroring
  `AndroidModule`'s `single { AndroidDatabase(...) }`). The demo screen exercises it end-to-end:
  representative real schema (NoteTable + spatial index, the blob-bearing WayGeometryTable), a typed
  row round-trip, a **blob** round-trip, and a DB-backed launch counter that survives reloads.
- **Bulk `Database` binding is present, but the "download a small area" flow at full data volume
  still wants Option 1.** The in-memory ceiling means a real OSM download is not yet in scope; that
  arrives with the Worker/OPFS follow-up.
- sql.js loads from a CDN (like maplibre) and is **not** part of the offline shell yet; if it is
  absent (offline / CDN blocked) the app comes up **without** persistence rather than crashing —
  the same graceful-degradation contract as the map.
- **iOS Safari / OPFS maturity** (roadmap §8) is deliberately side-stepped for now: IndexedDB is
  broadly supported, so Option 3 avoids the OPFS + sync-access-handle risk. That risk returns with
  Option 1 and must be spiked there — a Worker that opens a Wasm-SQLite DB on an OPFS-backed VFS,
  runs the shared `DatabaseInitializer` schema, and round-trips rows on the target browsers.
- **Large-integer precision:** sql.js returns integers as JS numbers, so values beyond 2^53 lose
  precision. Current OSM ids are within range; revisit (BigInt-capable build, or Option 1) before
  relying on it for larger ids.

## References

- Roadmap §5.2 (Persistence), §8 (Risks — OPFS/Wasm-SQLite maturity).
- `app/src/commonMain/kotlin/de/westnordost/streetcomplete/data/Database.kt`
- `app/src/androidMain/.../AndroidModule.kt` (`AndroidDatabase` wiring to mirror).
