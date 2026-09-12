package com.example.aggregator

import android.content.Context
import android.provider.Settings.Secure
import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// --- Legacy report (raw text blob) — kept for backward compat ---
data class ReportRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("date") val date: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("content") val content: String,
    @SerializedName("receivedAt") val receivedAt: String
)

data class ReportResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("id") val id: String?
)

// --- Structured record ---
data class RecordRequest(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("nurseId") val nurseId: String,
    @SerializedName("date") val date: String,
    @SerializedName("time") val time: String?,
    @SerializedName("bp") val bp: String?,
    @SerializedName("hr") val hr: Int?,
    @SerializedName("rr") val rr: Int?,
    @SerializedName("temp") val temp: Float?,
    @SerializedName("oxygenLevel", alternate = ["spo2"]) val oxygenLevel: Int? = null,
    @SerializedName("obs") val obs: String?,
    @SerializedName("med") val med: String?
)

data class RecordResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("recordId") val recordId: String?
)

interface HealthApiService {
    @POST("api/reports")
    suspend fun syncReport(@Body request: ReportRequest): ReportResponse

    @POST("api/records")
    suspend fun syncRecord(@Body request: RecordRequest): RecordResponse
}

enum class ServerStatus {
    AWAKE,
    WAKING_UP,
    NO_INTERNET,
    SERVER_DOWN
}

class SyncRepository {
    private val BASE_URL = "https://nfc-backend-ostp.onrender.com"
    private var apiService: HealthApiService

    // Short-timeout client just for health checks
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(HealthApiService::class.java)
    }

    suspend fun checkServerStatus(): ServerStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${BASE_URL}/")
                .head()
                .build()
            val response = pingClient.newCall(request).execute()
            val code = response.code
            response.close()

            when {
                code in 200..499 -> ServerStatus.AWAKE
                code == 502 || code == 503 -> ServerStatus.WAKING_UP
                else -> ServerStatus.SERVER_DOWN
            }
        } catch (e: java.net.SocketTimeoutException) {
            ServerStatus.WAKING_UP
        } catch (e: java.net.UnknownHostException) {
            ServerStatus.NO_INTERNET
        } catch (e: java.net.ConnectException) {
            ServerStatus.SERVER_DOWN
        } catch (e: Exception) {
            Log.w("SyncRepository", "Ping failed: ${e.javaClass.simpleName}: ${e.message}")
            ServerStatus.SERVER_DOWN
        }
    }

    // Parses the text file written by NursingDevice's SendForm into structured fields.
    // Format expected (from SendForm.generateTxtFile):
    //   Nurse ID: <id>
    //   Patient Name: <name>
    //   Blood Pressure: <value>
    //   Heart Rate: <value> bpm
    //   Respiratory Rate: <value> breaths/min
    //   Body Temperature: <value>F
    //   Oxygen Level: <value>%
    //   Medication: <value>
    //   Description: <value>
    //   Updated on: dd/MM/yyyy HH:mm:ss
    private fun parseToRecordRequest(
        content: String,
        date: String,
        context: Context,
        fallbackPatientId: String? = null
    ): RecordRequest? {
        fun extractLine(prefix: String): String? =
            content.lines().find { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()

        val nurseId = extractLine("Nurse ID: ") ?: return null
        val bp      = extractLine("Blood Pressure: ")
        val hr      = extractLine("Heart Rate: ")?.removeSuffix(" bpm")?.trim()?.toIntOrNull()
        val rr      = extractLine("Respiratory Rate: ")?.removeSuffix(" breaths/min")?.trim()?.toIntOrNull()
        val temp    = extractLine("Body Temperature: ")?.removeSuffix("F")?.trim()?.toFloatOrNull()
        val oxygenLevel = extractLine("Oxygen Level: ")?.removeSuffix("%")?.trim()?.toIntOrNull()
        val med     = extractLine("Medication: ")
        val obs     = extractLine("Description: ")

        // Extract HH:mm from "Updated on: dd/MM/yyyy HH:mm:ss"
        val time = extractLine("Updated on: ")?.let {
            try { it.substring(11, 16) } catch (e: Exception) { null }
        }

        // Prefer the registered patient's ID; fall back to name from the file
        val patientId = extractLine("Patient ID: ")
            ?: fallbackPatientId
            ?: PatientManager(context).getCurrentPatient()?.id
            ?: extractLine("Patient Name: ")
            ?: "unknown"

        return RecordRequest(
            patientId = patientId,
            nurseId = nurseId,
            date = date,
            time = time,
            bp = bp,
            hr = hr,
            rr = rr,
            temp = temp,
            obs = obs,
            med = med,
            oxygenLevel = oxygenLevel
        )
    }

    private suspend fun syncReportEntity(context: Context, report: PatientReportEntity): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = parseToRecordRequest(
                    content = report.content,
                    date = report.reportDate,
                    context = context,
                    fallbackPatientId = report.patientId
                )
                    ?: return@withContext Result.failure(Exception("Could not parse record — Nurse ID missing"))

                val response = apiService.syncRecord(request)
                if (response.success) {
                    AggregatorDatabase.getInstance(context).patientReportDao()
                        .markSynced(report.id, System.currentTimeMillis())
                    val fileName = "${report.patientName.replace(" ", "_")}_${report.reportDate}.txt"
                    Result.success("Synced $fileName: ${response.message}")
                } else {
                    AggregatorDatabase.getInstance(context).patientReportDao()
                        .markSyncFailed(report.id, System.currentTimeMillis(), response.message)
                    Result.failure(Exception(response.message ?: "Sync failed"))
                }
            } catch (e: Exception) {
                AggregatorDatabase.getInstance(context).patientReportDao()
                    .markSyncFailed(report.id, System.currentTimeMillis(), e.message)
                Log.e("SyncRepository", "Record sync error", e)
                Result.failure(e)
            }
        }

    // Primary sync method — sends structured fields to /api/records.
    suspend fun syncLatestRecord(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val latestReport = AggregatorDatabase.getInstance(context).patientReportDao().getLatestReport()
                ?: return@withContext Result.failure(Exception("No record files found"))

            syncReportEntity(context, latestReport)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Record sync error", e)
            Result.failure(e)
        }
    }

    suspend fun syncPendingRecords(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val reportDao = AggregatorDatabase.getInstance(context).patientReportDao()
        val pendingReports = reportDao.getPendingSyncReports()
        if (pendingReports.isEmpty()) return@withContext Result.success(0)

        var syncedCount = 0
        pendingReports.forEach { report ->
            val result = syncReportEntity(context, report)
            if (result.isSuccess) {
                syncedCount++
            } else {
                return@withContext Result.failure(
                    result.exceptionOrNull()
                        ?: Exception("Failed syncing pending report for ${report.patientName} on ${report.reportDate}")
                )
            }
        }

        Result.success(syncedCount)
    }

    // Legacy sync — sends raw text blob to /api/reports. Kept so nothing breaks.
    suspend fun syncLatestReport(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val latestReport = AggregatorDatabase.getInstance(context).patientReportDao().getLatestReport()
            if (latestReport == null) {
                return@withContext Result.failure(Exception("No report files found in any folder"))
            }

            val date = latestReport.reportDate
            val content = latestReport.content
            val fileName = "${latestReport.patientName.replace(" ", "_")}_${latestReport.reportDate}.txt"

            val deviceId = Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            val receivedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

            val request = ReportRequest(deviceId, date, fileName, content, receivedAt)
            val response = apiService.syncReport(request)

            if (response.success) {
                Result.success("Synced ${fileName} from ${date}: ${response.message}")
            } else {
                Result.failure(Exception(response.message ?: "Sync failed"))
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Sync error", e)
            Result.failure(e)
        }
    }
}
