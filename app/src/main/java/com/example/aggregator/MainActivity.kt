package com.example.aggregator

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var fileRecyclerView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var fileListAdapter: FileListAdapter

    private val APP_DIRECTORY = "NursingDevice"
    private val PERMISSION_REQUEST_CODE = 102
    private lateinit var rootDirectory: File
    private lateinit var currentDirectory: File
    private val navigationStack = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val patientName = intent.getStringExtra("patient_name") ?: "Patient"
        val directoryHeader = findViewById<TextView>(R.id.directoryHeader)
        directoryHeader.text = "📁 Files for $patientName:"

        val sharePatientBtn = findViewById<Button>(R.id.sharePatientBtn)
        val syncButton = findViewById<Button>(R.id.SenderButton)
        val updateButton = findViewById<Button>(R.id.ReceiverButton)

        // Cloud sync button — opens dedicated sync screen
        findViewById<Button>(R.id.SyncHealthDbButton)?.setOnClickListener {
            startActivity(Intent(this, SyncCloudActivity::class.java))
        }

        sharePatientBtn.setOnClickListener {
            val patientName = intent.getStringExtra("patient_name") ?: return@setOnClickListener
            startActivity(Intent(this, PatientShareActivity::class.java).apply {
                putExtra("patient_name", patientName)
            })
        }

        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        currentPathText = findViewById(R.id.currentPathText)
        backButton = findViewById(R.id.backButton)

        // ⚠️ SKIP PERMISSION FOR TESTING
        Log.d("MainActivity", "⚠️ Skipping permission check - testing mode")
        initializeFileExplorer()

        updateButton.setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        syncButton.setOnClickListener {
            val pName = intent.getStringExtra("patient_name") ?: "Unknown"
            startActivity(Intent(this, SyncActivity::class.java).apply {
                putExtra("patient_name", pName)
            })
        }

        backButton.setOnClickListener {
            navigateBack()
        }

    }

    override fun onResume() {
        super.onResume()
        if (::fileListAdapter.isInitialized) loadCurrentDirectory()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "✅ Permission granted")
                    initializeFileExplorer()
                } else {
                    currentPathText.text = "❌ Storage permission required"
                    Log.w("MainActivity", "Permission denied")
                }
            }
        }
    }
    private fun initializeFileExplorer() {
        val appFilesDir = getExternalFilesDir(null)
        if (appFilesDir == null) {
            Log.e("MainActivity", "Storage not available")
            currentPathText.text = "Storage not available"
            return
        }

        rootDirectory = File(appFilesDir, APP_DIRECTORY)
        if (!rootDirectory.exists()) rootDirectory.mkdirs()
        currentDirectory = rootDirectory

        createTodayFolderIfNeeded()

        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileListAdapter = FileListAdapter(emptyList()) { folder ->
            navigateToFolder(folder)
        }
        fileRecyclerView.adapter = fileListAdapter

        loadCurrentDirectory()
    }

    private fun createTodayFolderIfNeeded() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayFolder = dateFormat.format(Date())
        val todayDir = File(rootDirectory, todayFolder)
        if (!todayDir.exists()) {
            todayDir.mkdirs()
            Log.d("MainActivity", "Created today's folder: $todayFolder")
        }
    }

    private fun navigateToFolder(folder: File) {
        if (folder.isDirectory) {
            navigationStack.add(currentDirectory)
            currentDirectory = folder
            loadCurrentDirectory()
        }
    }

    private fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            currentDirectory = navigationStack.removeAt(navigationStack.size - 1)
            loadCurrentDirectory()
        } else if (currentDirectory != rootDirectory) {
            currentDirectory = rootDirectory
            loadCurrentDirectory()
        }
    }

    private fun loadCurrentDirectory() {
        val relativePath = currentDirectory.absolutePath.removePrefix(rootDirectory.absolutePath)
        currentPathText.text = if (relativePath.isEmpty()) "/" else relativePath

        backButton.visibility = if (currentDirectory == rootDirectory && navigationStack.isEmpty()) {
            View.GONE
        } else {
            View.VISIBLE
        }

        // Get current patient name for filtering (null = show all)
        val patientFilter = PatientManager(this).getCurrentPatient()
            ?.name?.replace(" ", "_")?.lowercase()

        val items = currentDirectory.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenByDescending { it.name })
            ?.filter { file ->
                // Always show directories; filter .txt files by patient name
                file.isDirectory || patientFilter == null ||
                    file.name.lowercase().contains(patientFilter)
            }
            ?: emptyList()

        fileListAdapter.updateFiles(items)
        fileRecyclerView.scrollToPosition(0)
    }
}
