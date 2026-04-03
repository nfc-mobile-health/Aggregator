package com.example.aggregator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateActivity : BaseActivity(), NfcAdapter.ReaderCallback {

    private lateinit var statusText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var transferProgressBar: ProgressBar

    private var isUiReady = false

    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("step_message")
            if (isUiReady && message != null) {
                runOnUiThread { statusText.text = message }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        statusText = findViewById(R.id.statusText)
        fileNameText = findViewById(R.id.fileNameText)
        transferProgressBar = findViewById(R.id.transferProgressBar)

        isUiReady = true
        statusText.text = "Waiting for NFC device..."
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (!isUiReady) return

        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            isoDep.timeout = 10000

            // 1. Handshake
            isoDep.transceive(Utils.SELECT_APD)

            val sessionKey = CryptoUtils.generateSessionKey()
            val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey, CryptoUtils.getOtherPublicKey())
            isoDep.transceive(Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey))

            val signature = CryptoUtils.rsaSign(encryptedKey, CryptoUtils.getMyPrivateKey())
            val authRes = isoDep.transceive(Utils.concatArrays(CryptoUtils.CMD_AUTH_SEND_SIG, signature))

            // 2. Verify Auth
            val decryptedAck = CryptoUtils.xorEncryptDecrypt(authRes.copyOfRange(0, authRes.size - 2), sessionKey)
            if (String(decryptedAck) == "AUTH_OK") {
                runOnUiThread { statusText.text = "Authenticated! Fetching Data..." }

                // 3. Request File Info
                val metaRes = isoDep.transceive(Utils.GET_FILE_INFO_COMMAND)
                if (metaRes.size <= 2) throw IOException("Failed to get metadata")

                val decryptedMeta = CryptoUtils.xorEncryptDecrypt(metaRes.copyOfRange(0, metaRes.size - 2), sessionKey)

                // The Nursing device sends Mode (1 byte) + Size (4 bytes) + MimeType (remaining)
                val mode = String(decryptedMeta.copyOfRange(0, 1), Charsets.UTF_8)
                val fileSize = ByteBuffer.wrap(decryptedMeta.copyOfRange(1, 5)).int

                if (mode == "F" || mode == "M") {
                    downloadAndSaveFile(isoDep, sessionKey, fileSize)
                } else {
                    throw IOException("Unsupported transfer mode: $mode")
                }
            } else {
                throw IOException("Authentication rejected by sender")
            }
        } catch (e: Exception) {
            runOnUiThread { statusText.text = "Error: ${e.message}" }
        } finally {
            try { isoDep.close() } catch (e: IOException) { }
        }
    }

    private fun downloadAndSaveFile(isoDep: IsoDep, sessionKey: ByteArray, fileSize: Int) {
        runOnUiThread {
            statusText.text = "Receiving Data..."
            transferProgressBar.max = 100
            transferProgressBar.progress = 0
        }

        var receivedBytes = 0
        val fileContentBytes = ByteArray(fileSize)

        try {
            while (receivedBytes < fileSize) {
                val chunkRes = isoDep.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
                if (chunkRes.size <= 2) throw IOException("Interrupted during transfer")

                val decryptedChunk = CryptoUtils.xorEncryptDecrypt(chunkRes.copyOfRange(0, chunkRes.size - 2), sessionKey)

                System.arraycopy(decryptedChunk, 0, fileContentBytes, receivedBytes, decryptedChunk.size)
                receivedBytes += decryptedChunk.size

                val progress = ((receivedBytes.toFloat() / fileSize) * 100).toInt()
                runOnUiThread {
                    transferProgressBar.progress = progress
                    statusText.text = "Downloading... $progress%"
                }
            }

            saveReceivedFile(fileContentBytes)

        } catch (e: Exception) {
            runOnUiThread { statusText.text = "Transfer failed: ${e.message}" }
        }
    }

    private fun saveReceivedFile(content: ByteArray) {
        try {
            val appFilesDir = getExternalFilesDir(null) ?: throw IOException("Storage unavailable")
            val rootDirectory = File(appFilesDir, "NursingDevice")
            if (!rootDirectory.exists()) rootDirectory.mkdirs()

            // 1. Map to Folder by Date (e.g. "2026-02-22")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateString = dateFormat.format(Date())
            val todayDir = File(rootDirectory, dateString)
            if (!todayDir.exists()) todayDir.mkdirs()

            // 2. Extract Patient Name
            val textContent = String(content, Charsets.UTF_8)
            var patientName = "Unknown"

            val lines = textContent.lines()
            lines.find { it.contains("Patient Name:") }?.let {
                patientName = it.substringAfter("Patient Name:").trim().replace(" ", "_")
            }

            // 3. Build File Name format: patientname_date.txt
            val fileName = "${patientName}_${dateString}.txt"
            val newFile = File(todayDir, fileName)

            // 4. Create a timestamp header so appended records are readable
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeString = timeFormat.format(Date())

            val appendHeader = if (newFile.exists()) {
                "\n\n=================================\nUpdate Received at: $timeString\n=================================\n\n"
            } else {
                "Initial Record Received at: $timeString\n=================================\n\n"
            }

            // 5. Write to disk using Append Mode (FileOutputStream parameter 'true')
            FileOutputStream(newFile, true).use { fos ->
                fos.write(appendHeader.toByteArray(Charsets.UTF_8))
                fos.write(content)
            }

            runOnUiThread {
                statusText.text = "Update Saved Successfully!"
                fileNameText.text = "Record updated for: $patientName\nSaved in folder: $dateString\nAppended to: $fileName"
                transferProgressBar.progress = 100
            }
        } catch (e: Exception) {
            runOnUiThread { statusText.text = "Error saving file: ${e.message}" }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("NFC_AUTH_STEP")
        val receiverFlags = ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(this, authReceiver, filter, receiverFlags)
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(authReceiver) } catch (e: Exception) {}
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }
}