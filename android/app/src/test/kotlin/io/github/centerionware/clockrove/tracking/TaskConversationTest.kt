package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.flutter.plugin.common.MethodCall
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
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )
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
}
