package io.github.centerionware.clockrove

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Test APK only. Keeps actual device results readable before explicit cleanup. */
class CiXmlListener : RunListener() {
    private data class Case(val classname: String, val name: String, var start: Long = System.nanoTime(), var end: Long = 0,
        var skipped: Boolean = false, val failures: MutableList<Failure> = mutableListOf())
    private val cases = linkedMapOf<String, Case>()
    private fun row(description: Description): Case = cases.getOrPut(description.displayName) {
        Case(description.className ?: "instrumentation", description.methodName ?: description.displayName)
    }
    override fun testStarted(description: Description) { row(description).start = System.nanoTime() }
    override fun testFinished(description: Description) { row(description).end = System.nanoTime() }
    override fun testFailure(failure: Failure) { row(failure.description).failures.add(failure) }
    override fun testAssumptionFailure(failure: Failure) { row(failure.description).skipped = true }
    override fun testIgnored(description: Description) { row(description).skipped = true }
    override fun testRunFinished(result: Result) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "clockrove-test-results.xml")
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            val xml = android.util.Xml.newSerializer()
            xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true)
            xml.startTag(null, "testsuite"); xml.attribute(null, "name", "Clockrove actual Android")
            xml.attribute(null, "tests", cases.size.toString())
            xml.attribute(null, "failures", cases.values.sumOf { c -> c.failures.count { it.exception is AssertionError } }.toString())
            xml.attribute(null, "errors", cases.values.sumOf { c -> c.failures.count { it.exception !is AssertionError } }.toString())
            xml.attribute(null, "skipped", cases.values.count { it.skipped }.toString())
            cases.values.forEach { c ->
                xml.startTag(null, "testcase"); xml.attribute(null, "classname", c.classname); xml.attribute(null, "name", c.name)
                xml.attribute(null, "time", String.format(Locale.ROOT,"%.6f",(c.end.takeIf { it >= c.start } ?: c.start).minus(c.start) / 1_000_000_000.0))
                if (c.skipped) { xml.startTag(null, "skipped"); xml.endTag(null, "skipped") }
                c.failures.forEach { failure ->
                    val tag = if (failure.exception is AssertionError) "failure" else "error"
                    xml.startTag(null, tag); xml.attribute(null, "type", failure.exception.javaClass.name)
                    xml.attribute(null, "message", failure.message.orEmpty()); xml.text(failure.trace); xml.endTag(null, tag)
                }
                xml.endTag(null,"testcase")
            }
            xml.endTag(null,"testsuite"); xml.endDocument(); xml.flush()
        }
    }
}
