package com.example.aggregator

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey val patientId: String,
    val name: String,
    val age: Int,
    val gender: String,
    val bloodType: String,
    @ColumnInfo(defaultValue = "''") val sugar: String = "",
    @ColumnInfo(defaultValue = "''") val height: String = "",
    @ColumnInfo(defaultValue = "''") val weight: String = "",
    @ColumnInfo(defaultValue = "''") val oxygenLevel: String = "",
    val medication: String,
    val description: String,
    @ColumnInfo(defaultValue = "0") val isCurrent: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "patient_reports",
    indices = [Index(value = ["patientId", "reportDate"], unique = true)]
)
data class PatientReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: String,
    val patientName: String,
    val reportDate: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "LOCAL",
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    val syncedAt: Long? = null,
    val lastSyncAttemptAt: Long? = null,
    val syncError: String? = null
)

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val ownerId: String,   // patientId
    val role: String,                  // "patient"
    val privateKeyB64: String,         // Kpri, PKCS#8 DER base64
    val publicKeyB64: String,          // Kpub, SPKI DER base64
    val certPem: String,
    val caCertPem: String,
    val issuedAt: Long = 0,
    val expiresAt: Long = 0
)

@Dao
interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(credential: CredentialEntity)

    @Query("SELECT * FROM credentials LIMIT 1")
    fun getCredential(): CredentialEntity?

    @Query("DELETE FROM credentials")
    fun clear()
}

@Dao
interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(patient: PatientEntity)

    @Query("UPDATE patients SET isCurrent = 0")
    fun clearCurrent()

    @Query("UPDATE patients SET isCurrent = 1 WHERE patientId = :patientId")
    fun markCurrent(patientId: String)

    @Query("SELECT * FROM patients WHERE isCurrent = 1 LIMIT 1")
    fun getCurrentPatient(): PatientEntity?
}

@Dao
interface PatientReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(report: PatientReportEntity): Long

    @Query("SELECT * FROM patient_reports WHERE patientId = :patientId AND reportDate = :reportDate LIMIT 1")
    fun getReportForDay(patientId: String, reportDate: String): PatientReportEntity?

    @Query("SELECT DISTINCT reportDate FROM patient_reports WHERE patientId = :patientId ORDER BY reportDate DESC")
    fun getAvailableDates(patientId: String): List<String>

    @Query("SELECT * FROM patient_reports WHERE patientId = :patientId AND reportDate = :reportDate ORDER BY updatedAt DESC")
    fun getReportsForDay(patientId: String, reportDate: String): List<PatientReportEntity>

    @Query("SELECT * FROM patient_reports WHERE id = :reportId LIMIT 1")
    fun getReportById(reportId: Long): PatientReportEntity?

    @Query("SELECT * FROM patient_reports ORDER BY updatedAt DESC LIMIT 1")
    fun getLatestReport(): PatientReportEntity?

    @Query("SELECT * FROM patient_reports WHERE isSynced = 0 ORDER BY updatedAt ASC")
    fun getPendingSyncReports(): List<PatientReportEntity>

    @Query("UPDATE patient_reports SET isSynced = 1, syncedAt = :syncedAt, lastSyncAttemptAt = :syncedAt, syncError = NULL WHERE id = :reportId")
    fun markSynced(reportId: Long, syncedAt: Long)

    @Query("UPDATE patient_reports SET lastSyncAttemptAt = :attemptedAt, syncError = :errorMessage WHERE id = :reportId")
    fun markSyncFailed(reportId: Long, attemptedAt: Long, errorMessage: String?)
}

@Database(
    entities = [PatientEntity::class, PatientReportEntity::class, CredentialEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun patientReportDao(): PatientReportDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        @Volatile
        private var INSTANCE: AggregatorDatabase? = null

        /**
         * Open the SQLCipher-encrypted DB using the in-memory PIN-derived passphrase.
         * Throws IllegalStateException if the session is locked (no passphrase) —
         * callers that may run before unlock (e.g. CloudSyncWorker) must guard this.
         *
         * New DB file name ("aggregator_secure.db") intentionally abandons the old
         * plaintext "aggregator_room.db" — a destructive switch, acceptable for the
         * prototype (CREDENTIALS_AND_STORAGE_PLAN.md §5.2).
         */
        fun getInstance(context: Context): AggregatorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): AggregatorDatabase {
            val passphrase = AggregatorSession.passphrase
                ?: throw IllegalStateException("Database is locked — provision the PIN first (login/register).")
            SQLiteDatabase.loadLibs(context)
            val factory = SupportFactory(passphrase.copyOf())
            return Room.databaseBuilder(
                context.applicationContext,
                AggregatorDatabase::class.java,
                "aggregator_secure.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        }

        fun reset() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
