package suwayomi.tachidesk.fork.progress

import suwayomi.tachidesk.fork.ForkProgressPolicy
import suwayomi.tachidesk.fork.merge.PartRange
import suwayomi.tachidesk.graphql.types.ProgressBookmarkPolicy
import suwayomi.tachidesk.graphql.types.ProgressConflictPolicy

data class ProgressState(
    val read: Boolean,
    val bookmarked: Boolean,
    val lastPageRead: Int,
    val lastReadAt: Long,
)

enum class ProgressSide {
    VIRTUAL,
    SOURCE,
}

/**
 * Pure progress rules (decision 4, settings 1020-1024):
 * - conflict winner: NEWEST_WINS by `last_read_at` (ties keep the target), VIRTUAL_WINS or SOURCE_WINS;
 * - read is monotonic when enabled: a read chapter is never silently turned unread by propagation;
 * - bookmarks: UNION or FOLLOW_LATEST (the conflict winner's value);
 * - pages are translated part-relative <-> source-absolute by [PartMapping].
 */
object ForkProgressResolver {
    /**
     * New state for [target] after [origin] was written. Returns [target] itself when nothing changes, so
     * callers can skip the write (idempotent; no ping-pong).
     */
    fun merge(
        origin: ProgressState,
        originSide: ProgressSide,
        target: ProgressState,
        policy: ForkProgressPolicy,
    ): ProgressState {
        val originWins =
            when (policy.conflict) {
                ProgressConflictPolicy.NEWEST_WINS -> origin.lastReadAt > target.lastReadAt
                ProgressConflictPolicy.VIRTUAL_WINS -> originSide == ProgressSide.VIRTUAL
                ProgressConflictPolicy.SOURCE_WINS -> originSide == ProgressSide.SOURCE
            }
        val winner = if (originWins) origin else target

        val read =
            if (policy.readIsMonotonic) {
                origin.read || target.read
            } else {
                winner.read
            }
        val bookmarked =
            when (policy.bookmark) {
                ProgressBookmarkPolicy.UNION -> origin.bookmarked || target.bookmarked
                ProgressBookmarkPolicy.FOLLOW_LATEST -> winner.bookmarked
            }
        return ProgressState(
            read = read,
            bookmarked = bookmarked,
            lastPageRead = winner.lastPageRead,
            lastReadAt = maxOf(winner.lastReadAt, if (originWins) origin.lastReadAt else target.lastReadAt),
        )
    }

    /** Initial state of a new virtual chapter folded from all its linked source chapters. */
    fun fold(
        states: List<ProgressState>,
        policy: ForkProgressPolicy,
    ): ProgressState {
        var acc = ProgressState(read = false, bookmarked = false, lastPageRead = 0, lastReadAt = 0)
        states.forEach { acc = merge(it, ProgressSide.SOURCE, acc, policy.copy(conflict = ProgressConflictPolicy.NEWEST_WINS)) }
        return acc
    }
}

/**
 * Page translation for chapter parts (F3, D7, D29). A virtual chapter is the concatenation of [parts];
 * a plain link has a single range covering the whole source chapter.
 */
class PartMapping(
    private val parts: List<PartRange>,
) {
    val virtualPageCount: Int = parts.sumOf { (it.pageEnd - it.pageStart + 1).coerceAtLeast(0) }

    /** Virtual page index -> (source chapter id, source page index), or null when out of range. */
    fun toSource(virtualPage: Int): Pair<Int, Int>? {
        var remaining = virtualPage
        parts.forEach { part ->
            val size = part.pageEnd - part.pageStart + 1
            if (remaining < size) return part.sourceChapterId to (part.pageStart + remaining)
            remaining -= size
        }
        return null
    }

    /**
     * Source page of [sourceChapterId] -> virtual page. Pages before a range map to its start, pages after
     * it to its end (the reader has passed this part); a chapter outside every range returns null.
     */
    fun toVirtual(
        sourceChapterId: Int,
        sourcePage: Int,
    ): Int? {
        var offset = 0
        parts.forEach { part ->
            val size = part.pageEnd - part.pageStart + 1
            if (part.sourceChapterId == sourceChapterId) {
                return offset + (sourcePage - part.pageStart).coerceIn(0, size - 1)
            }
            offset += size
        }
        return null
    }

    /** True when the source page is at or beyond the end of this chapter's last range for that source. */
    fun completesVirtual(
        sourceChapterId: Int,
        sourcePage: Int,
    ): Boolean {
        val last = parts.lastOrNull() ?: return false
        return last.sourceChapterId == sourceChapterId && sourcePage >= last.pageEnd
    }
}
