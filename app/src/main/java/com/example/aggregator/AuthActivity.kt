package com.example.aggregator

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-lifetime scope for background provisioning (backend registration +
 * credential persistence) that must outlive the AuthActivity — which finishes
 * itself the moment it navigates to Main. Tied to the app process, not any UI.
 */
private val ProvisioningScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class AuthActivity : AppCompatActivity() {
    private lateinit var patientManager: PatientManager
    private val patientRepo = PatientRepository()
    private lateinit var patientIdInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var pinVisibilityToggle: TextView
    private lateinit var registerButton: Button
    private lateinit var loginButton: Button
    private var isPinVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        SyncWorkScheduler.schedulePeriodicSync(this)

        patientManager = PatientManager(this)
        patientIdInput = findViewById(R.id.patientIdInput)
        pinInput = findViewById(R.id.pinInput)
        pinVisibilityToggle = findViewById(R.id.pinVisibilityToggle)
        registerButton = findViewById(R.id.registerBtn)
        loginButton = findViewById(R.id.loginBtn)

        pinVisibilityToggle.setOnClickListener { togglePinVisibility() }
        registerButton.setOnClickListener { handleRegister() }
        loginButton.setOnClickListener { handleLogin() }
    }

    private fun togglePinVisibility() {
        isPinVisible = !isPinVisible
        pinInput.inputType = InputType.TYPE_CLASS_NUMBER or if (isPinVisible) {
            InputType.TYPE_NUMBER_VARIATION_NORMAL
        } else {
            InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        pinInput.setSelection(pinInput.text.length)
        pinVisibilityToggle.text = if (isPinVisible) "Hide" else "Show"
    }

    private fun handleRegister() {
        val typedId     = patientIdInput.text.toString().trim()
        val name        = findViewById<EditText>(R.id.patientName).text.toString().trim()
        val age         = findViewById<EditText>(R.id.patientAge).text.toString().trim()
        val gender      = findViewById<EditText>(R.id.patientGender).text.toString().trim()
        val bloodType   = findViewById<EditText>(R.id.patientBloodType).text.toString().trim()
        val sugar       = findViewById<EditText>(R.id.patientSugar).text?.toString()?.trim().orEmpty()
        val height      = findViewById<EditText>(R.id.patientHeight).text?.toString()?.trim().orEmpty()
        val weight      = findViewById<EditText>(R.id.patientWeight).text?.toString()?.trim().orEmpty()
        val oxygenLevel = findViewById<EditText>(R.id.patientOxygenLevel).text?.toString()?.trim().orEmpty()
        val pin         = pinInput.text.toString().trim()

        if (name.isEmpty() || age.isEmpty() || gender.isEmpty() || bloodType.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.length < 4) {
            Toast.makeText(this, "Set a PIN of at least 4 digits", Toast.LENGTH_SHORT).show()
            return
        }

        val patient = Patient(
            name        = name,
            age         = age.toIntOrNull() ?: 0,
            gender      = gender,
            bloodType   = bloodType,
            sugar       = sugar,
            height      = height,
            weight      = weight,
            oxygenLevel = oxygenLevel
        ).let { if (typedId.isEmpty()) it else it.copy(id = typedId) }

        // Provision the PIN-derived DB key BEFORE any DB access (the DB is encrypted).
        AggregatorSession.provision(this, pin, patient.id)

        // Save locally immediately so the app works even if backend is offline.
        // Opening the encrypted DB can throw if this device already holds data secured
        // with a DIFFERENT PIN (SQLCipher can only reopen a file with its original key).
        // Guard it so we surface a clear message instead of crashing.
        try {
            patientManager.savePatient(patient)
        } catch (e: Exception) {
            AggregatorSession.lock()
            Toast.makeText(
                this,
                "This device already holds encrypted records secured with a different PIN. " +
                    "Log in with the original PIN, or clear the app's data to start over.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        SyncWorkScheduler.schedulePeriodicSync(this)
        SyncWorkScheduler.enqueueImmediateSync(this)
        patientIdInput.setText(patient.id)

        // Register with backend in the background — non-blocking, so we can navigate
        // to Main immediately (offline-first). This MUST run on a process-lifetime
        // scope, NOT lifecycleScope: goToMain() finishes this activity right away,
        // which would cancel a lifecycleScope coroutine mid-flight (especially during
        // a 30–60s Render cold start) and DROP the returned credential before
        // saveFromServer() persists it. Use applicationContext — the activity is gone.
        val appContext = applicationContext
        ProvisioningScope.launch {
            patientRepo.register(patient).fold(
                onSuccess = { reg ->
                    val creds = reg.credentials
                    val credNote = if (creds?.privateKey != null &&
                        CredentialStore.saveFromServer(appContext, patient.id, creds)
                    ) "credentials secured" else "no credentials"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "Registered: $name (Patient ID: ${patient.id}, $credNote)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "Saved locally (Patient ID: ${patient.id}, ${it.message})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        goToMain(patient.name)
    }

    private fun handleLogin() {
        val patientId = patientIdInput.text.toString().trim()
        val pin       = pinInput.text.toString().trim()
        if (patientId.isEmpty()) {
            Toast.makeText(this, "Enter your Patient ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (pin.isEmpty()) {
            Toast.makeText(this, "Enter your PIN", Toast.LENGTH_SHORT).show()
            return
        }

        // Provision the PIN-derived key and verify it opens the encrypted DB.
        AggregatorSession.provision(this, pin, patientId)
        var hasLocalCred = false
        val openError: String? = try {
            patientManager.getCurrentPatient()               // opens the encrypted DB (fails here on wrong PIN)
            hasLocalCred = CredentialStore.loadIntoSession(this)  // load credential into memory
            null
        } catch (e: Exception) {
            AggregatorSession.lock()
            e.message.orEmpty()
        }
        if (openError != null) {
            val reason = if (openError.contains("not a database", true) ||
                openError.contains("encrypted", true) || openError.contains("file is not", true)) {
                "incorrect PIN (could not decrypt your data)"
            } else {
                "could not open your encrypted data: $openError"
            }
            Toast.makeText(this, "Login failed: $reason", Toast.LENGTH_LONG).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            patientRepo.login(patientId).fold(
                onSuccess = { loginData ->
                    val cloudPatient = loginData.patient
                    val localPatient = Patient(
                        id = cloudPatient.patientId,
                        name = cloudPatient.name,
                        age = cloudPatient.age ?: 0,
                        gender = cloudPatient.gender.orEmpty(),
                        bloodType = cloudPatient.bloodType.orEmpty(),
                        sugar = cloudPatient.sugar.orEmpty(),
                        height = cloudPatient.height.orEmpty(),
                        weight = cloudPatient.weight.orEmpty(),
                        oxygenLevel = cloudPatient.oxygenLevel.orEmpty()
                    )
                    patientManager.savePatient(localPatient)

                    // If this device has no credential yet (e.g. app data was cleared),
                    // restore it from the server so NFC mutual auth works. The server
                    // returns the stored keypair + cert on login.
                    if (!hasLocalCred) {
                        val creds = loginData.credentials
                        if (creds?.privateKey != null) {
                            CredentialStore.saveFromServer(this@AuthActivity, cloudPatient.patientId, creds)
                        }
                    }

                    val syncMessage = patientRepo.syncPatientRecords(this@AuthActivity, cloudPatient).fold(
                        onSuccess = { count -> "Fetched $count record(s) from cloud." },
                        onFailure = { error -> "Logged in, but record download failed: ${error.message}" }
                    )

                    Toast.makeText(
                        this@AuthActivity,
                        "Welcome back, ${cloudPatient.name}! $syncMessage",
                        Toast.LENGTH_LONG
                    ).show()
                    SyncWorkScheduler.schedulePeriodicSync(this@AuthActivity)
                    SyncWorkScheduler.enqueueImmediateSync(this@AuthActivity)
                    goToMain(cloudPatient.name)
                },
                onFailure = { error ->
                    val cachedPatient = patientManager.getCurrentPatient()
                    if (cachedPatient?.id == patientId) {
                        Toast.makeText(
                            this@AuthActivity,
                            "Using cached patient data (${error.message})",
                            Toast.LENGTH_LONG
                        ).show()
                        goToMain(cachedPatient.name)
                    } else {
                        Toast.makeText(
                            this@AuthActivity,
                            error.message ?: "Login failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            setLoading(false)
        }
    }

    private fun goToMain(patientName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("patient_name", patientName)
        })
        finish()
    }

    private fun setLoading(loading: Boolean) {
        registerButton.isEnabled = !loading
        loginButton.isEnabled = !loading
    }
}
