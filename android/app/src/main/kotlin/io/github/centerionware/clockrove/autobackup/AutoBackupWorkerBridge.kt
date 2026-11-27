package io.github.centerionware.clockrove.autobackup

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class AutoBackupWorkerBridge {
    data class Outcome(val ok: Boolean, val retry: Boolean)

    private val result = CompletableFuture<Outcome>()

    fun complete(ok: Boolean, retry: Boolean) {
        result.complete(Outcome(ok, retry))
    }

    fun await(timeoutMillis: Long): Outcome? =
        try {
            result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
}
