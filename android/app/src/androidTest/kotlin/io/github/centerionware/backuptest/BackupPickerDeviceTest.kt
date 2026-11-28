package io.github.centerionware.backuptest

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.centerionware.clockrove.MainActivity
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupPickerDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var downloadsFixtureUri: android.net.Uri? = null

    @Test fun documentPickerRestoresIntoAnIndependentDatabase() {
        val app = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishFixtureInDownloads(app)
        val repository = TimerRepository.get(app)
        assertTrue(repository.snapshot().jobs.isEmpty())
        val existingJob = repository.createJob("Synthetic destination job")
        // API 24 DocumentsUI hides device storage behind an overflow menu that is
        // not reliably reachable headlessly. The debug flag asks the picker to show
        // it directly, matching what a user gets after choosing "Show internal storage".
        assertTrue(
            "CI advanced picker preference must be saved",
            app.getSharedPreferences("clockrove_ci_test_flags", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("document_picker_show_advanced", true).commit(),
        )
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        click("App settings and customers")
        click("Settings")
        waitFor("Settings backup section") { findLabel("Backup and restore") }
        click("Restore backup")
        openPicker()
        pressBack()
        waitFor("picker cancellation") { findLabel("File selection cancelled.") }
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })

        openPicker()
        if (findDocumentTitle(FIXTURE) == null) openSharedFixtureDirectory()
        openDocumentRow(FIXTURE, "backup document staged") {
            findLabel("Backup selected", prefix = true)
        }
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })

        // The fixture is a plaintext archive: preview detects it without a
        // password, which also avoids requiring an IME on headless images.
        click("Preview backup", scroll = true)
        val apply = waitFor("plaintext restore preview") {
            findLabel("Backup ready to review", prefix = true)
        }
        assertTrue(apply.isVisibleToUser)
        assertEquals(listOf(existingJob), repository.snapshot().jobs.map { it.id })
        // Independent installs create different revision roots for app settings.
        click("Keep the version on this device", scroll = true)
        click("Apply reviewed merge", scroll = true)
        waitFor("restore confirmation") { findLabel("Apply this backup?") }
        click("Apply reviewed merge")

        val expectedJobs = setOf(existingJob, "ci-document-source")
        val result = waitFor("committed restored job") {
            repository.snapshot().takeIf { snapshot -> snapshot.jobs.map { it.id }.toSet() == expectedJobs }
        }
        assertEquals(2, result.jobs.size)
        assertEquals("Synthetic restore source", result.jobs.single { it.id == "ci-document-source" }.name)
        assertNull(result.running)
        val entry = repository.sessionHistory("ci-document-source").page.entries.single()
        assertEquals("ci-document-session", entry.id)
        assertEquals(1735689600000L, entry.start)
        assertEquals(1735693200000L, entry.end)
        assertEquals("Etc/UTC", entry.timezone)
        val imported = repository.automationSnapshot("ci-document-source")
        assertEquals("ci-document-site", imported.sites.single().id)
        assertEquals(150.0, imported.sites.single().radiusMeters, 0.0)
        assertTrue("Imported automation should start disabled", !imported.settings.enabled)
        assertTrue("Imported site must not be registered", repository.sitesToRegister().isEmpty())
    }

    @After fun removePublishedFixture() {
        downloadsFixtureUri?.let {
            instrumentation.targetContext.contentResolver.delete(it, null, null)
        }
        downloadsFixtureUri = null
        instrumentation.targetContext
            .getSharedPreferences("clockrove_ci_test_flags", android.content.Context.MODE_PRIVATE)
            .edit().remove("document_picker_show_advanced").commit()
    }

    private fun publishFixtureInDownloads(app: android.content.Context) {
        val source = File(requireNotNull(app.getExternalFilesDir(null)), FIXTURE)
        assertTrue("The CI backup fixture was not staged for the system picker", source.isFile)
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FIXTURE)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FIXTURE_DIRECTORY/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        downloadsFixtureUri = uri
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            assertEquals(1, resolver.update(uri, values, null, null))
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            downloadsFixtureUri = null
            throw error
        }
    }

    private fun openSharedFixtureDirectory() {
        selectSharedStorageRoot()
        openDocumentRow("Download", "the shared Download directory") {
            findDocumentTitle(FIXTURE_DIRECTORY)
        }
        // List rows expose a title separate from the selection icon.
        val listView = findLabel("List view") ?: findLabel("View as list")
        if (listView != null) {
            if (!performAccessibilityClick(listView)) tapPickerNode(listView)
            automation.waitForIdle(250, 5_000)
        }
        openDocumentRow(FIXTURE_DIRECTORY, "backup fixture in the opened directory") {
            findDocumentTitle(FIXTURE)
        }
    }

    private fun selectSharedStorageRoot() {
        showPickerRoots()
        // The debug CI flag makes DocumentsUI list the device root directly.
        val root = waitFor("internal shared-storage root") { sharedStorageRoot() }
        if (!performAccessibilityClick(root)) tapPickerNode(root)
        if (findLabel("Open from") != null) closePickerRoots()
        waitForClosedPickerRoots()
    }

    private fun sharedStorageRoot(): AccessibilityNodeInfo? =
        findLabel(Build.MODEL) ?: findLabel("Internal storage")

    private fun closePickerRoots() {
        val window = automation.rootInActiveWindow ?: return
        val bounds = Rect().also(window::getBoundsInScreen)
        // Tapping the dimmed pane closes the roots drawer without exiting Documents.
        runShellInput("tap ${bounds.right - 8} ${bounds.centerY()}")
        waitForClosedPickerRoots()
    }

    private fun findDocumentTitle(label: String): AccessibilityNodeInfo? = findNode { node ->
        node.packageName?.toString() in DOCUMENT_PICKER_PACKAGES &&
            node.viewIdResourceName?.endsWith(":id/title") == true &&
            node.isVisibleToUser && node.isEnabled && node.text?.toString() == label
    }

    private fun openDocumentRow(
        label: String,
        destination: String,
        findDestination: () -> AccessibilityNodeInfo?,
    ) {
        var attempts = 0
        var nextTap = 0L
        waitFor("$destination after tapping Documents row $label") {
            findDestination()?.let { return@waitFor it }
            if (attempts < 3 && SystemClock.uptimeMillis() >= nextTap) {
                findDocumentTitle(label)?.let { title ->
                    touchNode(title)
                    attempts++
                    nextTap = SystemClock.uptimeMillis() + 1_000
                }
            }
            null
        }
    }

    private fun touchNode(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) failWithScreenshot("No touch bounds for ${node.text ?: node.contentDescription}")

        // On API 24, shell input tap leaves the tool type unknown. DocumentsUI's
        // directory grid and list require the tool type to identify a finger tap.
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX()
            y = bounds.exactCenterY()
            pressure = 1f
            size = 1f
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                if (!automation.injectInputEvent(event, true)) {
                    failWithScreenshot("Android did not receive touch action $action")
                }
            } finally {
                event.recycle()
            }
        }
    }

    private fun waitForClosedPickerRoots() {
        waitFor("closed document roots") {
            automation.rootInActiveWindow?.takeIf {
                it.packageName?.toString() in DOCUMENT_PICKER_PACKAGES &&
                    findLabel("Open from", root = it) == null
            }
        }
        automation.waitForIdle(250, 5_000)
    }

    private fun showPickerRoots() {
        var nextActivation = 0L
        var triedAccessibility = false
        waitFor("open document roots") {
            findLabel("Open from")?.let { return@waitFor it }
            if (SystemClock.uptimeMillis() >= nextActivation) {
                val navigation = findLabel("Show roots") ?: findLabel("Open navigation drawer")
                if (navigation?.isEnabled == true) {
                    if (triedAccessibility || !performAccessibilityClick(navigation)) {
                        touchNode(navigation)
                    }
                    triedAccessibility = true
                    nextActivation = SystemClock.uptimeMillis() + 1_000
                }
            }
            null
        }
    }

    private fun pressBack() = runShellInput("keyevent KEYCODE_BACK")

    private fun runShellInput(arguments: String) {
        val shellOutput = automation.executeShellCommand("input $arguments")
        ParcelFileDescriptor.AutoCloseInputStream(shellOutput).bufferedReader().use { it.readText() }
    }

    private fun openPicker() {
        click("Choose backup")
        val picker = waitFor("Android document picker or resolver") {
            automation.rootInActiveWindow?.takeIf {
                it.packageName?.toString() in DOCUMENT_PICKER_PACKAGES ||
                    (it.packageName?.toString() == "android" &&
                        (findLabel("Documents") != null || findLabel("Complete action using Documents") != null))
            }
        }
        if (picker.packageName?.toString() == "android" && findLabel("Documents") != null) {
            clickPickerLabel("Documents")
        }
        var confirmedResolver = false
        waitFor("Android document picker") {
            val root = automation.rootInActiveWindow
            if (!confirmedResolver && root?.packageName?.toString() == "android") {
                val once = findLabel("Just once", ignoreCase = true)?.takeIf { it.isEnabled }
                if (once != null) {
                    val bounds = Rect().also(once::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        tapPickerNode(once)
                        confirmedResolver = true
                    }
                }
            }
            root?.takeIf { it.packageName?.toString() in DOCUMENT_PICKER_PACKAGES }
        }
    }

    private fun findLabel(
        label: String,
        prefix: Boolean = false,
        ignoreCase: Boolean = false,
        root: AccessibilityNodeInfo? = automation.rootInActiveWindow,
    ): AccessibilityNodeInfo? = findNode(root) { node ->
        node.isVisibleToUser && listOf(node.text, node.contentDescription).any { value ->
            val text = value?.lineSequence()?.firstOrNull()?.trim()
            text.equals(label, ignoreCase = ignoreCase) || (prefix && text?.startsWith(label) == true)
        }
    }

    private fun findNode(
        root: AccessibilityNodeInfo? = automation.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return null
        }
        return root?.let(::visit)
    }

    private fun click(label: String, scroll: Boolean = false) {
        val node = waitFor(label) {
            findLabel(label)?.takeIf { it.isEnabled } ?: run {
                if (scroll) {
                    findNode { it.isVisibleToUser && it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(250)
                }
                null
            }
        }
        clickNode(node)
    }

    private fun clickPickerLabel(label: String) {
        val node = waitFor(label) { findLabel(label)?.takeIf { it.isEnabled } }
        tapPickerNode(node)
    }

    private fun tapPickerNode(node: AccessibilityNodeInfo) {
        // Documents and the resolver can display rows without exposing ACTION_CLICK.
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) failWithScreenshot("No tap bounds for ${node.text ?: node.contentDescription}")
        runShellInput("tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        if (!performAccessibilityClick(node)) {
            failWithScreenshot("Could not activate ${node.text ?: node.contentDescription}")
        }
    }

    private fun performAccessibilityClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        return false
    }

    private fun <T : Any> waitFor(label: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(100)
        }
        failWithScreenshot("Android did not expose $label")
    }

    private fun failWithScreenshot(message: String): Nothing {
        val activePackage = automation.rootInActiveWindow?.packageName
        try {
            val capture = File(instrumentation.targetContext.getExternalFilesDir(null), "backup-picker-failure.png")
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    capture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
            // Keep the original failure even if the runner cannot save a screenshot.
        }
        throw AssertionError("$message (active package: $activePackage)")
    }

    companion object {
        private val DOCUMENT_PICKER_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
        private const val FIXTURE_DIRECTORY = "Clockrove-CI-Backup"
        private const val FIXTURE = "clockrove-picker-fixture.clockrove"
    }
}
