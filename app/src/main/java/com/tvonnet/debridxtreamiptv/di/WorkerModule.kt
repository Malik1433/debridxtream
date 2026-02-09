package com.tvonnet.debridxtreamiptv.di

import com.tvonnet.debridxtreamiptv.worker.SeriesSyncScheduler
import com.tvonnet.debridxtreamiptv.worker.SeriesSyncSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {

    @Binds
    @Singleton
    abstract fun bindSeriesSyncScheduler(
        impl: SeriesSyncSchedulerImpl
    ): SeriesSyncScheduler
}
