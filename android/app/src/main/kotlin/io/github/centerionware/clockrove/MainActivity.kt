package io.github.centerionware.clockrove

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.github.centerionware.clockrove.tracking.TimerHostApi

class MainActivity : FlutterActivity() {
    private var timerApi: TimerHostApi? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        timerApi = TimerHostApi(this, flutterEngine.dartExecutor.binaryMessenger)
    }
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        timerApi?.detach()
        timerApi = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
