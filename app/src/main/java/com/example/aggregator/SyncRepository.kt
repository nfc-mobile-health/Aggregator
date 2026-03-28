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
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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

interface HealthApiService {
    @POST("api/reports")
    suspend fun syncReport(@Body request: ReportRequest): ReportResponse
}

enum class ServerStatus {
    AWAKE,
    WAKING_UP,
    NO_INTERNET,
    SERVER_DOWN
}

class SyncRepository {
    private val BASE_URL = "https://nursing-backend-vp5o.onrender.com"
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

    /**
     * Quick ping to determine current server state.
     * Uses a short timeout so we can differentiate:
     * - AWAKE: server responds (any HTTP code)
     * - WAKING_UP: SocketTimeoutException (Render is spinning up the container)
     * - NO_INTERNET: UnknownHostException / DNS failure
     * - SERVER_DOWN: connection refused or HTTP 502/503 from Render's proxy
     */
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
        } catch (e: SocketTimeoutException) {
            ServerStatus.WAKING_UP
        } catch (e: UnknownHostException) {
            ServerStatus.NO_INTERNET
        } catch (e: java.net.ConnectException) {
            ServerStatus.SERVER_DOWN
        } catch (e: Exception) {
            Log.w("SyncRepository", "Ping failed: ${e.javaClass.simpleName}: ${e.message}")
            ServerStatus.SERVER_DOWN
        }
    }

    suspend fun syncLatestReport(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appFilesDir = context.getExternalFilesDir(null) ?: return@withContext Result.failure(Exception("No storage"))
            val nursingDir = File(appFilesDir, "NursingDevice")
            if (!nursingDir.exists()) return@withContext Result.failure(Exception("No NursingDevice folder"))

            val lastUpdatedFile = nursingDir.listFiles()
                ?.filter { it.isDirectory }
                ?.flatMap { it.listFiles()?.toList() ?: emptyList() }
                ?.filter { it.isFile && it.extension == "txt" }
                ?.maxByOrNull { it.lastModified() }

            if (lastUpdatedFile == null) {
                return@withContext Result.failure(Exception("No report files found in any folder"))
            }

            val date = lastUpdatedFile.parentFile?.name ?: "Unknown Date"
            val content = lastUpdatedFile.readText()
            val fileName = lastUpdatedFile.name

            val deviceId = Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            val receivedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

            val request = ReportRequest(deviceId, date, fileName, content, receivedAt)
            val response = apiService.syncReport(request)

            if (response.success) {
                lastUpdatedFile.delete()
                // Remove parent date folder if now empty
                val parentDir = lastUpdatedFile.parentFile
                if (parentDir != null && parentDir.listFiles().isNullOrEmpty()) {
                    parentDir.delete()
                }
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
