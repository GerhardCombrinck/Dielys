package za.co.dielys.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import za.co.dielys.data.local.DielysDatabase

/**
 * A real Room database on the JVM.
 *
 * Room wants a `Context` even for an in-memory database, so these tests run under
 * Robolectric. That is still the JVM and still no emulator, which is the constraint
 * that matters (H1) -- a test that needed a device would mean the code under test
 * had an Android dependency it should not have (E1).
 *
 * No driver is set, so this goes through the same `android.database.sqlite` path the
 * app uses. Robolectric backs that with real SQLite, so byte-for-byte BINARY
 * collation -- which the whole ordering contract rests on -- is the real thing.
 */
fun inMemoryDatabase(): DielysDatabase =
    Room
        .inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DielysDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
