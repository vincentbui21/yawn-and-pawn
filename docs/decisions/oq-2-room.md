# OQ-2: Room 3.0 or Room 2.8 on KMP with AGP 9

- **Date:** 2026-09-27 (Story 1.7)
- **Question (architecture OQ-2):** does Room 3.0.3 (`androidx.room3`) work in this project (Kotlin 2.4.20, AGP 9.3.3 with the `com.android.kotlin.multiplatform.library` plugin, KSP 2.3.11, Gradle 9.7.1), or do we fall back to Room 2.8.5?
- **Decision:** Room **3.0.3**. No fallback to 2.8.5 was needed.

## What was tried

- `:data` applies `com.google.devtools.ksp` 2.3.11 and `androidx.room3` 3.0.3 (both also declared `apply false` in the root build so they share one classloader with the Kotlin and Android plugins).
- `room3-runtime` in `commonMain`, `room3-compiler` on the `kspAndroid` configuration, `room3 { schemaDirectory("$projectDir/schemas") }`.
- `@Database` + `@ConstructedBy(AppDatabaseConstructor::class)` with an `expect object AppDatabaseConstructor` in `commonMain`; KSP generates the Android `actual`.
- Result: KSP runs, the schema is exported to `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/1.json`, and the Robolectric host tests (`:data:testAndroidHostTest`) open real databases (in-memory and on file) and pass. `./gradlew qualityGate` passes.

## Choices made along the way

- **Driver: `AndroidSQLiteDriver` (framework SQLite, `androidx.sqlite:sqlite-framework`), not `BundledSQLiteDriver`.** The bundled driver ships its own native SQLite per ABI (bigger APK), and its Android variant cannot load on the host JVM, so the Robolectric tests would exercise a different driver than production. The framework driver is Room's Android default, works in device-protected storage and runs unchanged under Robolectric. Revisit if a later story needs SQLite features newer than the oldest supported framework SQLite (API 26).
- **Journal mode TRUNCATE, not WAL.** Auto Backup includes only `app.db` (NFR-14). With WAL, recent commits can sit in `app.db-wal` and would be missing from a backup. Alarm writes are rare, so WAL's concurrency is not needed.
- **No `@Upsert`.** Room's upsert catches every uniqueness error and falls back to an update by primary key, so a new alarm whose `request_code` clashes would be silently dropped. `AlarmDao.upsert` is a transaction of `@Update` then `@Insert` (both ABORT on conflict), so the clash surfaces as `DomainError.StorageFailure`.
- **No destructive migration fallback.** Schema changes need a migration and a migration test (first one: Story 1.13, v1 to v2).

## Artifacts added to the runtime classpath

`androidx.room3:room3-common(-jvm)`, `androidx.room3:room3-runtime(-android)`, `androidx.sqlite:sqlite(-android)`, `androidx.sqlite:sqlite-async(-android)`, `androidx.sqlite:sqlite-framework(-android)`, all listed and reviewed in `config/dependency-allowlist.txt`. None opens a network connection.
