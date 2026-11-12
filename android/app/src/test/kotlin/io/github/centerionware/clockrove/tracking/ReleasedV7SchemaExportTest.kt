package io.github.centerionware.clockrove.tracking

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One-time Actions export of released schema 7, before freezing the independent migration fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleasedV7SchemaExportTest {
    @Test fun exportReleasedSchemaForForwardMigrationFixture() {
        val app = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("schema-v7-export.db")
        file.parentFile!!.mkdirs()
        app.deleteDatabase(file.name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            javaClass.getResourceAsStream("/schema_v6.sql")!!.bufferedReader().use { source ->
                source.readText().split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
            }
            // These installers are the exact released schema-7 sources; do not
            // call the new helper or install payment tables into this baseline.
            InvoiceCoverageStore.install(db, 1793494800000L)
            db.version = 7
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid", null).use { rows -> buildList {
                while (rows.moveToNext()) add(rows.getString(0))
            } }
            assertFalse(tables.contains("invoice_payments"))
            assertTrue(tables.contains("invoice_task_claims"))
            val statements = db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END,rowid", null).use { rows -> buildList {
                while (rows.moveToNext()) add(rows.getString(0))
            } }.toMutableList()
            for (table in tables) db.rawQuery("SELECT * FROM $table", null).use { rows ->
                while (rows.moveToNext()) {
                    val values = (0 until rows.columnCount).map { column -> when (rows.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> "NULL"
                        Cursor.FIELD_TYPE_INTEGER -> rows.getLong(column).toString()
                        Cursor.FIELD_TYPE_FLOAT -> throw AssertionError("No real amounts or locations belong in the empty baseline")
                        Cursor.FIELD_TYPE_BLOB -> throw AssertionError("No private assets belong in the empty baseline")
                        else -> DatabaseUtils.sqlEscapeString(rows.getString(column))
                    } }
                    statements.add("INSERT INTO $table(${rows.columnNames.joinToString(",")}) VALUES(${values.joinToString(",")})")
                }
            }
            statements.add("PRAGMA user_version=7")
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            val workspace = System.getenv("GITHUB_WORKSPACE") ?: throw AssertionError("Schema exports run only in Actions")
            File(workspace, "build/native-schema/schema_v7.json").apply { parentFile!!.mkdirs(); writeText(JSONArray(statements).toString(2) + "\n") }
        }
    }
}
