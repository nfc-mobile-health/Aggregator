package com.example.aggregator

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class PatientRegisterRequest(
    @SerializedName("patientId")  val patientId: String,
    @SerializedName("name")       val name: String,
    @SerializedName("age")        val age: Int?,
    @SerializedName("gender")     val gender: String?,
    @SerializedName("bloodType")  val bloodType: String?
)

data class PatientRegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?
)

interface PatientApiService {
    @POST("api/patients/register")
    suspend fun registerPatient(@Body request: PatientRegisterRequest): PatientRegisterResponse
}

class PatientRepository {
    private val BASE_URL = "https://nursing-backend-vp5o.onrender.com"
    private val api: PatientApiService

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PatientApiService::class.java)
    }

    suspend fun register(patient: Patient): Result<String> = withContext(Dispatchers.IO) {
        try {
            val resp = api.registerPatient(
                PatientRegisterRequest(patient.id, patient.name, patient.age, patient.gender, patient.bloodType)
            )
            if (resp.success) Result.success(resp.message ?: "Registered")
            else Result.failure(Exception(resp.message ?: "Registration failed"))
        } catch (e: Exception) {
            Log.e("PatientRepository", "register", e)
            Result.failure(e)
        }
    }
}
