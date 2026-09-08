package za.co.dielys.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import za.co.dielys.BuildConfig
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.remote.baseUrlOf
import za.co.dielys.domain.Clock
import za.co.dielys.domain.SystemClock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The object graph. Nothing here has behaviour — it is wiring, so that every class
 * that does have behaviour can be constructed in a test without it.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): DielysDatabase =
        Room
            .databaseBuilder(context, DielysDatabase::class.java, DielysDatabase.NAME)
            // No fallbackToDestructiveMigration(). It is forbidden in release
            // builds (G2), and it would be worse than useless here: the outbox
            // holds edits the server has never seen, so wiping the database on a
            // schema mismatch loses user data silently.
            .addMigrations(*DielysDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun clock(): Clock = SystemClock

    @Provides
    @Singleton
    fun baseUrl(): HttpUrl = baseUrlOf(BuildConfig.SYNC_BASE_URL)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            // Retries are the outbox's job, with the same idempotency key (F5.2).
            // OkHttp retrying underneath would hide a failure the drain needs to see.
            .retryOnConnectionFailure(false)
            .build()

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    private const val CONNECT_SECONDS = 10L
    private const val READ_SECONDS = 30L
}
