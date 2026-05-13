package com.ultratv.tv.nativeapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,           // "XTREAM" for Phase 1
    val baseUrl: String,        // e.g. http://provider.com:8080
    val username: String,
    val password: String,
    val active: Boolean = true,
)

@Entity(
    tableName = "channel",
    indices = [
        Index("providerId"),
        Index(value = ["providerId", "categoryId"]),
        Index(value = ["providerId", "remoteId"], unique = true),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,       // stream_id from Xtream
    val name: String,
    val logo: String?,
    val categoryId: String?,
    val streamUrl: String,
    /** XMLTV channel id (`tvg-id` for M3U, `epg_channel_id` for Xtream). Used
     *  to match programmes loaded from a full xmltv feed. */
    val epgChannelId: String? = null,
)

@Entity(tableName = "category", indices = [Index(value = ["providerId", "kind", "remoteId"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: String,           // "LIVE", "MOVIE", "SERIES"
    val remoteId: String,
    val name: String,
    val locked: Boolean = false,
)

@Entity(
    tableName = "movie",
    indices = [
        Index("providerId"),
        Index(value = ["providerId", "categoryId"]),
        Index(value = ["providerId", "remoteId"], unique = true),
    ],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val streamUrl: String,
    val container: String?,
    val year: Int?,
    val rating: Double?,
    val plot: String?,
)

@Entity(
    tableName = "series",
    indices = [
        Index("providerId"),
        Index(value = ["providerId", "categoryId"]),
        Index(value = ["providerId", "remoteId"], unique = true),
    ],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val year: Int?,
    val rating: Double?,
    val plot: String?,
)

@Entity(
    tableName = "episode",
    indices = [Index("seriesId"), Index(value = ["seriesId", "remoteId"], unique = true)],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val remoteId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val container: String?,
    val plot: String?,
)

@Entity(
    tableName = "favorite",
    primaryKeys = ["providerId", "kind", "remoteId"],
)
data class FavoriteEntity(
    val providerId: Long,
    val kind: String,           // "LIVE", "MOVIE", "SERIES"
    val remoteId: String,
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["providerId", "kind", "remoteId"],
)
data class WatchHistoryEntity(
    val providerId: Long,
    val kind: String,           // "LIVE", "MOVIE", "EPISODE"
    val remoteId: String,
    val title: String,
    val poster: String?,
    val streamUrl: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watchedAt: Long = System.currentTimeMillis(),
    // For episodes: parent series id so we can group "continue watching this show".
    val parentRemoteId: String? = null,
)

@Entity(tableName = "recording")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: String,           // "MOVIE" | "EPISODE"
    val remoteId: String,
    val title: String,
    val sourceUrl: String,
    val filePath: String,       // absolute path under context.getExternalFilesDir
    val status: String,         // "queued" | "running" | "done" | "failed" | "cancelled"
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "epg",
    indices = [Index("channelId"), Index(value = ["channelId", "startMs"])],
)
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Full-text-search indices. Standalone (no contentEntity) — populated
 * manually from the matching content DAO upsert paths. This keeps the FTS
 * table portable across destructive migrations and avoids the SQLite rowid
 * pitfalls that come with Room's content-entity FTS mode.
 *
 * `rowid` mirrors the source entity's id so we can JOIN back to get the
 * full row. Unicode61 tokenizer normalises diacritics + lowercases for free.
 */
@Entity(tableName = "movie_fts")
@Fts4(tokenizer = "unicode61")
data class MovieFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val name: String,
    val plot: String?,
)

@Entity(tableName = "series_fts")
@Fts4(tokenizer = "unicode61")
data class SeriesFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val name: String,
    val plot: String?,
)

@Entity(tableName = "channel_fts")
@Fts4(tokenizer = "unicode61")
data class ChannelFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val name: String,
)
