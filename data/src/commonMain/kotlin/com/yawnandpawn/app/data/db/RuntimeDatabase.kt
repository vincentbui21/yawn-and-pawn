package com.yawnandpawn.app.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.yawnandpawn.app.data.session.ActiveSessionDao
import com.yawnandpawn.app.data.session.ActiveSessionEntity
import com.yawnandpawn.app.data.session.GrantLedgerDao
import com.yawnandpawn.app.data.session.GrantLedgerEntity
import com.yawnandpawn.app.data.session.PurchaseIntentDao
import com.yawnandpawn.app.data.session.PurchaseIntentEntity

/**
 * `runtime.db` (AD-6): the write-ahead copy of the active wake session (AD-2 rule 2). Never backed up: a restored
 * half-finished session would ring on another phone, and a restored purchase intent could price a payment on the wrong
 * phone (NFR-14), and a restored grant ledger row could consume a payment on the wrong phone. Version 1 (Story 1.12) holds
 * `active_session`; version 2 (Story 4.8) adds `purchase_intent`; version 3 (Story 4.10) adds `grant_ledger`, the only
 * store of raw purchase tokens. There is no destructive migration fallback.
 */
@Database(
    entities = [ActiveSessionEntity::class, PurchaseIntentEntity::class, GrantLedgerEntity::class],
    version = RuntimeDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@ConstructedBy(RuntimeDatabaseConstructor::class)
abstract class RuntimeDatabase : RoomDatabase() {
    abstract fun activeSessionDao(): ActiveSessionDao

    abstract fun purchaseIntentDao(): PurchaseIntentDao

    abstract fun grantLedgerDao(): GrantLedgerDao

    companion object {
        /** File name in the device-protected database directory; the backup rules exclude it by this name. */
        const val FILE_NAME = "runtime.db"

        /** The current schema version; every step to it is in [RUNTIME_DATABASE_MIGRATIONS]. */
        const val SCHEMA_VERSION = 3
    }
}

/** Implemented by the Room compiler for each target. */
@Suppress("KotlinNoActualForExpect")
expect object RuntimeDatabaseConstructor : RoomDatabaseConstructor<RuntimeDatabase> {
    override fun initialize(): RuntimeDatabase
}
