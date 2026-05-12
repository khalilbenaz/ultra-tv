package com.ultratv.tv.nativeapp.di

import android.content.Context
import androidx.room.Room
import com.ultratv.tv.nativeapp.data.db.CategoryDao
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.ProviderDao
import com.ultratv.tv.nativeapp.data.db.UltraDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): UltraDb =
        Room.databaseBuilder(ctx, UltraDb::class.java, "ultra-tv.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideProviderDao(db: UltraDb): ProviderDao = db.providerDao()
    @Provides fun provideChannelDao(db: UltraDb): ChannelDao = db.channelDao()
    @Provides fun provideMovieDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.MovieDao = db.movieDao()
    @Provides fun provideSeriesDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.EpisodeDao = db.episodeDao()
    @Provides fun provideCategoryDao(db: UltraDb): CategoryDao = db.categoryDao()
    @Provides fun provideFavoriteDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.FavoriteDao = db.favoriteDao()
    @Provides fun provideEpgDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.EpgDao = db.epgDao()
    @Provides fun provideWatchHistoryDao(db: UltraDb): com.ultratv.tv.nativeapp.data.db.WatchHistoryDao = db.watchHistoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
