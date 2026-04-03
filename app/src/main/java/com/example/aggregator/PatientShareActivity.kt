package com.example.aggregator

import android.annotation.SuppressLint
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class PatientShareActivity : BaseActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var patientManager: PatientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_share)  // Reuse sync layout or new one

        progressBar = findViewById(R.id.syncProgressBar)
        statusText = findViewById(R.id.syncStatusText)
        fileNameText = findViewById(R.id.syncFileNameText)
        patientManager = PatientManager(this)

        val patientName = intent.getStringExtra("patient_name") ?: run {
            Toast.makeText(this, "No patient selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        preparePatientDataForNFC(patientName)
    }

    @SuppressLint("SetTextI18n")
    private fun preparePatientDataForNFC(patientName: String) {
        try {
            // Get full patient from cache
            val patient = patientManager.getCurrentPatient() ?: throw Exception("No patient in cache")

            // Create JSON payload
            val patientJson = JSONObject().apply {
                put("type", "PATIENT_DATA")
                put("name", patient.name)
                put("age", patient.age)
                put("gender", patient.gender)
                put("bloodType", patient.bloodType)
                put("medication", patient.medication)     // Added payload item
                put("description", patient.description)   // Added payload item
                put("patientId", patient.id)
                put("sentAt", System.currentTimeMillis())
            }.toString()

            val fileName = "patient_${patient.name.replace(" ", "_")}.json"
            val patientData = FileData(fileName, patientJson.toByteArray(StandardCharsets.UTF_8))

            // Single file transfer (like SyncActivity)
            val fileDataList = listOf(patientData)
            MyHostApduService.setMultipleFilesForTransfer(fileDataList)

            val preview = patientJson.take(200)
            statusText.text = "✅ Ready to share patient details"
            fileNameText.text = """
                👤 Patient: ${patient.name}
                📄 File: $fileName
                Preview: $preview...
                
                🔄 Tap NFC receiver device now
            """.trimIndent()

            Toast.makeText(
                this,
                "✅ Ready to share $patientName - Tap receiver",
                Toast.LENGTH_LONG
            ).show()

            Log.d("PatientShareActivity", "Patient JSON prepared: ${patientJson.length} bytes")

        } catch (e: Exception) {
            Log.e("PatientShareActivity", "Error", e)
            statusText.text = "❌ Error"
            fileNameText.text = e.message ?: "Unknown error"
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private val authReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val message = intent?.getStringExtra("step_message")
            findViewById<TextView>(R.id.syncStatusText).text = message
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()

        val filter = IntentFilter("NFC_AUTH_STEP")
        val receiverFlags = ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(this, authReceiver, filter, receiverFlags)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(authReceiver)
    }
}