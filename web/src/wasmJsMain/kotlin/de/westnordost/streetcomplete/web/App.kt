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
import de.westnordost.streetcomplete.web.map.LatLon
import de.westnordost.streetcomplete.web.map.WebMap
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
                    "M2 — map preview. maplibre-gl-js renders below; pan, zoom and the " +
                        "locate button (top-right) work.",
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
            }
        }
    }
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
private const val DEMO_PLACE_ZOOM = 12.0

private const val KEY_LAUNCH_COUNT = "web.demo.launchCount"
private const val OSM_API_CAPABILITIES_URL = "https://api.openstreetmap.org/api/0.6/capabilities"
