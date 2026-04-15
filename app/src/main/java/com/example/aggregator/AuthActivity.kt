package com.example.aggregator

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {
    private lateinit var patientManager: PatientManager
    private val patientRepo = PatientRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        patientManager = PatientManager(this)

        findViewById<Button>(R.id.registerBtn).setOnClickListener { handleRegister() }
        findViewById<Button>(R.id.loginBtn).setOnClickListener { checkCachedPatient() }
    }

    private fun handleRegister() {
        val name      = findViewById<EditText>(R.id.patientName).text.toString().trim()
        val age       = findViewById<EditText>(R.id.patientAge).text.toString().trim()
        val gender    = findViewById<EditText>(R.id.patientGender).text.toString().trim()
        val bloodType = findViewById<EditText>(R.id.patientBloodType).text.toString().trim()

        if (name.isEmpty() || age.isEmpty() || gender.isEmpty() || bloodType.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val patient = Patient(
            name      = name,
            age       = age.toIntOrNull() ?: 0,
            gender    = gender,
            bloodType = bloodType
        )

        // Save locally immediately so the app works even if backend is offline
        patientManager.savePatient(patient)

        // Register with backend in background — non-blocking
        lifecycleScope.launch {
            patientRepo.register(patient).fold(
                onSuccess = { Toast.makeText(this@AuthActivity, "Registered: $name", Toast.LENGTH_SHORT).show() },
                onFailure = { Toast.makeText(this@AuthActivity, "Saved locally (${it.message})", Toast.LENGTH_LONG).show() }
            )
        }

        goToMain(patient.name)
    }

    private fun checkCachedPatient() {
        val patient = patientManager.getCurrentPatient()
        if (patient != null) {
            Toast.makeText(this, "Welcome back, ${patient.name}!", Toast.LENGTH_SHORT).show()
            goToMain(patient.name)
        } else {
            Toast.makeText(this, "No patient found. Please register first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToMain(patientName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("patient_name", patientName)
        })
        finish()
    }
}
