package be.delijn.verkenner

import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng
import kotlinx.serialization.json.*

private const val RESOLUTION = 10

private val h3 = H3Core.newInstance()

data class HexCell(val index: String, val centerLat: Double, val centerLon: Double)

fun buildGhentH3Grid(): List<HexCell> {
    val boundaryJson = object {}.javaClass.getResourceAsStream("/ghent-boundary.geojson")!!
        .bufferedReader().readText()
    val rings = extractBoundaryRings(boundaryJson)
    val indices = h3.polygonToCellAddresses(rings.outer, rings.holes, RESOLUTION)
    return indices.map { idx ->
        val center = h3.cellToLatLng(idx)
        HexCell(index = idx, centerLat = center.lat, centerLon = center.lng)
    }
}

fun hexBoundary(index: String): List<LatLng> = h3.cellToBoundary(index)

fun loadGhentBoundaryRing(): List<LatLng> {
    val json = object {}.javaClass.getResourceAsStream("/ghent-boundary.geojson")!!
        .bufferedReader().readText()
    return extractBoundaryRings(json).outer
}

fun isInsidePolygon(lat: Double, lon: Double, ring: List<LatLng>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i].lng; val yi = ring[i].lat
        val xj = ring[j].lng; val yj = ring[j].lat
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

private data class BoundaryRings(val outer: List<LatLng>, val holes: List<List<LatLng>>)

private fun extractBoundaryRings(geojson: String): BoundaryRings {
    val root = Json.parseToJsonElement(geojson).jsonObject
    val features = root["features"]?.jsonArray ?: error("No features in boundary GeoJSON")
    val geometry = features.first().jsonObject["geometry"]!!.jsonObject
    val type = geometry["type"]!!.jsonPrimitive.content

    return when (type) {
        "Polygon" -> {
            val rings = geometry["coordinates"]!!.jsonArray
            BoundaryRings(
                outer = rings[0].jsonArray.toLatLng(),
                holes = rings.drop(1).map { it.jsonArray.toLatLng() }
            )
        }
        "MultiPolygon" -> {
            // Pick the polygon whose outer ring has the most points (largest polygon)
            val polygons = geometry["coordinates"]!!.jsonArray
            val biggestPoly = polygons.maxByOrNull { it.jsonArray[0].jsonArray.size }!!.jsonArray
            BoundaryRings(
                outer = biggestPoly[0].jsonArray.toLatLng(),
                holes = biggestPoly.drop(1).map { it.jsonArray.toLatLng() }
            )
        }
        else -> error("Unsupported geometry type: $type")
    }
}

private fun JsonArray.toLatLng(): List<LatLng> = map { point ->
    val arr = point.jsonArray
    LatLng(arr[1].jsonPrimitive.double, arr[0].jsonPrimitive.double)
}
