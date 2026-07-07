package de.westnordost.streetcomplete.web

import androidx.compose.runtime.mutableStateOf
import de.westnordost.streetcomplete.web.data.Database

/**
 * Bridges the asynchronous [Database] bootstrap in [main] to the Compose UI. The database comes up
 * off the main path (sql.js Wasm load + IndexedDB read), so the overlay can't take it as a plain
 * constructor argument the way it does the map; instead it observes this snapshot state and
 * recomposes when the database becomes ready.
 *
 * [ready] flips true once the bootstrap has finished, whether or not it produced a database:
 * [database] is null when sql.js could not load (offline / CDN blocked), which the UI reports as
 * "no persistence" rather than a hang.
 */
object WebDatabaseHolder {
    val ready = mutableStateOf(false)
    val database = mutableStateOf<Database?>(null)
}
