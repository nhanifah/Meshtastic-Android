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
package org.meshtastic.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSessionEntity

@Dao
interface RangeTestDao {
    @Insert suspend fun insertSession(session: RangeTestSessionEntity): Long

    @Query("UPDATE range_test_session SET ended_at = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endedAt: Long)

    @Query(
        "UPDATE range_test_session SET target_latitude = :latitude, target_longitude = :longitude " +
            "WHERE id = :sessionId",
    )
    suspend fun updateTargetPosition(sessionId: Long, latitude: Double, longitude: Double)

    @Query("SELECT * FROM range_test_session ORDER BY started_at DESC")
    fun getSessions(): Flow<List<RangeTestSessionEntity>>

    @Query("SELECT * FROM range_test_session WHERE id = :sessionId")
    fun getSessionFlow(sessionId: Long): Flow<RangeTestSessionEntity?>

    /** Sessions left open by a process death; closed on the next recorder start. */
    @Query("UPDATE range_test_session SET ended_at = :endedAt WHERE ended_at IS NULL")
    suspend fun closeOpenSessions(endedAt: Long)

    @Query("DELETE FROM range_test_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Insert suspend fun insertSamples(samples: List<RangeTestSampleEntity>)

    @Query("SELECT * FROM range_test_sample WHERE session_id = :sessionId ORDER BY time_millis ASC")
    fun getSamples(sessionId: Long): Flow<List<RangeTestSampleEntity>>

    @Query("SELECT * FROM range_test_sample WHERE session_id = :sessionId ORDER BY time_millis ASC")
    suspend fun getSamplesSnapshot(sessionId: Long): List<RangeTestSampleEntity>
}
