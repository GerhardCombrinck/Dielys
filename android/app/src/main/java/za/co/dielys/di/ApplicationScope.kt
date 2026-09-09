package za.co.dielys.di

import javax.inject.Qualifier

/**
 * A `CoroutineScope` that lives as long as the process.
 *
 * Work that outlives every screen but is not `WorkManager` work belongs here —
 * which at present is exactly one thing, the socket supervisor. It is not a
 * general-purpose escape hatch from structured concurrency: anything that has to
 * survive the process being killed is `WorkManager`'s job (H3.4), and anything a
 * screen is waiting for belongs to that screen's `ViewModel`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
