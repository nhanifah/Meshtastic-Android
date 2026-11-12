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
package org.meshtastic.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One locked range-test run: a mobile receiver records the Range Test beacons of a single stationary sender
 * ([targetNodeNum]). The target position is captured when the run starts (and refreshed if the target later reports a
 * new one) so distances stay computable even after the node DB forgets the node.
 */
@Entity(tableName = "range_test_session")
data class RangeTestSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "target_node_num") val targetNodeNum: Int,
    @ColumnInfo(name = "target_name") val targetName: String,
    @ColumnInfo(name = "target_latitude") val targetLatitude: Double? = null,
    @ColumnInfo(name = "target_longitude") val targetLongitude: Double? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
)

/** What a [RangeTestSampleEntity] row records. */
object RangeTestSampleKind {
    /** A beacon from the target was received. */
    const val RECEIVED = "RECEIVED"

    /** A beacon was skipped (gap in the sender's sequence numbers); position is where the receiver was at that time. */
    const val MISSED = "MISSED"

    /** A periodic receiver position fix, used to draw the route and place missed beacons. */
    const val TRACK = "TRACK"
}

/** Where the receiver position of a [RangeTestSampleEntity] came from. */
object RangeTestLocationSource {
    const val RADIO = "RADIO"
    const val PHONE = "PHONE"
}

@Entity(
    tableName = "range_test_sample",
    foreignKeys =
    [
        ForeignKey(
            entity = RangeTestSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id", "time_millis"])],
)
data class RangeTestSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float? = null,
    @ColumnInfo(name = "location_source") val locationSource: String,
    @ColumnInfo(name = "seq") val seq: Int? = null,
    @ColumnInfo(name = "rssi") val rssi: Int? = null,
    @ColumnInfo(name = "snr") val snr: Float? = null,
    /** Hops the beacon travelled; 0 is a direct link, null when the sender's firmware does not report hop_start. */
    @ColumnInfo(name = "hops") val hops: Int? = null,
    @ColumnInfo(name = "distance_m") val distanceM: Double? = null,
)
