package be.delijn.verkenner

import com.graphhopper.GraphHopper
import com.graphhopper.GHRequest
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

private const val MAX_CANDIDATE_STOPS = 5

class Router(osmPbfPath: String, graphCacheDir: String) {

    private val routingErrors = AtomicInteger(0)

    private val hopper: GraphHopper = GraphHopper().apply {
        osmFile = osmPbfPath
        graphHopperLocation = graphCacheDir
        setEncodedValuesString("foot_access, foot_average_speed")
        val footModel = CustomModel()
            .addToPriority(Statement.If("!foot_access", Statement.Op.MULTIPLY, "0"))
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "foot_average_speed"))
        setProfiles(Profile("foot").setWeighting("custom").setCustomModel(footModel))
        importOrLoad()
    }

    fun routeToNearestStop(
        fromLat: Double,
        fromLon: Double,
        candidateStops: List<Stop>
    ): RoutingResult? {
        // Pre-sort by straight-line distance, take closest candidates to minimize routing calls
        val sorted = candidateStops.sortedBy { haversineKm(fromLat, fromLon, it.lat, it.lon) }
        val candidates = sorted.take(MAX_CANDIDATE_STOPS)

        return candidates.mapNotNull { stop ->
            val req = GHRequest(fromLat, fromLon, stop.lat, stop.lon).setProfile("foot")
            val rsp = hopper.route(req)
            if (rsp.hasErrors()) {
                routingErrors.incrementAndGet()
                null
            } else RoutingResult(
                walkingMinutes = rsp.best.time / 60_000.0,
                nearestStopName = stop.name
            )
        }.minByOrNull { it.walkingMinutes }
    }

    fun routingErrorCount(): Int = routingErrors.get()
    fun close() = hopper.close()
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
