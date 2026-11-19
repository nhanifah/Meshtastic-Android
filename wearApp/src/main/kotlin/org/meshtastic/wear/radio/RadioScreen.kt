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
package org.meshtastic.wear.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.koin.compose.viewmodel.koinViewModel

/**
 * The radio's OLED, drawn by the watch: one page per firmware frame, swiped (or turned with the bezel) left and right
 * like the button on the radio steps through frames. Monospace white on black, as on the device.
 */
@Composable
fun RadioScreen(modifier: Modifier = Modifier) {
    val viewModel: RadioScreenViewModel = koinViewModel()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { pages.size }

    HorizontalPagerScaffold(pagerState = pagerState, modifier = modifier) {
        HorizontalPager(state = pagerState) { index ->
            AnimatedPage(pageIndex = index, pagerState = pagerState) {
                ScreenScaffold { pages.getOrNull(index)?.let { OledPage(it) } }
            }
        }
    }
}

@Composable
private fun OledPage(page: RadioPage) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE_INSET, vertical = TOP_INSET),
            verticalArrangement = Arrangement.spacedBy(LINE_GAP),
        ) {
            Text(
                page.title,
                style = TITLE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            // The firmware draws a rule under each frame's header.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White))
            page.lines.forEach { line -> OledLine(line) }
            page.text?.let {
                Text(
                    it,
                    style = BODY,
                    maxLines = TEXT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = if (page.lines.isEmpty()) TextAlign.Center else TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun OledLine(line: RadioLine) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(line.label, style = LABEL, maxLines = 1)
        Spacer(Modifier.width(LABEL_GAP))
        Text(
            line.value,
            style = BODY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private val TITLE =
    TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
private val LABEL = TextStyle(color = Color(0xFFB0B0B0), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
private val BODY = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// Insets keep the rows inside the round display's chord at the top and bottom lines.
private val SIDE_INSET = 30.dp
private val TOP_INSET = 22.dp
private val LINE_GAP = 2.dp
private val LABEL_GAP = 6.dp
private const val TEXT_MAX_LINES = 5
