package de.westnordost.streetcomplete.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.ObservableSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * M1 demo screen. Its job is to prove that the web *platform services* wired up in
 * [de.westnordost.streetcomplete.web.di.webModule] actually work in a real browser via
 * Kotlin/Wasm, before the shared `:app` code starts depending on them:
 *
 *  - **Koin DI** — the [ObservableSettings] and [HttpClient] below are resolved from the
 *    container, not constructed inline.
 *  - **Settings over localStorage** — a launch counter is read from and written back to
 *    settings, so it survives a page reload (durable key–value storage).
 *  - **Ktor on the JS engine** — a button issues a real HTTP request to the OSM API and
 *    shows the outcome, which also surfaces the browser CORS reality early (a documented
 *    risk in the roadmap).
 *
 * This is deliberately throwaway UI; it will be replaced by the shared Compose UI as the
 * port progresses (see docs/pwa-port/ROADMAP.md).
 */
@Composable
fun App() {
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

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "StreetComplete Web",
                style = MaterialTheme.typography.h4,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "M1 — web platform services (Koin · settings · Ktor) running in your " +
                    "browser via Kotlin/Wasm.",
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )

            Spacer(Modifier.height(24.dp))
            Divider(Modifier.widthIn(max = 520.dp))
            Spacer(Modifier.height(24.dp))

            // --- Settings persistence ---
            Text(
                text = "Persisted in localStorage: opened this app $launchCount " +
                    "${if (launchCount == 1) "time" else "times"}.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Reload the page — the count keeps going up.",
                style = MaterialTheme.typography.caption,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // --- Ktor HTTP request ---
            OsmApiCheck(httpClient)
        }
    }
}

/** A button that pings the OSM API over Ktor's JS engine and reports the outcome. */
@Composable
private fun OsmApiCheck(httpClient: HttpClient) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                status = "Requesting…"
                scope.launch {
                    status = try {
                        val body = httpClient.get(OSM_API_CAPABILITIES_URL).bodyAsText()
                        "OK — OSM API reachable (${body.length} bytes of capabilities XML)."
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
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}

private const val KEY_LAUNCH_COUNT = "web.demo.launchCount"
private const val OSM_API_CAPABILITIES_URL = "https://api.openstreetmap.org/api/0.6/capabilities"
