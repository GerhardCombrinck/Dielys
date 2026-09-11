package za.co.dielys.data.local

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a localized string resource (#42) for the handful of places
 * outside Compose that build user-facing text — a sign-in error, a list's
 * placeholder title. A `ViewModel` may not hold a `Context` (E1: it would
 * outlive it and leak the Activity — `ArchitectureTest` enforces this), so
 * this is the seam: it holds the `Context` instead, and only ever hands back
 * a `String`.
 */
interface StringProvider {
    fun get(
        @StringRes id: Int,
        vararg args: Any,
    ): String
}

@Singleton
class AndroidStringProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : StringProvider {
        override fun get(
            @StringRes id: Int,
            vararg args: Any,
        ): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
