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

class UpdateActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

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

            // ===== NSE-AA Mutual Authentication (Sethia et al., 2019) =====

            // Step 1: SELECT — receive challenge: N_S(16) || T1(var) || SW(2)
            val selectRes = isoDep.transceive(Utils.SELECT_APD)
            if (selectRes.size < 18) throw IOException("Invalid challenge from server")

            val serverNonce = selectRes.copyOfRange(0, 16)
            val t1 = selectRes.copyOfRange(16, selectRes.size - 2)

            // Decrypt T1 to extract server's virtual identity and nonce
            val t1Plain = CryptoUtils.aesDecrypt(t1, CryptoUtils.getKUD())
            val serverVirtualId = t1Plain.copyOfRange(0, 16)
            val serverNonceFromT1 = t1Plain.copyOfRange(16, 32)

            // Verify server nonce consistency (anti-replay)
            if (!serverNonceFromT1.contentEquals(serverNonce))
                throw IOException("Challenge nonce mismatch")

            // Verify server virtual identity (proves server knows its own id + K_UD)
            val expectedVId = CryptoUtils.computeOtherVirtualIdentity(serverNonce)
            if (!serverVirtualId.contentEquals(expectedVId))
                throw IOException("Server identity verification failed")

            // Step 2: Build AUTH_RESP
            val readerNonce = CryptoUtils.generateNonce()
            val readerVirtualId = CryptoUtils.generateMyVirtualIdentity(readerNonce)

            // T2 = E(K_UD, pwb_R(32) || N_R(16) || N_S(16))
            val t2 = CryptoUtils.aesEncrypt(
                CryptoUtils.MY_PWB + readerNonce + serverNonce,
                CryptoUtils.getKUD()
            )

            // Derive session key K_S via NSE-AA KDF
            val sessionKey = CryptoUtils.deriveSessionKey(
                CryptoUtils.MY_PWB, CryptoUtils.OTHER_PWB, readerNonce, serverNonce
            )

            // T3 = HMAC(K_S, id_VR || N_R || id_VS || N_S) — integrity proof
            val t3 = CryptoUtils.hmacSha256(sessionKey,
                readerVirtualId + readerNonce + serverVirtualId + serverNonce)

            val authCmd = Utils.concatArrays(
                CryptoUtils.CMD_AUTH_RESP, readerVirtualId, readerNonce, t2, t3
            )
            val authRes = isoDep.transceive(authCmd)

            // Step 3: Verify server's mutual auth confirmation
            val t4 = authRes.copyOfRange(0, authRes.size - 2)
            val t4Plain = CryptoUtils.aesDecrypt(t4, sessionKey)
            val authOk = String(t4Plain.copyOfRange(0, 7), Charsets.UTF_8)
            val serverPwb = t4Plain.copyOfRange(7, 39)
            val echoedNonce = t4Plain.copyOfRange(39, 55)

            if (authOk == "AUTH_OK" &&
                serverPwb.contentEquals(CryptoUtils.OTHER_PWB) &&
                echoedNonce.contentEquals(readerNonce)) {
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
                throw IOException("NSE-AA mutual authentication failed")
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