package com.ultratv.tv.nativeapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider ORDER BY id ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider WHERE active = 1 LIMIT 1")
    suspend fun firstActive(): ProviderEntity?

    @Query("SELECT * FROM provider WHERE kind = :kind AND baseUrl = :baseUrl AND username = :username LIMIT 1")
    suspend fun findByIdentity(kind: String, baseUrl: String, username: String): ProviderEntity?

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun byId(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProviderEntity): Long

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE provider SET active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE provider SET active = 1 WHERE id = :id")
    suspend fun activate(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel WHERE providerId = :pid ORDER BY name COLLATE NOCASE ASC")
    fun observeForProvider(pid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE providerId = :pid AND categoryId = :cat ORDER BY name COLLATE NOCASE ASC")
    fun observeForCategory(pid: Long, cat: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE providerId = :pid AND name LIKE '%' || :q || '%' ORDER BY name LIMIT 50")
    suspend fun search(pid: Long, q: String): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)

    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :pid")
    suspend fun count(pid: Long): Int
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movie WHERE providerId = :pid ORDER BY name COLLATE NOCASE ASC")
    fun observeForProvider(pid: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movie WHERE providerId = :pid AND categoryId = :cat ORDER BY name COLLATE NOCASE ASC")
    fun observeForCategory(pid: Long, cat: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movie WHERE id = :id")
    suspend fun byId(id: Long): MovieEntity?

    @Query("SELECT * FROM movie WHERE providerId = :pid AND name LIKE '%' || :q || '%' ORDER BY name LIMIT 50")
    suspend fun search(pid: Long, q: String): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE providerId = :pid ORDER BY name COLLATE NOCASE ASC")
    fun observeForProvider(pid: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE providerId = :pid AND categoryId = :cat ORDER BY name COLLATE NOCASE ASC")
    fun observeForCategory(pid: Long, cat: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE providerId = :pid AND name LIKE '%' || :q || '%' ORDER BY name LIMIT 50")
    suspend fun search(pid: Long, q: String): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episode WHERE seriesId = :sid ORDER BY season, episode")
    fun observeForSeries(sid: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun byId(id: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE seriesId = :sid")
    suspend fun deleteForSeries(sid: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE providerId = :pid AND kind = :kind ORDER BY name")
    fun observeForProviderKind(pid: Long, kind: String): Flow<List<CategoryEntity>>

    @Query("UPDATE category SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CategoryEntity>)

    @Query("DELETE FROM category WHERE providerId = :pid AND kind = :kind")
    suspend fun deleteForProviderKind(pid: Long, kind: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite WHERE providerId = :pid AND kind = :kind")
    fun observeForKind(pid: Long, kind: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE providerId = :pid AND kind = :kind AND remoteId = :rid)")
    fun observeIsFavorite(pid: Long, kind: String, rid: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE providerId = :pid AND kind = :kind AND remoteId = :rid")
    suspend fun remove(pid: Long, kind: String, rid: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE providerId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(pid: Long, limit: Int = 30): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE providerId = :pid AND kind = :kind ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecentByKind(pid: Long, kind: String, limit: Int = 30): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE providerId = :pid AND positionMs > 0 AND (durationMs = 0 OR positionMs < durationMs - 60000) ORDER BY watchedAt DESC LIMIT :limit")
    fun observeContinueWatching(pid: Long, limit: Int = 20): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE providerId = :pid AND kind = :kind AND remoteId = :rid")
    suspend fun remove(pid: Long, kind: String, rid: String)

    @Query("DELETE FROM watch_history WHERE providerId = :pid")
    suspend fun clearForProvider(pid: Long)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg WHERE channelId = :cid AND endMs >= :nowMs ORDER BY startMs LIMIT 20")
    fun observeUpcoming(cid: Long, nowMs: Long): Flow<List<EpgEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EpgEntity>)

    @Query("DELETE FROM epg WHERE channelId = :cid")
    suspend fun deleteForChannel(cid: Long)
}
