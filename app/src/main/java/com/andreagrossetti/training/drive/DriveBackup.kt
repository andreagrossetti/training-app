package com.andreagrossetti.training.drive

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andreagrossetti.training.TrainingApp
import com.andreagrossetti.training.data.exportBackup
import com.andreagrossetti.training.data.programJson
import com.andreagrossetti.training.data.writeAtomically
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class DriveStatus(val lastUpload: Long? = null, val fileName: String? = null, val error: String? = null)

/** Why an upload didn't happen: the user must grant access to Drive first (only possible from the UI). */
class DriveConsentNeeded(val intent: PendingIntent) : Exception("Serve l'accesso a Google Drive")

/**
 * Uploads the full backup (the same file as "Esporta") to a "Allenamento" folder in the user's Drive,
 * keeping the last [KEEP] copies. Uses the drive.file scope: the app only sees files it created.
 */
class DriveBackup(private val context: Context) {
    private val stateFile = File(context.filesDir, "drive_backup.json")
    private val mutex = Mutex()
    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<DriveStatus> = _status.asStateFlow()

    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Asks for Drive access; returns the consent screen to show if the user hasn't granted it yet. */
    suspend fun authorize(): AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request).await()

    /** Uploads now. Throws [DriveConsentNeeded] if access was never granted or was revoked. */
    suspend fun upload(app: TrainingApp) = mutex.withLock {
        val result = runCatching {
            val auth = authorize()
            if (auth.hasResolution()) throw DriveConsentNeeded(auth.pendingIntent!!)
            val token = auth.accessToken ?: throw IOException("Nessun token da Google")
            val json = exportBackup(app.repository, app.log, app.settings, app.hrv)
            withContext(Dispatchers.IO) {
                val folder = folderId(token)
                val name = "allenamento-backup-${LocalDate.now()}.json"
                uploadFile(token, folder, name, json)
                prune(token, folder)
                name
            }
        }
        result.onSuccess { name -> save(DriveStatus(System.currentTimeMillis(), name)) }
            .onFailure {
                Log.w(TAG, "upload failed", it)
                save(_status.value.copy(error = it.message ?: it.javaClass.simpleName))
            }
        result.getOrThrow()
    }

    private fun folderId(token: String): String {
        val q = "name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = request(token, "GET", "$API/files?q=${q.encode()}&fields=files(id)&spaces=drive")
            .files().firstOrNull()?.get("id")?.jsonPrimitive?.content
        if (found != null) return found
        val body = """{"name":"$FOLDER","mimeType":"$FOLDER_MIME"}"""
        return request(token, "POST", "$API/files?fields=id", body, "application/json")["id"]!!.jsonPrimitive.content
    }

    private fun uploadFile(token: String, folder: String, name: String, json: String) {
        val boundary = "b" + UUID.randomUUID().toString().replace("-", "")
        val metadata = """{"name":"$name","parents":["$folder"],"mimeType":"application/json"}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
        request(token, "POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
    }

    /** Keeps the newest [KEEP] backups. */
    private fun prune(token: String, folder: String) {
        val q = "'$folder' in parents and trashed = false"
        val files = request(token, "GET", "$API/files?q=${q.encode()}&orderBy=${"createdTime desc".encode()}&fields=files(id)&pageSize=100")
            .files()
        files.drop(KEEP).forEach { f ->
            request(token, "DELETE", "$API/files/${f["id"]!!.jsonPrimitive.content}")
        }
    }

    private fun request(token: String, method: String, url: String, body: String? = null, type: String? = null): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Drive $code: ${text.take(300)}")
            return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.files() = this["files"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun save(status: DriveStatus) {
        _status.value = status
        stateFile.writeAtomically(programJson.encodeToString(status))
    }

    private fun loadStatus(): DriveStatus =
        runCatching { programJson.decodeFromString<DriveStatus>(stateFile.readText()) }.getOrDefault(DriveStatus())

    companion object {
        private const val TAG = "DriveBackup"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "Allenamento"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val KEEP = 8
        private const val WORK = "drive-backup"

        /** Weekly upload, on Wi-Fi while charging; cancelled when [enabled] is false. */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                )
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TrainingApp
        return runCatching { app.drive.upload(app) }.fold(
            onSuccess = { Result.success() },
            // No consent can't be fixed in the background: wait for next week (the settings show the error).
            onFailure = { if (it is DriveConsentNeeded || runAttemptCount >= 3) Result.failure() else Result.retry() },
        )
    }
}
