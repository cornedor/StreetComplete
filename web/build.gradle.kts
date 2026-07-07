import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * StreetComplete PWA — walking skeleton (milestone M0 of docs/pwa-port/ROADMAP.md).
 *
 * This is an intentionally isolated Compose Multiplatform for Web (Kotlin/Wasm) module.
 * It does NOT depend on `:app` yet: the point of M0 is to prove the web toolchain
 * (Kotlin/Wasm + Compose for Web + browser bundling + CI) end-to-end, without forcing
 * the whole `commonMain` codebase and every dependency/expect to compile for wasmJs.
 * Reuse of the shared `:app` code starts in M1 (see the roadmap).
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
            }
        }
    }
}
