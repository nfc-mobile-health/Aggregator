package com.example.aggregator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncActivity : BaseActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var logText: TextView

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("step_message")
            if (message != null) {
                runOnUiThread {
                    if (message.contains("Transmitting Data") || message.contains("Authenticated")) {
                        statusText.text = "Sending Data..."
                    }
                    val current = logText.text.toString()
                    logText.text = if (current.isEmpty()) message else "$current\n$message"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        statusText = findViewById(R.id.statusText)
        fileNameText = findViewById(R.id.fileNameText)
        logText = findViewById(R.id.logText)

        val patientName = intent.getStringExtra("patient_name") ?: "Unknown"
        prepareTodayRecord(patientName)
    }

    private fun prepareTodayRecord(patientName: String) {
        try {
            val appFilesDir = getExternalFilesDir(null)
            val rootDirectory = File(appFilesDir, "NursingDevice")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateString = dateFormat.format(Date())
            val todayDir = File(rootDirectory, dateString)

            val cleanName = patientName.replace(" ", "_")
            val fileName = "${cleanName}_${dateString}.txt"
            val file = File(todayDir, fileName)

            if (file.exists()) {
                val fileContent = file.readBytes()
                MyHostApduService.setFileForTransfer(fileContent, "text/plain")
                statusText.text = "Ready to Sync"
                fileNameText.text = "File loaded: $fileName\nSize: ${fileContent.size} bytes"
                logText.text = "Hold nursing device near reader to transfer.\n"
            } else {
                val msg = "No records logged for $patientName today."
                MyHostApduService.setTextForTransfer(msg)
                statusText.text = "No File Found"
                fileNameText.text = "No local file exists for today."
                logText.text = "Will transmit empty state alert to receiver.\n"
            }
        } catch (e: Exception) {
            statusText.text = "System Error"
            fileNameText.text = e.message
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("NFC_AUTH_STEP")
        ContextCompat.registerReceiver(this, authReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(authReceiver) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        MyHostApduService.resetTransferState()
    }
}