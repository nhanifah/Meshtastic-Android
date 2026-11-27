/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.settings.tracker

import org.meshtastic.core.repository.TrackerSettings
import org.meshtastic.core.testing.FakeTrackerPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerSettingsViewModelTest {

    @Test
    fun `the tracker is off until switched on`() {
        val prefs = FakeTrackerPrefs()
        val viewModel = TrackerSettingsViewModel(prefs)

        assertFalse(viewModel.settings.value.enabled)
        viewModel.setEnabled(true)
        assertTrue(prefs.settings.value.enabled)
    }

    @Test
    fun `a tail length that is not offered falls back to the default`() {
        val prefs = FakeTrackerPrefs()
        val viewModel = TrackerSettingsViewModel(prefs)

        viewModel.setTailMinutes(180)
        assertEquals(180, prefs.settings.value.tailMinutes)
        viewModel.setTailMinutes(7)
        assertEquals(TrackerSettings.DEFAULT_TAIL_MINUTES, prefs.settings.value.tailMinutes)
    }

    @Test
    fun `each drawing option toggles on its own`() {
        val prefs = FakeTrackerPrefs()
        val viewModel = TrackerSettingsViewModel(prefs)

        viewModel.setShowDots(false)
        viewModel.setShowJumps(false)

        assertEquals(
            TrackerSettings(showDots = false, showDirection = true, showJumps = false),
            prefs.settings.value,
        )
    }

    @Test
    fun `the offered tail lengths run from five minutes to eighteen hours`() {
        assertEquals(5, TrackerSettings.TAIL_MINUTES_OPTIONS.first())
        assertEquals(18 * 60, TrackerSettings.TAIL_MINUTES_OPTIONS.last())
        assertEquals(TrackerSettings.TAIL_MINUTES_OPTIONS.sorted(), TrackerSettings.TAIL_MINUTES_OPTIONS)
    }
}
