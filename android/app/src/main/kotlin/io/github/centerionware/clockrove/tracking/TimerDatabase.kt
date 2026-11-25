package io.github.centerionware.clockrove.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class TimerDatabase(context: Context, private val now: () -> Long) : SQLiteOpenHelper(context, "clockrove.db", null, 17) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")
        db.execSQL("INSERT INTO state VALUES(1,0)")
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,name TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),start_ms INTEGER NOT NULL,end_ms INTEGER,timezone TEXT NOT NULL,offset_seconds INTEGER NOT NULL,origin TEXT NOT NULL,CHECK(end_ms IS NULL OR end_ms>start_ms))")
        db.execSQL("CREATE UNIQUE INDEX one_open_session ON sessions((1)) WHERE end_ms IS NULL")
        db.execSQL("CREATE INDEX session_dates ON sessions(start_ms,end_ms)")
        db.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,version INTEGER NOT NULL,conflict TEXT)")
        createLocationTable(db)
        createDepartureTable(db)
        GeofenceStore.createTables(db)
        BusinessSchema.install(db, now())
        WidgetConfigurationStore.install(db)
        InvoiceCoverageStore.install(db, now())
        InvoicePaymentStore.install(db, now())
        QuoteStore.install(db)
        LocationRecordingStore.install(db, now())
        TaskConversationStore.install(db)
        InvoiceBillingPolicyStore.install(db, now())
        ActualCostStore.install(db)
        GpsDeletionStore.createTables(db)
    }
    private fun createLocationTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE location_points(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),measured_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),accuracy REAL NOT NULL CHECK(accuracy>=0))")
        db.execSQL("CREATE INDEX location_session_date ON location_points(session_id,measured_ms)")
    }
    private fun createDepartureTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_departure(session_id TEXT PRIMARY KEY REFERENCES sessions(id),detected_ms INTEGER NOT NULL,deadline_ms INTEGER NOT NULL CHECK(deadline_ms>=detected_ms))")
        db.execSQL("CREATE UNIQUE INDEX one_departure_candidate ON pending_departure((1))")
    }
    override fun onOpen(db: SQLiteDatabase) {
        // Repair retained cost heads damaged by older job purges or schema-14 payloads.
        ActualCostStore.repairRetainedHeads(db, now())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..16 && newVersion == 17) { "Unsupported schema upgrade $oldVersion -> $newVersion" }
        if (oldVersion < 2) createLocationTable(db)
        if (oldVersion < 3) createDepartureTable(db)
        if (oldVersion < 4) GeofenceStore.createTables(db)
        if (oldVersion < 5) BusinessSchema.install(db, now())
        if (oldVersion < 6) WidgetConfigurationStore.install(db)
        if (oldVersion < 7) InvoiceCoverageStore.install(db, now())
        if (oldVersion < 8) InvoicePaymentStore.install(db, now())
        if (oldVersion < 9) QuoteStore.install(db)
        if (oldVersion < 10) {
            LocationRecordingStore.install(db, now())
            TaskConversationStore.install(db)
        }
        if (oldVersion < 11) InvoiceBillingPolicyStore.install(db, now())
        if (oldVersion < 12) TaskClassificationStore.install(db, now())
        if (oldVersion < 13) TaskConversationStore.migrateImageByteLimit(db)
        if (oldVersion < 14) ActualCostStore.install(db)
        if (oldVersion == 14) ActualCostStore.migrateCustomerSnapshot(db, now())
        if (oldVersion >= 4) GeofenceStore.migrateSmallSiteRadius(db)
        GpsDeletionStore.migrate(db, now())
        TaskConversationStore.backfillEntityRevisions(db, now())
    }
}
