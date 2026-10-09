package com.ultratv.tv.nativeapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultratv.tv.nativeapp.data.security.SecretBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Dao
interface ProviderRawDao {
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

    /** Lecture BRUTE pour la migration de chiffrement (voir ProviderSecretsMigrator). */
    @Query("SELECT id, password AS raw FROM provider")
    suspend fun rawPasswords(): List<RawPassword>

    @Query("UPDATE provider SET password = :raw WHERE id = :id")
    suspend fun setRawPassword(id: Long, raw: String)

    @Query("UPDATE provider SET active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE provider SET active = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Query("UPDATE provider SET lastLiveSyncAt = :t WHERE id = :id")
    suspend fun setLiveSyncAt(id: Long, t: Long)

    /** Horodatage du catalogue films + séries : change à la FIN d'une synchro (pas à chaque ligne insérée). */
    @Query("SELECT lastVodSyncAt + lastSeriesSyncAt FROM provider WHERE id = :id")
    fun observeCatalogStamp(id: Long): kotlinx.coroutines.flow.Flow<Long?>

    @Query("UPDATE provider SET lastVodSyncAt = :t WHERE id = :id")
    suspend fun setVodSyncAt(id: Long, t: Long)

    @Query("UPDATE provider SET lastSeriesSyncAt = :t WHERE id = :id")
    suspend fun setSeriesSyncAt(id: Long, t: Long)

    @Query("UPDATE provider SET categoryFilter = :v WHERE id = :id")
    suspend fun setCategoryFilter(id: Long, v: Int)

    @Query("UPDATE provider SET lastEpgSyncAt = :t WHERE id = :id")
    suspend fun setEpgSyncAt(id: Long, t: Long)

    /** Remet les horodatages à zéro : la prochaine synchro recharge tout (« Actualiser »). */
    @Query("UPDATE provider SET lastLiveSyncAt = 0, lastVodSyncAt = 0, lastSeriesSyncAt = 0, lastEpgSyncAt = 0 WHERE id = :id")
    suspend fun resetSyncAt(id: Long)
}

/**
 * Façade de [ProviderRawDao] qui chiffre le mot de passe à l'écriture et le
 * déchiffre à la lecture (AES-GCM / Keystore, voir [SecretBox]). Même API que
 * l'ancien DAO : les appelants ne voient que des mots de passe en clair en mémoire,
 * jamais sur disque. (Un TypeConverter String→String est refusé par Room.)
 */
@Singleton
class ProviderDao @Inject constructor(private val raw: ProviderRawDao) {
    private fun ProviderEntity.dec() = copy(password = SecretBox.shared.decrypt(password))
    private fun ProviderEntity.enc() = copy(password = SecretBox.shared.encrypt(password))

    fun observeAll(): Flow<List<ProviderEntity>> = raw.observeAll().map { l -> l.map { it.dec() } }
    suspend fun firstActive(): ProviderEntity? = raw.firstActive()?.dec()
    suspend fun findByIdentity(kind: String, baseUrl: String, username: String): ProviderEntity? =
        raw.findByIdentity(kind, baseUrl, username)?.dec()
    suspend fun byId(id: Long): ProviderEntity? = raw.byId(id)?.dec()
    suspend fun upsert(p: ProviderEntity): Long = raw.upsert(p.enc())
    suspend fun delete(id: Long) = raw.delete(id)
    suspend fun deactivateAll() = raw.deactivateAll()
    suspend fun activate(id: Long) = raw.activate(id)
    fun observeCatalogStamp(id: Long): Flow<Long?> = raw.observeCatalogStamp(id)
    suspend fun markSynced(id: Long, part: SyncPart, t: Long) = when (part) {
        SyncPart.LIVE -> raw.setLiveSyncAt(id, t)
        SyncPart.VOD -> raw.setVodSyncAt(id, t)
        SyncPart.SERIES -> raw.setSeriesSyncAt(id, t)
        SyncPart.EPG -> raw.setEpgSyncAt(id, t)
    }
    suspend fun resetSyncAt(id: Long) = raw.resetSyncAt(id)
    suspend fun setCategoryFilter(id: Long, v: Int) = raw.setCategoryFilter(id, v)
    suspend fun rawPasswords(): List<RawPassword> = raw.rawPasswords()
    suspend fun setRawPassword(id: Long, value: String) = raw.setRawPassword(id, value)
}

@Dao
interface ChannelDao {
    // userPosition first (0 = unset, sorted last via CASE), then alpha by name.
    @Query("""
        SELECT * FROM channel WHERE providerId = :pid
        ORDER BY num, id
    """)
    fun observeForProvider(pid: Long): Flow<List<ChannelEntity>>

    /** Les [limit] premières chaînes seulement (rails de l'accueil) : charger les
     *  40 000 lignes pour n'en garder que 30 saturait le CPU à chaque lot inséré. */
    @Query("""
        SELECT * FROM channel WHERE providerId = :pid
        ORDER BY num, id
        LIMIT :limit
    """)
    fun observeTop(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("""
        SELECT * FROM channel WHERE providerId = :pid AND categoryId = :cat
        ORDER BY num, id
    """)
    fun observeForCategory(pid: Long, cat: String): Flow<List<ChannelEntity>>

    /** Seulement (id, epgChannelId) : évite de charger 40 000 entités complètes pour l'EPG. */
    @Query("SELECT id, epgChannelId FROM channel WHERE providerId = :pid AND epgChannelId IS NOT NULL AND epgChannelId != ''")
    suspend fun epgMapping(pid: Long): List<EpgMapping>

    /** (remoteId → id) : la resynchronisation réécrit les chaînes en gardant leur identifiant (programmes, liens). */
    @Query("SELECT id, remoteId FROM channel WHERE providerId = :pid")
    suspend fun idsByRemote(pid: Long): List<ChannelIdRow>

    /** (id, titre, identifiant EPG) de toutes les chaînes : rattache « TF1 +1 » au programme de « TF1 ». */
    @Query("SELECT id, title, epgChannelId, country FROM channel WHERE providerId = :pid AND isSeparator = 0")
    suspend fun epgTitles(pid: Long): List<EpgTitle>

    @Query("SELECT id FROM channel WHERE providerId = :pid AND title = :title AND isSeparator = 0")
    suspend fun idsByTitle(pid: Long, title: String): List<Long>

    /** Atomic position update. Swap two channels by calling twice in a transaction. */
    @Query("UPDATE channel SET userPosition = :pos WHERE id = :id")
    suspend fun setPosition(id: Long, pos: Int)

    @Query("UPDATE channel SET userPosition = 0 WHERE providerId = :pid")
    suspend fun resetPositions(pid: Long)

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    /** Lookup by (providerId, remoteId) — used by PlaybackContext to rehydrate
     *  the channel after a navigation that only carried the persisted identifiers. */
    @Query("SELECT * FROM channel WHERE providerId = :pid AND remoteId = :rid LIMIT 1")
    suspend fun byRemoteId(pid: Long, rid: String): ChannelEntity?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)

    @Query("DELETE FROM channel WHERE providerId = :pid AND categoryId IN (:ids)")
    suspend fun deleteForCategories(pid: Long, ids: List<String>)

    @Query("DELETE FROM channel WHERE providerId = :pid AND categoryId = :cat")
    suspend fun deleteForCategory(pid: Long, cat: String)

    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :pid")
    suspend fun count(pid: Long): Int

    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :pid")
    fun observeCount(pid: Long): Flow<Int>

    /** Rail d'accueil sans favoris : des chaînes avec logo, par ordre alphabétique (hors décorations). */
    @Query("SELECT * FROM channel WHERE providerId = :pid AND junk = 0 AND isSeparator = 0 AND logo IS NOT NULL AND logo != '' AND sortKey >= 'a' ORDER BY num, id LIMIT :limit")
    fun observeTopWithLogo(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("""
        SELECT c.* FROM channel c
        JOIN favorite f ON f.profileId = :profileId AND f.providerId = c.providerId AND f.kind = 'LIVE' AND f.remoteId = c.remoteId
        WHERE c.providerId = :pid AND c.isSeparator = 0 ORDER BY c.num, c.id LIMIT :limit
    """)
    fun observeFavoritesTop(pid: Long, limit: Int, profileId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT lang AS lang, COUNT(*) AS n FROM channel WHERE providerId = :pid AND junk = 0 AND isSeparator = 0 GROUP BY lang")
    fun observeLangCounts(pid: Long): Flow<List<LangCount>>

    /** Pagination Room : seules les lignes visibles (+ marge) sont chargées, l'ordre vient de l'index. */
    // « Tout » : SANS les séparateurs de sections — mélangés à toutes les catégories ils n'ont pas de sens, et sans
    // vrai numéro de playlist ils remontaient tous en tête (38 en-têtes vides avant la première chaîne).
    // Ordre : celui des catégories (Paramètres › Catégories, même tri que la liste de gauche), puis la playlist dans
    // chaque catégorie. Avant : numéro de chaîne seul, sans rapport avec l'ordre choisi.
    @Query("SELECT ch.* FROM channel ch LEFT JOIN category k ON k.providerId = ch.providerId AND k.kind = 'LIVE' AND k.remoteId = ch.categoryId WHERE ch.providerId = :pid AND ch.junk = 0 AND ch.isSeparator = 0 AND (:useLang = 0 OR ch.lang IN (:langs)) ORDER BY CASE WHEN COALESCE(k.position, 0) = 0 THEN 1 ELSE 0 END, k.position, k.id, ch.num, ch.id")
    fun pagedAll(pid: Long, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, ChannelEntity>

    @Query("SELECT * FROM channel WHERE providerId = :pid AND categoryId = :cat AND junk = 0 AND (:useLang = 0 OR lang IN (:langs)) ORDER BY num, id")
    fun pagedForCategory(pid: Long, cat: String, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, ChannelEntity>

    @Query("SELECT ch.* FROM channel ch LEFT JOIN category k ON k.providerId = ch.providerId AND k.kind = 'LIVE' AND k.remoteId = ch.categoryId WHERE ch.providerId = :pid AND ch.junk = 0 AND ch.isSeparator = 0 AND (ch.categoryId IS NULL OR ch.categoryId NOT IN (:hidden)) AND (:useLang = 0 OR ch.lang IN (:langs)) ORDER BY CASE WHEN COALESCE(k.position, 0) = 0 THEN 1 ELSE 0 END, k.position, k.id, ch.num, ch.id")
    fun pagedAllExcluding(pid: Long, hidden: List<String>, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, ChannelEntity>

    /** Chaînes qui ONT un programme dans la fenêtre [from, to] (lignes de la grille du guide). */
    @Query("""
        SELECT * FROM channel c WHERE c.providerId = :pid AND c.junk = 0 AND c.isSeparator = 0
        AND EXISTS (SELECT 1 FROM epg e WHERE e.channelId = c.id AND e.endMs >= :from AND e.startMs <= :to)
        ORDER BY c.num, c.id
    """)
    fun pagedWithEpg(pid: Long, from: Long, to: Long): androidx.paging.PagingSource<Int, ChannelEntity>

    /** Fenêtres pour le zapping du lecteur (haut/bas) : jamais toute la liste en mémoire. */
    // Même ordre que la liste « Tout » (pagedAll) : Haut/Bas suivent exactement ce qui est affiché.
    @Query("SELECT ch.* FROM channel ch LEFT JOIN category k ON k.providerId = ch.providerId AND k.kind = 'LIVE' AND k.remoteId = ch.categoryId WHERE ch.providerId = :pid AND ch.junk = 0 AND ch.isSeparator = 0 ORDER BY CASE WHEN COALESCE(k.position, 0) = 0 THEN 1 ELSE 0 END, k.position, k.id, ch.num, ch.id LIMIT :limit OFFSET :offset")
    suspend fun windowAll(pid: Long, limit: Int, offset: Int): List<ChannelEntity>

    /** Identifiants de « Tout » dans l'ordre affiché : rang d'une chaîne pour centrer la fenêtre de zapping. */
    @Query("SELECT ch.id FROM channel ch LEFT JOIN category k ON k.providerId = ch.providerId AND k.kind = 'LIVE' AND k.remoteId = ch.categoryId WHERE ch.providerId = :pid AND ch.junk = 0 AND ch.isSeparator = 0 ORDER BY CASE WHEN COALESCE(k.position, 0) = 0 THEN 1 ELSE 0 END, k.position, k.id, ch.num, ch.id")
    suspend fun orderedAllIds(pid: Long): List<Long>

    @Query("SELECT * FROM channel WHERE providerId = :pid AND categoryId = :cat AND junk = 0 AND isSeparator = 0 ORDER BY num, id LIMIT :limit OFFSET :offset")
    suspend fun windowCategory(pid: Long, cat: String, limit: Int, offset: Int): List<ChannelEntity>


    @Query("SELECT COUNT(*) FROM channel WHERE providerId = :pid AND categoryId = :cat AND junk = 0 AND isSeparator = 0 AND (num < :num OR (num = :num AND id < :id))")
    suspend fun rankCategory(pid: Long, cat: String, num: Int, id: Long): Int

    @Query("""
        SELECT c.* FROM channel c
        JOIN favorite f ON f.profileId = :profileId AND f.providerId = c.providerId AND f.kind = 'LIVE' AND f.remoteId = c.remoteId
        WHERE c.providerId = :pid AND c.isSeparator = 0 ORDER BY c.num, c.id
    """)
    suspend fun favoritesList(pid: Long, profileId: Long): List<ChannelEntity>

    /** Autres qualités d'une même chaîne (même titre nettoyé et même langue), de la meilleure à la moins bonne. */
    @Query("SELECT * FROM channel WHERE providerId = :pid AND title = :title AND lang = :lang AND junk = 0 AND isSeparator = 0 ORDER BY quality DESC, num LIMIT 12")
    suspend fun variantsOf(pid: Long, title: String, lang: String): List<ChannelEntity>

    /** Chaînes précises d'une source (écran « Chaînes verrouillées » : seulement celles qu'on a verrouillées). */
    @Query("SELECT * FROM channel WHERE providerId = :pid AND remoteId IN (:ids) ORDER BY sortKey")
    suspend fun byRemoteIds(pid: Long, ids: List<String>): List<ChannelEntity>

    @Query("""
        SELECT c.* FROM channel c
        JOIN favorite f ON f.profileId = :profileId AND f.providerId = c.providerId AND f.kind = 'LIVE' AND f.remoteId = c.remoteId
        WHERE c.providerId = :pid AND c.isSeparator = 0 AND (:useLang = 0 OR c.lang IN (:langs)) ORDER BY c.num, c.id
    """)
    fun pagedFavorites(pid: Long, useLang: Int, langs: List<String>, profileId: Long): androidx.paging.PagingSource<Int, ChannelEntity>

    /** Compteurs par catégorie (colonne de gauche du Direct), servis par l'index couvrant. */
    @Query("SELECT categoryId AS categoryId, SUM(CASE WHEN isSeparator = 0 THEN 1 ELSE 0 END) AS n, SUM(isSeparator) AS sections FROM channel WHERE providerId = :pid AND junk = 0 GROUP BY categoryId")
    fun observeCategoryCounts(pid: Long): Flow<List<CategoryCount>>

    @Query("""
        SELECT c.* FROM channel c JOIN channel_fts ON c.id = channel_fts.docid
        WHERE channel_fts MATCH :match AND c.providerId = :pid AND c.junk = 0 AND c.isSeparator = 0 LIMIT :limit
    """)
    suspend fun searchFts(pid: Long, match: String, limit: Int): List<ChannelEntity>
}

@Dao
interface MovieDao {
    /** Favoris : seulement les films demandés (index unique providerId+remoteId), jamais tout le catalogue. */
    @Query("SELECT * FROM movie WHERE providerId = :pid AND remoteId IN (:remoteIds) ORDER BY id")
    fun observeByRemoteIds(pid: Long, remoteIds: List<String>): Flow<List<MovieEntity>>

    @Query("SELECT COUNT(*) FROM movie WHERE providerId = :pid")
    fun observeCount(pid: Long): Flow<Int>

    /** Film « à la une » : le mieux noté qui a une affiche. */
    @Query("SELECT * FROM movie WHERE providerId = :pid AND poster IS NOT NULL AND poster != '' AND rating IS NOT NULL AND rating <= 10 ORDER BY rating DESC LIMIT 1")
    fun observeHero(pid: Long): Flow<MovieEntity?>

    @Query("SELECT * FROM movie WHERE providerId = :pid ORDER BY id LIMIT :limit")
    fun observeTop(pid: Long, limit: Int): Flow<List<MovieEntity>>

    /** Derniers ajoutés : l'identifiant Xtream (stream_id) croît à chaque ajout du fournisseur. */
    @Query("SELECT * FROM movie WHERE providerId = :pid ORDER BY addedKey DESC LIMIT :limit")
    fun observeLatest(pid: Long, limit: Int): Flow<List<MovieEntity>>

    /** Instantané des derniers ajoutés (chaîne « Nouveautés » de Google TV : relue à la FIN d'une synchro, pas observée). */
    @Query("SELECT * FROM movie WHERE providerId = :pid ORDER BY addedKey DESC LIMIT :limit")
    suspend fun latest(pid: Long, limit: Int): List<MovieEntity>

    /** Rangée d'une catégorie (accueil Films) : les plus récents d'abord. */
    @Query("SELECT * FROM movie WHERE providerId = :pid AND categoryId = :cat ORDER BY addedKey DESC LIMIT :limit")
    fun observeRow(pid: Long, cat: String, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movie WHERE providerId = :pid AND categoryId = :cat ORDER BY id")
    fun observeForCategory(pid: Long, cat: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movie WHERE providerId = :pid AND remoteId = :rid LIMIT 1")
    suspend fun byRemoteId(pid: Long, rid: String): MovieEntity?

    @Query("SELECT * FROM movie WHERE id = :id")
    suspend fun byId(id: Long): MovieEntity?

    /** Rapprochement Trakt : colonnes minimales, par tranches (pagination par clé, sans OFFSET). */
    @Query("SELECT id, title, poster, year, rating FROM movie WHERE providerId = :pid AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun liteChunk(pid: Long, afterId: Long, limit: Int): List<CatalogLite>


    @Query("SELECT * FROM movie WHERE providerId = :pid AND (:useLang = 0 OR lang IN (:langs)) ORDER BY id")
    fun pagedAll(pid: Long, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movie WHERE providerId = :pid AND categoryId = :cat AND (:useLang = 0 OR lang IN (:langs)) ORDER BY id")
    fun pagedForCategory(pid: Long, cat: String, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, MovieEntity>

    @Query("UPDATE movie SET plot = :plot, cast = :cast, genre = :genre, duration = :duration, backdrop = :backdrop WHERE id = :id")
    suspend fun updateDetails(id: Long, plot: String?, cast: String?, genre: String?, duration: String?, backdrop: String?)

    @Query("SELECT lang AS lang, COUNT(*) AS n FROM movie WHERE providerId = :pid GROUP BY lang")
    fun observeLangCounts(pid: Long): Flow<List<LangCount>>

    @Query("SELECT categoryId AS categoryId, COUNT(*) AS n, 0 AS sections FROM movie WHERE providerId = :pid GROUP BY categoryId")
    fun observeCategoryCounts(pid: Long): Flow<List<CategoryCount>>

    @Query("""
        SELECT m.* FROM movie m JOIN movie_fts ON m.id = movie_fts.docid
        WHERE movie_fts MATCH :match AND m.providerId = :pid LIMIT :limit
    """)
    suspend fun searchFts(pid: Long, match: String, limit: Int): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)

    @Query("DELETE FROM movie WHERE providerId = :pid AND categoryId IN (:ids)")
    suspend fun deleteForCategories(pid: Long, ids: List<String>)

    @Query("DELETE FROM movie WHERE providerId = :pid AND categoryId = :cat")
    suspend fun deleteForCategory(pid: Long, cat: String)
}

@Dao
interface SeriesDao {
    /** Favoris : seulement les séries demandées (index unique providerId+remoteId), jamais tout le catalogue. */
    @Query("SELECT * FROM series WHERE providerId = :pid AND remoteId IN (:remoteIds) ORDER BY id")
    fun observeByRemoteIds(pid: Long, remoteIds: List<String>): Flow<List<SeriesEntity>>

    /** Complète la fiche série avec ce que renvoie get_series_info (sans écraser par du vide). */
    @Query("UPDATE series SET plot = COALESCE(:plot, plot), genre = COALESCE(:genre, genre), `cast` = COALESCE(:cast, `cast`), backdrop = COALESCE(:backdrop, backdrop), year = COALESCE(:year, year), rating = COALESCE(:rating, rating) WHERE id = :id")
    suspend fun updateInfo(id: Long, plot: String?, genre: String?, cast: String?, backdrop: String?, year: Int?, rating: Double?)

    @Query("SELECT COUNT(*) FROM series WHERE providerId = :pid")
    fun observeCount(pid: Long): Flow<Int>

    /** Série « à la une » : la mieux notée qui a une image paysage (backdrop_path). */
    @Query("SELECT * FROM series WHERE providerId = :pid AND backdrop IS NOT NULL AND rating IS NOT NULL AND rating <= 10 ORDER BY rating DESC LIMIT 1")
    fun observeHero(pid: Long): Flow<SeriesEntity?>

    @Query("SELECT * FROM series WHERE providerId = :pid ORDER BY id LIMIT :limit")
    fun observeTop(pid: Long, limit: Int): Flow<List<SeriesEntity>>

    /** Dernières ajoutées : l'identifiant Xtream (series_id) croît à chaque ajout du fournisseur. */
    @Query("SELECT * FROM series WHERE providerId = :pid ORDER BY addedKey DESC LIMIT :limit")
    fun observeLatest(pid: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE providerId = :pid ORDER BY addedKey DESC LIMIT :limit")
    suspend fun latest(pid: Long, limit: Int): List<SeriesEntity>

    /** Rangée d'une catégorie (accueil Séries) : les plus récentes d'abord. */
    @Query("SELECT * FROM series WHERE providerId = :pid AND categoryId = :cat ORDER BY addedKey DESC LIMIT :limit")
    fun observeRow(pid: Long, cat: String, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE providerId = :pid AND categoryId = :cat ORDER BY id")
    fun observeForCategory(pid: Long, cat: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE providerId = :pid AND remoteId = :rid LIMIT 1")
    suspend fun byRemoteId(pid: Long, rid: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): SeriesEntity?

    /** Rapprochement Trakt : colonnes minimales, par tranches (pagination par clé, sans OFFSET). */
    @Query("SELECT id, title, poster, year, rating FROM series WHERE providerId = :pid AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun liteChunk(pid: Long, afterId: Long, limit: Int): List<CatalogLite>


    @Query("SELECT * FROM series WHERE providerId = :pid AND (:useLang = 0 OR lang IN (:langs)) ORDER BY id")
    fun pagedAll(pid: Long, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series WHERE providerId = :pid AND categoryId = :cat AND (:useLang = 0 OR lang IN (:langs)) ORDER BY id")
    fun pagedForCategory(pid: Long, cat: String, useLang: Int, langs: List<String>): androidx.paging.PagingSource<Int, SeriesEntity>

    @Query("SELECT lang AS lang, COUNT(*) AS n FROM series WHERE providerId = :pid GROUP BY lang")
    fun observeLangCounts(pid: Long): Flow<List<LangCount>>

    @Query("SELECT categoryId AS categoryId, COUNT(*) AS n, 0 AS sections FROM series WHERE providerId = :pid GROUP BY categoryId")
    fun observeCategoryCounts(pid: Long): Flow<List<CategoryCount>>

    @Query("""
        SELECT s.* FROM series s JOIN series_fts ON s.id = series_fts.docid
        WHERE series_fts MATCH :match AND s.providerId = :pid LIMIT :limit
    """)
    suspend fun searchFts(pid: Long, match: String, limit: Int): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE providerId = :pid")
    suspend fun deleteForProvider(pid: Long)

    @Query("DELETE FROM series WHERE providerId = :pid AND categoryId IN (:ids)")
    suspend fun deleteForCategories(pid: Long, ids: List<String>)

    @Query("DELETE FROM series WHERE providerId = :pid AND categoryId = :cat")
    suspend fun deleteForCategory(pid: Long, cat: String)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episode WHERE seriesId = :sid ORDER BY season, episode")
    fun observeForSeries(sid: Long): Flow<List<EpisodeEntity>>

    /** Épisode d'une source par son identifiant distant (liens profonds Watch Next). */
    @Query("SELECT e.* FROM episode e JOIN series s ON s.id = e.seriesId WHERE s.providerId = :pid AND e.remoteId = :rid LIMIT 1")
    suspend fun byRemoteId(pid: Long, rid: String): EpisodeEntity?

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun byId(id: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE seriesId = :sid")
    suspend fun deleteForSeries(sid: Long)

    @Query("SELECT * FROM episode WHERE seriesId = :sid ORDER BY season, episode")
    suspend fun forSeries(sid: Long): List<EpisodeEntity>

    /** Remplacement ATOMIQUE : l'écran ne voit jamais la liste vide entre la suppression et la réinsertion. */
    @androidx.room.Transaction
    suspend fun replaceForSeries(sid: Long, eps: List<EpisodeEntity>) {
        deleteForSeries(sid)
        upsertAll(eps)
    }
}

@Dao
interface CategoryDao {
    /** Catégories désactivées d'une source (« type|identifiant fournisseur ») : réglages partagés entre appareils. */
    @Query("SELECT kind || '|' || remoteId FROM category WHERE providerId = :pid AND enabled = 0")
    fun observeDisabledKeys(pid: Long): Flow<List<String>>

    @Query("SELECT remoteId FROM category WHERE providerId = :pid AND kind = :kind")
    suspend fun remoteIds(pid: Long, kind: String): List<String>

    @Query("SELECT remoteId FROM category WHERE providerId = :pid AND kind = :kind AND enabled = 0")
    suspend fun disabledIds(pid: Long, kind: String): List<String>

    @Query("SELECT * FROM category WHERE providerId = :pid AND kind = :kind ORDER BY CASE WHEN position = 0 THEN 1 ELSE 0 END, position, id")
    fun observeForProviderKind(pid: Long, kind: String): Flow<List<CategoryEntity>>

    @Query("UPDATE category SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("SELECT kind AS kind, enabled AS enabled, COUNT(*) AS n FROM category WHERE providerId = :pid GROUP BY kind, enabled")
    fun observeEnabledCounts(pid: Long): Flow<List<EnabledCount>>

    @Query("SELECT lang AS lang, COUNT(*) AS n FROM category WHERE providerId = :pid GROUP BY lang")
    fun observeLangCounts(pid: Long): Flow<List<LangCount>>

    @Query("SELECT * FROM category WHERE providerId = :pid AND kind = :kind")
    suspend fun forProviderKind(pid: Long, kind: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE providerId = :pid AND kind = :kind ORDER BY CASE WHEN position = 0 THEN 1 ELSE 0 END, position, id")
    fun observeOrdered(pid: Long, kind: String): Flow<List<CategoryEntity>>

    @Query("UPDATE category SET enabled = :enabled WHERE providerId = :pid AND kind = :kind AND remoteId IN (:ids)")
    suspend fun setEnabled(pid: Long, kind: String, ids: List<String>, enabled: Boolean)

    @Query("UPDATE category SET position = :position WHERE providerId = :pid AND kind = :kind AND remoteId = :id")
    suspend fun setPosition(pid: Long, kind: String, id: String, position: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CategoryEntity>)

    @Query("DELETE FROM category WHERE providerId = :pid AND kind = :kind")
    suspend fun deleteForProviderKind(pid: Long, kind: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite WHERE profileId = :profileId AND providerId = :pid AND kind = :kind")
    fun observeForKind(profileId: Long, pid: Long, kind: String): Flow<List<FavoriteEntity>>

    @Query("SELECT COUNT(*) FROM favorite WHERE profileId = :profileId AND providerId = :pid AND kind = :kind")
    fun observeCount(profileId: Long, pid: Long, kind: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE profileId = :profileId AND providerId = :pid AND kind = :kind AND remoteId = :rid)")
    fun observeIsFavorite(profileId: Long, pid: Long, kind: String, rid: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: FavoriteEntity)

    /** Favoris (tous profils) qui dépendent d'une source : sert à confirmer son retrait. */
    @Query("SELECT * FROM favorite WHERE providerId = :pid")
    suspend fun allForProvider(pid: Long): List<FavoriteEntity>

    @Query("SELECT * FROM favorite")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT COUNT(*) FROM favorite WHERE providerId = :pid")
    suspend fun countForProvider(pid: Long): Int

    @Query("DELETE FROM favorite WHERE profileId = :profileId AND providerId = :pid AND kind = :kind AND remoteId = :rid")
    suspend fun remove(profileId: Long, pid: Long, kind: String, rid: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND providerId = :pid ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(profileId: Long, pid: Long, limit: Int = 30): Flow<List<WatchHistoryEntity>>

    @Query("SELECT positionMs FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND kind = :kind AND remoteId = :rid LIMIT 1")
    suspend fun positionFor(profileId: Long, pid: Long, kind: String, rid: String): Long?

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND kind = :kind ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecentByKind(profileId: Long, pid: Long, kind: String, limit: Int = 30): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND positionMs > 0 AND (durationMs = 0 OR positionMs < durationMs - 60000) ORDER BY watchedAt DESC LIMIT :limit")
    fun observeContinueWatching(profileId: Long, pid: Long, limit: Int = 20): Flow<List<WatchHistoryEntity>>

    /** Progression des épisodes d'une série (fiche série : barre de progression, « Reprendre »). */
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND kind = 'EPISODE' AND parentRemoteId = :parent ORDER BY watchedAt DESC")
    fun observeEpisodesOf(profileId: Long, pid: Long, parent: String): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND kind = :kind AND remoteId = :rid")
    suspend fun remove(profileId: Long, pid: Long, kind: String, rid: String)

    /** Purge l'historique de TOUS les profils pour un fournisseur supprimé. */
    @Query("SELECT * FROM watch_history WHERE providerId = :pid AND watchedAt > :since ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun changedSince(pid: Long, since: Long, limit: Int = 400): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND providerId = :pid AND kind = :kind AND remoteId = :rid LIMIT 1")
    suspend fun get(profileId: Long, pid: Long, kind: String, rid: String): WatchHistoryEntity?

    @Query("SELECT MAX(watchedAt) FROM watch_history")
    fun observeLatestAt(): Flow<Long?>

    @Query("DELETE FROM watch_history WHERE providerId = :pid")
    suspend fun clearForProvider(pid: Long)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recording ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun byId(id: Long): RecordingEntity?

    @Query("SELECT COUNT(*) FROM recording WHERE providerId = :pid")
    suspend fun countForProvider(pid: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: RecordingEntity): Long

    @Query("UPDATE recording SET status = :status, downloadedBytes = :down, totalBytes = :total, errorMessage = :err, completedAt = :doneAt WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, down: Long, total: Long, err: String?, doneAt: Long?)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM recording WHERE status = 'scheduled' AND scheduledEndMs > :nowMs ORDER BY scheduledStartMs")
    suspend fun scheduledPending(nowMs: Long): List<RecordingEntity>

    @Query("UPDATE recording SET status = 'failed', errorMessage = 'missed' WHERE status = 'scheduled' AND scheduledEndMs < :nowMs")
    suspend fun expireMissed(nowMs: Long)

    @Query("SELECT COUNT(*) FROM recording WHERE status = 'scheduled' AND providerId = :pid AND remoteId = :rid AND scheduledStartMs = :startMs")
    suspend fun countScheduled(pid: Long, rid: String, startMs: Long): Int

    @Query("SELECT COUNT(*) FROM recording WHERE status = 'running'")
    fun observeRunningCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM recording WHERE status = 'running'")
    suspend fun runningCount(): Int

    @Query("UPDATE recording SET status = :status, errorMessage = :err WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, err: String?)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg WHERE channelId = :cid AND endMs >= :nowMs ORDER BY startMs LIMIT 20")
    fun observeUpcoming(cid: Long, nowMs: Long): Flow<List<EpgEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<EpgEntity>)

    /** Programmes en cours ou à venir dont le titre correspond au motif LIKE (ESCAPE antislash), triés par début : le premier de chaque chaîne est le plus proche. */
    @Query("""
        SELECT c.*, e.title AS pTitle, e.startMs AS pStart, e.endMs AS pEnd
        FROM epg e JOIN channel c ON c.id = e.channelId
        WHERE c.providerId = :pid AND c.junk = 0 AND c.isSeparator = 0
          AND e.endMs > :nowMs AND e.startMs <= :toMs AND e.title LIKE :pattern ESCAPE '\'
        ORDER BY e.startMs LIMIT :limit
    """)
    suspend fun searchPrograms(pid: Long, pattern: String, nowMs: Long, toMs: Long, limit: Int): List<com.ultratv.tv.nativeapp.data.repo.ProgramHitRow>

    /** Chaînes de la source qui ont au moins un programme encore à venir ou en cours. */
    // Index endMs : seuls les programmes en cours / à venir sont parcourus (avant : jointure sur tout le guide).
    @Query("SELECT DISTINCT e.channelId FROM epg e WHERE e.endMs >= :nowMs AND e.channelId IN (SELECT id FROM channel WHERE providerId = :pid)")
    suspend fun channelsWithProgrammes(pid: Long, nowMs: Long): List<Long>

    @Query("DELETE FROM epg WHERE channelId = :cid")
    suspend fun deleteForChannel(cid: Long)

    @Query("DELETE FROM epg WHERE channelId IN (SELECT id FROM channel WHERE providerId = :pid)")
    suspend fun deleteForProvider(pid: Long)

    /**
     * Fin du dernier programme du guide de la source (null si guide vide) : le guide couvre-t-il encore les heures à
     * venir ? Parcourt l'index endMs à rebours et s'arrête à la première ligne de la source (pas toute la table).
     */
    @Query("SELECT e.endMs FROM epg e WHERE e.channelId IN (SELECT id FROM channel WHERE providerId = :pid) ORDER BY e.endMs DESC LIMIT 1")
    suspend fun lastEndForProvider(pid: Long): Long?

    /** Programmes terminés avant [cutoffMs] : le guide ne grossit plus sans fin. */
    @Query("DELETE FROM epg WHERE endMs < :cutoffMs")
    suspend fun deletePast(cutoffMs: Long): Int

    @Query("SELECT * FROM epg WHERE channelId IN (:channelIds) AND endMs >= :nowMs AND startMs <= :windowEndMs ORDER BY startMs")
    suspend fun rangeForChannels(channelIds: List<Long>, nowMs: Long, windowEndMs: Long): List<EpgEntity>

    /** Full programme list for one channel within a time window — used by the
     *  TiviMate-style "tonight's schedule" column on the Live screen. */
    @Query("SELECT * FROM epg WHERE channelId = :cid AND endMs >= :fromMs AND startMs <= :toMs ORDER BY startMs")
    suspend fun forChannelInRange(cid: Long, fromMs: Long, toMs: Long): List<EpgEntity>
}

data class LangCount(val lang: String, val n: Int)

/** Projection légère d'un film / d'une série (rapprochement Trakt). `title` = titre déjà nettoyé. */
data class CatalogLite(val id: Long, val title: String, val poster: String?, val year: Int?, val rating: Double?)

data class EnabledCount(val kind: String, val enabled: Boolean, val n: Int)

data class CategoryCount(val categoryId: String?, val n: Int, val sections: Int = 0)

/** Parties du catalogue synchronisées (et horodatées) indépendamment. */
enum class SyncPart { LIVE, VOD, SERIES, EPG }

data class EpgMapping(val id: Long, val epgChannelId: String)
data class ChannelIdRow(val id: Long, val remoteId: String)
data class EpgTitle(val id: Long, val title: String, val epgChannelId: String?, val country: String? = null)

data class RawPassword(val id: Long, val raw: String)

@Dao
interface VodInfoDao {
    @Query("SELECT * FROM vod_info WHERE providerId = :pid AND remoteId = :rid")
    suspend fun get(pid: Long, rid: String): VodInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: VodInfoEntity)
}
