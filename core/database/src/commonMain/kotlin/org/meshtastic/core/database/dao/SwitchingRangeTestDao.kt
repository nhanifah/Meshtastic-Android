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

import kotlinx.coroutines.flow.Flow
import org.meshtastic.core.database.DatabaseProvider
import org.meshtastic.core.database.entity.RangeTestSampleEntity
import org.meshtastic.core.database.entity.RangeTestSessionEntity

/**
 * Switch-aware [RangeTestDao]: resolves the active database per call so the long-lived range-test recorder and open
 * screens follow a device/DB switch, the same contract as [SwitchingDiscoveryDao].
 */
class SwitchingRangeTestDao(private val dbManager: DatabaseProvider) : RangeTestDao {
    override suspend fun insertSession(session: RangeTestSessionEntity): Long =
        checkNotNull(dbManager.withDb { it.rangeTestDao().insertSession(session) })

    override suspend fun endSession(sessionId: Long, endedAt: Long) {
        dbManager.withDb { it.rangeTestDao().endSession(sessionId, endedAt) }
    }

    override suspend fun updateTargetPosition(sessionId: Long, latitude: Double, longitude: Double) {
        dbManager.withDb { it.rangeTestDao().updateTargetPosition(sessionId, latitude, longitude) }
    }

    override fun getSessions(): Flow<List<RangeTestSessionEntity>> =
        dbManager.observeCurrentDb("RangeTestDao.getSessions") { it.rangeTestDao().getSessions() }

    override fun getSessionFlow(sessionId: Long): Flow<RangeTestSessionEntity?> =
        dbManager.observeCurrentDb("RangeTestDao.getSessionFlow") { it.rangeTestDao().getSessionFlow(sessionId) }

    override suspend fun closeOpenSessions(endedAt: Long) {
        dbManager.withDb { it.rangeTestDao().closeOpenSessions(endedAt) }
    }

    override suspend fun deleteSession(sessionId: Long) {
        dbManager.withDb { it.rangeTestDao().deleteSession(sessionId) }
    }

    override suspend fun insertSamples(samples: List<RangeTestSampleEntity>) {
        if (samples.isEmpty()) return
        dbManager.withDb { it.rangeTestDao().insertSamples(samples) }
    }

    override fun getSamples(sessionId: Long): Flow<List<RangeTestSampleEntity>> =
        dbManager.observeCurrentDb("RangeTestDao.getSamples") { it.rangeTestDao().getSamples(sessionId) }

    override suspend fun getSamplesSnapshot(sessionId: Long): List<RangeTestSampleEntity> =
        dbManager.withDb { it.rangeTestDao().getSamplesSnapshot(sessionId) }.orEmpty()
}
