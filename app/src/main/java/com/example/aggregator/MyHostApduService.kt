package com.example.aggregator

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.Arrays
import kotlin.compareTo
import kotlin.math.min

class MyHostApduService : HostApduService() {

    private enum class AuthState { IDLE, KEY_RECEIVED, AUTHENTICATED }
    private var currentAuthState = AuthState.IDLE
    private var sessionKey: ByteArray? = null
    private var encryptedKeyBuffer: ByteArray? = null
    // Phase 3: peer's public key, extracted from its certificate during cert exchange.
    private var peerPublicKey: PublicKey? = null
    // Chunked-APDU reassembly for the auth phase (AUTH_CRT / AUTH_KEY / AUTH_SIG) and
    // chunked serving of our own cert (AUTH_CRG). Needed because some controllers
    // (e.g. Galaxy Tab Active5) can't receive extended-length APDUs in HCE mode.
    private var chunkedCmdTag: String? = null
    private val chunkedBuffer = java.io.ByteArrayOutputStream()
    private var certSendOffset = 0
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

        fun snapshotTransferState(): TransferSnapshot =
            TransferSnapshot(
                mode = sharedTransferMode,
                text = sharedTextContent,
                fileContent = sharedFileContent,
                fileMimeType = sharedFileMimeType,
                files = sharedFileQueue.toList()
            )
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // Step 1: Selection
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            currentAuthState = AuthState.IDLE
            sessionKey = null
            peerPublicKey = null
            chunkedCmdTag = null
            chunkedBuffer.reset()
            certSendOffset = 0

            // CRITICAL FIX 1: Reset indices on every new NFC tap
            currentFileIndex = 0
            fileChunkOffset = 0

            notifyUI("Step 1: Connection Established")
            return Utils.SELECT_OK_SW
        }

        // Phase 3: certificate exchange (reader -> card). The reader uploads its cert
        // in chunks; on the final chunk we verify it against our CA and remember its
        // public key. The reader then pulls our cert in chunks via AUTH_CRG.
        val cmdCert = CryptoUtils.CMD_AUTH_SEND_CERT
        if (CryptoUtils.CERT_AUTH_ENABLED && commandApdu.size > cmdCert.size &&
            commandApdu.take(cmdCert.size).toByteArray().contentEquals(cmdCert)) {
            val myCert = CryptoUtils.getMyCertificatePem()
            if (myCert == null) {
                notifyUI("Cert exchange failed: no credential on this device — log in with your PIN")
                return Utils.UNKNOWN_CMD_SW
            }
            val peerCertBytes = reassembleChunks("AUTH_CRT", commandApdu) ?: return Utils.SELECT_OK_SW
            try {
                peerPublicKey = CryptoUtils.verifyPeerCert(String(peerCertBytes, Charsets.UTF_8))
            } catch (e: PeerCertException) {
                notifyUI("Peer cert rejected: ${e.message}")
                return Utils.UNKNOWN_CMD_SW
            }
            certSendOffset = 0
            notifyUI("Certificates exchanged")
            return Utils.SELECT_OK_SW
        }

        // Phase 3: serve our own cert to the reader, one chunk per AUTH_CRG command.
        if (CryptoUtils.CERT_AUTH_ENABLED && Arrays.equals(commandApdu, CryptoUtils.CMD_AUTH_GET_CERT)) {
            val myCert = CryptoUtils.getMyCertificatePem()?.toByteArray(Charsets.UTF_8)
            if (myCert == null) {
                notifyUI("Cert exchange failed: no credential on this device — log in with your PIN")
                return Utils.UNKNOWN_CMD_SW
            }
            val end = min(certSendOffset + CryptoUtils.AUTH_CHUNK_SIZE, myCert.size)
            val flag = if (end == myCert.size) CryptoUtils.AUTH_CHUNK_LAST else CryptoUtils.AUTH_CHUNK_MORE
            val chunk = myCert.copyOfRange(certSendOffset, end)
            Log.d("HCE_AUTH", "serveCert -> chunk from $certSendOffset..$end of ${myCert.size} last=${end == myCert.size}")
            certSendOffset = if (end == myCert.size) 0 else end
            return Utils.concatArrays(byteArrayOf(flag), chunk, Utils.SELECT_OK_SW)
        }

        // Step 2: Key Exchange
        val cmdKey = CryptoUtils.CMD_AUTH_SEND_KEY
        if (commandApdu.size > cmdKey.size && commandApdu.take(cmdKey.size).toByteArray().contentEquals(cmdKey)) {
            encryptedKeyBuffer = reassembleChunks("AUTH_KEY", commandApdu) ?: return Utils.SELECT_OK_SW
            currentAuthState = AuthState.KEY_RECEIVED
            notifyUI("Step 2: Key Received")
            return Utils.SELECT_OK_SW
        }

        // Step 3: Signature & Final Auth
        val cmdSig = CryptoUtils.CMD_AUTH_SEND_SIG
        if (commandApdu.size > cmdSig.size && commandApdu.take(cmdSig.size).toByteArray().contentEquals(cmdSig)) {
            if (currentAuthState != AuthState.KEY_RECEIVED) return Utils.UNKNOWN_CMD_SW

            val signature = reassembleChunks("AUTH_SIG", commandApdu) ?: return Utils.SELECT_OK_SW
            val encryptedKey = encryptedKeyBuffer ?: return Utils.UNKNOWN_CMD_SW

            // Phase 3: verify with the peer's cert key + decrypt with our own
            // credential; fall back to the hardcoded pair when cert-auth is off.
            val verifyKey = if (CryptoUtils.CERT_AUTH_ENABLED) peerPublicKey else CryptoUtils.getOtherPublicKey()
            if (verifyKey == null) {
                notifyUI("Auth Failed: certificate not exchanged")
                return Utils.UNKNOWN_CMD_SW
            }
            val verified = CryptoUtils.rsaVerify(encryptedKey, signature, verifyKey)
            if (verified) {
                val myPriv = if (CryptoUtils.CERT_AUTH_ENABLED) CryptoUtils.getSessionPrivateKey() else CryptoUtils.getMyPrivateKey()
                if (myPriv == null) {
                    notifyUI("Auth Failed: no credential on this device")
                    return Utils.UNKNOWN_CMD_SW
                }
                sessionKey = CryptoUtils.rsaDecrypt(encryptedKey, myPriv)
                currentAuthState = AuthState.AUTHENTICATED

                // CRITICAL FIX 2: Load the data and enforce reset at the moment of authentication
                this.transferMode = sharedTransferMode
                this.textContent = sharedTextContent

                // THESE TWO LINES WERE MISSING:
                this.fileContent = sharedFileContent
                this.fileMimeType = sharedFileMimeType

                this.fileQueue = sharedFileQueue.toMutableList()
                this.currentFileIndex = 0
                this.fileChunkOffset = 0

                notifyUI("Step 3: Authenticated Securely")

                val ack = CryptoUtils.xorEncryptDecrypt("AUTH_OK".toByteArray(), sessionKey!!)
                return Utils.concatArrays(ack, Utils.SELECT_OK_SW)
            }
            notifyUI("Authentication Failed")
            return Utils.UNKNOWN_CMD_SW
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
                if (fileChunkOffset >= currentFile.content.size) {
                    currentFileIndex++
                    fileChunkOffset = 0
                    Log.d("HCE_SERVICE", "Appended file transfer complete.")
                }
                return Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }

            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        currentAuthState = AuthState.IDLE
        sessionKey = null
        peerPublicKey = null
    }
    private fun notifyUI(step: String) {
        val intent = Intent("NFC_AUTH_STEP")
        intent.putExtra("step_message", step)
        sendBroadcast(intent)
    }

    /**
     * Reassemble a chunked auth command ([cmd(8)][flag(1)][chunk]). Returns null
     * while more chunks are pending (caller acks with SELECT_OK_SW), or the fully
     * assembled payload on the final chunk. Switching to a different command tag
     * discards any half-finished buffer from an interrupted exchange.
     */
    private fun reassembleChunks(cmdName: String, commandApdu: ByteArray): ByteArray? {
        if (chunkedCmdTag != cmdName) {
            chunkedCmdTag = cmdName
            chunkedBuffer.reset()
        }
        val flag = commandApdu[8]
        chunkedBuffer.write(commandApdu, 9, commandApdu.size - 9)
        val last = flag == CryptoUtils.AUTH_CHUNK_LAST
        Log.d("HCE_AUTH", "reassemble[$cmdName]: rxApduLen=${commandApdu.size} chunk=${commandApdu.size - 9} last=$last total=${chunkedBuffer.size()}")
        if (!last) return null
        val payload = chunkedBuffer.toByteArray()
        chunkedBuffer.reset()
        chunkedCmdTag = null
        return payload
    }

}
