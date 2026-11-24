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
package org.meshtastic.feature.rangetest.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.range_test_band_fair
import org.meshtastic.core.resources.range_test_band_good
import org.meshtastic.core.resources.range_test_band_strong
import org.meshtastic.core.resources.range_test_band_very_weak
import org.meshtastic.core.resources.range_test_band_weak
import org.meshtastic.core.resources.range_test_signal_strength
import org.meshtastic.core.resources.range_test_waiting_beacon
import org.meshtastic.feature.rangetest.RangeTestSignal
import org.meshtastic.feature.rangetest.SignalBand

private const val BAR_COUNT = 5
private const val PERCENT = 100
private const val STALE_AFTER_MS = 90_000L
private const val STALE_ALPHA = 0.4f
private val MeterHeight = 112.dp
private val ReadoutWidth = 128.dp

/**
 * Large five-bar meter of the last beacon's link quality, from [RangeTestSignal.combinedStrength]. The bars fill
 * continuously and take their band's color; they fade once no beacon has arrived for [STALE_AFTER_MS], so an old
 * reading is not mistaken for the current signal.
 */
@Composable
internal fun SignalMeter(snr: Float?, rssi: Int?, heardAt: Long?, modifier: Modifier = Modifier) {
    val now by rememberTickingNow()
    val strength = RangeTestSignal.combinedStrength(snr, rssi)
    val band = strength?.let(RangeTestSignal::bandForStrength)
    val isStale = heardAt != null && now - heardAt > STALE_AFTER_MS
    val target = band?.let { Color(it.argb) } ?: MaterialTheme.colorScheme.outline
    val color by animateColorAsState(if (isStale) target.copy(alpha = STALE_ALPHA) else target)
    val level by animateFloatAsState(strength ?: 0f)

    val title = stringResource(Res.string.range_test_signal_strength)
    val percent = strength?.let { "${(it * PERCENT).toInt()}%" } ?: "–"
    val label = band?.let { stringResource(it.labelRes()) } ?: stringResource(Res.string.range_test_waiting_beacon)

    Card(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$title: $percent, $label" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.widthIn(max = ReadoutWidth)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(percent, style = MaterialTheme.typography.displayMedium)
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            SignalBars(level, color, Modifier.weight(1f).height(MeterHeight))
        }
    }
}

@Composable
private fun SignalBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(BAR_COUNT) { index ->
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight((index + 1) / BAR_COUNT.toFloat())
                    .clip(RoundedCornerShape(6.dp))
                    .background(track),
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight((level * BAR_COUNT - index).coerceIn(0f, 1f))
                        .background(color),
                )
            }
        }
    }
}

private fun SignalBand.labelRes(): StringResource = when (this) {
    SignalBand.STRONG -> Res.string.range_test_band_strong

    SignalBand.GOOD -> Res.string.range_test_band_good

    SignalBand.FAIR -> Res.string.range_test_band_fair

    SignalBand.WEAK -> Res.string.range_test_band_weak

    SignalBand.VERY_WEAK,
    SignalBand.MISSED,
    -> Res.string.range_test_band_very_weak
}
