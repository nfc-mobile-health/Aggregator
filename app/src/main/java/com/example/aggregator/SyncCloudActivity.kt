package com.example.aggregator

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SyncCloudActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var cancelBtn: Button

    private val syncRepository by lazy { SyncRepository() }
    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_cloud)

        progressBar = findViewById(R.id.syncProgressBar)
        statusText = findViewById(R.id.syncStatusText)
        detailText = findViewById(R.id.syncDetailText)
        cancelBtn = findViewById(R.id.syncCancelBtn)

        cancelBtn.setOnClickListener {
            syncJob?.cancel()
            finish()
        }

        startSync()
    }

    private fun startSync() {
        syncJob = CoroutineScope(Dispatchers.Main).launch {
            // Phase 1: Poll until server is awake
            var attempts = 0
            val maxWakeAttempts = 12
            var serverReady = false

            statusText.text = "Checking server status..."
            detailText.text = "Pinging server..."

            while (attempts < maxWakeAttempts) {
                val status = syncRepository.checkServerStatus()
                attempts++

                when (status) {
                    ServerStatus.AWAKE -> {
                        serverReady = true
                        break
                    }
                    ServerStatus.WAKING_UP -> {
                        statusText.text = "Server is waking up..."
                        detailText.text = "Free tier is spinning up. This may take up to a minute.\nAttempt $attempts of $maxWakeAttempts"
                    }
                    ServerStatus.NO_INTERNET -> {
                        showError("No internet connection", "Please check your network and try again.")
                        return@launch
                    }
                    ServerStatus.SERVER_DOWN -> {
                        showError(
                            "Server is unreachable",
                            "The server appears to be down.\nThis is not a wake-up delay — the server may be offline."
                        )
                        return@launch
                    }
                }

                delay(5000)
            }

            if (!serverReady) {
                showError(
                    "Server did not wake up",
                    "Timed out after $maxWakeAttempts attempts.\nThe server may need manual attention."
                )
                return@launch
            }

            // Phase 2: Server is awake — sync
            statusText.text = "Server is awake!"
            detailText.text = "Uploading report..."

            val result = syncRepository.syncLatestReport(this@SyncCloudActivity)

            result.fold(
                onSuccess = { message ->
                    progressBar.visibility = View.GONE
                    statusText.text = "Sync successful!"
                    detailText.text = message
                    cancelBtn.text = "Done"
                },
                onFailure = { error ->
                    showError("Sync failed", error.message ?: "Unknown error")
                }
            )
        }
    }

    private fun showError(title: String, detail: String) {
        progressBar.visibility = View.GONE
        statusText.text = title
        detailText.text = detail
        cancelBtn.text = "Close"
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
    }
}
