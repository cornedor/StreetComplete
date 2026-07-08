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
    id("org.jetbrains.kotlin.plugin.serialization")
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
                // kotlinx-serialization JSON runtime: the value-marshalling format across the sql.js
                // interop boundary in data/WebDatabase.kt, and the serializer runtime for the shared
                // @Serializable model classes now compiled via the bridge below (Element, BoundingBox…).
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

                // --- Deps required by the bridged shared source (see the srcDir bridge below) ---
                // The real shared OSM data model + MapDataApiParser use these; versions match
                // app/build.gradle.kts so nothing changes when :app itself gains a wasmJs target.
                // kotlinx-io: the `Source` the shared MapDataApiParser reads from.
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
                // xmlutil: the streaming XML reader the shared parser uses (core-io bridges it to
                // kotlinx-io via `xmlStreaming.newReader(source)`).
                implementation("io.github.pdvrieze.xmlutil:core:0.91.3")
                implementation("io.github.pdvrieze.xmlutil:core-io:0.91.3")
            }

            // --- Bridge: compile a curated slice of :app's real commonMain for wasmJs ---
            // The port's end state is that :web consumes :app's commonMain directly. That is blocked
            // today because two of :app's own dependencies have no wasmJs target — `osmfeatures` (no
            // js/wasm at all) and `countryboundaries` (js but no wasm) — plus a couple of files use
            // JVM/native-only coroutine APIs (runBlocking, Dispatchers.IO). See
            // docs/pwa-port/adr/0002-shared-source-on-wasm.md.
            //
            // Rather than fork/duplicate, we compile the REAL :app source files (no copies) for the
            // dependency-light core that IS wasm-ready — the OSM element model, geometry, and the OSM
            // API XML parser — by adding :app's commonMain as a source directory and restricting, via
            // the include filter below, exactly which files are compiled. This keeps the closure from
            // pulling in the blocked deps, and the include list doubles as the manifest of "shared code
            // proven on wasm". As more of commonMain becomes wasm-safe the list grows; when :app gets
            // its own wasmJs target this whole block is deleted and :web depends on :app instead.
            kotlin.srcDir(rootProject.projectDir.resolve("app/src/commonMain/kotlin"))
            kotlin.include(
                // this module's own sources
                "de/westnordost/streetcomplete/web/**",
                // --- bridged real :app/commonMain sources (wasm-safe core) ---
                // OSM element model (Element/Node/Way/Relation/RelationMember/ElementType/LatLon)
                "de/westnordost/streetcomplete/data/osm/mapdata/Element.kt",
                "de/westnordost/streetcomplete/data/osm/mapdata/ElementKey.kt",
                "de/westnordost/streetcomplete/data/osm/mapdata/ElementUpdate.kt",
                "de/westnordost/streetcomplete/data/osm/mapdata/MapData.kt",
                "de/westnordost/streetcomplete/data/osm/mapdata/MutableMapData.kt",
                "de/westnordost/streetcomplete/data/osm/mapdata/BoundingBox.kt",
                // OSM API XML parser (parses a /map download into MutableMapData)
                "de/westnordost/streetcomplete/data/osm/mapdata/MapDataApiParser.kt",
                // element geometry model
                "de/westnordost/streetcomplete/data/osm/geometry/ElementGeometry.kt",
                "de/westnordost/streetcomplete/data/osm/geometry/ElementGeometryEntry.kt",
                // small pure-Kotlin helpers the above transitively need
                "de/westnordost/streetcomplete/util/ktx/XmlReader.kt",
                "de/westnordost/streetcomplete/util/ktx/Double.kt",
                "de/westnordost/streetcomplete/util/ktx/Collections.kt",
                "de/westnordost/streetcomplete/util/math/SphericalEarthMath.kt",
                "de/westnordost/streetcomplete/util/math/SphericalEarthMathVector3d.kt",
                "de/westnordost/streetcomplete/util/math/Vector3d.kt",
                "de/westnordost/streetcomplete/util/math/AngleMath.kt",
            )
        }
    }
}
