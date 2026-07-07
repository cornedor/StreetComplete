package de.westnordost.streetcomplete.web.map

import kotlin.js.JsAny

/**
 * Milestone M2 (Map MVP) of docs/pwa-port/ROADMAP.md — the web map, rendered by
 * [maplibre-gl-js](https://maplibre.org/maplibre-gl-js/docs/) (the web sibling of the Android
 * MapLibre SDK the app already uses).
 *
 * This is the boundary the roadmap (§5.1) asks for: **all JavaScript / maplibre types stay behind
 * this file.** The `js(...)` interop functions below are the only place that touches the
 * `maplibregl` global; everything else in the Kotlin codebase talks to the [WebMap] wrapper in
 * terms of plain Kotlin types ([LatLon], `Double`, `String`). When the shared map-component
 * abstractions under `screens/main/map/components` are ported, they will bind to a type like this
 * one instead of the Android `MapView`, and no domain code will ever see a `JsAny`.
 *
 * The maplibre library itself is loaded as a global `<script>` by `index.html` (see resources),
 * so the `maplibregl` symbol referenced in the `js(...)` bodies is available by the time any of
 * these run. [isMapLibreLoaded] lets the caller check that before constructing a map, so an
 * offline launch (no CDN) degrades to "no map" rather than crashing the whole app.
 */
class WebMap private constructor(private val handle: JsAny) {

    /** Animate the camera to [position] at [zoom]. Used by the overlay UI to drive the map. */
    fun flyTo(position: LatLon, zoom: Double) {
        mlFlyTo(handle, position.longitude, position.latitude, zoom)
    }

    /**
     * Drop a pin at [position]. A stand-in for the quest pins that the ported
     * `screens/main/map/components` pin layer will manage in later milestones; for M2 it just
     * proves markers render through the interop boundary.
     */
    fun addPin(position: LatLon, color: String = PIN_COLOR) {
        mlAddMarker(handle, position.longitude, position.latitude, color)
    }

    /** Recompute the map size after its container changed. */
    fun resize() {
        mlResize(handle)
    }

    companion object {
        /** The StreetComplete quest-pin red, matching the Android app's pin tint. */
        const val PIN_COLOR = "#D14841"

        /**
         * Create a map in the DOM element with id [containerId], rendering [styleUrl] centered on
         * [center] at [zoom]. Adds maplibre's built-in navigation (zoom) and geolocate
         * (current-location, over the browser Geolocation API) controls.
         *
         * Call [isMapLibreLoaded] first — this throws if the `maplibregl` global is absent.
         */
        fun create(containerId: String, styleUrl: String, center: LatLon, zoom: Double): WebMap =
            WebMap(mlCreateMap(containerId, styleUrl, center.longitude, center.latitude, zoom))
    }
}

/** Whether the maplibre-gl-js global finished loading (it is a CDN `<script>`; may be offline). */
fun isMapLibreLoaded(): Boolean =
    js("typeof maplibregl !== 'undefined'")

// --- Interop with maplibre-gl-js -------------------------------------------------------------
// Each function's whole body is a single js(...) call — the Kotlin/Wasm form for JS interop — so
// the maplibre types never enter Kotlin. Object handles cross the boundary as opaque JsAny.

private fun mlCreateMap(
    containerId: String,
    styleUrl: String,
    lng: Double,
    lat: Double,
    zoom: Double,
): JsAny = js(
    """(() => {
        const map = new maplibregl.Map({
            container: containerId,
            style: styleUrl,
            center: [lng, lat],
            zoom: zoom,
            attributionControl: { compact: true },
        });
        map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
        map.addControl(new maplibregl.GeolocateControl({
            positionOptions: { enableHighAccuracy: true },
            trackUserLocation: true,
            showUserLocation: true,
        }), 'top-right');
        return map;
    })()"""
)

private fun mlFlyTo(map: JsAny, lng: Double, lat: Double, zoom: Double): JsAny? =
    js("map.flyTo({ center: [lng, lat], zoom: zoom, essential: true })")

private fun mlAddMarker(map: JsAny, lng: Double, lat: Double, color: String): JsAny =
    js("new maplibregl.Marker({ color: color }).setLngLat([lng, lat]).addTo(map)")

private fun mlResize(map: JsAny): JsAny? =
    js("map.resize()")
