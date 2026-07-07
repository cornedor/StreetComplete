package de.westnordost.streetcomplete.web.map

/**
 * A WGS84 coordinate. Mirrors the shape of the shared
 * `de.westnordost.streetcomplete.data.osm.mapdata.LatLon` (same `latitude` / `longitude`
 * property names) so that, once `:web` starts compiling against `:app`'s `commonMain`, the map
 * code here can switch to that shared type without touching call sites. Kept local for now for the
 * same reason [de.westnordost.streetcomplete.web.di.WEB_USER_AGENT] is — `:web` is still
 * intentionally decoupled from `:app` (see docs/pwa-port/ROADMAP.md).
 */
data class LatLon(
    val latitude: Double,
    val longitude: Double,
)
