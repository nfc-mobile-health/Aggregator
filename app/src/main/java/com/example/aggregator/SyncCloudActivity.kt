package com.example.aggregator

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class SyncCloudActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var syncNowButton: Button

    private val repo = SyncRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_cloud)

        statusText   = findViewById(R.id.cloudStatusText)
        fileNameText = findViewById(R.id.cloudFileNameText)
        progressBar  = findViewById(R.id.cloudProgressBar)
        syncNowButton = findViewById(R.id.syncNowButton)

        syncNowButton.setOnClickListener { startSync() }

        checkServer()
    }

    private fun checkServer() {
        setLoading(true)
        statusText.text = "Checking server..."

        lifecycleScope.launch {
            when (repo.checkServerStatus()) {
                ServerStatus.AWAKE -> {
                    statusText.text = "Server online — ready to sync"
                    showLatestFile()
                    syncNowButton.isEnabled = true
                }
                ServerStatus.WAKING_UP -> {
                    statusText.text = "Server is starting up (30–60s). Try again shortly."
                    fileNameText.text = "Render free tier cold start."
                }
                ServerStatus.NO_INTERNET -> {
                    statusText.text = "No internet connection."
                    fileNameText.text = "Check Wi-Fi or mobile data."
                }
                ServerStatus.SERVER_DOWN -> {
                    statusText.text = "Server unreachable."
                    fileNameText.text = "Backend may be down."
                }
            }
            setLoading(false)
        }
    }

    private fun showLatestFile() {
        val appFilesDir = getExternalFilesDir(null) ?: return
        val nursingDir = File(appFilesDir, "NursingDevice")

        val latestFile = nursingDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { it.listFiles()?.toList() ?: emptyList() }
            ?.filter { it.isFile && it.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }

        if (latestFile != null) {
            val dateLabel = latestFile.parentFile?.name ?: "unknown date"
            fileNameText.text = "File: ${latestFile.name}\nDate: $dateLabel\nSize: ${latestFile.length()} bytes"
        } else {
            fileNameText.text = "No record files found in NursingDevice folder."
            syncNowButton.isEnabled = false
        }
    }

    private fun startSync() {
        setLoading(true)
        syncNowButton.isEnabled = false
        statusText.text = "Syncing..."

        lifecycleScope.launch {
            val result = repo.syncLatestRecord(this@SyncCloudActivity)

            result.fold(
                onSuccess = { message ->
                    statusText.text = "Sync complete"
                    fileNameText.text = message
                    syncNowButton.isEnabled = true
                },
                onFailure = { error ->
                    statusText.text = "Sync failed"
                    fileNameText.text = error.message ?: "Unknown error"
                    syncNowButton.isEnabled = true
                }
            )
            setLoading(false)
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
