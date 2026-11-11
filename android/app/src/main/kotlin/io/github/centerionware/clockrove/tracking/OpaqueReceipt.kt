package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.security.MessageDigest

/** Erases personal command payloads without forgetting committed response-loss outcomes. */
internal object OpaqueReceipt {
    private const val marker = "clockrove:purged-receipt:v1"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun parse(value: String): JSONArray? = try { JSONArray(value) } catch (_: org.json.JSONException) { null }
    private fun surface(raw: JSONArray): JSONArray? {
        if (raw.length() != 7 || raw.opt(0) !is Number || raw.optString(1) !in setOf("START", "STOP", "SWITCH") ||
            raw.opt(3) !is Number || raw.opt(4) !is String || raw.opt(5) !is Number || raw.optString(6) !in setOf("MANUAL", "WIDGET", "NOTIFICATION")) return null
        return JSONArray(listOf(raw.getLong(0), raw.getString(1), if (raw.isNull(2)) null else raw.getString(2), raw.getString(6)))
    }
    fun matches(stored: String, candidate: String): Boolean {
        if (stored == candidate) return true
        val raw = parse(stored) ?: return false
        return raw.length() == 3 && raw.optString(0) == marker && raw.optString(1) == digest(candidate)
    }
    fun matchesSurface(stored: String, version: Long, action: String, jobId: String?, origin: String): Boolean {
        val raw = parse(stored) ?: return false
        val header = if (raw.length() == 3 && raw.optString(0) == marker) raw.optJSONArray(2) else surface(raw)
        return header?.toString() == JSONArray(listOf(version, action, jobId, origin)).toString()
    }
    fun purgePayloads(db: SQLiteDatabase) {
        val replacements = db.rawQuery("SELECT id,fingerprint FROM commands", null).use { rows -> buildList {
            while (rows.moveToNext()) {
                val original = rows.getString(1); val raw = parse(original) ?: continue
                if (raw.optString(0) == marker) continue
                add(rows.getString(0) to JSONArray(listOf(marker, digest(original), surface(raw))).toString())
            }
        } }
        replacements.forEach { (id, value) -> db.execSQL("UPDATE commands SET fingerprint=? WHERE id=?", arrayOf(value, id)) }
    }
}
