package io.github.centerionware.clockrove

import android.content.Context
import android.content.res.Configuration
import io.github.centerionware.clockrove.widget.TimerWidget
import java.util.Locale
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec

internal class LanguageHostApi(context: Context, messenger: BinaryMessenger) {
    private val app = context.applicationContext
    private val preferences = languagePreferences(app)
    private val channel = MethodChannel(
        messenger,
        "clockrove/language/v1",
        StandardMethodCodec.INSTANCE,
        messenger.makeBackgroundTaskQueue(),
    )

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "get" -> result.success(preferences.getString("language", null))
                "set" -> {
                    val tag = call.arguments
                    if (tag != null && (tag !is String || !LANGUAGE_TAG.matches(tag))) {
                        result.error("INVALID_LANGUAGE", null, null)
                    } else if (preferences.edit().putString("language", tag as String?).commit()) {
                        result.success(null)
                        // Widget refresh is best effort after the preference has committed.
                        runCatching { TimerWidget.refreshAll(app) }
                    } else {
                        result.error("LANGUAGE_WRITE_FAILED", null, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    fun detach() = channel.setMethodCallHandler(null)

    private companion object {
        val LANGUAGE_TAG = Regex("[a-z]{2,3}(?:-[A-Z][a-z]{3})?")
    }
}

internal fun languagePreferences(context: Context) =
    context.applicationContext.getSharedPreferences("clockrove-language", Context.MODE_PRIVATE)

internal fun localizedContext(context: Context): Context {
    val tag = languagePreferences(context).getString("language", null) ?: return context
    val locale = Locale.forLanguageTag(tag)
    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
    return context.createConfigurationContext(configuration)
}
