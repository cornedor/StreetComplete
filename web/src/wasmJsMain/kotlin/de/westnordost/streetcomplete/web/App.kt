package de.westnordost.streetcomplete.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Placeholder UI for the M0 walking skeleton. Its only job is to prove that Compose
 * Multiplatform renders and stays interactive in the browser via Kotlin/Wasm. It will be
 * replaced by the shared `:app` UI as the port progresses (see docs/pwa-port/ROADMAP.md).
 */
@Composable
fun App() {
    MaterialTheme {
        var count by remember { mutableStateOf(0) }
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
                text = "Walking skeleton — Compose Multiplatform is running in your browser via Kotlin/Wasm.",
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { count++ }) {
                Text("Tapped $count ${if (count == 1) "time" else "times"}")
            }
        }
    }
}
