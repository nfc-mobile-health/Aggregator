package com.example.aggregator

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PatientRegisterRequest(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("name") val name: String,
    @SerializedName("age") val age: Int?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("bloodType") val bloodType: String?,
    @SerializedName("sugar") val sugar: String?,
    @SerializedName("height") val height: String?,
    @SerializedName("weight") val weight: String?,
    @SerializedName("oxygenLevel", alternate = ["spo2"]) val oxygenLevel: String? = null
)

data class PatientRegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("credentials") val credentials: Credentials? = null,
    @SerializedName("credentialError") val credentialError: String? = null
)

/** Registration result: server message + (on first register) the patient's credentials. */
data class PatientRegistration(
    val message: String,
    val credentials: Credentials?
)

data class PatientApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("patient") val patient: PatientCloudData?
)

data class PatientCloudData(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("name") val name: String,
    @SerializedName("age") val age: Int?,
    @SerializedName("gender") val gender: String?,
    @SerializedName("bloodType") val bloodType: String?,
    @SerializedName("sugar") val sugar: String?,
    @SerializedName("height") val height: String?,
    @SerializedName("weight") val weight: String?,
    @SerializedName("oxygenLevel", alternate = ["spo2"]) val oxygenLevel: String? = null
)

/** Login result: the cloud patient profile plus any stored credentials to restore. */
data class PatientLoginData(
    val patient: PatientCloudData,
    val credentials: Credentials?
)

data class PatientRecordData(
    @SerializedName("patientId") val patientId: String,
    @SerializedName("nurseId") val nurseId: String?,
    @SerializedName("date") val date: String?,
    @SerializedName("time") val time: String?,
    @SerializedName("bp") val bp: String?,
    @SerializedName("hr") val hr: Int?,
    @SerializedName("rr") val rr: Int?,
    @SerializedName("temp") val temp: Float?,
    @SerializedName("oxygenLevel", alternate = ["spo2"]) val oxygenLevel: Int? = null,
    @SerializedName("obs") val obs: String?,
    @SerializedName("med") val med: String?
)

interface PatientApiService {
    @POST("api/patients/register")
    suspend fun registerPatient(@Body request: PatientRegisterRequest): PatientRegisterResponse

    @GET("api/patients/{patientId}")
    suspend fun getPatient(@Path("patientId") patientId: String): PatientApiResponse
}

class PatientRepository {
    private val primaryBaseUrl = "https://nfc-backend-ostp.onrender.com"
    private val secondaryBaseUrl = "https://nursing-backend-vp5o.onrender.com"
    private val gson = Gson()

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        // 60s to absorb a Render free-tier cold start (UptimeRobot keeps it warm,
        // but a missed ping can still leave the first request waking the instance).
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .build()

    private val primaryApi = createApi(primaryBaseUrl)
    private val secondaryApi = createApi(secondaryBaseUrl)

    private fun createApi(baseUrl: String): PatientApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PatientApiService::class.java)

    suspend fun register(patient: Patient): Result<PatientRegistration> = withContext(Dispatchers.IO) {
        val request = PatientRegisterRequest(
            patientId = patient.id,
            name = patient.name,
            age = patient.age,
            gender = patient.gender,
            bloodType = patient.bloodType,
            sugar = patient.sugar,
            height = patient.height,
            weight = patient.weight,
            oxygenLevel = patient.oxygenLevel
        )

        val primaryResult = runCatching { primaryApi.registerPatient(request) }
        val response = when {
            primaryResult.isSuccess -> primaryResult.getOrThrow()
            else -> runCatching { secondaryApi.registerPatient(request) }.getOrElse { error ->
                Log.e("PatientRepository", "register", error)
                return@withContext Result.failure(error)
            }
        }

        if (response.success) {
            Result.success(PatientRegistration(response.message ?: "Registered", response.credentials))
        } else {
            Result.failure(Exception(response.message ?: "Registration failed"))
        }
    }

    suspend fun login(patientId: String): Result<PatientLoginData> = withContext(Dispatchers.IO) {
        fetchPatient(patientId)
    }

    suspend fun syncPatientRecords(context: Context, patient: PatientCloudData): Result<Int> =
        withContext(Dispatchers.IO) {
            fetchPatientRecords(patient.patientId).mapCatching { records ->
                writeRecordsToLocalStorage(context, patient, records)
            }
        }

    private suspend fun fetchPatient(patientId: String): Result<PatientLoginData> = withContext(Dispatchers.IO) {
        val candidateUrls = listOf(
            "$primaryBaseUrl/api/patients/$patientId",
            "$secondaryBaseUrl/api/patients/$patientId"
        )

        var lastError: Exception? = null

        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = Exception("HTTP ${response.code} for $url")
                        return@use
                    }

                    val body = response.body?.string().orEmpty()
                    val patient = parsePatient(body)
                    if (patient != null) {
                        return@withContext Result.success(PatientLoginData(patient, parseCredentials(body)))
                    }

                    lastError = Exception("Unexpected patient response from $url")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Patient ID not found. Please register first."))
    }

    /** Extract the optional `credentials` object from a login response body. */
    private fun parseCredentials(body: String): Credentials? {
        if (body.isBlank()) return null
        return try {
            val root = JsonParser().parse(body)
            if (!root.isJsonObject) return null
            val credEl = root.asJsonObject.get("credentials")
            if (credEl != null && credEl.isJsonObject) gson.fromJson(credEl, Credentials::class.java) else null
        } catch (e: Exception) {
            Log.e("PatientRepository", "parseCredentials", e)
            null
        }
    }

    private suspend fun fetchPatientRecords(patientId: String): Result<List<PatientRecordData>> =
        withContext(Dispatchers.IO) {
            val candidateUrls = listOf(
                "$primaryBaseUrl/api/records/patient/$patientId",
                "$primaryBaseUrl/api/patients/$patientId/records",
                "$primaryBaseUrl/api/records/$patientId",
                "$secondaryBaseUrl/api/records/patient/$patientId",
                "$secondaryBaseUrl/api/patients/$patientId/records",
                "$secondaryBaseUrl/api/records/$patientId"
            )

            var lastError: Exception? = null

            for (url in candidateUrls) {
                try {
                    val request = Request.Builder().url(url).get().build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastError = Exception("HTTP ${response.code} for $url")
                            return@use
                        }

                        val body = response.body?.string().orEmpty()
                        val records = parseRecords(body)
                        if (records != null) {
                            return@withContext Result.success(records.filter { it.patientId == patientId })
                        }

                        lastError = Exception("Unexpected records response from $url")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            Result.failure(lastError ?: Exception("Unable to fetch records"))
        }

    private fun parseRecords(body: String): List<PatientRecordData>? {
        if (body.isBlank()) return emptyList()

        return try {
            val root = JsonParser().parse(body)
            when {
                root.isJsonArray -> gson.fromJson(body, Array<PatientRecordData>::class.java).toList()
                root.isJsonObject -> extractRecordsFromObject(root.asJsonObject)
                else -> null
            }
        } catch (e: Exception) {
            Log.e("PatientRepository", "parseRecords", e)
            null
        }
    }

    private fun parsePatient(body: String): PatientCloudData? {
        if (body.isBlank()) return null

        return try {
            val root = JsonParser().parse(body)
            if (!root.isJsonObject) return null

            val jsonObject = root.asJsonObject
            when {
                jsonObject.has("patient") && jsonObject.get("patient").isJsonObject ->
                    gson.fromJson(jsonObject.get("patient"), PatientCloudData::class.java)

                jsonObject.has("data") && jsonObject.get("data").isJsonObject ->
                    gson.fromJson(jsonObject.get("data"), PatientCloudData::class.java)

                jsonObject.has("patientId") && jsonObject.has("name") ->
                    gson.fromJson(jsonObject, PatientCloudData::class.java)

                else -> null
            }
        } catch (e: Exception) {
            Log.e("PatientRepository", "parsePatient", e)
            null
        }
    }

    private fun extractRecordsFromObject(jsonObject: JsonObject): List<PatientRecordData>? {
        val arrayElement = listOf("records", "data", "details")
            .firstNotNullOfOrNull { key -> jsonObject.get(key)?.takeIf { element -> element.isJsonArray } }

        if (arrayElement != null) {
            return gson.fromJson(arrayElement.toString(), Array<PatientRecordData>::class.java).toList()
        }

        if (jsonObject.has("patientId") && jsonObject.has("date")) {
            return listOf(gson.fromJson(jsonObject, PatientRecordData::class.java))
        }

        return emptyList()
    }

    private fun writeRecordsToLocalStorage(
        context: Context,
        patient: PatientCloudData,
        records: List<PatientRecordData>
    ): Int {
        val reportDao = AggregatorDatabase.getInstance(context).patientReportDao()
        val groupedByDate = records.groupBy { it.date ?: "undated" }

        groupedByDate.forEach { (date, dateRecords) ->
            val content = dateRecords
                .sortedBy { it.time ?: "" }
                .joinToString(
                    separator = "\n\n=================================\n\n"
                ) { record ->
                    buildRecordBlock(patient, record)
                }
            val existing = reportDao.getReportForDay(patient.patientId, date)
            reportDao.upsert(
                PatientReportEntity(
                    id = existing?.id ?: 0,
                    patientId = patient.patientId,
                    patientName = patient.name,
                    reportDate = date,
                    content = content,
                    updatedAt = System.currentTimeMillis(),
                    source = "CLOUD",
                    isSynced = true,
                    syncedAt = System.currentTimeMillis(),
                    lastSyncAttemptAt = System.currentTimeMillis(),
                    syncError = null
                )
            )
        }

        return records.size
    }

    private fun buildRecordBlock(patient: PatientCloudData, record: PatientRecordData): String {
        val updatedOn = buildUpdatedOn(record.date, record.time)
        return buildString {
            appendLine("Patient ID: ${patient.patientId}")
            appendLine("Patient Name: ${patient.name}")
            appendLine("Age: ${patient.age ?: 0}")
            appendLine("Gender: ${patient.gender.orEmpty()}")
            appendLine("Blood Type: ${patient.bloodType.orEmpty()}")
            appendLine("Blood Sugar: ${patient.sugar.orEmpty()}")
            appendLine("Height: ${patient.height.orEmpty()}")
            appendLine("Weight: ${patient.weight.orEmpty()}")
            if (!patient.oxygenLevel.isNullOrBlank()) {
                appendLine("Oxygen Level: ${patient.oxygenLevel}%")
            }
            appendLine("Nurse ID: ${record.nurseId.orEmpty()}")
            appendLine("Blood Pressure: ${record.bp.orEmpty()}")
            appendLine("Heart Rate: ${record.hr?.toString() ?: ""} bpm")
            appendLine("Respiratory Rate: ${record.rr?.toString() ?: ""} breaths/min")
            appendLine("Body Temperature: ${formatTemperature(record.temp)}F")
            if (record.oxygenLevel != null) {
                appendLine("Oxygen Level: ${record.oxygenLevel}%")
            }
            appendLine("Medication: ${record.med.orEmpty()}")
            appendLine("Description: ${record.obs.orEmpty()}")
            append("Updated on: $updatedOn")
        }
    }

    private fun formatTemperature(temp: Float?): String {
        if (temp == null) return ""
        return if (temp % 1f == 0f) temp.toInt().toString() else temp.toString()
    }

    private fun buildUpdatedOn(date: String?, time: String?): String {
        if (date.isNullOrBlank()) {
            return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        }

        val hourMinute = time?.takeIf { it.isNotBlank() } ?: "00:00"
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .parse("$date $hourMinute")
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(parsed ?: Date())
        } catch (e: Exception) {
            "$date $hourMinute"
        }
    }
}
