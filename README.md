# Aggregator

Android hub device app. Registers patients, sends demographics to NursingDevice via NFC, receives medical records back, stores them locally by date, and syncs structured records to the cloud backend.

## Prerequisites

- Android Studio (latest stable)
- Physical Android device with NFC, Android 11+ (API 30+)
- NursingDevice app running on a second device
- Nursing-Backend running (local or on Render.com)
- Internet connection for cloud sync

## Build & Run

```bash
# From the Aggregator/ directory:
./gradlew assembleDebug     # build APK
./gradlew installDebug      # build + install to connected device
./gradlew test              # unit tests
```

Or open `Aggregator/` in Android Studio → press **Run ▶**.

**Enable USB Debugging:** Settings → About Phone → tap Build Number 7 times → Developer Options → USB Debugging ON

| Setting | Value |
|---------|-------|
| minSdk | 30 (Android 11) |
| compileSdk | 36 (Android 16) |
| Language | Kotlin, Java 11 |

## First-Time Setup

1. Make sure Nursing-Backend is running
2. Install the app on Phone 2
3. Open the app → **Patient Portal** (registration screen)

## Patient Registration Screen

**Register (new patient):**
- Fill Name, Age, Gender, Blood Type → tap **Register**
- Saves locally to SharedPreferences immediately
- Calls `POST /api/patients/register` in background (non-blocking — app proceeds even if offline)

**Access Existing Records (login):**
- Loads patient from local SharedPreferences cache
- No network call needed

## Workflow (after registration)

```
MainActivity shows patient's files, with buttons:

1. "Share Patient" button
   → PatientShareActivity opens
   → Prepares patient JSON payload, loads into MyHostApduService
   → Hold near NursingDevice → NFC handshake → patient demographics sent
   → NursingDevice's SessionCache fills with patient info

2. "Receive Record" (UpdateActivity) button
   → Puts device in NFC reader mode
   → Hold near NursingDevice after nurse fills form
   → Receives encrypted .txt file, saves to:
        /sdcard/Android/data/com.example.aggregator/files/NursingDevice/yyyy-MM-dd/

3. "Sync to Cloud" button (SyncCloudActivity)
   → Pings backend (GET /) to check server status
   → Displays latest .txt file found locally
   → Tap "Sync to Cloud" → POST /api/records with structured fields
   → Backend creates Record + Detail, updates Patient.details[]

4. File browser (RecyclerView in MainActivity)
   → Shows NursingDevice/ date folders
   → Inside each folder: only shows files matching current patient's name
   → Tap any .txt file → TextViewerActivity shows full content
```

## Local Storage Structure

```
/sdcard/Android/data/com.example.aggregator/files/
└── NursingDevice/
    ├── 2026-04-07/
    │   └── medical_data_John_Doe.txt
    └── 2026-04-06/
        └── medical_data_John_Doe.txt
```

Files are named `medical_data_<patient_name>.txt` (spaces replaced with underscores). The file browser filters to only show files for the currently registered patient.

## Key Source Files

| File | What it does |
|------|-------------|
| `AuthActivity.kt` | Patient registration and login. Saves locally first, then calls `PatientRepository` for backend registration in background. |
| `PatientRepository.kt` | Retrofit client for `POST /api/patients/register`. |
| `PatientManager.kt` | SharedPreferences store for the current patient (`patientId`, `name`, `age`, `gender`, `bloodType`, `medication`, `description`). The `id` field is a timestamp string used as `patientId` in the backend. |
| `MyHostApduService.kt` | NFC HCE background service. Sends patient JSON to NursingDevice. Transfer modes: `TEXT` (patient JSON), `FILE` (single file), `MULTI_FILE`. States: `IDLE → AUTHENTICATED → READY_TO_SEND → SENDING`. |
| `PatientShareActivity.kt` | Reads patient from `PatientManager`, builds JSON payload, loads into `MyHostApduService` for NFC send. |
| `UpdateActivity.kt` | NFC reader that receives the medical record `.txt` from NursingDevice and saves it to local storage. |
| `SyncCloudActivity.kt` | Cloud sync UI. Checks server status, shows latest local file, triggers `SyncRepository.syncLatestRecord()`. |
| `SyncRepository.kt` | Retrofit HTTP client. `syncLatestRecord()` parses the `.txt` file into structured fields and calls `POST /api/records`. `syncLatestReport()` is the legacy raw-blob fallback to `POST /api/reports`. |
| `MainActivity.kt` | File browser. Lists `NursingDevice/` date folders and `.txt` files filtered by current patient. |
| `CryptoUtils.kt` | RSA-2048, AES-128, XOR cipher — same interface as NursingDevice. |
| `Utils.kt` | NFC AID and APDU constants. Must stay in sync with NursingDevice's `Utils.kt`. |

## How SyncRepository Parses the Record File

`SyncRepository.parseToRecordRequest()` extracts structured fields from the `.txt` format written by NursingDevice's `SendForm`:

| Line prefix in .txt | Backend field |
|--------------------|---------------|
| `Nurse ID: ` | `nurseId` |
| `Blood Pressure: ` | `bp` |
| `Heart Rate: <n> bpm` | `hr` (Int) |
| `Respiratory Rate: <n> breaths/min` | `rr` (Int) |
| `Body Temperature: <n>F` | `temp` (Float) |
| `Medication: ` | `med` |
| `Description: ` | `obs` |
| `Updated on: dd/MM/yyyy HH:mm:ss` | `date` (from folder name) + `time` (HH:mm) |

`patientId` comes from `PatientManager.getCurrentPatient()?.id`. The Nurse ID in the file is whatever the nurse typed/auto-populated — once nurse registration is complete end-to-end, this will always be the registered nurse ID.

## Cloud Sync — Server Status

Before syncing, `SyncRepository.checkServerStatus()` pings the backend root URL with a 5-second timeout:

| Status | Meaning |
|--------|---------|
| `AWAKE` | Server responded in < 5s — sync button enabled |
| `WAKING_UP` | Render.com cold start (30–60s) — wait and retry |
| `NO_INTERNET` | DNS failure — check Wi-Fi/mobile data |
| `SERVER_DOWN` | Connection refused or 5xx |

Backend base URL: `https://nursing-backend-vp5o.onrender.com`  
All Retrofit timeouts: 60 seconds (connect / read / write)

## Permissions Required

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-feature android:name="android.hardware.nfc.hce" required="true" />
```

## Dependencies

```kotlin
// core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
// networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

## Logcat Tags

```
AuthActivity        patient registration flow
PatientRepository   backend HTTP calls
MyHostApduService   NFC HCE events (sending patient data)
UpdateActivity      NFC reader events (receiving record)
SyncRepository      cloud sync HTTP requests/responses
MainActivity        file browser events
```

## Common Issues

| Problem | Cause | Fix |
|---------|-------|-----|
| `TagLostException` during NFC | Devices moved apart | Hold steady, ~3–5 cm, flat surfaces facing each other |
| "Sync failed: No NursingDevice folder" | No records received yet | Receive at least one record from NursingDevice first |
| Sync stuck on "Server is starting up" | Render.com cold start | Wait 30–60s, tap back and re-enter SyncCloudActivity |
| Files not showing in browser | Wrong patient registered | Ensure the registered patient name matches the received file names |
| `6A88` APDU response | HCE service not ready | Wait 1–2 seconds after opening app, retry NFC tap |
