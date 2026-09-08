package za.co.dielys.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import za.co.dielys.data.SessionRepository
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.HttpSyncApi
import za.co.dielys.data.remote.SyncApi
import za.co.dielys.data.sync.SyncScheduler
import za.co.dielys.data.sync.WorkManagerSyncScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /** The sync engine talks to an interface so it can be tested against a fake. */
    @Binds
    @Singleton
    abstract fun syncApi(impl: HttpSyncApi): SyncApi

    @Binds
    @Singleton
    abstract fun accessTokens(impl: SessionRepository): AccessTokens

    @Binds
    @Singleton
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    @Singleton
    abstract fun deviceIdentity(impl: SessionStore): DeviceIdentity
}
