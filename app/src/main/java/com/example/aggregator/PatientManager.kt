package com.example.aggregator

import android.content.Context
import android.content.SharedPreferences


data class Patient(
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    val medication: String = "",   // Added field
    val description: String = "",  // Added field
    val id: String = System.currentTimeMillis().toString()
)

class PatientManager(private val context : Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("patient_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PATIENT_ID = "current_patient_id"
        private const val KEY_PATIENT_NAME = "patient_name"
        private const val KEY_PATIENT_AGE = "patient_age"
        private const val KEY_PATIENT_GENDER = "patient_gender"
        private const val KEY_PATIENT_BLOOD_TYPE = "patient_blood_type"
        private const val KEY_PATIENT_MEDICATION = "patient_medication"     // Added key
        private const val KEY_PATIENT_DESCRIPTION = "patient_description"   // Added key
    }

    fun savePatient(patient: com.example.aggregator.Patient) {
        prefs.edit().apply {
            putString(KEY_PATIENT_ID, patient.id)
            putString(KEY_PATIENT_NAME, patient.name)
            putInt(KEY_PATIENT_AGE, patient.age)
            putString(KEY_PATIENT_GENDER, patient.gender)
            putString(KEY_PATIENT_BLOOD_TYPE, patient.bloodType)
            putString(KEY_PATIENT_MEDICATION, patient.medication)     // Added put
            putString(KEY_PATIENT_DESCRIPTION, patient.description)   // Added put
        }.apply()
    }

    fun getCurrentPatient(): com.example.aggregator.Patient? {
        val id = prefs.getString(KEY_PATIENT_ID, null) ?: return null
        return Patient(
            name = prefs.getString(KEY_PATIENT_NAME, "") ?: "",
            age = prefs.getInt(KEY_PATIENT_AGE, 0),
            gender = prefs.getString(KEY_PATIENT_GENDER, "") ?: "",
            bloodType = prefs.getString(KEY_PATIENT_BLOOD_TYPE, "") ?: "",
            medication = prefs.getString(KEY_PATIENT_MEDICATION, "") ?: "",    // Added get
            description = prefs.getString(KEY_PATIENT_DESCRIPTION, "") ?: "",  // Added get
            id = id
        )
    }

}