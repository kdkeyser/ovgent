package be.delijn.verkenner

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val stopsOnly = args.contains("--stops-only")

    val gtfsDir = "data/gtfs"
    val osmFiles = File("data/osm").listFiles { f -> f.name.endsWith(".osm.pbf") } ?: emptyArray()
    require(osmFiles.size == 1) { "Expected exactly one .osm.pbf in data/osm/, found ${osmFiles.size}" }
    val osmPbf = osmFiles.single().path
    val graphCache = "data/osm/graphhopper-cache"
    val outputDir = "frontend/public"

    File(outputDir).mkdirs()

    println("Parsing GTFS stops...")
    val stops = parseStops(gtfsDir)
    println("  ${stops.size} stops in Ghent bounding box")

    println("Parsing GTFS schedule...")
    val serviceCalendar = parseCalendarDates(gtfsDir)
    val serviceToTrips = parseTrips(gtfsDir)
    val departuresPerStopPerDay = parseDeparturesPerStopPerDay(
        gtfsDir, stops.keys, serviceCalendar, serviceToTrips
    )
    println("  Schedule loaded for ${departuresPerStopPerDay.size} stops")

    println("Filtering qualifying stops...")
    val qualifying = buildQualifyingStops(departuresPerStopPerDay)
    for (mode in FilterMode.entries) {
        println("  ${mode.key}: ${qualifying[mode]?.size ?: 0} stops")
    }

    val stopFeatures = stops.values
        .filter { stop -> FilterMode.entries.any { stop.id in qualifying[it].orEmpty() } }
        .map { stop ->
            buildStopFeature(
                stop,
                qualifiesByMode = FilterMode.entries.associateWith { stop.id in qualifying[it].orEmpty() },
                departuresByDay = departuresPerStopPerDay[stop.id].orEmpty()
            )
        }
    File("$outputDir/ghent-stops.geojson")
        .writeText(buildFeatureCollection(stopFeatures))
    println("Wrote $outputDir/ghent-stops.geojson")

    if (stopsOnly) {
        println("Done (stops-only mode). Skipping hex grid and routing.")
        return
    }

    println("Building H3 grid...")
    val hexCells = buildGhentH3Grid()
    println("  ${hexCells.size} hexes at resolution 10")

    println("Initializing GraphHopper (may take several minutes on first run)...")
    val router = Router(osmPbf, graphCache)

    val stopsByMode = FilterMode.entries.associateWith { mode ->
        stops.values.filter { it.id in qualifying[mode].orEmpty() }
    }

    val threadCount = Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threadCount)
    val completed = AtomicInteger(0)
    val total = hexCells.size

    println("Routing $total hexes across ${FilterMode.entries.size} modes on $threadCount threads...")
    val futures = hexCells.map { hex ->
        pool.submit(Callable<HexResult> {
            val result = HexResult(
                h3Index = hex.index,
                byMode = FilterMode.entries.associateWith { mode ->
                    router.routeToNearestStop(hex.centerLat, hex.centerLon, stopsByMode[mode]!!)
                }
            )
            val done = completed.incrementAndGet()
            if (done % 200 == 0 || done == total) print("  $done/$total\r")
            result
        })
    }
    val hexResults: List<HexResult>
    try {
        hexResults = futures.map { it.get() }
        println("  Done.                    ")
    } finally {
        pool.shutdown()
        router.close()
    }
    val errors = router.routingErrorCount()
    if (errors > 0) println("  Warning: $errors routing call(s) failed (counted as no stop nearby)")

    println("Writing hex GeoJSON...")
    val hexFeatures = hexResults.map { result ->
        buildHexFeature(result, hexBoundary(result.h3Index))
    }
    File("$outputDir/ghent-hexes.geojson")
        .writeText(buildFeatureCollection(hexFeatures))

    println("Done. Output written to $outputDir/")
}
