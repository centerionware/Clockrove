package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.flutter.plugin.common.MethodCall
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class TaskConversationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private lateinit var task: String
    private val version get() = repository.snapshot().version
    private fun png(): ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
    private fun largePng(): ByteArray {
        val original = png()
        val payload = byteArrayOf('K'.code.toByte(), 0) + ByteArray(2_100_000) { 'A'.code.toByte() }
        val typeAndData = "tEXt".toByteArray(Charsets.US_ASCII) + payload
        val chunk = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).putInt(payload.size).array())
            write(typeAndData)
            write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(typeAndData) }.value.toInt()).array())
        }.toByteArray()
        val iendOffset = original.size - 12
        return original.copyOfRange(0, iendOffset) + chunk + original.copyOfRange(iendOffset, original.size)
    }
    private fun count(table: String) = SQLiteDatabase.openDatabase(app.getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @Before fun setup() {
        app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { 2_000_000 })
        job = repository.createJob("Synthetic conversation job")
        val board = repository.boardSnapshot(job).board
        task = "conversation-task"
        assertNull(repository.saveCard("conversation-card", version, BoardStore.Draft(
            task, job, board.columns.first().id, "Synthetic tile installation", currency = board.currency,
        )).conflict)
    }
    @After fun close() { repository.close() }

    @Test fun commentsAreOrderedEditableAuditedAndImageOwnershipCascades() {
        val initial = repository.taskConversation(job, task)
        assertNull(repository.saveTaskComment("comment-add", initial.version, job, task, "comment-one", "First private progress note").conflict)
        var conversation = repository.taskConversation(job, task).conversation
        assertEquals("First private progress note", conversation.comments.single().body)
        assertNull(repository.attachTaskImage("image-comment", version, job, task, "comment-one", "image-one", png()).conflict)
        assertNull(repository.attachTaskImage("image-task", version, job, task, null, "image-two", png()).conflict)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), requireNotNull(repository.taskAttachment("image-one")).bytes.copyOfRange(0, 8))
        val dispatch = BusinessDispatch(repository)
        val imageResponse = dispatch.call(MethodCall("taskAttachment", mapOf("attachmentId" to "image-one"))) as Map<*, *>
        assertArrayEquals(requireNotNull(repository.taskAttachment("image-one")).bytes, imageResponse["image"] as ByteArray)
        conversation = repository.taskConversation(job, task).conversation
        assertEquals(1, conversation.comments.single().attachments.size)
        val conversationResponse = dispatch.call(MethodCall("taskConversation", mapOf("jobId" to job, "taskId" to task))) as Map<*, *>
        val comments = conversationResponse["comments"] as List<*>
        assertEquals("First private progress note", (comments.single() as Map<*, *>)["body"])

        assertNull(repository.saveTaskComment("comment-edit", version, job, task, "comment-one", "Edited private progress note").conflict)
        conversation = repository.taskConversation(job, task).conversation
        assertEquals("Edited private progress note", conversation.comments.single().body)
        assertTrue(conversation.comments.single().updatedMillis >= conversation.comments.single().createdMillis)
        assertTrue(conversation.events.any { it.action == "EDITED" })

        assertNull(repository.removeTaskComment("comment-delete", version, job, task, "comment-one").conflict)
        conversation = repository.taskConversation(job, task).conversation
        assertTrue(conversation.comments.isEmpty())
        assertTrue(conversation.events.any { it.action == "DELETED" })
        assertNull(repository.taskAttachment("image-one"))
        assertNotNull(repository.taskAttachment("image-two"))
        assertEquals(1, count("task_attachments"))

        assertNull(repository.removeCard("remove-parent-task", version, job, task).conflict)
        assertEquals(0, count("task_comments"))
        assertEquals(0, count("task_comment_events"))
        assertEquals(0, count("task_attachments"))
    }

    @Test fun normalizedImageBytesAreBoundedAndTaskAllowsAtMostTwentyAttachments() {
        val valid = png()
        assertNotNull(repository.attachTaskImage("invalid-image", version, job, task, null, "invalid", byteArrayOf(1, 2, 3)).conflict)
        for (index in 1..20) {
            assertNull(repository.attachTaskImage("image-$index", version, job, task, null, "image-$index", valid).conflict)
        }
        assertEquals("TASK_IMAGE_LIMIT", repository.attachTaskImage("image-21", version, job, task, null, "image-21", valid).conflict)
        assertEquals(20, repository.taskConversation(job, task).conversation.taskAttachments.size)
    }

    @Test fun selectedImagesCommitTogetherAndKeepSelectionOrder() {
        val valid = png()
        val invalid = byteArrayOf(1, 2, 3)
        val rejected = repository.attachTaskImages(
            "batch-invalid", version, job, task, null,
            listOf("first-selection" to valid, "second-selection" to invalid),
        )
        assertEquals("INVALID_TASK_IMAGE", rejected.conflict)
        assertTrue(repository.taskConversation(job, task).conversation.taskAttachments.isEmpty())

        val selected = listOf("first-selection" to valid, "second-selection" to valid)
        val commandVersion = version
        val accepted = repository.attachTaskImages(
            "batch-valid", commandVersion, job, task, null,
            selected,
        )
        assertNull(accepted.conflict)
        assertEquals(accepted, repository.attachTaskImages("batch-valid", commandVersion, job, task, null, selected))
        assertEquals(
            listOf("first-selection", "second-selection"),
            repository.taskConversation(job, task).conversation.taskAttachments.map { it.id },
        )
    }

    @Test fun schemaUpgradeWidensImageStorageAndPreservesPriorAttachments() {
        val legacyImage = png()
        assertNull(repository.attachTaskImage("legacy-image-add", version, job, task, null, "legacy-image", legacyImage).conflict)
        repository.close()

        SQLiteDatabase.openDatabase(
            app.getDatabasePath("clockrove.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE task_attachments")
                db.execSQL("CREATE TABLE task_attachments(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES kanban_cards(id) ON DELETE CASCADE,comment_id TEXT REFERENCES task_comments(id) ON DELETE CASCADE,created_ms INTEGER NOT NULL,mime TEXT NOT NULL CHECK(mime='image/png'),data BLOB NOT NULL CHECK(length(data) BETWEEN 1 AND 2000000))")
                db.execSQL("CREATE INDEX task_attachments_task ON task_attachments(task_id,created_ms,id)")
                db.execSQL("INSERT INTO task_attachments VALUES(?,?,?,?,?,?)", arrayOf("legacy-image", task, null, 1L, "image/png", legacyImage))
                db.version = 12
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        repository = TimerRepository(app, wallClock = { 2_000_000 })
        assertArrayEquals(legacyImage, repository.taskAttachment("legacy-image")!!.bytes)
        val largerImage = largePng()
        val added = repository.attachTaskImage(
            "expanded-image-add", version, job, task, null, "expanded-image", largerImage,
        )
        assertNull(added.conflict)
        assertArrayEquals(largerImage, repository.taskAttachment("expanded-image")!!.bytes)
    }
}
