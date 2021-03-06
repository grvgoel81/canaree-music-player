package dev.olog.data.repository

import dev.olog.core.entity.OfflineLyrics
import dev.olog.core.gateway.OfflineLyricsGateway
import dev.olog.data.db.LyricsSyncAdjustmentDao
import dev.olog.data.db.OfflineLyricsDao
import dev.olog.data.model.db.LyricsSyncAdjustmentEntity
import dev.olog.data.model.db.OfflineLyricsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

internal class OfflineLyricsRepository @Inject constructor(
    private val offlineLyricsDao: OfflineLyricsDao,
    private val syncDao: LyricsSyncAdjustmentDao

) : OfflineLyricsGateway {

    override fun observeLyrics(id: Long): Flow<String> {
        return offlineLyricsDao.observeLyrics(id)
            .map { it.getOrNull(0)?.lyrics ?: "" }
    }

    override suspend fun saveLyrics(offlineLyrics: OfflineLyrics) {
        return offlineLyricsDao.saveLyrics(
            OfflineLyricsEntity(
                trackId = offlineLyrics.trackId,
                lyrics = offlineLyrics.lyrics
            )
        )
    }

    override fun getSyncAdjustment(id: Long): Long {
        return syncDao.getSync(id)?.millis ?: 0L
    }

    override fun observeSyncAdjustment(id: Long): Flow<Long> {
        return syncDao.observeSync(id)
            .onStart { syncDao.insertSyncIfEmpty(
                LyricsSyncAdjustmentEntity(
                    id = id,
                    millis = 0
                )
            ) }
            .map { it.millis }
    }

    override suspend fun setSyncAdjustment(id: Long, millis: Long) {
        syncDao.setSync(
            LyricsSyncAdjustmentEntity(
                id = id,
                millis = millis
            )
        )
    }
}