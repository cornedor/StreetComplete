import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * StreetComplete PWA — web target (milestones M0–M1 of docs/pwa-port/ROADMAP.md).
 *
 * This is an intentionally isolated Compose Multiplatform for Web (Kotlin/Wasm) module.
 * It does NOT depend on `:app` yet: forcing the whole `commonMain` codebase and every
 * dependency/expect to compile for wasmJs is the larger M1+ effort. Keeping this module
 * separate lets us bring the web *platform services* online one at a time — proving each
 * against the browser — before wiring them to the shared code.
 *
 * M1 (in progress) adds the web-side platform services the shared `:app` code will plug
 * into: Koin DI, key–value settings over `localStorage` (multiplatform-settings), and a
 * Ktor HTTP client on the JS engine. These are exercised end-to-end by the demo screen so
 * the plumbing is verified in a real browser. The versions below intentionally match the
 * ones `:app` already uses (see app/build.gradle.kts) so nothing has to change when the
 * shared modules start compiling for wasmJs.
 *
 * Plugin versions are intentionally omitted — they are declared once, with `apply false`,
 * in the root build.gradle.kts.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // The generated bundle is named by the webpack output filename below; index.html
        // references it as `streetcomplete-web.js`.
        browser {
            commonWebpackConfig {
                outputFileName = "streetcomplete-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                // Compose Multiplatform — direct coordinates, matching the convention in :app
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
                implementation("org.jetbrains.compose.material:material:1.11.1")
                implementation("org.jetbrains.compose.ui:ui:1.11.1")
                // browser globals (document, window) for the Compose entrypoint
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")

                // --- M1 platform services (versions match app/build.gradle.kts) ---
                // Coroutines: async plumbing for HTTP + UI-driven flows
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                // Koin: the same DI container the shared code registers its modules in
                implementation(project.dependencies.platform("io.insert-koin:koin-bom:4.2.2"))
                implementation("io.insert-koin:koin-core")
                // Key–value settings: `StorageSettings` is a localStorage-backed `Settings`;
                // `.makeObservable()` (make-observable artifact) wraps it as the
                // `ObservableSettings` the shared `Preferences` class depends on.
                implementation("com.russhwolf:multiplatform-settings:1.3.0")
                implementation("com.russhwolf:multiplatform-settings-make-observable:1.3.0")
                // Ktor on the JS engine: the web sibling of the Android/Darwin engines
                implementation("io.ktor:ktor-client-core:3.5.0")
                implementation("io.ktor:ktor-client-js:3.5.0")
                implementation("io.ktor:ktor-client-encoding:3.5.0")
            }
        }
    }
}
