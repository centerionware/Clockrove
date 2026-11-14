package io.github.centerionware.clockrove.maps

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/** The Android Keystore is the only persistence boundary for map credentials. */
internal class MapProviderSettingsHostApi(
    context: android.content.Context,
    messenger: BinaryMessenger,
) {
    private val store = MapProviderSettingsStore(context)
    private val channel = MethodChannel(
        messenger,
        "clockrove/map-provider/v1",
        io.flutter.plugin.common.StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )

    init {
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "read" -> result.success(store.read().toPublicMap())
                    "tileCredential" -> result.success(store.read().tileCredentialMap())
                    "save" -> {
                        val args = call.arguments as? Map<*, *>
                            ?: throw IllegalArgumentException("INVALID_MAP_SETTINGS")
                        result.success(store.save(args).toPublicMap())
                    }
                    "reset" -> result.success(store.reset().toPublicMap())
                    else -> result.notImplemented()
                }
            } catch (invalid: IllegalArgumentException) {
                result.error("INVALID_MAP_SETTINGS", "Review the map provider settings.", null)
            } catch (_: Exception) {
                result.error(
                    "MAP_SETTINGS_UNAVAILABLE",
                    "Map settings could not be read or saved. Reset them or try again.",
                    null,
                )
            }
        }
    }

    fun detach() = channel.setMethodCallHandler(null)
}
