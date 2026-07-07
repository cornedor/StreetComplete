package de.westnordost.streetcomplete.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import de.westnordost.streetcomplete.web.di.webModule
import kotlinx.browser.document
import org.koin.core.context.startKoin

/** Entry point for the StreetComplete web (PWA) build. Starts Koin, then mounts Compose. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Bring up dependency injection before the UI, mirroring how the Android app starts Koin
    // in its Application. The web platform services live in [webModule] (see di/WebModule.kt).
    startKoin {
        modules(webModule)
    }
    ComposeViewport(document.body!!) {
        App()
    }
}
