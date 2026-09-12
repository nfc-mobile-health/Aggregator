package com.example.aggregator

import android.content.Context

data class Patient(
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    val sugar: String = "",
    val height: String = "",
    val weight: String = "",
    val oxygenLevel: String = "",
    val medication: String = "",
    val description: String = "",
    val id: String = System.currentTimeMillis().toString()
)

class PatientManager(private val context: Context) {
    // Lazy: the DB is encrypted and can only open AFTER the PIN is provisioned,
    // so we must not touch it during construction (AuthActivity builds this in onCreate).
    private val patientDao get() = AggregatorDatabase.getInstance(context).patientDao()

    fun savePatient(patient: Patient) {
        patientDao.clearCurrent()
        patientDao.upsert(
            PatientEntity(
                patientId = patient.id,
                name = patient.name,
                age = patient.age,
                gender = patient.gender,
                bloodType = patient.bloodType,
                sugar = patient.sugar,
                height = patient.height,
                weight = patient.weight,
                oxygenLevel = patient.oxygenLevel,
                medication = patient.medication,
                description = patient.description,
                isCurrent = true
            )
        )
        patientDao.markCurrent(patient.id)
    }

    fun getCurrentPatient(): Patient? =
        patientDao.getCurrentPatient()?.toDomain()
}

fun PatientEntity.toDomain(): Patient =
    Patient(
        name = name,
        age = age,
        gender = gender,
        bloodType = bloodType,
        sugar = sugar,
        height = height,
        weight = weight,
        oxygenLevel = oxygenLevel,
        medication = medication,
        description = description,
        id = patientId
    )
