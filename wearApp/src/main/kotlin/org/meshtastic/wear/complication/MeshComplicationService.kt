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
package org.meshtastic.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.koin.core.context.GlobalContext
import org.meshtastic.core.resources.R
import org.meshtastic.wear.WearMainActivity
import org.meshtastic.wear.surface.MAX_BATTERY
import org.meshtastic.wear.surface.MeshSnapshot
import org.meshtastic.wear.surface.readSnapshot

/**
 * Watch-face complications. Short text: unread messages (or nodes online when there are none). Ranged value: the
 * connected radio's battery.
 */
class MeshComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(
        type,
        MeshSnapshot(
            connected = true,
            statusText = "",
            nodeName = "MESH",
            onlineText = "5",
            unread = 2,
            unreadText = "2",
            messagesLabel = "Mesh",
            battery = PREVIEW_BATTERY,
        ),
    )

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val koin = GlobalContext.getOrNull() ?: return null
        return build(request.complicationType, readSnapshot(koin.get(), koin.get(), koin.get()))
    }

    private fun build(type: ComplicationType, s: MeshSnapshot): ComplicationData? {
        val description = plain(s.unreadText)
        val image =
            MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.meshtastic_ic_notification)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    plain(if (s.unread > 0) s.unread.toString() else s.onlineText.filter { it.isDigit() }),
                    description,
                )
                    .setMonochromaticImage(image)
                    .setTapAction(openApp())
                    .build()

            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = (s.battery ?: 0).toFloat(),
                    min = 0f,
                    max = MAX_BATTERY.toFloat(),
                    contentDescription = plain(s.battery?.let { "$it%" } ?: s.statusText),
                )
                    .setText(plain(s.battery?.let { "$it%" } ?: "—"))
                    .setMonochromaticImage(image)
                    .setTapAction(openApp())
                    .build()

            else -> null
        }
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val PREVIEW_BATTERY = 87
    }
}
