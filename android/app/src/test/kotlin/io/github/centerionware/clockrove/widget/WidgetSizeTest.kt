package io.github.centerionware.clockrove.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetSizeTest {
    @Test fun combinedRequiresRoomForBothTimerAndReadableTaskRowAtCurrentTextScale() {
        assertFalse(TimerWidget.hasTaskRoom(132, 56, 1f))
        assertFalse(TimerWidget.hasTaskRoom(132, 300, 1f))
        assertTrue(TimerWidget.hasTaskRoom(240, 200, 1f))
        assertFalse(TimerWidget.hasTaskRoom(240, 200, 1.8f))
        assertTrue(TimerWidget.hasTaskRoom(240, 300, 1.8f))
    }
}
