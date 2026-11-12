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
package org.meshtastic.feature.rangetest

import okio.BufferedSink
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSampleKind
import org.meshtastic.core.database.entity.RangeTestSessionEntity

/** Writes a range-test session as CSV (every sample) or KML (colored beacon placemarks, route and sender). */
object RangeTestExport {
    private const val DECIMETERS = 10.0
    private const val CSV_HEADER =
        "time_millis,kind,seq,latitude,longitude,accuracy_m,location_source,rssi,snr,hops,distance_m\n"

    fun writeCsv(sink: BufferedSink, samples: List<RangeTestSampleEntity>) {
        sink.writeUtf8(CSV_HEADER)
        samples.forEach { s ->
            sink.writeUtf8(
                listOf(
                    s.timeMillis,
                    s.kind,
                    s.seq,
                    s.latitude,
                    s.longitude,
                    s.accuracyM,
                    s.locationSource,
                    s.rssi,
                    s.snr,
                    s.hops,
                    s.distanceM?.let { kotlin.math.round(it * DECIMETERS) / DECIMETERS },
                )
                    .joinToString(",") { it?.toString().orEmpty() } + "\n",
            )
        }
    }

    fun writeKml(
        sink: BufferedSink,
        session: RangeTestSessionEntity,
        samples: List<RangeTestSampleEntity>,
        metric: RangeTestMetric,
    ) {
        sink.writeUtf8("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        sink.writeUtf8("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" + "\n")
        sink.writeUtf8("<name>${escape("Range test – ${session.targetName}")}</name>\n")
        SignalBand.entries.forEach { band ->
            sink.writeUtf8(
                "<Style id=\"${band.name}\"><IconStyle><color>${kmlColor(band.argb)}</color><scale>0.6</scale>" +
                    "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon>" +
                    "</IconStyle></Style>\n",
            )
        }
        val targetLat = session.targetLatitude
        val targetLon = session.targetLongitude
        if (targetLat != null && targetLon != null) {
            sink.writeUtf8(
                "<Placemark><name>${escape(session.targetName)}</name>" +
                    "<Point><coordinates>$targetLon,$targetLat</coordinates></Point></Placemark>\n",
            )
        }
        val track = samples.filter { it.kind == RangeTestSampleKind.TRACK }
        if (track.size >= 2) {
            sink.writeUtf8("<Placemark><name>Route</name><LineString><tessellate>1</tessellate><coordinates>")
            sink.writeUtf8(track.joinToString(" ") { "${it.longitude},${it.latitude}" })
            sink.writeUtf8("</coordinates></LineString></Placemark>\n")
        }
        samples
            .filter { it.kind != RangeTestSampleKind.TRACK }
            .forEach { s ->
                val band = RangeTestGrid.sampleBand(s, metric)
                val label =
                    if (s.kind == RangeTestSampleKind.MISSED) {
                        "missed seq ${s.seq ?: "?"}"
                    } else {
                        "seq ${s.seq ?: "?"} snr ${s.snr ?: "?"} rssi ${s.rssi ?: "?"} hops ${s.hops ?: "?"}"
                    }
                sink.writeUtf8(
                    "<Placemark><name>${escape(label)}</name><styleUrl>#${band.name}</styleUrl>" +
                        "<Point><coordinates>${s.longitude},${s.latitude}</coordinates></Point></Placemark>\n",
                )
            }
        sink.writeUtf8("</Document></kml>\n")
    }

    /** KML colors are aabbggrr. */
    @Suppress("MagicNumber")
    private fun kmlColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return listOf(a, b, g, r).joinToString("") { it.toString(16).padStart(2, '0') }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
