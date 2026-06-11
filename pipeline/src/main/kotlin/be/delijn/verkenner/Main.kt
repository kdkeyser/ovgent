package be.delijn.verkenner

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

private val MONTH_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")

fun main(args: Array<String>) {
    val weekIdx = args.indexOf("--week")
    require(weekIdx >= 0 && weekIdx + 1 < args.size) {
        "--week YYYY-Www is required (e.g. --week 2026-W23)"
    }
    val weekArg = args[weekIdx + 1]
    val stopsOnly = args.contains("--stops-only")

    val monday = parseWeekArg(weekArg)
    val representativeWeek: Map<DayOfWeek, LocalDate> = DayOfWeek.entries
        .associateWith { dow -> monday.plusDays((dow.value - 1).toLong()) }

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

    val allServiceDates = serviceCalendar.values.flatten().toSet()
    val sunday = monday.plusDays(6)
    println("Coverage for $weekArg (${monday.format(MONTH_DAY_FMT)} – ${sunday.format(MONTH_DAY_FMT)} ${monday.format(YEAR_FMT)}):")
    val missingDays = mutableListOf<String>()
    for (dow in DayOfWeek.entries) {
        val date = representativeWeek[dow]!!
        val present = date in allServiceDates
        println("  ${dow.name.padEnd(10)} ${if (present) "✓" else "✗  (no service data)"}")
        if (!present) missingDays.add(dow.name)
    }
    val coverageWarning: String? = if (missingDays.isEmpty()) null else "Missing: ${missingDays.joinToString(", ")}"

    val departuresPerStopPerDay = parseDeparturesPerStopPerDay(
        gtfsDir, stops.keys, serviceCalendar, serviceToTrips, representativeWeek
    )
    println("  Schedule loaded for ${departuresPerStopPerDay.size} stops")

    println("Filtering qualifying stops...")
    val qualifying: Map<DayScope, Map<FilterMode, Set<String>>> = DayScope.entries
        .associateWith { scope -> buildQualifyingStops(departuresPerStopPerDay, scope) }
    for (scope in DayScope.entries) {
        for (mode in FilterMode.entries) {
            println("  ${mode.key}/${scope.key}: ${qualifying[scope]!![mode]?.size ?: 0} stops")
        }
    }

    val ghentRing = loadGhentBoundaryRing()

    if (stopsOnly) {
        writeStopFile(outputDir, weekArg, stops, qualifying, departuresPerStopPerDay, ghentRing, referencedStopNames = emptySet())
        upsertManifest("$outputDir/weeks-manifest.json", weekArg, monday, coverageWarning)
        println("Done (stops-only mode).")
        return
    }

    println("Building H3 grid...")
    val hexCells = buildGhentH3Grid()
    println("  ${hexCells.size} hexes at resolution 10")

    println("Initializing GraphHopper (may take several minutes on first run)...")
    val router = Router(osmPbf, graphCache)

    val stopsByModeAndScope: Map<FilterMode, Map<DayScope, List<Stop>>> =
        FilterMode.entries.associateWith { mode ->
            DayScope.entries.associateWith { scope ->
                stops.values.filter { it.id in qualifying[scope]!![mode].orEmpty() }
            }
        }

    val threadCount = Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threadCount)
    val completed = AtomicInteger(0)
    val total = hexCells.size

    println("Routing $total hexes × ${FilterMode.entries.size} modes × ${DayScope.entries.size} scopes on $threadCount threads...")
    val futures = hexCells.map { hex ->
        pool.submit(Callable<HexResult> {
            val result = HexResult(
                h3Index = hex.index,
                byMode = FilterMode.entries.associateWith { mode ->
                    DayScope.entries.associateWith { scope ->
                        router.routeToNearestStop(
                            hex.centerLat, hex.centerLon,
                            stopsByModeAndScope[mode]!![scope]!!
                        )
                    }
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

    val referencedStopNames: Set<String> = hexResults.flatMapTo(mutableSetOf()) { result ->
        result.byMode.values.flatMap { scopeMap -> scopeMap.values.mapNotNull { it?.nearestStopName } }
    }
    writeStopFile(outputDir, weekArg, stops, qualifying, departuresPerStopPerDay, ghentRing, referencedStopNames)

    println("Writing hex GeoJSON...")
    val hexFeatures = hexResults.map { result ->
        buildHexFeature(result, hexBoundary(result.h3Index))
    }
    File("$outputDir/ghent-hexes-$weekArg.geojson")
        .writeText(buildFeatureCollection(hexFeatures))

    upsertManifest("$outputDir/weeks-manifest.json", weekArg, monday, coverageWarning)
    println("Done. Output written to $outputDir/")
}

private fun writeStopFile(
    outputDir: String,
    weekArg: String,
    stops: Map<String, Stop>,
    qualifying: Map<DayScope, Map<FilterMode, Set<String>>>,
    departuresPerStopPerDay: Map<String, Map<java.time.DayOfWeek, List<java.time.LocalTime>>>,
    ghentRing: List<com.uber.h3core.util.LatLng>,
    referencedStopNames: Set<String>
) {
    val features = stops.values
        .filter { stop ->
            isInsidePolygon(stop.lat, stop.lon, ghentRing) || stop.name in referencedStopNames
        }
        .map { stop ->
            buildStopFeature(
                stop,
                qualifiesByModeAndScope = FilterMode.entries.associateWith { mode ->
                    DayScope.entries.associateWith { scope ->
                        stop.id in qualifying[scope]!![mode].orEmpty()
                    }
                },
                departuresByDay = departuresPerStopPerDay[stop.id].orEmpty()
            )
        }
    File("$outputDir/ghent-stops-$weekArg.geojson").writeText(buildFeatureCollection(features))
    println("Wrote $outputDir/ghent-stops-$weekArg.geojson (${features.size} stops)")
}

private fun upsertManifest(
    manifestPath: String,
    weekId: String,
    monday: LocalDate,
    coverageWarning: String?
) {
    val sunday = monday.plusDays(6)
    val startStr = monday.format(MONTH_DAY_FMT)
    val endStr = if (monday.month == sunday.month) {
        sunday.format(DateTimeFormatter.ofPattern("d yyyy"))
    } else {
        sunday.format(DateTimeFormatter.ofPattern("MMM d yyyy"))
    }
    val label = "$startStr – $endStr"

    val file = File(manifestPath)
    val existing = if (file.exists()) {
        Json.parseToJsonElement(file.readText()).jsonObject
    } else {
        buildJsonObject { put("weeks", buildJsonArray {}) }
    }

    val newEntry = buildJsonObject {
        put("id", weekId)
        put("label", label)
        put("startDate", monday.toString())
        put("coverageWarning", coverageWarning?.let(::JsonPrimitive) ?: JsonNull)
    }

    val updatedWeeks = existing["weeks"]!!.jsonArray
        .filter { it.jsonObject["id"]!!.jsonPrimitive.content != weekId }
        .plus(newEntry)
        .sortedBy { it.jsonObject["startDate"]!!.jsonPrimitive.content }

    val updated = buildJsonObject {
        put("weeks", buildJsonArray { updatedWeeks.forEach { add(it) } })
    }

    file.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), updated))
}
