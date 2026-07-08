package de.westnordost.streetcomplete.web.map

import de.westnordost.streetcomplete.data.osm.mapdata.MapData
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Convert shared [MapData] — the real model the shared `MapDataApiParser` produces — into a GeoJSON
 * `FeatureCollection` string for the web map ([WebMap.setMapData]).
 *
 *  - every **way** becomes a `LineString` of its resolved node positions (its outline; closed ways
 *    included), skipping any way whose nodes did not all come down in the same response;
 *  - every **tagged node** becomes a `Point`. Untagged nodes are just way vertices, so they are
 *    skipped to keep the feature count — and the map — readable.
 *
 * Only geometry is emitted for now (no tag properties); [WebMap.setMapData] styles features by
 * geometry type. This is deliberately built by hand from the model rather than reusing
 * `ElementGeometryCreator`: that creator pulls in the parts of `commonMain` still tied to
 * `osmfeatures`, which has no wasmJs target yet (see docs/pwa-port/adr/0002-shared-source-on-wasm.md).
 */
fun MapData.toGeoJson(): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        for (way in ways) {
            // Resolve node ids → positions. A way missing some of its nodes (only partially in this
            // download) can't be drawn as a continuous line, so require every node to be present.
            val coords = way.nodeIds.map { getNode(it)?.position }
            if (coords.size < 2 || coords.any { it == null }) continue
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "LineString")
                    putJsonArray("coordinates") {
                        for (p in coords) addJsonArray { add(p!!.longitude); add(p.latitude) }
                    }
                }
            }
        }
        for (node in nodes) {
            if (node.tags.isEmpty()) continue
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {}
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") {
                        add(node.position.longitude)
                        add(node.position.latitude)
                    }
                }
            }
        }
    }
}.toString()
