package io.github.centerionware.clockrove.tracking

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class LogoStoreTest {
    private lateinit var repository: TimerRepository
    private lateinit var job: String
    private var now = 1793534400000L
    private val version get() = repository.snapshot().version
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication(); app.deleteDatabase("clockrove.db")
        repository = TimerRepository(app, wallClock = { now }); job = repository.createJob("Synthetic branding job")
    }
    @After fun close() { repository.close() }
    /** Small, valid synthetic PNG; Android decoding is tested on the emulator. */
    private fun asset(color: Int): LogoStore.Asset {
        val output = ByteArrayOutputStream()
        val stream = java.io.DataOutputStream(output)
        stream.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            stream.writeInt(bytes.size); stream.write(name); stream.write(bytes)
            stream.writeInt(java.util.zip.CRC32().apply { update(name); update(bytes) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { java.io.DataOutputStream(it).apply { writeInt(2); writeInt(1); write(byteArrayOf(8,6,0,0,0)) } }.toByteArray()
        chunk("IHDR", header)
        val pixels = ByteArrayOutputStream().apply {
            write(0)
            repeat(2) { write(color ushr 16 and 255); write(color ushr 8 and 255); write(color and 255); write(color ushr 24 and 255) }
        }.toByteArray()
        val compressed = ByteArrayOutputStream().also { java.util.zip.DeflaterOutputStream(it).use { zip -> zip.write(pixels) } }.toByteArray()
        chunk("IDAT", compressed); chunk("IEND", byteArrayOf()); stream.flush()
        val bytes = output.toByteArray()
        return LogoStore.Asset(LogoStore.hash(bytes),2,1,bytes)
    }
    private fun apply(command: String, asset: LogoStore.Asset) = repository.applyLogo(command, version, asset.id) { asset }
    private fun <T> inspect(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("clockrove.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    @Test fun applyAndRemovePersistRealAssetBytesAndOpaqueRevisionMetadata() {
        val asset = asset(0xff176b55.toInt())
        assertNull(apply("apply", asset).conflict); assertEquals(asset.id, repository.logo().id); assertArrayEquals(asset.bytes, repository.logo().bytes)
        assertNull(repository.removeLogo("remove", version).conflict); assertNull(repository.logo().id)
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM logo_assets", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) } }
    }
    @Test fun responseLossReplayDoesNotNeedAnExpiredDraftOrReapplyRemovedLogo() {
        val asset = asset(0xff176b55.toInt()); val originalVersion = version
        val first = repository.applyLogo("same", originalVersion, asset.id) { asset }
        repository.removeLogo("remove", version)
        assertEquals(first, repository.applyLogo("same", originalVersion, asset.id) { error("Replay must not load draft bytes") })
        assertNull(repository.logo().id)
        assertEquals("COMMAND_ID_REUSED", repository.applyLogo("same", originalVersion, asset(0xff000000.toInt()).id) { error("Reuse must not load") }.conflict)
    }
    @Test fun staleAndInvalidAssetsCannotWriteAnyBranding() {
        val valid = asset(0xff176b55.toInt())
        assertEquals("STALE_STATE", repository.applyLogo("stale", version - 1, valid.id) { error("Stale command loaded bytes") }.conflict)
        assertEquals("LOGO_IMPORT_EXPIRED", repository.applyLogo("expired", version, valid.id) { null }.conflict)
        assertEquals("INVALID_LOGO", repository.applyLogo("bad-size", version, valid.id) { valid.copy(width = 1025) }.conflict)
        assertEquals("INVALID_LOGO", repository.applyLogo("bad-dimension", version, valid.id) { valid.copy(width = 1) }.conflict)
        assertEquals("INVALID_LOGO", repository.applyLogo("bad-hash", version, valid.id) { valid.copy(bytes = byteArrayOf(1,2,3)) }.conflict)
        assertNull(repository.logo().id)
    }
    @Test fun preparedInvoiceRetainsOriginalLogoWhenCompanyChangesOrRemovesIt() {
        val original = asset(0xff176b55.toInt()); apply("first-logo", original)
        repository.saveBusinessSettings("rate", version, repository.businessSnapshot().settings.copy(defaultHourlyMinor = 10000))
        repository.execute(TimerRepository.Command("start", version, TimerRepository.Action.START, job, 1793491200000, "UTC", 0, TimerRepository.Origin.MANUAL))
        repository.execute(TimerRepository.Command("stop", version, TimerRepository.Action.STOP, null, 1793494800000, "UTC", 0, TimerRepository.Origin.MANUAL))
        repository.prepareInvoice("invoice", version, "invoice", ReportStore.Selection("2026-11-01", "2026-11-01", "UTC", listOf(job)))
        val second = asset(0xff2563eb.toInt()); assertNull(apply("second-logo", second).conflict)
        assertArrayEquals(original.bytes, repository.invoice("invoice").logo)
        repository.removeLogo("remove", version); assertArrayEquals(original.bytes, repository.invoice("invoice").logo)
        inspect { db -> db.rawQuery("SELECT id FROM logo_assets", null).use { it.moveToFirst(); assertEquals(original.id, it.getString(0)); assertFalse(it.moveToNext()) } }
        repository.archiveJob("archive", version, job); now += JobLifecycleStore.retentionMillis
        assertNull(repository.deleteJob("delete", version, job).conflict)
        assertArrayEquals(original.bytes, repository.invoice("invoice").logo)
        inspect { db -> db.rawQuery("SELECT id FROM logo_assets", null).use { it.moveToFirst(); assertEquals(original.id, it.getString(0)); assertFalse(it.moveToNext()) } }
    }
    @Test fun failedLogoRevisionRollsBackAssetSettingsVersionAndReceipt() {
        val before = repository.logo(); val asset = asset(0xff176b55.toInt())
        inspect { it.execSQL("CREATE TRIGGER fail_logo BEFORE INSERT ON entity_revisions WHEN NEW.kind='logo' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        try { apply("failed", asset); fail("Partial logo write succeeded") } catch (_: android.database.SQLException) {}
        assertEquals(before, repository.logo())
        inspect { db -> db.rawQuery("SELECT COUNT(*) FROM logo_assets", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }; db.rawQuery("SELECT id FROM commands WHERE id='failed'", null).use { assertFalse(it.moveToFirst()) } }
    }
}
