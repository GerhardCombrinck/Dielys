package za.co.dielys.domain

/**
 * What kind of change one task change was, for deciding whether somebody asked
 * to be notified about it (ADR 0012). [wire] is the protocol's `NotifyEvent`
 * value for the same kind.
 */
enum class TaskActivityKind(
    val wire: String,
) {
    ADDED("added"),
    CHECKED("checked"),
    DELETED("deleted"),
    UPDATED("updated"),
}

/** The facts about a task that decide which kind of change it was. */
data class TaskFacts(
    val title: String,
    val done: Boolean,
    val starred: Boolean,
    val deleted: Boolean,
)

/**
 * Compares a task as this phone last had it ([before], null if it never had
 * it) with the same task as a change leaves it, and names the change.
 *
 * One change can touch several fields; it is named once, by the one that
 * matters most to somebody glancing at a lock screen — gone, then ticked, then
 * anything else. Null means nothing worth a notification: a move (position
 * only), a change to a task already deleted here (tombstones are sticky, F5.3,
 * so there is nothing left to tell), or a task created and deleted before this
 * phone ever saw it.
 */
fun classifyTaskChange(
    before: TaskFacts?,
    after: TaskFacts,
): TaskActivityKind? =
    when {
        before == null -> if (after.deleted) null else TaskActivityKind.ADDED
        before.deleted -> null
        after.deleted -> TaskActivityKind.DELETED
        before.done != after.done -> TaskActivityKind.CHECKED
        before.title != after.title || before.starred != after.starred -> TaskActivityKind.UPDATED
        else -> null
    }
