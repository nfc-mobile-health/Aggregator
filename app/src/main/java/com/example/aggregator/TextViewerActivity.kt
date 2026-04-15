package com.example.aggregator

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class TextViewerActivity : BaseActivity() {
    private lateinit var fileNameText: TextView
    private lateinit var fileContentText: TextView
    private lateinit var backButton: Button
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_viewer)

        fileNameText = findViewById(R.id.fileNameText)
        fileContentText = findViewById(R.id.fileContentText)
        backButton = findViewById(R.id.backButton)
        scrollView = findViewById(R.id.scrollView)

        // Get file path from intent
        val filePath = intent.getStringExtra("file_path") ?: ""
        val fileName = intent.getStringExtra("file_name") ?: "Unknown"

        Log.d("TextViewerActivity", "Opening file: $filePath")

        // Set title
        fileNameText.text = "📄 $fileName"

        // Load and display file content
        displayFile(filePath)

        // Back button
        backButton.setOnClickListener {
            finish()
        }
    }

    private fun displayFile(filePath: String) {
        try {
            if (filePath.isEmpty()) {
                fileContentText.text = "❌ No file path provided"
                return
            }

            val file = File(filePath)
            if (!file.exists()) {
                fileContentText.text = "❌ File not found: $filePath"
                Log.e("TextViewerActivity", "File not found: $filePath")
                return
            }

            if (!file.isFile) {
                fileContentText.text = "❌ Path is not a file: $filePath"
                Log.e("TextViewerActivity", "Path is not a file: $filePath")
                return
            }

            // Read file content
            val content = file.readText(Charsets.UTF_8)
            fileContentText.text = content

            Log.d("TextViewerActivity", "✅ File loaded: ${file.length()} bytes")

            // Scroll to top
            scrollView.post {
                scrollView.scrollTo(0, 0)
            }

        } catch (e: Exception) {
            Log.e("TextViewerActivity", "❌ Error reading file: ${e.message}", e)
            fileContentText.text = "❌ Error reading file:\n${e.message}"
        }
    }
}