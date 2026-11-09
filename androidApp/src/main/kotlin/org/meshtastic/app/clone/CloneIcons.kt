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
package org.meshtastic.app.clone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import co.touchlab.kermit.Logger
import org.meshtastic.app.R
import java.io.File
import kotlin.math.min

/** Draws a clone's icon: the user's own picture, or the Meshtastic logo in white on the clone's color. */
object CloneIcons {
    /** Longest side of a stored custom icon. */
    private const val MAX_CUSTOM_PX = 512

    /** An adaptive icon is a 108dp canvas of which only the middle 72dp is guaranteed to show. */
    private const val ADAPTIVE_CANVAS_DP = 108f
    private const val ADAPTIVE_VISIBLE_DP = 72f
    private const val ADAPTIVE_SIZE_PX = 432
    private const val PNG_QUALITY = 100

    /** Brand-friendly colors that keep a white logo readable (every one is at least 4.5:1 against white). */
    val defaultColors: List<Long> =
        listOf(
            0xFF2E7D32,
            0xFF1565C0,
            0xFF6A1B9A,
            0xFFC62828,
            0xFFD84315,
            0xFF00838F,
            0xFFAD1457,
            0xFF4E342E,
            0xFF37474F,
            0xFF283593,
        )

    fun defaultColorFor(slot: Int): Long = defaultColors[Math.floorMod(slot - 1, defaultColors.size)]

    /** A square icon of [sizePx]: the custom picture center-cropped, or the logo on a circle of the clone's color. */
    fun render(context: Context, profile: CloneProfile, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        loadCustom(context, profile, sizePx)?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            return bitmap
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = profile.color.toInt() }
        val half = sizePx / 2f
        canvas.drawCircle(half, half, half, paint)
        drawLogo(context, canvas, sizePx)
        return bitmap
    }

    /** An adaptive-icon bitmap for shortcuts, with the content kept inside the launcher's safe zone. */
    fun adaptiveIcon(context: Context, profile: CloneProfile, sizePx: Int = ADAPTIVE_SIZE_PX): IconCompat {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(profile.color.toInt())
        val custom = loadCustom(context, profile, (sizePx * ADAPTIVE_VISIBLE_DP / ADAPTIVE_CANVAS_DP).toInt())
        if (custom != null) {
            val offset = (sizePx - custom.width) / 2f
            canvas.drawBitmap(custom, offset, offset, null)
        } else {
            drawLogo(context, canvas, sizePx)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /** Builds the home-screen shortcut for [profile]; the intent targets the clone's own activity. */
    fun shortcut(context: Context, profile: CloneProfile): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, CloneLauncher.shortcutId(profile.slot))
            .setShortLabel(profile.name)
            .setLongLabel(profile.name)
            .setIcon(adaptiveIcon(context, profile))
            .setIntent(CloneLauncher.launchIntent(context, profile.slot))
            .build()

    /**
     * Copies the picture at [source] into the clone's custom icon file, center-cropped to a square of at most
     * [MAX_CUSTOM_PX]. Returns false when the picture can't be read.
     */
    fun saveCustom(context: Context, slot: Int, source: Uri): Boolean = runCatching {
        val decoded = decodeSampled(context, source) ?: return@runCatching false
        val out = centerCrop(decoded, min(MAX_CUSTOM_PX, min(decoded.width, decoded.height)))
        val file = CloneRegistry.customIconFile(context, slot)
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        true
    }
        .getOrElse {
            Logger.w(it) { "Could not import a custom clone icon" }
            false
        }

    /** Decodes [source] at a power-of-two reduction that keeps its short side at or above [MAX_CUSTOM_PX]. */
    private fun decodeSampled(context: Context, source: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shortSide = min(bounds.outWidth, bounds.outHeight)
        if (shortSide <= 0) return null
        var sample = 1
        while (shortSide / (sample * 2) >= MAX_CUSTOM_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun loadCustom(context: Context, profile: CloneProfile, sizePx: Int): Bitmap? {
        if (!profile.hasCustomIcon) return null
        val file: File = CloneRegistry.customIconFile(context, profile.slot)
        return file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { centerCrop(it, sizePx) }
    }

    /** Scales [source] to cover a square of [sizePx] and crops the overflow evenly from both sides. */
    private fun centerCrop(source: Bitmap, sizePx: Int): Bitmap {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val srcRect = Rect(left, top, left + side, top + side)
        val dstRect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    /** The launcher logo in white, filling [canvas]; the vector already leaves a safe-zone margin. */
    private fun drawLogo(context: Context, canvas: Canvas, sizePx: Int) {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.mutate() ?: return
        val tinted = DrawableCompat.wrap(drawable)
        DrawableCompat.setTint(tinted, Color.WHITE)
        tinted.setBounds(0, 0, sizePx, sizePx)
        tinted.draw(canvas)
    }
}
