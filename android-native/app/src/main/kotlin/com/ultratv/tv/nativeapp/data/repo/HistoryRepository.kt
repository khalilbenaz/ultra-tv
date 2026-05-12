package com.ultratv.tv.nativeapp.data.repo

import com.ultratv.tv.nativeapp.data.db.WatchHistoryDao
import com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: WatchHistoryDao,
) {
    fun recent(pid: Long, limit: Int = 30): Flow<List<WatchHistoryEntity>> =
        dao.observeRecent(pid, limit)

    fun recentByKind(pid: Long, kind: String, limit: Int = 30): Flow<List<WatchHistoryEntity>> =
        dao.observeRecentByKind(pid, kind, limit)

    fun continueWatching(pid: Long, limit: Int = 20): Flow<List<WatchHistoryEntity>> =
        dao.observeContinueWatching(pid, limit)

    suspend fun record(
        providerId: Long,
        kind: String,
        remoteId: String,
        title: String,
        poster: String?,
        streamUrl: String,
        positionMs: Long,
        durationMs: Long,
        parentRemoteId: String? = null,
    ) {
        dao.upsert(
            WatchHistoryEntity(
                providerId = providerId,
                kind = kind,
                remoteId = remoteId,
                title = title,
                poster = poster,
                streamUrl = streamUrl,
                positionMs = positionMs,
                durationMs = durationMs,
                watchedAt = System.currentTimeMillis(),
                parentRemoteId = parentRemoteId,
            ),
        )
    }

    suspend fun remove(providerId: Long, kind: String, remoteId: String) =
        dao.remove(providerId, kind, remoteId)
}
