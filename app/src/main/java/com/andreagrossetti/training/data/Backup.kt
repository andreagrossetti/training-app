package com.andreagrossetti.training.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the app stores, in one file. */
@Serializable
data class Backup(
    val version: Int = 1,
    val programs: List<Program>,
    val log: List<LogEntry>,
    val routes: Map<String, List<RoutePoint>> = emptyMap(),
    val settings: Settings = Settings(),
    val hrv: List<HrvMeasurement> = emptyList(),
)

fun exportBackup(programs: ProgramRepository, log: LogRepository, settings: SettingsRepository, hrv: HrvRepository): String {
    val entries = log.entries.value
    return backupJson.encodeToString(
        Backup(
            programs = programs.programs.value,
            log = entries,
            routes = entries.filter { it.hasRoute }.associate { it.id to log.routes.load(it.id) },
            settings = settings.value,
            hrv = hrv.measurements.value,
        )
    )
}

/** Replaces all data with the backup's. Returns the number of diary entries restored. */
fun importBackup(
    text: String,
    programs: ProgramRepository,
    log: LogRepository,
    settings: SettingsRepository,
    hrv: HrvRepository,
): Int {
    val backup = backupJson.decodeFromString<Backup>(text)
    backup.routes.forEach { (id, points) -> log.routes.save(id, points) }
    programs.replaceAll(backup.programs)
    log.replaceAll(backup.log)
    settings.replace(backup.settings)
    hrv.replaceAll(backup.hrv)
    return backup.log.size
}

private val backupJson = Json(programJson) { prettyPrint = false }
