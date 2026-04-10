package com.example.aggregator

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NSE-AA (NFC Secure Element-Based Mutual Authentication and Attestation)
 * Cryptographic utilities implementing the protocol from:
 * Sethia, D., Gupta, D., & Saran, H. (2019). "NFC Secure Element-Based
 * Mutual Authentication and Attestation for IoT Access"
 * IEEE Transactions on Consumer Electronics, 64(4).
 */
object CryptoUtils {

    // ===== NSE-AA Pre-shared Credentials (TCA Personalization Phase, Section III-A.1) =====

    // K_UD: Pre-shared 256-bit symmetric key between Aggregator (D) and NursingDevice (U)
    // In a production deployment, provisioned by the Trusted Certified Authority (TCA)
    private val K_UD = hexToBytes(
        "A1B2C3D4E5F60718293A4B5C6D7E8F90" +
        "F0E1D2C3B4A59687A1B2C3D4E5F60718"
    )

    // --- Aggregator identity (IoT Device D in the paper) ---
    private const val MY_DEVICE_ID = "AGGREGATOR_DEVICE_001"
    private val MY_SALT = hexToBytes("1A2B3C4D5E6F708192A3B4C5D6E7F801") // b_D
    private const val MY_PWD = "agg_secure_2024"                          // pwd_D
    // pwb_D = H(pwd_D || b_D) — credential stored on the SE per paper Section III-A.1
    val MY_PWB: ByteArray = computePwb(MY_PWD, MY_SALT)

    // --- NursingDevice identity (User Device U) — for verification ---
    private const val OTHER_DEVICE_ID = "NURSING_DEVICE_001"
    private val OTHER_SALT = hexToBytes("F801E7D6C5B4A392810F6E5D4C3B2A19") // b_U
    private const val OTHER_PWD = "nurse_secure_2024"                        // pwd_U
    val OTHER_PWB: ByteArray = computePwb(OTHER_PWD, OTHER_SALT)

    // Auth command prefix for the mutual authentication response message
    val CMD_AUTH_RESP = "AUTH_RES".toByteArray(Charsets.UTF_8) // 8 bytes

    // ===== Virtual Identity Generation (Section III-A.2) =====
    // id_VH = H(id_AH || N_H)[0:16] — anonymizes real device identity per session

    fun generateMyVirtualIdentity(nonce: ByteArray): ByteArray =
        sha256(MY_DEVICE_ID.toByteArray(Charsets.UTF_8) + nonce).copyOfRange(0, 16)

    fun computeOtherVirtualIdentity(nonce: ByteArray): ByteArray =
        sha256(OTHER_DEVICE_ID.toByteArray(Charsets.UTF_8) + nonce).copyOfRange(0, 16)

    // ===== Cryptographic Primitives =====

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // AES-256-CBC encrypt with random IV. Returns: IV(16) || ciphertext
    fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(data)
    }

    // AES-256-CBC decrypt. Expects: IV(16) || ciphertext
    fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            IvParameterSpec(data.copyOfRange(0, 16))
        )
        return cipher.doFinal(data.copyOfRange(16, data.size))
    }

    // HMAC-SHA256 for message integrity verification
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ===== NSE-AA Session Key Derivation (KDF, Section III-A.2) =====
    // K_S = HMAC(K_UD, "NSE-AA" || pwb_reader || pwb_server || N_reader || N_server)[0:16]
    fun deriveSessionKey(
        pwbReader: ByteArray, pwbServer: ByteArray,
        nonceReader: ByteArray, nonceServer: ByteArray
    ): ByteArray {
        val material = "NSE-AA".toByteArray(Charsets.UTF_8) +
            pwbReader + pwbServer + nonceReader + nonceServer
        return hmacSha256(K_UD, material).copyOfRange(0, 16) // 128-bit AES session key
    }

    fun getKUD(): ByteArray = K_UD.copyOf()

    // XOR encryption for post-auth lightweight data transfer (per paper's symmetric approach)
    fun xorEncryptDecrypt(data: ByteArray, sessionKey: ByteArray): ByteArray {
        val output = ByteArray(data.size)
        for (i in data.indices) {
            output[i] = (data[i].toInt() xor sessionKey[i % sessionKey.size].toInt()).toByte()
        }
        return output
    }

    // ===== Internal Helpers =====

    private fun computePwb(pwd: String, salt: ByteArray): ByteArray =
        sha256(pwd.toByteArray(Charsets.UTF_8) + salt)

    private fun hexToBytes(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in data.indices) {
            data[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return data
    }
}
