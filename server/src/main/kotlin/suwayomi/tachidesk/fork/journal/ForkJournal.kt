package suwayomi.tachidesk.fork.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.database.ForkJournalTable
import suwayomi.tachidesk.server.database.dbTransaction

/** Journal states for `plan -> confirm -> execute -> journaled -> undoable` (design-overview.md). */
enum class ForkJournalState {
    PLANNED,
    APPLIED,
    FAILED,
    UNDONE,
}

/** Typed operation names stored in `fork_merge_journal.operation`. */
enum class ForkOperation {
    LIFECYCLE_SOFT_REMOVE,
    LIFECYCLE_REMOVE_MERGED,
    LIFECYCLE_UNMERGE,
    LIFECYCLE_REMOVE_SOURCE,
    LIFECYCLE_RESTORE,
    LIFECYCLE_PURGE,
    MERGE_CREATE,
    MERGE_ADD_SOURCE,
    MERGE_REORDER,
    MERGE_OPTIONS,
    MERGE_RECONCILE,
    CHAPTER_PARTS,
    SERIES_SPLIT,
    PROGRESS_DIAGNOSTIC,
    REINDEX_ADOPT,
    DOCTOR_REPAIR,
}

data class ForkJournalEntry(
    val id: Int,
    val operation: ForkOperation,
    val state: ForkJournalState,
    val groupId: Int?,
    val mangaId: Int?,
    val payload: String?,
    val reversible: Boolean,
    val createdAt: Long,
    val undoneAt: Long?,
    val errorMessage: String?,
)

/**
 * The single writer of `fork_merge_journal`. Every call joins the caller's transaction
 * ([dbTransaction]), so a journal row and the change it describes commit or roll back together.
 */
object ForkJournal {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun record(
        operation: ForkOperation,
        payload: String?,
        groupId: Int? = null,
        mangaId: Int? = null,
        reversible: Boolean = true,
        state: ForkJournalState = ForkJournalState.APPLIED,
    ): Int =
        dbTransaction {
            val now = ForkClock.now()
            ForkJournalTable
                .insertAndGetId {
                    it[ForkJournalTable.operation] = operation.name
                    it[ForkJournalTable.state] = state.name
                    it[ForkJournalTable.groupId] = groupId
                    it[ForkJournalTable.mangaId] = mangaId
                    it[ForkJournalTable.payload] = payload
                    it[ForkJournalTable.reversible] = reversible
                    it[ForkJournalTable.createdAt] = now
                    it[ForkJournalTable.updatedAt] = now
                }.value
        }

    fun markState(
        id: Int,
        state: ForkJournalState,
        errorMessage: String? = null,
    ) {
        dbTransaction {
            val now = ForkClock.now()
            ForkJournalTable.update({ ForkJournalTable.id eq id }) {
                it[ForkJournalTable.state] = state.name
                it[ForkJournalTable.updatedAt] = now
                if (state == ForkJournalState.UNDONE) it[ForkJournalTable.undoneAt] = now
                if (errorMessage != null) it[ForkJournalTable.errorMessage] = errorMessage.take(2048)
            }
        }
    }

    fun get(id: Int): ForkJournalEntry? =
        dbTransaction {
            ForkJournalTable
                .selectAll()
                .where { ForkJournalTable.id eq id }
                .firstOrNull()
                ?.toEntry()
        }

    fun recent(limit: Int = 100): List<ForkJournalEntry> =
        dbTransaction {
            ForkJournalTable
                .selectAll()
                .orderBy(ForkJournalTable.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .map { it.toEntry() }
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toEntry() =
        ForkJournalEntry(
            id = this[ForkJournalTable.id].value,
            operation = ForkOperation.valueOf(this[ForkJournalTable.operation]),
            state = ForkJournalState.valueOf(this[ForkJournalTable.state]),
            groupId = this[ForkJournalTable.groupId],
            mangaId = this[ForkJournalTable.mangaId],
            payload = this[ForkJournalTable.payload],
            reversible = this[ForkJournalTable.reversible],
            createdAt = this[ForkJournalTable.createdAt],
            undoneAt = this[ForkJournalTable.undoneAt],
            errorMessage = this[ForkJournalTable.errorMessage],
        )
}

/** Lifecycle row as captured in a journal payload, so undo restores exactly the previous state. */
@Serializable
data class LifecycleRowSnapshot(
    val mangaId: Int,
    val state: String?,
    val reason: String?,
    val ownerGroupId: Int?,
    val changedAt: Long?,
)
