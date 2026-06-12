package be.delijn.verkenner

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

private val MONTH_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")

fun main(args: Array<String>) {
    val weekArgs = parseWeeksArg(args)
    val stopsOnly = args.contains("--stops-only")

    val gtfsDir = "data/gtfs"
    val osmFiles = File("data/osm").listFiles { f -> f.name.endsWith(".osm.pbf") } ?: emptyArray()
    require(osmFiles.size == 1) { "Expected exactly one .osm.pbf in data/osm/, found ${osmFiles.size}" }
    val osmPbf = osmFiles.single().path
    val outputDir = "frontend/public"
    File(outputDir).mkdirs()

    println("Parsing GTFS stops...")
    val stops = parseStops(gtfsDir)
    println("  ${stops.size} stops in bounding box")

    println("Parsing GTFS schedule...")
    val serviceCalendar = parseCalendarDates(gtfsDir)
    val serviceToTrips = parseTrips(gtfsDir)

    println("Parsing stop times (once for all ${weekArgs.size} week(s))...")
    val allStopTimes = parseStopTimes(gtfsDir, stops.keys)

    val allServiceDates = serviceCalendar.values.flatten().toSet()
    val ghentRing = loadGhentBoundaryRing()

    data class WeekData(
        val weekArg: String,
        val monday: LocalDate,
        val coverageWarning: String?,
        val departuresPerStopPerDay: Map<String, Map<DayOfWeek, List<LocalTime>>>,
        val qualifying: Map<DayScope, Map<FilterMode, Set<String>>>,
        val stopsByModeAndScope: Map<FilterMode, Map<DayScope, List<Stop>>>
    )

    val allWeekData = weekArgs.map { weekArg ->
        val monday = parseWeekArg(weekArg)
        val representativeWeek: Map<DayOfWeek, LocalDate> = DayOfWeek.entries
            .associateWith { dow -> monday.plusDays((dow.value - 1).toLong()) }

        val sunday = monday.plusDays(6)
        println("Coverage for $weekArg (${monday.format(MONTH_DAY_FMT)} – ${sunday.format(MONTH_DAY_FMT)} ${monday.format(YEAR_FMT)}):")
        val missingDays = mutableListOf<String>()
        for (dow in DayOfWeek.entries) {
            val date = representativeWeek[dow]!!
            val present = date in allServiceDates
            println("  ${dow.name.padEnd(10)} ${if (present) "✓" else "✗  (no service data)"}")
            if (!present) missingDays.add(dow.name)
        }
        val coverageWarning = if (missingDays.isEmpty()) null else "Missing: ${missingDays.joinToString(", ")}"

        val departuresPerStopPerDay = computeDeparturesPerStopPerDay(
            allStopTimes, serviceCalendar, serviceToTrips, representativeWeek
        )
        println("  Schedule loaded for ${departuresPerStopPerDay.size} stops")

        val qualifying: Map<DayScope, Map<FilterMode, Set<String>>> = DayScope.entries
            .associateWith { scope -> buildQualifyingStops(departuresPerStopPerDay, scope) }
        for (scope in DayScope.entries) {
            for (mode in FilterMode.entries) {
                println("  ${mode.key}/${scope.key}: ${qualifying[scope]!![mode]?.size ?: 0} stops")
            }
        }

        val stopsByModeAndScope: Map<FilterMode, Map<DayScope, List<Stop>>> =
            FilterMode.entries.associateWith { mode ->
                DayScope.entries.associateWith { scope ->
                    stops.values.filter { it.id in qualifying[scope]!![mode].orEmpty() }
                }
            }

        WeekData(weekArg, monday, coverageWarning, departuresPerStopPerDay, qualifying, stopsByModeAndScope)
    }

    if (stopsOnly) {
        for (week in allWeekData) {
            writeStopFile(outputDir, week.weekArg, stops, week.qualifying, week.departuresPerStopPerDay, ghentRing, referencedStopNames = emptySet())
            upsertManifest("$outputDir/weeks-manifest.json", week.weekArg, week.monday, week.coverageWarning)
        }
        println("Done (stops-only mode).")
        return
    }

    println("Building H3 grid...")
    val hexCells = buildGhentH3Grid()
    println("  ${hexCells.size} hexes at resolution 10")

    println("Writing router input...")
    val routerInput = buildJsonObject {
        put("hex_cells", buildJsonArray {
            hexCells.forEach { hex ->
                add(buildJsonObject {
                    put("h3_index", hex.index)
                    put("center_lat", hex.centerLat)
                    put("center_lon", hex.centerLon)
                })
            }
        })
        put("weeks", buildJsonObject {
            for (week in allWeekData) {
                put(week.weekArg, buildJsonObject {
                    for (mode in FilterMode.entries) {
                        for (scope in DayScope.entries) {
                            put("${mode.key}_${scope.key}", buildJsonArray {
                                week.stopsByModeAndScope[mode]!![scope]!!.forEach { stop ->
                                    add(buildJsonObject {
                                        put("id", stop.id)
                                        put("name", stop.name)
                                        put("lat", stop.lat)
                                        put("lon", stop.lon)
                                    })
                                }
                            })
                        }
                    }
                })
            }
        })
    }

    val inputFile = File("$outputDir/router-input.json")
    inputFile.writeText(Json.encodeToString(JsonElement.serializer(), routerInput))

    val totalPasses = weekArgs.size * FilterMode.entries.size * DayScope.entries.size
    println("Running Rust router (${hexCells.size} hexes × $totalPasses routing passes across ${weekArgs.size} week(s))...")
    val rustBinary = "pipeline-rs/target/release/ovgent-router"
    val proc = ProcessBuilder(rustBinary, "--osm", osmPbf, "--input", inputFile.path, "--output-dir", outputDir)
        .inheritIO()
        .start()
    val exitCode = proc.waitFor()
    require(exitCode == 0) { "Rust router exited with code $exitCode" }

    // Parse and write GeoJSON for all weeks in parallel; manifest writes are sequential after.
    println("Processing router output (parallel)...")
    allWeekData.parallelStream().forEach { week ->
        val hexOutputArray = Json.parseToJsonElement(
            File("$outputDir/router-output-${week.weekArg}.json").readText()
        ).jsonArray

        val hexResults: List<HexResult> = hexOutputArray.map { elem ->
            val obj = elem.jsonObject
            val h3Index = obj["h3_index"]!!.jsonPrimitive.content
            val results = obj["results"]!!.jsonObject
            HexResult(
                h3Index = h3Index,
                byMode = FilterMode.entries.associateWith { mode ->
                    DayScope.entries.associateWith { scope ->
                        val key = "${mode.key}_${scope.key}"
                        val r = results[key]
                        if (r == null || r is JsonNull) null
                        else r.jsonObject.let { ro ->
                            RoutingResult(
                                walkingMinutes = ro["walking_minutes"]!!.jsonPrimitive.double,
                                nearestStopName = ro["nearest_stop_name"]!!.jsonPrimitive.content
                            )
                        }
                    }
                }
            )
        }

        val referencedStopNames: Set<String> = hexResults.flatMapTo(mutableSetOf()) { result ->
            result.byMode.values.flatMap { scopeMap -> scopeMap.values.mapNotNull { it?.nearestStopName } }
        }
        writeStopFile(outputDir, week.weekArg, stops, week.qualifying, week.departuresPerStopPerDay, ghentRing, referencedStopNames)

        val hexFeatures = hexResults.map { result -> buildHexFeature(result, hexBoundary(result.h3Index)) }
        File("$outputDir/ghent-hexes-${week.weekArg}.geojson").writeText(buildFeatureCollection(hexFeatures))
        println("Wrote ghent-hexes-${week.weekArg}.geojson")
    }

    // Manifest writes are sequential — they read-modify-write a single shared file.
    for (week in allWeekData) {
        upsertManifest("$outputDir/weeks-manifest.json", week.weekArg, week.monday, week.coverageWarning)
    }

    println("Done. Output written to $outputDir/")
}

private fun parseWeeksArg(args: Array<String>): List<String> {
    val weeksIdx = args.indexOf("--weeks")
    if (weeksIdx >= 0) {
        require(weeksIdx + 1 < args.size) { "--weeks requires a comma-separated list of weeks" }
        return args[weeksIdx + 1].split(",").map { it.trim() }
    }
    val weekIdx = args.indexOf("--week")
    require(weekIdx >= 0 && weekIdx + 1 < args.size) {
        "--week YYYY-Www or --weeks YYYY-Www,... is required"
    }
    return listOf(args[weekIdx + 1])
}

private fun writeStopFile(
    outputDir: String,
    weekArg: String,
    stops: Map<String, Stop>,
    qualifying: Map<DayScope, Map<FilterMode, Set<String>>>,
    departuresPerStopPerDay: Map<String, Map<DayOfWeek, List<LocalTime>>>,
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
