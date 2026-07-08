package de.westnordost.streetcomplete.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.ObservableSettings
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataApiParser
import de.westnordost.streetcomplete.data.osm.mapdata.toOsmApiString
import de.westnordost.streetcomplete.web.data.ConflictAlgorithm
import de.westnordost.streetcomplete.web.data.Database
import de.westnordost.streetcomplete.web.map.WebMap
import de.westnordost.streetcomplete.web.map.toGeoJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * M2 overlay panel. It sits in a fixed card on top of the full-screen maplibre map (see
 * [main]) and demonstrates the pieces wired up so far:
 *
 *  - **Compose ↔ map interop** — the "fly to" buttons drive the [WebMap] through the Kotlin
 *    wrapper, proving Compose UI can control the map across the JS-interop boundary (roadmap §5.1)
 *    without touching any JS types.
 *  - **Koin DI + settings over localStorage (M1)** — the durable launch counter is read/written
 *    through the DI-resolved [ObservableSettings].
 *  - **Ktor on the JS engine (M1)** — the button issues a real request to the OSM API.
 *
 * [map] is nullable: if maplibre-gl-js could not load (e.g. an offline launch with no CDN), the
 * app still comes up — the shell and services work, only the map is absent. This keeps the M5
 * "launches offline" property from regressing.
 *
 * This is still throwaway UI; it is replaced by the shared Compose UI as the port progresses.
 */
@Composable
fun App(map: WebMap?) {
    MaterialTheme {
        // Resolve platform services from Koin (started in main()).
        val koin = remember { KoinPlatform.getKoin() }
        val settings = remember { koin.get<ObservableSettings>() }
        val httpClient = remember { koin.get<HttpClient>() }

        // Durable, localStorage-backed launch counter: incremented once per page load.
        val launchCount = remember {
            val next = settings.getInt(KEY_LAUNCH_COUNT, 0) + 1
            settings.putInt(KEY_LAUNCH_COUNT, next)
            next
        }

        Card(elevation = 6.dp, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("StreetComplete Web", style = MaterialTheme.typography.h6)
                Text(
                    "M2 — map preview. maplibre-gl-js renders below; pan, zoom and the locate " +
                        "button (top-right) work. Scroll down to download the visible area and draw " +
                        "the real shared-parser output on the map.",
                    style = MaterialTheme.typography.caption,
                )

                Divider()

                // --- Compose drives the map across the interop boundary ---
                if (map != null) {
                    Text("Fly the map to:", style = MaterialTheme.typography.body2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((name, pos) in DEMO_PLACES) {
                            Button(onClick = { map.flyTo(pos, DEMO_PLACE_ZOOM) }) { Text(name) }
                        }
                    }
                } else {
                    Text(
                        "Map unavailable — maplibre-gl-js did not load (offline, or its CDN is " +
                            "blocked). The app shell and services below still work.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                    )
                }

                Divider()

                // --- Settings persistence (M1) ---
                Text(
                    "Opened $launchCount ${if (launchCount == 1) "time" else "times"} " +
                        "(persisted in localStorage).",
                    style = MaterialTheme.typography.body2,
                )

                // --- Ktor HTTP request (M1) ---
                OsmApiCheck(httpClient)

                Divider()

                // --- Web Database: sql.js + IndexedDB (M1) ---
                DatabaseSection()

                Divider()

                // --- Real shared code on wasm: download + parse + render OSM data (M1/M2/M3) ---
                OsmDownloadParseSection(httpClient, map)
            }
        }
    }
}

/**
 * Downloads an OpenStreetMap area, parses it with the **real, shared** [MapDataApiParser] — the
 * exact class the Android/iOS apps use, compiled here to wasm — and **draws the result on the map**.
 *
 * This closes the loop between the two things that already worked in isolation: the shared parser
 * (M1) and the maplibre map (M2). When a [map] is present the download targets its **visible
 * viewport** ([WebMap.getBounds]) and the parsed [de.westnordost.streetcomplete.data.osm.mapdata.MapData]
 * is rendered as GeoJSON via [WebMap.setMapData] — the first real map component that consumes shared
 * `MapData` (roadmap §5.1), and a concrete step toward MVP §7.2 ("download data for the area"). With
 * no map (offline / CDN blocked) it falls back to the fixed [DEMO_BBOX] and just parses + reports.
 *
 * The whole pipeline is shared domain code: [BoundingBox] builds the OSM `bbox` query via its real
 * `toOsmApiString()`, and the shared parser produces the real model of `Node`/`Way`/`Relation`.
 */
@Composable
private fun OsmDownloadParseSection(httpClient: HttpClient, map: WebMap?) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    Text("Shared OSM parser on wasm:", style = MaterialTheme.typography.body2)
    Text(
        if (map != null) {
            "Downloads the map's visible area, parses it with the real shared MapDataApiParser " +
                "(the same class the Android/iOS apps use), and draws it on the map. Zoom in first."
        } else {
            "Downloads a ~150 m area of central Berlin and parses it with the real shared " +
                "MapDataApiParser (the same class the Android/iOS apps use)."
        },
        style = MaterialTheme.typography.caption,
    )
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            status = "Downloading & parsing…"
            report = emptyList()
            scope.launch {
                try {
                    report = downloadParseAndRender(httpClient, map)
                    status = ""
                } catch (e: Throwable) {
                    // A CORS/network failure, or a parser/model error, surfaces here.
                    status = "Failed: ${e.message ?: e::class.simpleName}"
                } finally {
                    busy = false
                }
            }
        },
    ) {
        Text(if (map != null) "Download & render visible area" else "Download & parse area")
    }
    if (status.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.body2)
    }
    for (line in report) {
        Text("• $line", style = MaterialTheme.typography.body2)
    }
}

/**
 * Fetches an area from the OSM API, parses it with the shared [MapDataApiParser], and — when a
 * [map] is given — renders the parsed [de.westnordost.streetcomplete.data.osm.mapdata.MapData] on
 * it. Returns a human-readable report. The bbox is the map's visible viewport (or [DEMO_BBOX] if
 * there is no map / its bounds can't be read).
 */
private suspend fun downloadParseAndRender(httpClient: HttpClient, map: WebMap?): List<String> {
    val lines = mutableListOf<String>()

    val bbox = map?.getBounds() ?: DEMO_BBOX

    // The OSM /map endpoint rejects bboxes above 0.25 deg², and a large area would be a heavy
    // in-browser parse+render anyway. Guard with a demo-friendly cap and a clear "zoom in" message
    // rather than surfacing an opaque HTTP 400.
    val area = (bbox.max.latitude - bbox.min.latitude) * (bbox.max.longitude - bbox.min.longitude)
    if (area > MAX_DOWNLOAD_AREA_DEG2) {
        return listOf("Visible area too large to download — zoom in and try again.")
    }

    // Build the bbox query with the shared BoundingBox.toOsmApiString() (minLon,minLat,maxLon,maxLat).
    val url = "$OSM_API_BASE/map?bbox=${bbox.toOsmApiString()}"
    val xml = httpClient.get(url).bodyAsText()
    lines += "Downloaded ${xml.length} chars of OSM XML."

    // Parse with the real shared MapDataApiParser. We pass the decoded string (its CharSequence
    // overload) rather than a byte Source: on wasm xmlutil's byte reader aborts at the first
    // multi-byte UTF-8 char (see MapDataApiParser). This is real shared domain code running on wasm.
    val mapData = MapDataApiParser().parseMapData(xml)
    lines += "Parsed: ${mapData.nodes.size} nodes, ${mapData.ways.size} ways, " +
        "${mapData.relations.size} relations."

    // Draw the parsed model on the real map — ways as lines, tagged nodes as points (see toGeoJson).
    if (map != null) {
        map.setMapData(mapData.toGeoJson())
        lines += "Rendered ways + tagged nodes on the map."
    }

    val named = mapData.mapNotNull { el -> el.tags["name"]?.let { "${el.type.name.lowercase()}: $it" } }
        .distinct()
        .take(5)
    if (named.isNotEmpty()) {
        lines += "Named features include: ${named.joinToString("; ")}."
    }
    return lines
}

/**
 * Exercises the web [Database] (sql.js + IndexedDB, see data/WebDatabase.kt) end-to-end in the
 * browser: it creates a representative slice of StreetComplete's real schema, round-trips a typed
 * row and a blob through the synchronous `Database` API, and shows a DB-backed launch counter that
 * survives reloads — proving the persistence layer, not just the in-memory store.
 *
 * The database comes up asynchronously (Wasm load + IndexedDB read), so this observes
 * [WebDatabaseHolder]: while it is still initializing it says so, and if sql.js could not load
 * (offline / CDN blocked) it reports "unavailable" rather than hanging — the rest of the app works.
 */
@Composable
private fun DatabaseSection() {
    Text("Web database (sql.js + IndexedDB):", style = MaterialTheme.typography.body2)

    val ready = WebDatabaseHolder.ready.value
    val database = WebDatabaseHolder.database.value

    when {
        !ready -> Text("Initializing…", style = MaterialTheme.typography.body2)
        database == null -> Text(
            "Unavailable — sql.js did not load (offline, or its CDN is blocked). The rest of the " +
                "app still works; only persistence is off.",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.error,
        )
        else -> {
            val report = remember(database) { runDatabaseDemo(database) }
            for (line in report) {
                Text("• $line", style = MaterialTheme.typography.body2)
            }
        }
    }
}

/**
 * Runs a few operations against the real, synchronous [Database] contract and returns a
 * human-readable report. Kept deliberately close to how the shared DAOs use the interface: real
 * table shapes, typed columns, a blob, and conflict-replace upserts.
 *
 * Uses `CREATE TABLE IF NOT EXISTS` because — unlike the shared `DatabaseInitializer.onCreate`,
 * which runs exactly once — this demo re-runs on every load against the persisted database.
 */
private fun runDatabaseDemo(db: Database): List<String> = try {
    val lines = mutableListOf<String>()

    // A representative slice of the real schema: NoteTable + its spatial index, and the
    // blob-bearing WayGeometryTable (verbatim column shapes from app/.../data/**).
    db.exec(
        "CREATE TABLE IF NOT EXISTS osm_notes (" +
            "note_id int PRIMARY KEY, latitude double NOT NULL, longitude double NOT NULL, " +
            "note_created int NOT NULL, note_closed int, note_status varchar(255) NOT NULL, " +
            "comments text NOT NULL, last_sync int NOT NULL)"
    )
    db.exec("CREATE INDEX IF NOT EXISTS osm_notes_spatial_index ON osm_notes (latitude, longitude)")
    db.exec(
        "CREATE TABLE IF NOT EXISTS elements_geometry_ways (" +
            "id int PRIMARY KEY, geometry_polylines blob, geometry_polygons blob, " +
            "latitude double NOT NULL, longitude double NOT NULL)"
    )
    db.exec("CREATE TABLE IF NOT EXISTS web_launches (id int PRIMARY KEY, n int NOT NULL)")
    lines += "Schema created (osm_notes + index, elements_geometry_ways, web_launches)."

    // DB-backed launch counter — proves the image is persisted to IndexedDB and reloaded.
    val previous = db.queryOne(
        "web_launches", columns = arrayOf("n"), where = "id = ?", args = arrayOf<Any>(1)
    ) { it.getInt("n") }
    val count = if (previous == null) {
        db.insert("web_launches", listOf("id" to 1, "n" to 1)); 1
    } else {
        db.update("web_launches", listOf("n" to previous + 1), "id = ?", arrayOf<Any>(1)); previous + 1
    }
    lines += "Opened $count ${if (count == 1) "time" else "times"} (persisted in SQLite → IndexedDB)."

    // Typed-row round-trip through insert + query (note_closed left NULL to exercise nullables).
    db.insert(
        "osm_notes",
        listOf(
            "note_id" to 1L, "latitude" to 52.5200, "longitude" to 13.4050,
            "note_created" to 1_700_000_000L, "note_status" to "open",
            "comments" to "[]", "last_sync" to 0L,
        ),
        ConflictAlgorithm.REPLACE,
    )
    val note = db.queryOne("osm_notes", where = "note_id = ?", args = arrayOf<Any>(1L)) {
        Triple(it.getDouble("latitude"), it.getStringOrNull("note_status"), it.getLongOrNull("note_closed"))
    }
    lines += "Row round-trip: lat=${note?.first}, status=${note?.second}, closed=${note?.third}."

    // Blob round-trip (the geometry columns are BLOBs; verify the bytes survive the JSON boundary).
    val blob = byteArrayOf(0, 1, 2, 3, 126, 127, -1, -128, 42)
    db.insert(
        "elements_geometry_ways",
        listOf(
            "id" to 1L, "geometry_polylines" to blob, "geometry_polygons" to null,
            "latitude" to 52.52, "longitude" to 13.40,
        ),
        ConflictAlgorithm.REPLACE,
    )
    val readBlob = db.queryOne(
        "elements_geometry_ways", columns = arrayOf("geometry_polylines"), where = "id = ?", args = arrayOf<Any>(1L)
    ) { it.getBlobOrNull("geometry_polylines") }
    val blobOk = readBlob != null && readBlob.contentEquals(blob)
    lines += "Blob round-trip (${blob.size} bytes): ${if (blobOk) "OK — bytes match" else "FAILED"}."

    lines
} catch (e: Throwable) {
    listOf("Database demo failed: ${e.message ?: e::class.simpleName}")
}

/** A button that pings the OSM API over Ktor's JS engine and reports the outcome. */
@Composable
private fun OsmApiCheck(httpClient: HttpClient) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Button(
        enabled = !busy,
        onClick = {
            busy = true
            status = "Requesting…"
            scope.launch {
                status = try {
                    val body = httpClient.get(OSM_API_CAPABILITIES_URL).bodyAsText()
                    "OK — OSM API reachable (${body.length} bytes)."
                } catch (e: Throwable) {
                    // In the browser this is where a CORS or network failure would surface.
                    "Request failed: ${e.message ?: e::class.simpleName}"
                } finally {
                    busy = false
                }
            }
        },
    ) {
        Text("Ping OSM API")
    }
    if (status.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.body2, modifier = Modifier.fillMaxWidth())
    }
}

/** A few well-known places the overlay can fly the map to, to demonstrate Compose → map control. */
private val DEMO_PLACES = listOf(
    "Berlin" to LatLon(52.5200, 13.4050),
    "Paris" to LatLon(48.8566, 2.3522),
    "Tokyo" to LatLon(35.6762, 139.6503),
)
private const val DEMO_PLACE_ZOOM = 16.0

private const val KEY_LAUNCH_COUNT = "web.demo.launchCount"
private const val OSM_API_BASE = "https://api.openstreetmap.org/api/0.6"
private const val OSM_API_CAPABILITIES_URL = "$OSM_API_BASE/capabilities"

/** Cap on the downloadable viewport (deg²). Well below OSM's hard 0.25 limit: a full desktop
 *  viewport at zoom 16 over dense inner city is already ~0.001 deg² / ~16 MB of XML, so this leaves
 *  a little headroom (down to ~zoom 15) while a more zoomed-out view is asked to zoom in — otherwise
 *  the in-browser download + parse + render would be needlessly heavy. */
private const val MAX_DOWNLOAD_AREA_DEG2 = 0.004

/** A tiny (~150 m) area of central Berlin to download & parse — small enough to keep the request
 *  and in-browser parse light, dense enough to return real nodes, ways and named features. */
private val DEMO_BBOX = BoundingBox(
    minLatitude = 52.5190, minLongitude = 13.4030,
    maxLatitude = 52.5205, maxLongitude = 13.4050,
)
