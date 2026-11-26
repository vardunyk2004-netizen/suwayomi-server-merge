package suwayomi.tachidesk.fork.lifecycle

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkLifecyclePolicy
import suwayomi.tachidesk.fork.ForkMergePolicy
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.database.ForkMergeGroupTable
import suwayomi.tachidesk.fork.database.ForkMergeSourceTable
import suwayomi.tachidesk.fork.database.ForkSplitSourceTable
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.journal.ForkJournalState
import suwayomi.tachidesk.fork.journal.ForkOperation
import suwayomi.tachidesk.fork.journal.LifecycleRowSnapshot
import suwayomi.tachidesk.fork.merge.ForkMergeRepository
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.fork.merge.MergeOptions
import suwayomi.tachidesk.fork.progress.ForkProgressService
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** GraphQL-facing action input (R11: nullable options, conservative server defaults). */
data class LifecycleActionRequest(
    val action: LifecycleAction,
    val mangaId: Int,
    val sourceMangaId: Int? = null,
    val reason: String? = null,
    /** Null -> setting 1032 (default false). */
    val restoreSources: Boolean? = null,
    /** Only materialized copies owned by the merged manga; never source files (D16). Default false. */
    val deleteMaterializedDownloads: Boolean? = null,
)

data class LifecyclePreview(
    val plan: LifecyclePlan,
    val affectedVirtualChapters: Int,
    val sourceImpact: String,
)

data class LifecycleOutcome(
    val journalId: Int,
    val plan: LifecyclePlan,
    val progressWrittenBack: Int,
)

data class TrashItem(
    val mangaId: Int,
    val title: String,
    val reason: String?,
    val removedAt: Long,
    val ownerGroupId: Int?,
    val isMerged: Boolean,
    /** 0 when retention is "keep forever". */
    val purgeAfter: Long,
)

data class PurgePreview(
    val nonce: String,
    val mangaIds: List<Int>,
    val mergedMangaRows: Int,
    val virtualChapterRows: Int,
    val libraryRemovals: Int,
    val sourceRowsDeleted: Int,
    val sourceFilesDeleted: Int,
    val expiresAt: Long,
)

@Serializable
data class SourceSnapshot(
    val sourceMangaId: Int,
    val rank: Int,
    val offset: Float,
    val enabled: Boolean,
)

@Serializable
data class GroupSnapshot(
    val groupId: Int,
    val mangaId: Int,
    val mode: String,
    val options: MergeOptions,
    val coverSourceMangaId: Int?,
    val sources: List<SourceSnapshot>,
)

@Serializable
data class LifecyclePayload(
    val action: String,
    val mangaId: Int,
    val before: List<LifecycleRowSnapshot>,
    val after: List<LifecycleRowSnapshot> = emptyList(),
    val group: GroupSnapshot? = null,
    val removedSource: SourceSnapshot? = null,
    val virtualChapterCount: Int = 0,
)

/**
 * F5 executor: applies a [ForkLifecyclePlanner] plan in ONE transaction with a journal row, and undoes it
 * from the saved before-state. Purge is the only terminal action (nonce + typed PURGE). Source manga rows,
 * source chapters and source files are never deleted or changed in library state (D3-D5, D16).
 */
class ForkLifecycleExecutor(
    private val gate: ForkGate = ForkGate.production,
    private val policy: () -> ForkLifecyclePolicy = ForkLifecyclePolicy::fromConfig,
    private val mergePolicy: () -> ForkMergePolicy = ForkMergePolicy::fromConfig,
    private val merge: ForkMergeService = ForkMergeService.default,
    private val progress: ForkProgressService = ForkProgressService.default,
) {
    private data class PendingPurge(
        val mangaIds: List<Int>,
        val fingerprint: String,
        val expiresAt: Long,
    )

    private val pendingPurges = ConcurrentHashMap<String, PendingPurge>()

    fun preview(request: LifecycleActionRequest): LifecyclePreview {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val (plan, virtualCount) = planFor(request)
            LifecyclePreview(plan, virtualCount, sourceImpact = "none")
        }
    }

    fun apply(request: LifecycleActionRequest): LifecycleOutcome {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val (plan, virtualCount) = planFor(request)
            val group = ForkMergeRepository.groupByMergedManga(request.mangaId)
            val reason = request.reason?.take(200)
            val before = mutableListOf(ForkLifecycleStore.get(request.mangaId))
            var progressWritten = 0
            var groupSnapshot: GroupSnapshot? = null
            var removedSource: SourceSnapshot? = null
            val operation: ForkOperation

            when (request.action) {
                LifecycleAction.SOFT_REMOVE, LifecycleAction.REMOVE_MERGED -> {
                    operation =
                        if (request.action == LifecycleAction.SOFT_REMOVE) {
                            ForkOperation.LIFECYCLE_SOFT_REMOVE
                        } else {
                            ForkOperation.LIFECYCLE_REMOVE_MERGED
                        }
                    forkRequire(policy().trashEnabled, "TRASH_DISABLED") { "Trash is disabled (setting 1030)" }
                    if (plan.sourceMangaIdsToRestore.isNotEmpty() && group != null) {
                        before += plan.sourceMangaIdsToRestore.map { ForkLifecycleStore.get(it) }
                        ForkLifecycleStore.releaseOwnedBy(group.id, plan.sourceMangaIdsToRestore)
                    }
                    ForkLifecycleStore.set(
                        request.mangaId,
                        LifecycleState.SOFT_REMOVED,
                        reason ?: request.action.name.lowercase(),
                        group?.id,
                    )
                    // Materialized copies are not implemented (decision 8 = later): nothing to delete.
                }

                LifecycleAction.UNMERGE -> {
                    operation = ForkOperation.LIFECYCLE_UNMERGE
                    val g = group ?: throw ForkRequestException("NOT_MERGED", "Manga ${request.mangaId} is not a merged manga")
                    groupSnapshot = snapshot(g.id)
                    progressWritten = progress.writeBackGroup(g.id, onlySourceMangaId = null)
                    before += ForkLifecycleStore.releaseOwnedBy(g.id)
                    ChapterTable.deleteWhere { ChapterTable.manga eq request.mangaId } // links/parts/fallbacks cascade
                    ForkMergeGroupTable.deleteWhere { ForkMergeGroupTable.id eq g.id } // sources cascade
                    ForkLifecycleStore.set(request.mangaId, LifecycleState.SOFT_REMOVED, reason ?: "unmerged", null)
                }

                LifecycleAction.REMOVE_SOURCE_FROM_MERGE -> {
                    operation = ForkOperation.LIFECYCLE_REMOVE_SOURCE
                    val g = group ?: throw ForkRequestException("NOT_MERGED", "Manga ${request.mangaId} is not a merged manga")
                    val sourceId = plan.sourceMangaIdsToRestore.single()
                    val sources = ForkMergeRepository.sources(g)
                    forkRequire(sources.size - 1 >= mergePolicy().minimumSources.coerceAtLeast(2), "MIN_SOURCES") {
                        "A merge must keep at least ${mergePolicy().minimumSources} sources"
                    }
                    removedSource =
                        sources
                            .first { it.sourceMangaId == sourceId }
                            .let { SourceSnapshot(it.sourceMangaId, it.rank, it.offset, it.enabled) }
                    progressWritten = progress.writeBackGroup(g.id, onlySourceMangaId = sourceId)
                    before += ForkLifecycleStore.get(sourceId)
                    ForkLifecycleStore.releaseOwnedBy(g.id, listOf(sourceId))
                    ForkMergeSourceTable.deleteWhere {
                        (ForkMergeSourceTable.groupId eq g.id) and (ForkMergeSourceTable.sourceMangaId eq sourceId)
                    }
                    merge.reconcile(g.id)
                }
            }

            val payload =
                LifecyclePayload(
                    action = request.action.name,
                    mangaId = request.mangaId,
                    before = before.distinctBy { it.mangaId },
                    after = (listOf(request.mangaId) + before.map { it.mangaId }).distinct().map { ForkLifecycleStore.get(it) },
                    group = groupSnapshot,
                    removedSource = removedSource,
                    virtualChapterCount = virtualCount,
                )
            val journalId =
                ForkJournal.record(
                    operation,
                    ForkJournal.json.encodeToString(LifecyclePayload.serializer(), payload),
                    groupId = if (request.action == LifecycleAction.UNMERGE) null else group?.id,
                    mangaId = request.mangaId,
                )
            LifecycleOutcome(journalId, plan, progressWritten)
        }
    }

    fun restoreFromTrash(mangaId: Int): Int {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val before = ForkLifecycleStore.get(mangaId)
            forkRequire(before.state == LifecycleState.SOFT_REMOVED.name, "NOT_IN_TRASH") { "Manga $mangaId is not in the trash" }
            ForkLifecycleStore.set(mangaId, LifecycleState.ACTIVE, null, null)
            ForkJournal.record(
                ForkOperation.LIFECYCLE_RESTORE,
                ForkJournal.json.encodeToString(
                    LifecyclePayload.serializer(),
                    LifecyclePayload(LifecycleAction.SOFT_REMOVE.name, mangaId, listOf(before), listOf(ForkLifecycleStore.get(mangaId))),
                ),
                mangaId = mangaId,
            )
        }
    }

    /** Undo from the journal's before-state; refused when the current state is not the recorded after-state. */
    fun undo(journalId: Int) {
        gate.require(Feature.MERGE)
        dbTransaction {
            val entry = ForkJournal.get(journalId)
            forkRequire(entry != null && entry.state == ForkJournalState.APPLIED && entry.reversible, "NOT_UNDOABLE") {
                "Journal entry $journalId cannot be undone"
            }
            val payload = ForkJournal.json.decodeFromString(LifecyclePayload.serializer(), entry!!.payload!!)
            val current = payload.after.map { ForkLifecycleStore.get(it.mangaId) }
            forkRequire(current.map { it.state to it.ownerGroupId } == payload.after.map { it.state to it.ownerGroupId }, "STALE") {
                "The library changed since this operation; undo refused"
            }
            when (entry.operation) {
                ForkOperation.LIFECYCLE_SOFT_REMOVE, ForkOperation.LIFECYCLE_REMOVE_MERGED, ForkOperation.LIFECYCLE_RESTORE -> {
                    payload.before.forEach { ForkLifecycleStore.restore(it) }
                }

                ForkOperation.LIFECYCLE_UNMERGE -> {
                    val group = payload.group ?: throw ForkRequestException("NOT_UNDOABLE", "Missing group snapshot")
                    val newGroupId = recreateGroup(group)
                    payload.before.forEach { row ->
                        val owner = if (row.ownerGroupId == group.groupId) newGroupId else row.ownerGroupId
                        ForkLifecycleStore.restore(row.copy(ownerGroupId = owner))
                    }
                    merge.reconcile(newGroupId)
                }

                ForkOperation.LIFECYCLE_REMOVE_SOURCE -> {
                    val group =
                        ForkMergeRepository.groupByMergedManga(payload.mangaId)
                            ?: throw ForkRequestException("STALE", "The merge no longer exists")
                    val source = payload.removedSource ?: throw ForkRequestException("NOT_UNDOABLE", "Missing source snapshot")
                    forkRequire(ForkMergeRepository.groupIdOfSource(source.sourceMangaId) == null, "STALE") {
                        "The source is now in another merge"
                    }
                    ForkMergeSourceTable.insert {
                        it[ForkMergeSourceTable.groupId] = group.id
                        it[ForkMergeSourceTable.sourceMangaId] = source.sourceMangaId
                        it[ForkMergeSourceTable.rank] = source.rank
                        it[ForkMergeSourceTable.chapterOffset] = source.offset
                        it[ForkMergeSourceTable.enabled] = source.enabled
                    }
                    payload.before.filter { it.mangaId == source.sourceMangaId }.forEach {
                        ForkLifecycleStore.restore(it.copy(ownerGroupId = group.id))
                    }
                    merge.reconcile(group.id)
                }

                else -> {
                    forkRequire(false, "NOT_UNDOABLE") { "Operation ${entry.operation} is not a lifecycle operation" }
                }
            }
            ForkJournal.markState(journalId, ForkJournalState.UNDONE)
        }
    }

    fun trash(): List<TrashItem> {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val rows = ForkLifecycleStore.listByState(LifecycleState.SOFT_REMOVED)
            val mangas =
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList rows.map { it.mangaId } }
                    .associateBy { it[MangaTable.id].value }
            val retention = policy().retentionDays
            rows
                    .mapNotNull { row ->
                        val manga = mangas[row.mangaId] ?: return@mapNotNull null
                        val removedAt = row.changedAt ?: 0
                        TrashItem(
                            mangaId = row.mangaId,
                            title = manga[MangaTable.title],
                            reason = row.reason,
                            removedAt = removedAt,
                            ownerGroupId = row.ownerGroupId,
                            isMerged = manga[MangaTable.sourceReference] == ForkMergedSource.ID,
                            purgeAfter = if (retention == 0) 0 else removedAt + retention * 86_400L,
                        )
                    }.sortedByDescending { it.removedAt }
        }
    }

    /** Step 1 of purge: exact counts and a short-lived nonce. Nothing is changed. */
    fun previewPurge(mangaIds: List<Int>): PurgePreview {
        gate.require(Feature.MERGE)
        return dbTransaction {
            forkRequire(mangaIds.isNotEmpty(), "EMPTY") { "Choose items to purge" }
            mangaIds.forEach { id ->
                forkRequire(ForkLifecycleStore.stateOf(id) == LifecycleState.SOFT_REMOVED, "NOT_IN_TRASH") {
                    "Manga $id is not in the trash"
                }
            }
            val counts = purgeCounts(mangaIds)
            val nonce = UUID.randomUUID().toString()
            val expiresAt = ForkClock.now() + 600
            pendingPurges[nonce] = PendingPurge(mangaIds.sorted(), counts.toString(), expiresAt)
            PurgePreview(
                nonce = nonce,
                mangaIds = mangaIds.sorted(),
                mergedMangaRows = counts.merged,
                virtualChapterRows = counts.virtualChapters,
                libraryRemovals = counts.libraryRemovals,
                sourceRowsDeleted = 0,
                sourceFilesDeleted = 0,
                expiresAt = expiresAt,
            )
        }
    }

    /**
     * Step 2: requires the nonce and the literal text PURGE (setting 1033). Merged rows are deleted with their
     * virtual chapters; a normal manga is only removed from the library (stock semantics). Never touches
     * source rows or files. Not reversible.
     */
    fun confirmPurge(
        nonce: String,
        typedConfirmation: String?,
    ): Int {
        gate.require(Feature.MERGE)
        val pending = pendingPurges.remove(nonce) ?: throw ForkRequestException("NONCE_INVALID", "Preview again before purging")
        forkRequire(pending.expiresAt >= ForkClock.now(), "NONCE_EXPIRED") { "The purge preview expired; preview again" }
        if (policy().confirmPurgeText) {
            forkRequire(typedConfirmation == "PURGE", "CONFIRMATION_REQUIRED") { "Type PURGE to permanently delete the listed items" }
        }
        return dbTransaction {
            forkRequire(purgeCounts(pending.mangaIds).toString() == pending.fingerprint, "STALE") {
                "The trash changed since the preview; preview again"
            }
            purgeInTransaction(pending.mangaIds, reason = "user")
        }
    }

    /** Scheduled retention purge (setting 1031); 0 days keeps forever. Journaled, never deletes files. */
    fun purgeExpired(): Int {
        if (!gate.isEnabled(Feature.MERGE)) return 0
        val days = policy().retentionDays
        if (days == 0) return 0
        val cutoff = ForkClock.now() - days * 86_400L
        return dbTransaction {
            val expired = ForkLifecycleStore.listByState(LifecycleState.SOFT_REMOVED).filter { (it.changedAt ?: Long.MAX_VALUE) < cutoff }
            if (expired.isEmpty()) 0 else purgeInTransaction(expired.map { it.mangaId }, reason = "retention")
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun planFor(request: LifecycleActionRequest): Pair<LifecyclePlan, Int> {
        val group = ForkMergeRepository.groupByMergedManga(request.mangaId)
        val sources = group?.let { ForkMergeRepository.sources(it).map { s -> s.sourceMangaId } }.orEmpty()
        val virtualIds = if (group != null) ForkMergeRepository.virtualChapterIds(request.mangaId) else emptyList()
        if (request.action == LifecycleAction.UNMERGE || request.action == LifecycleAction.REMOVE_SOURCE_FROM_MERGE) {
            forkRequire(group != null, "NOT_MERGED") { "Manga ${request.mangaId} is not a merged manga" }
            forkRequire(group!!.mode != MergeMode.SPLIT || request.action == LifecycleAction.UNMERGE, "MODE_INVALID") {
                "A split view has one source; unmerge it instead"
            }
        }
        val eligible =
            sources.filter {
                ForkLifecycleStore.get(it).let { row ->
                    row.state == LifecycleState.HIDDEN_BY_MERGE.name && row.ownerGroupId == group?.id
                }
            }
        val plan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = request.action,
                    mergedMangaId = request.mangaId,
                    sourceMangaIds = sources,
                    sourceMangaIdToRemove = request.sourceMangaId,
                    sourceMangaIdsEligibleForRestore = eligible,
                    virtualChapterIds = virtualIds,
                    restoreSources = request.restoreSources ?: policy().restoreSourcesOnRemoveMerged,
                    deleteMaterializedDownloads = request.deleteMaterializedDownloads ?: false,
                ),
            )
        return plan to virtualIds.size
    }

    private fun snapshot(groupId: Int): GroupSnapshot {
        val group = ForkMergeRepository.groupById(groupId)!!
        return GroupSnapshot(
            groupId = group.id,
            mangaId = group.mangaId,
            mode = group.mode.name,
            options = group.options,
            coverSourceMangaId = group.coverSourceMangaId,
            sources = ForkMergeRepository.sources(group).map { SourceSnapshot(it.sourceMangaId, it.rank, it.offset, it.enabled) },
        )
    }

    private fun recreateGroup(group: GroupSnapshot): Int {
        group.sources.forEach { source ->
            forkRequire(ForkMergeRepository.groupIdOfSource(source.sourceMangaId) == null, "STALE") {
                "Source ${source.sourceMangaId} is now in another merge"
            }
        }
        val now = ForkClock.now()
        val id =
            ForkMergeGroupTable
                .insertAndGetId {
                    it[ForkMergeGroupTable.mangaId] = group.mangaId
                    it[ForkMergeGroupTable.mode] = group.mode
                    it[ForkMergeGroupTable.options] = ForkMergeRepository.encodeOptions(group.options)
                    it[ForkMergeGroupTable.coverSourceMangaId] = group.coverSourceMangaId
                    it[ForkMergeGroupTable.createdAt] = now
                    it[ForkMergeGroupTable.updatedAt] = now
                }.value
        if (group.mode == MergeMode.SPLIT.name) {
            ForkSplitSourceTable.insert {
                it[ForkSplitSourceTable.groupId] = id
                it[ForkSplitSourceTable.sourceMangaId] = group.sources.single().sourceMangaId
            }
        } else {
            group.sources.forEach { source ->
                ForkMergeSourceTable.insert {
                    it[ForkMergeSourceTable.groupId] = id
                    it[ForkMergeSourceTable.sourceMangaId] = source.sourceMangaId
                    it[ForkMergeSourceTable.rank] = source.rank
                    it[ForkMergeSourceTable.chapterOffset] = source.offset
                    it[ForkMergeSourceTable.enabled] = source.enabled
                }
            }
        }
        return id
    }

    private data class PurgeCounts(
        val merged: Int,
        val virtualChapters: Int,
        val libraryRemovals: Int,
    )

    private fun purgeCounts(mangaIds: List<Int>): PurgeCounts {
        val rows = MangaTable.selectAll().where { MangaTable.id inList mangaIds }.toList()
        val mergedIds = rows.filter { it[MangaTable.sourceReference] == ForkMergedSource.ID }.map { it[MangaTable.id].value }
        val virtualChapters =
            if (mergedIds.isEmpty()) {
                0
            } else {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga inList mergedIds }
                    .count()
                    .toInt()
            }
        return PurgeCounts(mergedIds.size, virtualChapters, rows.size - mergedIds.size)
    }

    private fun purgeInTransaction(
        mangaIds: List<Int>,
        reason: String,
    ): Int {
        val rows = MangaTable.selectAll().where { MangaTable.id inList mangaIds }.toList()
        rows.forEach { row ->
            val mangaId = row[MangaTable.id].value
            val before = ForkLifecycleStore.get(mangaId)
            if (row[MangaTable.sourceReference] == ForkMergedSource.ID) {
                ForkMergeRepository.groupByMergedManga(mangaId)?.let { group ->
                    ForkLifecycleStore.releaseOwnedBy(group.id) // never leave a source stuck hidden (D4)
                    ForkMergeGroupTable.deleteWhere { ForkMergeGroupTable.id eq group.id }
                }
                ChapterTable.deleteWhere { ChapterTable.manga eq mangaId }
                MangaTable.deleteWhere { MangaTable.id eq mangaId } // lifecycle row cascades
            } else {
                MangaTable.update({ MangaTable.id eq mangaId }) { it[MangaTable.inLibrary] = false }
                ForkLifecycleStore.set(mangaId, LifecycleState.ACTIVE, null, null)
            }
            ForkJournal.record(
                ForkOperation.LIFECYCLE_PURGE,
                ForkJournal.json.encodeToString(
                    LifecyclePayload.serializer(),
                    LifecyclePayload("PURGE:$reason", mangaId, listOf(before)),
                ),
                mangaId = null,
                reversible = false,
            )
        }
        return rows.size
    }

    companion object {
        /** Production instance; tests replace it to inject a gate without writing global settings. */
        @Volatile
        var default: ForkLifecycleExecutor = ForkLifecycleExecutor()
            internal set
    }
}
