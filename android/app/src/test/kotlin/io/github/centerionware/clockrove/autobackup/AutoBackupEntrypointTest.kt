package io.github.centerionware.clockrove.autobackup

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupEntrypointTest {
    @Test
    fun usesAssetBundlePathAndWorkerLibrary() {
        val entrypoint = AutoBackupEntrypoint.create("/data/app/flutter_assets")

        assertEquals("/data/app/flutter_assets", entrypoint.pathToBundle)
        assertEquals(
            "package:clockrove/auto_backup_worker.dart",
            entrypoint.dartEntrypointLibrary,
        )
        assertEquals("autoBackupMain", entrypoint.dartEntrypointFunctionName)
    }
}
