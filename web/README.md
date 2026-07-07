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
| `src/wasmJsMain/resources/index.html` | host page that loads the Wasm bundle |

## Next (M1)

Wire the shared services (web `Database` over Wasm-SQLite + OPFS, Ktor JS engine, Koin
web module) and start consuming shared code from `:app`. See
[`docs/pwa-port/ROADMAP.md`](../docs/pwa-port/ROADMAP.md).
