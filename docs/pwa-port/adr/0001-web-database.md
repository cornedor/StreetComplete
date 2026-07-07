# ADR 0001 — Web persistence: the synchronous `Database` interface vs. the browser

> Status: **Proposed** · Milestone: M1 · Last updated: 2026-07-07

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

**Deferred — do not implement the web `Database` in the same step as the other M1 services.**

Option 1 (data layer in a Worker) is the current front-runner because it preserves the
synchronous `Database` contract and therefore maximizes reuse of the shared code — the whole
point of the port. But it is a substantial, standalone piece of work (Worker bootstrap,
Wasm-SQLite + OPFS VFS wiring, a UI↔Worker RPC boundary) and it interacts with the
threading model of the entire app. It deserves its own milestone step and its own
verification, rather than being rushed in alongside settings/HTTP/DI.

The rest of M1 — Koin, `localStorage`-backed settings, and the Ktor JS client — is
independent of this decision and ships first (see the `:web` module). Those are exactly the
services that do **not** depend on how bulk persistence is solved.

## Consequences

- `webModule` provides `ObservableSettings` and `HttpClient` now; it does **not** yet bind a
  `Database`. The "headless data flow (download a small area)" M1 exit criterion depends on
  this ADR being resolved and implemented.
- Before implementing, spike **Option 1** end-to-end: a Worker that opens a Wasm-SQLite DB on
  an OPFS-backed VFS, runs the shared `DatabaseInitializer` schema, and round-trips a few
  rows. Confirm OPFS + sync access handles behave on the target browsers — **especially iOS
  Safari**, called out as a maturity risk in the roadmap (§8).
- If the Worker RPC boundary proves too heavy, revisit Option 2 (async `Database`) as the
  cross-platform direction, coordinating with the iOS migration.

## References

- Roadmap §5.2 (Persistence), §8 (Risks — OPFS/Wasm-SQLite maturity).
- `app/src/commonMain/kotlin/de/westnordost/streetcomplete/data/Database.kt`
- `app/src/androidMain/.../AndroidModule.kt` (`AndroidDatabase` wiring to mirror).
