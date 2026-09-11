package za.co.dielys.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import za.co.dielys.data.SessionRepository
import za.co.dielys.data.local.DeviceIdentity
import za.co.dielys.data.local.NewTaskPlacement
import za.co.dielys.data.local.PushTokenStore
import za.co.dielys.data.local.SessionSignal
import za.co.dielys.data.local.SessionStore
import za.co.dielys.data.local.UiPrefs
import za.co.dielys.data.remote.AccessTokens
import za.co.dielys.data.remote.AuthApi
import za.co.dielys.data.remote.HttpAuthApi
import za.co.dielys.data.remote.HttpSyncApi
import za.co.dielys.data.remote.ListSockets
import za.co.dielys.data.remote.OkHttpListSockets
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

    /** Login, registration and refresh, behind the same kind of seam. */
    @Binds
    @Singleton
    abstract fun authApi(impl: HttpAuthApi): AuthApi

    /** The socket half of the same seam, for the same reason (H1). */
    @Binds
    @Singleton
    abstract fun listSockets(impl: OkHttpListSockets): ListSockets

    @Binds
    @Singleton
    abstract fun accessTokens(impl: SessionRepository): AccessTokens

    @Binds
    @Singleton
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    @Singleton
    abstract fun deviceIdentity(impl: SessionStore): DeviceIdentity

    /**
     * Same store, second face. The sync engine and the push handler need the two
     * token fields and nothing else about a session (M2).
     */
    @Binds
    @Singleton
    abstract fun pushTokenStore(impl: SessionStore): PushTokenStore

    /**
     * Third face, watched rather than read. Deliberately not bound to
     * [SessionRepository]: the socket supervisor needs both this and the access
     * token, and taking both from the repository would close a dependency cycle
     * through [HttpSyncApi].
     */
    @Binds
    @Singleton
    abstract fun sessionSignal(impl: SessionStore): SessionSignal

    /** Tested on the JVM, where there is no `Context` to back [UiPrefs] with. */
    @Binds
    @Singleton
    abstract fun newTaskPlacement(impl: UiPrefs): NewTaskPlacement
}
