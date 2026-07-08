package de.westnordost.streetcomplete.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import de.westnordost.streetcomplete.web.data.Database
import de.westnordost.streetcomplete.web.data.WebDatabase
import de.westnordost.streetcomplete.web.data.installDatabaseInterop
import de.westnordost.streetcomplete.web.di.webModule
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.web.map.WebMap
import de.westnordost.streetcomplete.web.map.isMapLibreLoaded
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.w3c.dom.HTMLElement

/** Entry point for the StreetComplete web (PWA) build. Starts Koin, brings up the map, kicks off
 *  the async database bootstrap, then mounts the Compose overlay. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Bring up dependency injection before the UI, mirroring how the Android app starts Koin in
    // its Application. The web platform services live in [webModule] (see di/WebModule.kt).
    startKoin {
        modules(webModule)
    }

    // Milestone M2: create the maplibre map on its full-screen container (see index.html). Guard on
    // the library actually being present so an offline launch (no CDN) degrades to "no map" rather
    // than crashing the shell — the app must still come up (preserves the M5 offline property).
    val map: WebMap? = try {
        if (isMapLibreLoaded()) {
            WebMap.create(MAP_CONTAINER_ID, DEFAULT_MAP_STYLE, INITIAL_CENTER, INITIAL_ZOOM).also {
                it.addPin(INITIAL_CENTER)
            }
        } else {
            null
        }
    } catch (e: Throwable) {
        null
    }

    // Milestone M1: bring up the web Database (sql.js + IndexedDB, see data/WebDatabase.kt). This is
    // asynchronous — sql.js loads its Wasm and the saved image is read from IndexedDB — so it runs
    // off the main() path and publishes into [WebDatabaseHolder] when ready. Like the map, it
    // degrades gracefully: if sql.js can't load (offline / CDN blocked) the app still comes up, only
    // without persistence. Once resolved it is also bound into Koin, mirroring AndroidModule's
    // `single { AndroidDatabase(...) }`, so the shared DAOs will consume it there.
    installDatabaseInterop()
    val scope = CoroutineScope(Dispatchers.Default)
    scope.launch {
        val database: Database? = try {
            if (WebDatabase.isSqlJsLoaded()) WebDatabase.create(DB_NAME, scope) else null
        } catch (e: Throwable) {
            null
        }
        if (database != null) {
            loadKoinModules(module { single { database } })
        }
        WebDatabaseHolder.database.value = database
        WebDatabaseHolder.ready.value = true
    }

    // Mount Compose into the small overlay container (not document.body) so its canvas covers only
    // the control card — the rest of the screen stays the interactive map.
    val overlay = document.getElementById(OVERLAY_CONTAINER_ID) as HTMLElement
    ComposeViewport(overlay) {
        App(map)
    }
}

/** DOM id of the full-screen map element (declared in index.html). */
private const val MAP_CONTAINER_ID = "map"

/** DOM id of the Compose overlay element (declared in index.html). */
private const val OVERLAY_CONTAINER_ID = "overlay"

/** Name of the persisted database image (its key in IndexedDB). */
private const val DB_NAME = "streetcomplete"

/**
 * Default base map style. [OpenFreeMap](https://openfreemap.org/) Liberty is a keyless,
 * sign-up-free, full-detail vector style built on OpenStreetMap data — a natural fit for a
 * StreetComplete preview. It is a single swappable constant on purpose; later milestones may point
 * this at StreetComplete's own style/scene assets (roadmap §5.1).
 */
private const val DEFAULT_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** Where the map opens: central Berlin (home of the OSM community). Zoom 16 is StreetComplete's own
 *  quest display/download zoom, so the "download & render visible area" demo works out of the box. */
private val INITIAL_CENTER = LatLon(52.5200, 13.4050)
private const val INITIAL_ZOOM = 16.0
