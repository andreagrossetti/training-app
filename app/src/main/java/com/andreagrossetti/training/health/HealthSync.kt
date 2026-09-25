package com.andreagrossetti.training.health

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.andreagrossetti.training.data.HrvMeasurement
import com.andreagrossetti.training.data.LogEntry
import com.andreagrossetti.training.data.LogKind
import com.andreagrossetti.training.data.RouteStore
import com.andreagrossetti.training.data.programJson
import com.andreagrossetti.training.data.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** What was last written to Health Connect for one record, to send only changes and deletions. */
@Serializable
private data class Synced(val type: String, val hash: Int)

data class HealthStatus(val lastSync: Long? = null, val records: Int = 0, val error: String? = null)

/**
 * Mirrors the diary (workouts, runs with their route) and the HRV readings into Health Connect,
 * where Samsung Health and other apps can read them. Records carry the app's ids as client record ids,
 * so writing again updates instead of duplicating; entries deleted here are deleted there too.
 */
class HealthSync(private val context: Context, private val routes: RouteStore) {
    private val stateFile = File(context.filesDir, "health_sync.json")
    private val mutex = Mutex()
    private val _status = MutableStateFlow(HealthStatus(records = loadState().size))
    val status: StateFlow<HealthStatus> = _status.asStateFlow()

    val available: Boolean get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Brings Health Connect in line with [entries] and [hrv]. Safe to call often: unchanged records are skipped. */
    suspend fun sync(entries: List<LogEntry>, hrv: List<HrvMeasurement>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val state = loadState().toMutableMap()
                val wanted = desired(entries, hrv)
                val version = System.currentTimeMillis()

                val changed = wanted.filter { (id, w) -> state[id]?.hash != w.hash }
                changed.entries.chunked(BATCH).forEach { batch ->
                    client.insertRecords(batch.mapNotNull { (id, w) -> w.build(id, version) })
                    batch.forEach { (id, w) -> state[id] = Synced(w.type, w.hash) }
                    saveState(state)
                }
                val removed = state.keys - wanted.keys
                removed.groupBy { state.getValue(it).type }.forEach { (type, ids) ->
                    client.deleteRecords(recordClass(type), emptyList(), ids)
                    ids.forEach(state::remove)
                    saveState(state)
                }
                Log.d(TAG, "synced: ${changed.size} written, ${removed.size} deleted, ${state.size} total")
                _status.value = HealthStatus(System.currentTimeMillis(), state.size)
            }.onFailure {
                Log.w(TAG, "sync failed", it)
                _status.value = _status.value.copy(error = it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** Deletes everything this app wrote to Health Connect. */
    suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val state = loadState()
                state.entries.groupBy({ it.value.type }, { it.key }).forEach { (type, ids) ->
                    ids.chunked(BATCH).forEach { client.deleteRecords(recordClass(type), emptyList(), it) }
                }
                saveState(emptyMap())
                _status.value = HealthStatus()
            }.onFailure {
                Log.w(TAG, "remove failed", it)
                _status.value = _status.value.copy(error = it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** A record to have in Health Connect, built only if it changed ([build] may read the route from disk). */
    private class Wanted(val type: String, val hash: Int, val build: (id: String, version: Long) -> Record?)

    private fun desired(entries: List<LogEntry>, hrv: List<HrvMeasurement>): Map<String, Wanted> {
        val out = mutableMapOf<String, Wanted>()
        for (e in entries) {
            val seconds = e.durationSeconds?.takeIf { it > 0 } ?: continue
            val start = Instant.ofEpochMilli(e.timestamp)
            val end = start.plusSeconds(seconds.toLong())
            val hash = listOf(e.timestamp, e.kind, e.title, seconds, e.distanceKm, e.notes, e.hasRoute).hashCode()
            val manual = e.kind != LogKind.RUN && e.sets == null
            out["log-${e.id}"] = Wanted(SESSION, hash) { id, v ->
                session(e, start, end, metadata(id, v, manual, phone))
            }
            val km = e.distanceKm?.takeIf { it > 0 }
            if (km != null) {
                out["log-${e.id}-distance"] = Wanted(DISTANCE, listOf(e.timestamp, seconds, km).hashCode()) { id, v ->
                    DistanceRecord(start, offset(start), end, offset(end), Length.kilometers(km), metadata(id, v, manual, phone))
                }
            }
        }
        for (m in hrv) {
            if (!m.reliable) continue
            val time = Instant.ofEpochMilli(m.timestamp)
            val hash = listOf(m.timestamp, m.rmssd, m.meanHr).hashCode()
            if (m.rmssd in 1.0..200.0) {
                out["hrv-${m.id}"] = Wanted(HRV, hash) { id, v ->
                    HeartRateVariabilityRmssdRecord(time, offset(time), m.rmssd, metadata(id, v, false, corsense))
                }
            }
            out["rhr-${m.id}"] = Wanted(RESTING_HR, hash) { id, v ->
                RestingHeartRateRecord(time, offset(time), Math.round(m.meanHr), metadata(id, v, false, corsense))
            }
        }
        return out
    }

    private fun session(e: LogEntry, start: Instant, end: Instant, metadata: Metadata): ExerciseSessionRecord {
        val type = when (e.kind) {
            LogKind.PROGRAM -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            LogKind.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            LogKind.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
        val route = if (e.hasRoute) route(e, start, end) else null
        return ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = offset(start),
            endTime = end,
            endZoneOffset = offset(end),
            metadata = metadata,
            exerciseType = type,
            title = e.title,
            notes = e.notes.ifBlank { null },
            exerciseRoute = route,
        )
    }

    /** Route points carry moving time from the start, which keeps them inside the session. */
    private fun route(e: LogEntry, start: Instant, end: Instant): ExerciseRoute? {
        var last = Instant.MIN
        val locations = routes.load(e.id).mapNotNull { p ->
            val time = start.plusMillis(p.t)
            if (time <= last || time >= end) return@mapNotNull null
            last = time
            ExerciseRoute.Location(time = time, latitude = p.lat, longitude = p.lon, horizontalAccuracy = p.a?.let { Length.meters(it.toDouble()) })
        }
        return if (locations.size >= 2) ExerciseRoute(locations) else null
    }

    private fun metadata(id: String, version: Long, manual: Boolean, device: Device): Metadata =
        if (manual) Metadata.manualEntry(id, version, device) else Metadata.activelyRecorded(device, id, version)

    private val phone = Device(Device.TYPE_PHONE, Build.MANUFACTURER, Build.MODEL)
    private val corsense = Device(Device.TYPE_UNKNOWN, "Elite HRV", "CorSense")

    private fun offset(at: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(at)

    private fun loadState(): Map<String, Synced> = runCatching {
        programJson.decodeFromString<Map<String, Synced>>(stateFile.readText())
    }.getOrDefault(emptyMap())

    private fun saveState(state: Map<String, Synced>) = stateFile.writeAtomically(programJson.encodeToString(state))

    companion object {
        private const val TAG = "HealthSync"
        private const val BATCH = 50
        private const val SESSION = "session"
        private const val DISTANCE = "distance"
        private const val HRV = "hrv"
        private const val RESTING_HR = "resting_hr"

        private fun recordClass(type: String): KClass<out Record> = when (type) {
            SESSION -> ExerciseSessionRecord::class
            DISTANCE -> DistanceRecord::class
            HRV -> HeartRateVariabilityRmssdRecord::class
            else -> RestingHeartRateRecord::class
        }

        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        )
    }
}
