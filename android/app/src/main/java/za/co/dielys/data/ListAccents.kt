package za.co.dielys.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import za.co.dielys.data.local.DielysDatabase
import za.co.dielys.data.local.ListAccentEntity
import za.co.dielys.di.ApplicationScope
import za.co.dielys.domain.ACCENT_COUNT
import za.co.dielys.domain.leastUsedAccent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What colour each list is, on this phone (#57).
 *
 * Its own thing rather than more methods on [DielysRepository] because it
 * shares none of that class's rules: a colour is not a mutation, so there is no
 * outbox row, no idempotency key and nothing to commit in the same transaction
 * as one. It never leaves the phone — the colour is not in `protocol/`, so the
 * other person on a shared list picks their own.
 *
 * [start] keeps every list wearing one. It watches Room rather than hooking the
 * places a list is created: there are three of them — [DielysRepository.createList],
 * membership discovery in `SyncEngine`, and a changelog entry arriving for a
 * list this phone has never seen — and a fourth would be easy to add and easy
 * to forget. A query for "live lists with no colour" cannot be forgotten, and it
 * also sweeps up every list that predates the feature on the first launch after
 * the upgrade.
 */
@Singleton
class ListAccents
    @Inject
    constructor(
        private val db: DielysDatabase,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private var job: Job? = null

        /**
         * Each list's colour, by id. A list missing from the map has none yet;
         * the UI draws the hashed fallback for those rather than waiting.
         */
        fun observeAll(): Flow<Map<String, Int>> =
            db.listAccents().observeAll().map { rows -> rows.associate { it.listId to it.accent } }

        fun observe(listId: String): Flow<Int?> = db.listAccents().observe(listId)

        /** The picker on the list's options menu — the one place a colour that
         *  has already been decided may be overwritten. */
        suspend fun set(
            listId: String,
            accent: Int,
        ) {
            db.listAccents().set(ListAccentEntity(listId, accent))
        }

        /**
         * Whichever colour the fewest live lists are wearing. Read separately
         * from the write so [DielysRepository.createList] can put the result
         * inside its own transaction, and the list is already the right colour
         * the first time it is drawn — this collector would get to it a frame or
         * two later, which is a visible change of colour on the screen you just
         * used.
         */
        suspend fun next(): Int =
            leastUsedAccent(
                usage = db.listAccents().usage().associate { it.accent to it.count },
                paletteSize = ACCENT_COUNT,
            )

        /** Called once, from `Application.onCreate`. */
        fun start() {
            if (job != null) return
            job = scope.launch { colourAsTheyArrive() }
        }

        private suspend fun colourAsTheyArrive() {
            db.listAccents().observeUnassigned().collect { ids ->
                // One at a time, because each one re-reads the tally: five lists
                // arriving together have to spread across the palette rather
                // than all take whatever was least used when the batch started.
                //
                // `assignIfUnset`, so a list coloured by createList a moment ago
                // keeps that colour even if this is acting on a query result
                // from just before that write.
                for (id in ids) db.listAccents().assignIfUnset(ListAccentEntity(id, next()))
            }
        }
    }
