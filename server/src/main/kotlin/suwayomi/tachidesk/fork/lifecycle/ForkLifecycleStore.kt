package suwayomi.tachidesk.fork.lifecycle

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.database.ForkMangaLifecycleTable
import suwayomi.tachidesk.fork.journal.LifecycleRowSnapshot
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * R1: the ONLY writer of `fork_manga_lifecycle`. A missing row means ACTIVE.
 * Callers must already hold the merge gate; this object does not re-check it.
 */
object ForkLifecycleStore {
    fun get(mangaId: Int): LifecycleRowSnapshot =
        dbTransaction {
            ForkMangaLifecycleTable
                .selectAll()
                .where { ForkMangaLifecycleTable.mangaId eq mangaId }
                .firstOrNull()
                ?.let {
                    LifecycleRowSnapshot(
                        mangaId = mangaId,
                        state = it[ForkMangaLifecycleTable.state],
                        reason = it[ForkMangaLifecycleTable.reason],
                        ownerGroupId = it[ForkMangaLifecycleTable.ownerGroupId],
                        changedAt = it[ForkMangaLifecycleTable.changedAt],
                    )
                } ?: LifecycleRowSnapshot(mangaId, null, null, null, null)
        }

    fun stateOf(mangaId: Int): LifecycleState = get(mangaId).state?.let(LifecycleState::valueOf) ?: LifecycleState.ACTIVE

    fun set(
        mangaId: Int,
        state: LifecycleState,
        reason: String?,
        ownerGroupId: Int?,
    ) {
        dbTransaction {
            if (state == LifecycleState.ACTIVE) {
                ForkMangaLifecycleTable.deleteWhere { ForkMangaLifecycleTable.mangaId eq mangaId }
            } else {
                ForkMangaLifecycleTable.upsert {
                    it[ForkMangaLifecycleTable.mangaId] = mangaId
                    it[ForkMangaLifecycleTable.state] = state.name
                    it[ForkMangaLifecycleTable.reason] = reason?.take(2048)
                    it[ForkMangaLifecycleTable.ownerGroupId] = ownerGroupId
                    it[ForkMangaLifecycleTable.changedAt] = ForkClock.now()
                }
            }
        }
    }

    /** Puts a row back exactly as captured in a journal payload (undo). */
    fun restore(snapshot: LifecycleRowSnapshot) {
        dbTransaction {
            if (snapshot.state == null) {
                ForkMangaLifecycleTable.deleteWhere { ForkMangaLifecycleTable.mangaId eq snapshot.mangaId }
            } else {
                ForkMangaLifecycleTable.upsert {
                    it[ForkMangaLifecycleTable.mangaId] = snapshot.mangaId
                    it[ForkMangaLifecycleTable.state] = snapshot.state
                    it[ForkMangaLifecycleTable.reason] = snapshot.reason
                    it[ForkMangaLifecycleTable.ownerGroupId] = snapshot.ownerGroupId
                    it[ForkMangaLifecycleTable.changedAt] = snapshot.changedAt ?: ForkClock.now()
                }
            }
        }
    }

    /** Clears HIDDEN_BY_MERGE rows owned by a group (sources become visible again, D4). */
    fun releaseOwnedBy(
        groupId: Int,
        onlyMangaIds: Collection<Int>? = null,
    ): List<LifecycleRowSnapshot> =
        dbTransaction {
            val condition: Op<Boolean> =
                (ForkMangaLifecycleTable.ownerGroupId eq groupId) and
                    (ForkMangaLifecycleTable.state eq LifecycleState.HIDDEN_BY_MERGE.name)
            val rows =
                ForkMangaLifecycleTable
                    .selectAll()
                    .where { condition }
                    .map {
                        LifecycleRowSnapshot(
                            it[ForkMangaLifecycleTable.mangaId],
                            it[ForkMangaLifecycleTable.state],
                            it[ForkMangaLifecycleTable.reason],
                            it[ForkMangaLifecycleTable.ownerGroupId],
                            it[ForkMangaLifecycleTable.changedAt],
                        )
                    }.filter { onlyMangaIds == null || it.mangaId in onlyMangaIds }
            val ids = rows.map { it.mangaId }
            if (ids.isNotEmpty()) {
                ForkMangaLifecycleTable.deleteWhere { ForkMangaLifecycleTable.mangaId inList ids }
            }
            rows
        }

    fun listByState(state: LifecycleState): List<LifecycleRowSnapshot> =
        dbTransaction {
            ForkMangaLifecycleTable
                .selectAll()
                .where { ForkMangaLifecycleTable.state eq state.name }
                .map {
                    LifecycleRowSnapshot(
                        it[ForkMangaLifecycleTable.mangaId],
                        it[ForkMangaLifecycleTable.state],
                        it[ForkMangaLifecycleTable.reason],
                        it[ForkMangaLifecycleTable.ownerGroupId],
                        it[ForkMangaLifecycleTable.changedAt],
                    )
                }
        }
}

/**
 * R4: the one visibility predicate. Hidden-by-merge sources and soft-removed manga are excluded from
 * library lists, OPDS and sync export. When merge is off (or stock mode is on) every function returns
 * its input unchanged and builds no SQL, so stock queries are byte-for-byte unchanged.
 */
object ForkVisibility {
    @Volatile
    internal var gate: ForkGate = ForkGate.production

    private val hiddenStates = listOf(LifecycleState.HIDDEN_BY_MERGE.name, LifecycleState.SOFT_REMOVED.name)

    fun isActive(): Boolean = gate.isEnabled(ForkFeatureAvailability.Feature.MERGE)

    /** `MangaTable.id NOT IN (hidden ids)`, or null when the predicate does not apply. */
    fun visibleMangaOp(): Op<Boolean>? {
        if (!isActive()) return null
        return MangaTable.id notInSubQuery
            ForkMangaLifecycleTable
                .select(ForkMangaLifecycleTable.mangaId)
                .where { ForkMangaLifecycleTable.state inList hiddenStates }
    }

    /** Adds the predicate to an existing manga query (library, OPDS). */
    fun applyTo(query: Query): Query {
        val op = visibleMangaOp() ?: return query
        return query.andWhere { op }
    }

    /**
     * OPDS: hides trashed manga only. Merged manga have no `source` table row and the OPDS queries inner-join
     * `source`, so hiding merge sources here would remove the series from OPDS entirely. Setting 1053
     * (MERGED_ONLY) therefore cannot be honored yet; see NOTES.md (open gap).
     */
    fun opds(base: Op<Boolean>): Op<Boolean> {
        if (!isActive()) return base
        return base and (
            MangaTable.id notInSubQuery
                ForkMangaLifecycleTable
                    .select(ForkMangaLifecycleTable.mangaId)
                    .where { ForkMangaLifecycleTable.state eq LifecycleState.SOFT_REMOVED.name }
        )
    }

    /** Combines with an existing condition; returns [base] unchanged when inactive. */
    fun and(base: Op<Boolean>): Op<Boolean> {
        val op = visibleMangaOp() ?: return base
        return base and op
    }
}
