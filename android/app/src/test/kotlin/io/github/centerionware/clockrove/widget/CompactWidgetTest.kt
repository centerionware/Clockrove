package io.github.centerionware.clockrove.widget

import android.content.res.Configuration
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import io.github.centerionware.clockrove.R
import io.github.centerionware.clockrove.tracking.TimerRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class CompactWidgetTest {
    @Test fun compactRunningAndIdleViewsKeepAnAccessibleActionWithinOneRow() {
        val app = RuntimeEnvironment.getApplication()
        for (running in listOf(false, true)) {
            val state = TimerRepository.Snapshot(version = 7, jobs = emptyList(), running = if (running)
                TimerRepository.Session("synthetic-session", "synthetic-job", System.currentTimeMillis() - 60000,
                    "Synthetic long job name that must be ellipsized") else null)
            val root = TimerWidget.viewsFor(app, state, 100).apply(app, FrameLayout(app))
            val density = app.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(px(132), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px(56), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val action = root.findViewById<ImageButton>(R.id.widget_action)
            assertEquals(app.getString(if (running) R.string.widget_stop else R.string.widget_open), action.contentDescription)
            assertTrue(action.width >= px(48)); assertTrue(action.height >= px(48))
            assertTrue(action.left >= 0); assertTrue(action.right <= root.width)
            assertTrue(action.top >= 0); assertTrue(action.bottom <= root.height)
            assertNotNull(action.drawable)
            assertEquals(if (running) View.VISIBLE else View.GONE, root.findViewById<Chronometer>(R.id.widget_elapsed).visibility)
            assertEquals(if (running) state.running!!.jobName else app.getString(R.string.widget_idle), root.findViewById<TextView>(R.id.widget_job).text)
        }
    }
    @Test fun largeTextPrioritizesJobAndClockOutWithoutClippingAnElapsedSecondLine() {
        val app = RuntimeEnvironment.getApplication()
        val large = app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = 1.8f })
        val state = TimerRepository.Snapshot(version = 7, jobs = emptyList(), running = TimerRepository.Session("s", "j", 1000, "Synthetic work"))
        val root = TimerWidget.viewsFor(large, state, 100).apply(large, FrameLayout(large))
        assertEquals(View.GONE, root.findViewById<Chronometer>(R.id.widget_elapsed).visibility)
        assertEquals(large.getString(R.string.widget_stop), root.findViewById<ImageButton>(R.id.widget_action).contentDescription)
        assertEquals("Synthetic work", root.findViewById<TextView>(R.id.widget_job).text)
    }
}
