package de.westnordost.streetcomplete.web.di

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.StorageSettings
import com.russhwolf.settings.observable.makeObservable
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.userAgent
import org.koin.dsl.module

/**
 * User agent sent by the web build. Mirrors the shape of the shared
 * `ApplicationConstants.USER_AGENT`; once `:web` consumes `:app`'s `commonMain` this can be
 * replaced by that shared constant.
 */
const val WEB_USER_AGENT = "StreetComplete-Web"

/**
 * Koin module for the web platform services (milestone M1).
 *
 * This is the web analogue of `:app`'s `appModule` / `AndroidModule`: it supplies the
 * platform-specific implementations of the seams the shared code depends on. Today that is:
 *
 *  - [ObservableSettings] — the browser's `localStorage` ([StorageSettings]) wrapped via
 *    [makeObservable]. This is exactly the type the shared `Preferences` class takes, so when
 *    the shared preferences module starts compiling for wasmJs it can bind against this single
 *    provided instance unchanged. (Note: `makeObservable` only notifies listeners of changes
 *    made through the same instance — fine here, since all writes go through this singleton.)
 *  - [HttpClient] — on Ktor's JS engine, configured to match `appModule`'s client (user agent +
 *    gzip content-encoding). The shared API clients take an `HttpClient`, so they will consume
 *    this one directly.
 *
 * The web `Database` (sql.js + IndexedDB, see data/WebDatabase.kt) is the third M1 seam. Because it
 * comes up asynchronously (Wasm load + IndexedDB read), it is not declared here but bound into Koin by
 * `main()` once ready — mirroring `AndroidModule`'s `single { AndroidDatabase(...) }`. See
 * docs/pwa-port/adr/0001-web-database.md.
 */
@OptIn(ExperimentalSettingsApi::class)
val webModule = module {
    single<ObservableSettings> { StorageSettings().makeObservable() }

    single {
        HttpClient(Js) {
            defaultRequest {
                userAgent(WEB_USER_AGENT)
            }
            install(ContentEncoding) {
                gzip()
                // deflate is broken in KTOR, see https://youtrack.jetbrains.com/issue/KTOR-6999
                identity()
            }
        }
    }
}
