package io.github.centerionware.clockrove.tracking

/** Resource bounds for imported photos and their private, normalized copies. */
internal object ImageLimits {
    const val SOURCE_BYTES = 50_000_000
    const val SOURCE_DIMENSION = 32_768
    const val SOURCE_PIXELS = 250_000_000L
    const val NORMALIZED_BYTES = 5_000_000
    const val TASK_IMAGES_PER_TASK = 20
    const val TASK_IMAGES_PER_SELECTION = 10
    const val TASK_IMAGE_BATCH_BYTES = 30_000_000L
    const val TASK_IMAGE_BYTES_PER_JOB = 100_000_000L
}
