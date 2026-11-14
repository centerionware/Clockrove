package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Local comments and normalized private task photos. All writes run in the timer transaction. */
internal object TaskConversationStore {
    private const val MAX_IMAGE_BYTES = ImageLimits.NORMALIZED_BYTES
    private const val MAX_IMAGES_PER_TASK = ImageLimits.TASK_IMAGES_PER_TASK
    private const val MAX_IMAGE_BYTES_PER_JOB = ImageLimits.TASK_IMAGE_BYTES_PER_JOB
    private const val ATTACHMENT_READ_CHUNK_BYTES = 512 * 1024
    private val id = Regex("[A-Za-z0-9_-]{1,128}")

    data class AttachmentInfo(val id: String, val commentId: String?, val createdMillis: Long, val bytes: Int)
    data class Comment(val id: String, val body: String, val createdMillis: Long, val updatedMillis: Long,
        val attachments: List<AttachmentInfo>)
    data class Event(val commentId: String, val action: String, val recordedMillis: Long)
    data class Conversation(val taskId: String, val jobId: String, val archived: Boolean,
        val taskAttachments: List<AttachmentInfo>, val comments: List<Comment>, val events: List<Event>)
    data class Attachment(val id: String, val taskId: String, val commentId: String?, val bytes: ByteArray)
    private data class AttachmentMetadata(val id: String, val taskId: String, val commentId: String?, val bytes: Int)

    fun install(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE task_comments(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 2000),created_ms INTEGER NOT NULL,updated_ms INTEGER NOT NULL CHECK(updated_ms>=created_ms))")
        db.execSQL("CREATE INDEX task_comments_order ON task_comments(task_id,created_ms,id)")
        db.execSQL("CREATE TABLE task_comment_events(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,comment_id TEXT NOT NULL,action TEXT NOT NULL CHECK(action IN('ADDED','EDITED','DELETED')),recorded_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX task_comment_events_order ON task_comment_events(task_id,recorded_ms,id)")
        createAttachmentsTable(db, "task_attachments")
        db.execSQL("CREATE INDEX task_attachments_task ON task_attachments(task_id,created_ms,id)")
    }

    /** Rebuild the old size-constrained table while retaining every existing attachment. */
    fun migrateImageByteLimit(db: SQLiteDatabase) {
        createAttachmentsTable(db, "task_attachments_expanded")
        db.execSQL("INSERT INTO task_attachments_expanded(id,task_id,comment_id,created_ms,mime,data) SELECT id,task_id,comment_id,created_ms,mime,data FROM task_attachments")
        db.execSQL("DROP TABLE task_attachments")
        db.execSQL("ALTER TABLE task_attachments_expanded RENAME TO task_attachments")
        db.execSQL("CREATE INDEX task_attachments_task ON task_attachments(task_id,created_ms,id)")
    }

    private fun createAttachmentsTable(db: SQLiteDatabase, table: String) {
        db.execSQL("CREATE TABLE $table(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,comment_id TEXT REFERENCES task_comments(id) ON DELETE CASCADE,created_ms INTEGER NOT NULL,mime TEXT NOT NULL CHECK(mime='image/png'),data BLOB NOT NULL CHECK(length(data) BETWEEN 1 AND $MAX_IMAGE_BYTES))")
    }

    private fun exists(db: SQLiteDatabase, jobId: String, taskId: String, editable: Boolean): Boolean {
        val archived = if (editable) "AND j.archived=0" else ""
        return db.rawQuery("SELECT c.id FROM kanban_cards c JOIN jobs j ON j.id=c.job_id WHERE c.id=? AND c.job_id=? $archived", arrayOf(taskId, jobId)).use { it.moveToFirst() }
    }

    private fun attachmentInfo(db: SQLiteDatabase, taskId: String, commentId: String? = null): List<AttachmentInfo> {
        val filter = if (commentId == null) "AND comment_id IS NULL" else "AND comment_id=?"
        val args = if (commentId == null) arrayOf(taskId) else arrayOf(taskId, commentId)
        return db.rawQuery("SELECT id,comment_id,created_ms,length(data) FROM task_attachments WHERE task_id=? $filter ORDER BY created_ms,id", args).use { rows ->
            buildList { while (rows.moveToNext()) add(AttachmentInfo(rows.getString(0), if (rows.isNull(1)) null else rows.getString(1), rows.getLong(2), rows.getInt(3))) }
        }
    }

    fun read(db: SQLiteDatabase, jobId: String, taskId: String): Conversation {
        var archived = false
        db.rawQuery("SELECT archived FROM jobs WHERE id=? AND EXISTS(SELECT 1 FROM kanban_cards WHERE id=? AND job_id=?)", arrayOf(jobId, taskId, jobId)).use {
            require(it.moveToFirst()) { "TASK_UNAVAILABLE" }
            archived = it.getInt(0) != 0
        }
        val comments = db.rawQuery("SELECT id,body,created_ms,updated_ms FROM task_comments WHERE task_id=? ORDER BY created_ms,id", arrayOf(taskId)).use { rows ->
            buildList { while (rows.moveToNext()) {
                val commentId = rows.getString(0)
                add(Comment(commentId, rows.getString(1), rows.getLong(2), rows.getLong(3), attachmentInfo(db, taskId, commentId)))
            } }
        }
        val events = db.rawQuery("SELECT comment_id,action,recorded_ms FROM task_comment_events WHERE task_id=? ORDER BY recorded_ms DESC,id DESC LIMIT 20", arrayOf(taskId)).use { rows ->
            buildList { while (rows.moveToNext()) add(Event(rows.getString(0), rows.getString(1), rows.getLong(2))) }
        }
        return Conversation(taskId, jobId, archived, attachmentInfo(db, taskId), comments, events)
    }

    fun attachment(db: SQLiteDatabase, attachmentId: String): Attachment? {
        val metadata = db.rawQuery(
            "SELECT id,task_id,comment_id,length(data) FROM task_attachments WHERE id=? AND length(data) BETWEEN 1 AND ?",
            arrayOf(attachmentId, MAX_IMAGE_BYTES.toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) return null
            AttachmentMetadata(
                rows.getString(0), rows.getString(1),
                if (rows.isNull(2)) null else rows.getString(2), rows.getInt(3),
            )
        }
        val image = ByteArrayOutputStream(metadata.bytes)
        var offset = 0
        while (offset < metadata.bytes) {
            val requested = minOf(ATTACHMENT_READ_CHUNK_BYTES, metadata.bytes - offset)
            val chunk = db.rawQuery(
                "SELECT substr(data,?,?) FROM task_attachments WHERE id=? AND length(data)=?",
                arrayOf((offset + 1).toString(), requested.toString(), metadata.id, metadata.bytes.toString()),
            ).use { rows ->
                if (!rows.moveToFirst()) return null
                rows.getBlob(0)
            }
            if (chunk.size != requested) return null
            image.write(chunk)
            offset += chunk.size
        }
        return Attachment(metadata.id, metadata.taskId, metadata.commentId, image.toByteArray())
    }

    private fun event(db: SQLiteDatabase, taskId: String, commentId: String, action: String, at: Long) {
        db.execSQL("INSERT INTO task_comment_events VALUES(?,?,?,?,?)", arrayOf<Any?>(UUID.randomUUID().toString(), taskId, commentId, action, at))
    }

    fun saveComment(db: SQLiteDatabase, jobId: String, taskId: String, commentId: String, body: String, at: Long): String? {
        if (!id.matches(commentId) || body.trim().isEmpty() || body.length > 2000 || '\u0000' in body || at < 0) return "INVALID_TASK_COMMENT"
        if (!exists(db, jobId, taskId, editable = true)) return "TASK_UNAVAILABLE"
        val previous = db.rawQuery("SELECT task_id,created_ms FROM task_comments WHERE id=?", arrayOf(commentId)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        }
        if (previous != null && previous.first != taskId) return "TASK_COMMENT_MISMATCH"
        if (previous == null) {
            db.execSQL("INSERT INTO task_comments VALUES(?,?,?,?,?)", arrayOf<Any?>(commentId, taskId, body.trim(), at, at))
            event(db, taskId, commentId, "ADDED", at)
        } else {
            db.execSQL("UPDATE task_comments SET body=?,updated_ms=? WHERE id=?", arrayOf<Any?>(body.trim(), maxOf(at, previous.second), commentId))
            event(db, taskId, commentId, "EDITED", at)
        }
        return null
    }

    fun removeComment(db: SQLiteDatabase, jobId: String, taskId: String, commentId: String, at: Long): String? {
        if (!exists(db, jobId, taskId, editable = true)) return "TASK_UNAVAILABLE"
        val found = db.rawQuery("SELECT id FROM task_comments WHERE id=? AND task_id=?", arrayOf(commentId, taskId)).use { it.moveToFirst() }
        if (!found) return "TASK_COMMENT_UNAVAILABLE"
        event(db, taskId, commentId, "DELETED", at)
        db.execSQL("DELETE FROM task_comments WHERE id=?", arrayOf(commentId))
        return null
    }

    fun addAttachment(db: SQLiteDatabase, jobId: String, taskId: String, commentId: String?, attachmentId: String, image: ByteArray, at: Long): String? {
        return addAttachments(db, jobId, taskId, commentId, listOf(attachmentId to image), at)
    }

    fun addAttachments(db: SQLiteDatabase, jobId: String, taskId: String, commentId: String?, attachments: List<Pair<String, ByteArray>>, at: Long): String? {
        if (attachments.isEmpty()) return "INVALID_TASK_IMAGE"
        if (attachments.size > ImageLimits.TASK_IMAGES_PER_SELECTION) return "TASK_IMAGE_SELECTION_LIMIT"
        if (at < 0 || attachments.any { (attachmentId, image) ->
                !id.matches(attachmentId) || image.size !in 1..MAX_IMAGE_BYTES || image.size < 8 ||
                    !image.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            }) return "INVALID_TASK_IMAGE"
        if (attachments.map { it.first }.toSet().size != attachments.size) return "TASK_IMAGE_ID_EXISTS"
        if (!exists(db, jobId, taskId, editable = true)) return "TASK_UNAVAILABLE"
        if (commentId != null && db.rawQuery("SELECT id FROM task_comments WHERE id=? AND task_id=?", arrayOf(commentId, taskId)).use { !it.moveToFirst() }) return "TASK_COMMENT_UNAVAILABLE"
        for ((attachmentId, image) in attachments) {
            if (db.rawQuery("SELECT id FROM task_attachments WHERE id=?", arrayOf(attachmentId)).use { it.moveToFirst() }) return "TASK_IMAGE_ID_EXISTS"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024 || bounds.outWidth.toLong() * bounds.outHeight > 1_048_576) return "INVALID_TASK_IMAGE"
        }
        val count = db.rawQuery("SELECT COUNT(*) FROM task_attachments WHERE task_id=?", arrayOf(taskId)).use { it.moveToFirst(); it.getInt(0) }
        if (count + attachments.size > MAX_IMAGES_PER_TASK) return "TASK_IMAGE_LIMIT"
        val batchBytes = attachments.sumOf { it.second.size.toLong() }
        if (batchBytes > ImageLimits.TASK_IMAGE_BATCH_BYTES) return "TASK_IMAGE_BATCH_STORAGE_LIMIT"
        val jobBytes = db.rawQuery("SELECT COALESCE(SUM(length(a.data)),0) FROM task_attachments a JOIN kanban_cards c ON c.id=a.task_id WHERE c.job_id=?", arrayOf(jobId)).use { it.moveToFirst(); it.getLong(0) }
        if (jobBytes + batchBytes > MAX_IMAGE_BYTES_PER_JOB) return "TASK_IMAGE_STORAGE_LIMIT"
        attachments.forEachIndexed { index, (attachmentId, image) ->
            db.execSQL("INSERT INTO task_attachments(id,task_id,comment_id,created_ms,mime,data) VALUES(?,?,?,?,?,?)", arrayOf<Any?>(attachmentId, taskId, commentId, Math.addExact(at, index.toLong()), "image/png", image))
        }
        return null
    }

    fun removeAttachment(db: SQLiteDatabase, jobId: String, taskId: String, attachmentId: String): String? {
        if (!exists(db, jobId, taskId, editable = true)) return "TASK_UNAVAILABLE"
        val found = db.rawQuery("SELECT id FROM task_attachments WHERE id=? AND task_id=?", arrayOf(attachmentId, taskId)).use { it.moveToFirst() }
        if (!found) return "TASK_IMAGE_UNAVAILABLE"
        db.execSQL("DELETE FROM task_attachments WHERE id=?", arrayOf(attachmentId))
        return null
    }
}
