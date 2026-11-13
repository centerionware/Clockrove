package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** Immutable company assets; prepared invoices retain the exact referenced logo. */
internal object LogoStore {
    data class Asset(val id: String, val width: Int, val height: Int, val bytes: ByteArray)
    data class Snapshot(val version: Long, val id: String?, val width: Int?, val height: Int?, val bytes: ByteArray?)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun apply(db: SQLiteDatabase, token: String, load: () -> Asset?, at: Long): String? {
        if (!token.matches(Regex("[a-f0-9]{64}"))) return "INVALID_LOGO"
        val asset = load() ?: return "LOGO_IMPORT_EXPIRED"
        if (asset.id != token || hash(asset.bytes) != token || asset.bytes.size !in 1..2_000_000 || asset.width !in 1..1024 || asset.height !in 1..1024) return "INVALID_LOGO"
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (asset.bytes.size < 24 || !asset.bytes.copyOfRange(0, 8).contentEquals(signature) ||
            java.nio.ByteBuffer.wrap(asset.bytes, 16, 8).let { it.int != asset.width || it.int != asset.height }) return "INVALID_LOGO"
        db.execSQL("INSERT OR IGNORE INTO logo_assets(id,mime,width,height,content_hash,bytes) VALUES(?,'image/png',?,?,?,?)", arrayOf<Any?>(asset.id, asset.width, asset.height, asset.id, asset.bytes))
        RevisionStore.record(db, "logo", asset.id, null, mapOf("id" to asset.id, "mime" to "image/png", "width" to asset.width, "height" to asset.height, "content_hash" to asset.id), at)
        db.execSQL("UPDATE app_settings SET logo_id=? WHERE id=1", arrayOf(asset.id))
        RevisionStore.snapshotRows(db, "app_settings", "settings", BusinessSchema.settingsColumns, at)
        collectUnused(db, at)
        return null
    }
    fun remove(db: SQLiteDatabase, at: Long): String? {
        db.execSQL("UPDATE app_settings SET logo_id=NULL WHERE id=1")
        RevisionStore.snapshotRows(db, "app_settings", "settings", BusinessSchema.settingsColumns, at)
        collectUnused(db, at)
        return null
    }
    fun collectUnused(db: SQLiteDatabase, at: Long) {
        val unused = db.rawQuery("SELECT id FROM logo_assets WHERE id NOT IN(SELECT logo_id FROM app_settings WHERE logo_id IS NOT NULL) AND id NOT IN(SELECT logo_id FROM invoices WHERE logo_id IS NOT NULL) AND id NOT IN(SELECT logo_id FROM quote_revisions WHERE logo_id IS NOT NULL)", null).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        unused.forEach { id -> db.execSQL("DELETE FROM logo_assets WHERE id=?", arrayOf(id)); RevisionStore.record(db, "logo", id, null, mapOf("id" to id), at, deleted = true) }
    }
}
