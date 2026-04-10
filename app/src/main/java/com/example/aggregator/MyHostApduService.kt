package com.example.aggregator

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.compareTo
import kotlin.math.min

class MyHostApduService : HostApduService() {

    // NSE-AA auth states: IDLE → CHALLENGE_SENT → AUTHENTICATED
    private enum class AuthState { IDLE, CHALLENGE_SENT, AUTHENTICATED }
    private var currentAuthState = AuthState.IDLE
    private var sessionKey: ByteArray? = null
    private var serverNonce: ByteArray? = null
    private var serverVirtualId: ByteArray? = null
    private var transferMode = "NONE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var fileQueue: MutableList<FileData> = mutableListOf()
    private var currentFileIndex: Int = 0


    companion object {
        private var sharedTransferMode = "NONE"
        private var sharedTextContent: String? = null
        private var sharedFileContent: ByteArray? = null
        private var sharedFileMimeType: String? = null
        private var sharedFileQueue: MutableList<FileData> = mutableListOf()

        fun setSingleTextForTransfer(text: String) {
            sharedTransferMode = "TEXT"
            sharedTextContent = text
            sharedFileContent = null
            sharedFileQueue.clear()
        }

        // Added for SyncActivity text messaging
        fun setTextForTransfer(text: String) {
            setSingleTextForTransfer(text)
        }

        // Added for SyncActivity single file transfer
        fun setFileForTransfer(content: ByteArray, mimeType: String) {
            sharedTransferMode = "FILE"
            sharedFileContent = content
            sharedFileMimeType = mimeType
            sharedTextContent = null
            sharedFileQueue.clear()

            Log.d("HCE_SERVICE", "Service armed for SINGLE FILE transfer. Size: ${content.size}")
        }

        fun setMultipleFilesForTransfer(files: List<FileData>) {
            sharedTransferMode = "MULTI_FILE"
            sharedFileQueue.clear()
            sharedFileQueue.addAll(files)
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null

            Log.d("HCE_SERVICE", "Service armed for APPENDED FILE transfer. Total bytes: ${files.sumOf { it.content.size }}")
            if (files.isNotEmpty()) {
                Log.d("HCE_SERVICE", "Sending file: ${files[0].name}")
            }
        }

        fun resetTransferState() {
            sharedTransferMode = "NONE"
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
            sharedFileQueue.clear()
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // ===== NSE-AA Step 1: SELECT — Generate and send challenge =====
        // Server generates nonce N_S and virtual identity id_VS, encrypts with K_UD
        // Response: N_S(16) || T1(AES(K_UD, id_VS || N_S)) || SW(2)
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            currentAuthState = AuthState.IDLE
            sessionKey = null
            serverNonce = null
            serverVirtualId = null
            currentFileIndex = 0
            fileChunkOffset = 0

            // Generate challenge per NSE-AA protocol (Table II, Step 2)
            serverNonce = CryptoUtils.generateNonce()
            serverVirtualId = CryptoUtils.generateMyVirtualIdentity(serverNonce!!)

            // T1 = E(K_UD, id_VS || N_S) — encrypted challenge
            val t1 = CryptoUtils.aesEncrypt(
                serverVirtualId!! + serverNonce!!,
                CryptoUtils.getKUD()
            )

            currentAuthState = AuthState.CHALLENGE_SENT
            notifyUI("Step 1: Challenge Sent")

            return Utils.concatArrays(serverNonce!!, t1, Utils.SELECT_OK_SW)
        }

        // ===== NSE-AA Step 2: AUTH_RESP — Verify reader, derive session key K_S =====
        // Reader sends: CMD(8) || id_VR(16) || N_R(16) || T2(var) || T3(32)
        // Server verifies pwb, nonces, HMAC, then derives K_S and responds with T4
        val cmdResp = CryptoUtils.CMD_AUTH_RESP
        if (commandApdu.size > cmdResp.size &&
            commandApdu.take(cmdResp.size).toByteArray().contentEquals(cmdResp)) {

            if (currentAuthState != AuthState.CHALLENGE_SENT) return Utils.UNKNOWN_CMD_SW
            val storedNonce = serverNonce ?: return Utils.UNKNOWN_CMD_SW
            val storedVirtualId = serverVirtualId ?: return Utils.UNKNOWN_CMD_SW

            try {
                val payload = commandApdu.copyOfRange(cmdResp.size, commandApdu.size)
                val idVR = payload.copyOfRange(0, 16)
                val nonceR = payload.copyOfRange(16, 32)
                val t3 = payload.copyOfRange(payload.size - 32, payload.size)
                val t2 = payload.copyOfRange(32, payload.size - 32)

                // Decrypt T2: E(K_UD, pwb_R(32) || N_R(16) || N_S(16))
                val t2Plain = CryptoUtils.aesDecrypt(t2, CryptoUtils.getKUD())
                val pwbR = t2Plain.copyOfRange(0, 32)
                val nR = t2Plain.copyOfRange(32, 48)
                val nS = t2Plain.copyOfRange(48, 64)

                // Verify reader's password-hash identity (Table II, Step 6)
                if (!pwbR.contentEquals(CryptoUtils.OTHER_PWB)) {
                    notifyUI("Auth Failed: Unknown device")
                    return Utils.UNKNOWN_CMD_SW
                }
                // Verify nonce freshness (anti-replay, Table II, Step 5)
                if (!nS.contentEquals(storedNonce)) {
                    notifyUI("Auth Failed: Stale challenge")
                    return Utils.UNKNOWN_CMD_SW
                }
                if (!nR.contentEquals(nonceR)) {
                    notifyUI("Auth Failed: Nonce mismatch")
                    return Utils.UNKNOWN_CMD_SW
                }

                // Derive session key K_S per NSE-AA KDF (Section III-A.2)
                val kS = CryptoUtils.deriveSessionKey(pwbR, CryptoUtils.MY_PWB, nonceR, storedNonce)

                // Verify HMAC T3 for message integrity
                val expectedT3 = CryptoUtils.hmacSha256(kS,
                    idVR + nonceR + storedVirtualId + storedNonce)
                if (!t3.contentEquals(expectedT3)) {
                    notifyUI("Auth Failed: Integrity check failed")
                    return Utils.UNKNOWN_CMD_SW
                }

                sessionKey = kS
                currentAuthState = AuthState.AUTHENTICATED

                // Load transfer data at authentication time
                this.transferMode = sharedTransferMode
                this.textContent = sharedTextContent
                this.fileContent = sharedFileContent
                this.fileMimeType = sharedFileMimeType
                this.fileQueue = sharedFileQueue.toMutableList()
                this.currentFileIndex = 0
                this.fileChunkOffset = 0

                notifyUI("Step 2: Mutually Authenticated (NSE-AA)")

                // T4 = E(K_S, "AUTH_OK"(7) || pwb_S(32) || N_R(16))
                // Proves server identity to reader (mutual authentication)
                val t4Plain = "AUTH_OK".toByteArray(Charsets.UTF_8) +
                    CryptoUtils.MY_PWB + nonceR
                val t4 = CryptoUtils.aesEncrypt(t4Plain, kS)
                return Utils.concatArrays(t4, Utils.SELECT_OK_SW)

            } catch (e: Exception) {
                notifyUI("Auth Error: ${e.message}")
                return Utils.UNKNOWN_CMD_SW
            }
        }

        // Only process data if Authenticated
        if (currentAuthState != AuthState.AUTHENTICATED) return Utils.FILE_NOT_READY_SW

        // Sync state and handle transfer
        transferMode = sharedTransferMode
        textContent = sharedTextContent

        val rawResponse = when (transferMode) {
            "TEXT" -> handleTextTransfer(commandApdu)
            "FILE" -> handleFileTransfer(commandApdu)
            "MULTI_FILE" -> handleAppendedFileTransfer(commandApdu)
            else -> Utils.FILE_NOT_READY_SW
        }

        // Encrypt Data Responses
        return if (rawResponse.size > 2) {
            val data = rawResponse.copyOfRange(0, rawResponse.size - 2)
            val encryptedData = CryptoUtils.xorEncryptDecrypt(data, sessionKey!!)
            Utils.concatArrays(encryptedData, Utils.SELECT_OK_SW)
        } else {
            rawResponse
        }
    }

    private fun handleTextTransfer(commandApdu: ByteArray): ByteArray {
        if (!Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND)) {
            return Utils.UNKNOWN_CMD_SW
        }

        val text = textContent ?: return Utils.FILE_NOT_READY_SW
        val textBytes = text.toByteArray(Charsets.UTF_8)

        // Metadata Payload for Text: [1 byte for mode ('T')] + [N bytes for the text itself]
        val modeByte = "T".toByteArray(Charsets.UTF_8)
        val textPayload = Utils.concatArrays(modeByte, textBytes)

        Log.d("HCE_SERVICE", "Sending text payload of size ${textPayload.size}")
        return Utils.concatArrays(textPayload, Utils.SELECT_OK_SW)
    }

    private fun handleFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val mimeBytes = fileMimeType?.toByteArray(Charsets.UTF_8) ?: return Utils.FILE_NOT_READY_SW

                // Metadata Payload for File: [1 byte for mode ('F')] + [4 bytes for size] + [N bytes for MIME]
                val modeByte = "F".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, mimeBytes)

                Log.d("HCE_SERVICE", "Sending file metadata payload.")
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val remaining = content.size - fileChunkOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW

                val chunkSize = min(remaining, 245)
                val chunk = content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    private fun handleAppendedFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                if (currentFileIndex >= fileQueue.size) {
                    Log.d("HCE_SERVICE", "All files sent. Ending transfer.")
                    return Utils.concatArrays(byteArrayOf(), Utils.SELECT_OK_SW)
                }

                val currentFile = fileQueue[currentFileIndex]
                val fileNameBytes = currentFile.name.toByteArray(Charsets.UTF_8)
                val modeByte = "M".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(currentFile.content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, fileNameBytes)

                fileChunkOffset = 0
                Log.d(
                    "HCE_SERVICE",
                    "Sending appended file: ${currentFile.name} (Size: ${currentFile.content.size} bytes)"
                )
                return Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }

            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                if (currentFileIndex >= fileQueue.size) return Utils.FILE_NOT_READY_SW

                val currentFile = fileQueue[currentFileIndex]
                val remaining = currentFile.content.size - fileChunkOffset

                if (remaining <= 0) {
                    // Single file transfer complete
                    currentFileIndex++
                    fileChunkOffset = 0
                    Log.d("HCE_SERVICE", "Appended file transfer complete.")
                    return Utils.SELECT_OK_SW
                }

                val chunkSize = min(remaining, 245)
                val chunk =
                    currentFile.content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize

                Log.d(
                    "HCE_SERVICE",
                    "Sending chunk: $fileChunkOffset / ${currentFile.content.size}"
                )
                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        currentAuthState = AuthState.IDLE
        sessionKey = null
    }
    private fun notifyUI(step: String) {
        val intent = Intent("NFC_AUTH_STEP")
        intent.putExtra("step_message", step)
        sendBroadcast(intent)
    }

}