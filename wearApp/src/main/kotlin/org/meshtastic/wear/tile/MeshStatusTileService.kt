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
package org.meshtastic.wear.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import org.koin.core.context.GlobalContext
import org.meshtastic.wear.WearMainActivity
import org.meshtastic.wear.surface.MeshSnapshot
import org.meshtastic.wear.surface.readSnapshot

/** Radio status at a glance: connection, our node, unread messages and how many nodes are online. Tap to open. */
class MeshStatusTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val koin = GlobalContext.get()
            val snapshot = readSnapshot(koin.get(), koin.get(), koin.get())
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(snapshot)))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(s: MeshSnapshot): LayoutElementBuilders.LayoutElement {
        val open =
            ModifiersBuilders.Clickable.Builder()
                .setId(CLICK_OPEN)
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName(WearMainActivity::class.java.name)
                                .build(),
                        )
                        .build(),
                )
                .build()
        val column =
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text("Meshtastic", SMALL_SP, COLOR_ACCENT))
                .addContent(text(s.nodeName ?: s.statusText, LARGE_SP, if (s.connected) COLOR_TEXT else COLOR_DIM))
                .addContent(text(s.statusText, SMALL_SP, if (s.connected) COLOR_ACCENT else COLOR_DIM))
                .addContent(text(s.unreadText, MEDIUM_SP, if (s.unread > 0) COLOR_ACCENT else COLOR_TEXT))
                .addContent(text(s.onlineText, MEDIUM_SP, COLOR_TEXT))
                .apply { s.battery?.let { addContent(text("🔋 $it%", SMALL_SP, COLOR_DIM)) } }
                .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column)
            .build()
    }

    private fun text(value: String, sizeSp: Float, color: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(sizeSp)).setColor(argb(color)).build())
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLICK_OPEN = "open"
        const val FRESHNESS_MS = 5 * 60_000L
        const val SMALL_SP = 12f
        const val MEDIUM_SP = 14f
        const val LARGE_SP = 20f

        // Meshtastic brand green on the tile's black background.
        const val COLOR_ACCENT = 0xFF67EA94.toInt()
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_DIM = 0xFF9E9E9E.toInt()
    }
}
