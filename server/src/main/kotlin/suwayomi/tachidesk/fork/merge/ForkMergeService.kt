package suwayomi.tachidesk.fork.merge

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkMergePolicy
import suwayomi.tachidesk.fork.ForkProgressPolicy
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.database.ForkChapterFallbackTable
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkChapterPartTable
import suwayomi.tachidesk.fork.database.ForkMergeGroupTable
import suwayomi.tachidesk.fork.database.ForkMergeSourceTable
import suwayomi.tachidesk.fork.database.ForkSplitSourceTable
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.journal.ForkJournalState
import suwayomi.tachidesk.fork.journal.ForkOperation
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.fork.progress.ForkProgressResolver
import suwayomi.tachidesk.fork.progress.ProgressState
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.graphql.types.MergeChapterDisplay
import suwayomi.tachidesk.graphql.types.MergeTrackerPolicy
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.server.database.dbTransaction
import java.util.UUID

data class CreateMergeRequest(
    /** Ordered; the first source is the preferred one (rank 0). */
    val sourceMangaIds: List<Int>,
    val mode: MergeMode,
    val title: String? = null,
    val coverSourceMangaId: Int? = null,
    /** SEQUENCE only: offset per source manga id. Ignored in MIRROR (D12). */
    val offsets: Map<Int, Float> = emptyMap(),
    val options: MergeOptions = MergeOptions(),
)

data class MergePreview(
    val plan: MergePlan,
    val sourceMangaIds: List<Int>,
)

data class MergeResult(
    val groupId: Int,
    val mergedMangaId: Int,
    val journalId: Int,
    val reconcile: ReconcileResult,
    val warnings: List<String>,
)

data class ReconcileResult(
    val added: Int,
    val removed: Int,
    val updated: Int,
    val total: Int,
)

data class BatchMergeOutcome(
    val index: Int,
    val result: MergeResult?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Serializable
data class MergeJournalPayload(
    val groupId: Int,
    val mergedMangaId: Int,
    val sourceMangaIds: List<Int>,
    val optionsBefore: MergeOptions? = null,
    val optionsAfter: MergeOptions? = null,
    val ranksBefore: Map<Int, Int> = emptyMap(),
    val offsetsBefore: Map<Int, Float> = emptyMap(),
    val coverBefore: Int? = null,
)

/**
 * Linked merge domain service (F2). Every public function checks the merge gate first, so with merge
 * disabled no fork table is read or written. Mutations are single transactions with a journal row.
 */
class ForkMergeService(
    private val gate: ForkGate = ForkGate.production,
    private val policy: () -> ForkMergePolicy = ForkMergePolicy::fromConfig,
    private val progressPolicy: () -> ForkProgressPolicy = ForkProgressPolicy::fromConfig,
) {
    private val logger = KotlinLogging.logger {}

    /** D28: the preview IS the plan that [create] will persist. */
    fun preview(request: CreateMergeRequest): MergePreview {
        gate.require(Feature.MERGE)
        return dbTransaction {
            validate(request)
            MergePreview(planFor(request, groupId = 0), request.sourceMangaIds)
        }
    }

    fun create(request: CreateMergeRequest): MergeResult {
        gate.require(Feature.MERGE)
        ForkMergedSource.ensureRegistered()
        return dbTransaction {
            validate(request)
            val now = ForkClock.now()
            val sourceRows =
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList request.sourceMangaIds }
                    .associateBy { it[MangaTable.id].value }
            val coverId = request.coverSourceMangaId ?: request.sourceMangaIds.first()
            val cover = sourceRows.getValue(coverId)

            val mergedMangaId =
                MangaTable
                    .insertAndGetId {
                        it[MangaTable.url] = "fork-merge://group/" + UUID.randomUUID()
                        it[MangaTable.title] = request.title?.takeIf { title -> title.isNotBlank() } ?: cover[MangaTable.title]
                        it[MangaTable.initialized] = true
                        it[MangaTable.artist] = cover[MangaTable.artist]
                        it[MangaTable.author] = cover[MangaTable.author]
                        it[MangaTable.description] = cover[MangaTable.description]
                        it[MangaTable.genre] = cover[MangaTable.genre]
                        it[MangaTable.status] = cover[MangaTable.status]
                        it[MangaTable.thumbnail_url] = cover[MangaTable.thumbnail_url]
                        it[MangaTable.inLibrary] = true
                        it[MangaTable.inLibraryAt] = now
                        it[MangaTable.sourceReference] = ForkMergedSource.ID
                        it[MangaTable.memo] = JsonObject.EMPTY
                    }.value

            val groupId =
                ForkMergeGroupTable
                    .insertAndGetId {
                        it[ForkMergeGroupTable.mangaId] = mergedMangaId
                        it[ForkMergeGroupTable.mode] = request.mode.name
                        it[ForkMergeGroupTable.optionsJson] = ForkMergeRepository.encodeOptions(request.options)
                        it[ForkMergeGroupTable.coverSourceMangaId] = request.coverSourceMangaId
                        it[ForkMergeGroupTable.createdAt] = now
                        it[ForkMergeGroupTable.updatedAt] = now
                    }.value

            if (request.mode == MergeMode.SPLIT) {
                ForkSplitSourceTable.insert {
                    it[ForkSplitSourceTable.groupId] = groupId
                    it[ForkSplitSourceTable.sourceMangaId] = request.sourceMangaIds.single()
                }
            } else {
                request.sourceMangaIds.forEachIndexed { rank, sourceMangaId ->
                    ForkMergeSourceTable.insert {
                        it[ForkMergeSourceTable.groupId] = groupId
                        it[ForkMergeSourceTable.sourceMangaId] = sourceMangaId
                        it[ForkMergeSourceTable.rank] = rank
                        it[ForkMergeSourceTable.chapterOffset] =
                            if (request.mode == MergeMode.SEQUENCE) request.offsets[sourceMangaId] ?: 0f else 0f
                        it[ForkMergeSourceTable.enabled] = true
                    }
                }
            }

            // Library placement: union of the sources' categories (sources keep theirs, decision 1 B).
            val categoryIds = request.sourceMangaIds.flatMap { CategoryManga.getMangaCategories(it).map { c -> c.id } }.distinct()
            if (categoryIds.isNotEmpty()) CategoryManga.addMangaToCategories(mergedMangaId, categoryIds)

            val warnings = mutableListOf<String>()
            if (request.mode != MergeMode.SPLIT) warnings += copyTrackers(mergedMangaId, request.sourceMangaIds)

            val reconcile = reconcileInTransaction(groupId)
            val journalId =
                ForkJournal.record(
                    ForkOperation.MERGE_CREATE,
                    ForkJournal.json.encodeToString(
                        MergeJournalPayload.serializer(),
                        MergeJournalPayload(groupId, mergedMangaId, request.sourceMangaIds),
                    ),
                    groupId = groupId,
                    mangaId = mergedMangaId,
                )
            MergeResult(groupId, mergedMangaId, journalId, reconcile, warnings)
        }
    }

    /** D20: each group in its own transaction; one failure never hides or undoes the others. */
    fun createBatch(requests: List<CreateMergeRequest>): List<BatchMergeOutcome> {
        gate.require(Feature.MERGE)
        return requests.mapIndexed { index, request -> BatchMergeOutcome(index, create(request), null, null) }
    }

    fun reconcile(
        groupId: Int,
        removedSourceMangaId: Int? = null,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction { reconcileInTransaction(groupId, removedSourceMangaId) }
    }

    fun addSource(
        groupId: Int,
        sourceMangaId: Int,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val group = requireGroup(groupId)
            forkRequire(group.mode != MergeMode.SPLIT, "MODE_INVALID") { "A split view has exactly one source" }
            validateSourceCandidate(sourceMangaId)
            val nextRank = ForkMergeRepository.sources(group).maxOfOrNull { it.rank }?.plus(1) ?: 0
            ForkMergeSourceTable.insert {
                it[ForkMergeSourceTable.groupId] = groupId
                it[ForkMergeSourceTable.sourceMangaId] = sourceMangaId
                it[ForkMergeSourceTable.rank] = nextRank
                it[ForkMergeSourceTable.chapterOffset] = 0f
                it[ForkMergeSourceTable.enabled] = true
            }
            if (policy().trackerPolicy != MergeTrackerPolicy.IGNORE) copyTrackers(group.mangaId, listOf(sourceMangaId))
            ForkJournal.record(
                ForkOperation.MERGE_ADD_SOURCE,
                ForkJournal.json.encodeToString(
                    MergeJournalPayload.serializer(),
                    MergeJournalPayload(groupId, group.mangaId, listOf(sourceMangaId)),
                ),
                groupId = groupId,
                mangaId = group.mangaId,
            )
            reconcileInTransaction(groupId)
        }
    }

    fun reorder(
        groupId: Int,
        orderedSourceMangaIds: List<Int>,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val group = requireGroup(groupId)
            val current = ForkMergeRepository.sources(group)
            val sameSources = orderedSourceMangaIds.toSet() == current.map { it.sourceMangaId }.toSet()
            forkRequire(sameSources && orderedSourceMangaIds.distinct().size == current.size, "SOURCES_MISMATCH") {
                "Reorder must list every source of the merge exactly once"
            }
            orderedSourceMangaIds.forEachIndexed { rank, id ->
                ForkMergeSourceTable.update({ (ForkMergeSourceTable.groupId eq groupId) and (ForkMergeSourceTable.sourceMangaId eq id) }) {
                    it[ForkMergeSourceTable.rank] = rank
                }
            }
            ForkJournal.record(
                ForkOperation.MERGE_REORDER,
                ForkJournal.json.encodeToString(
                    MergeJournalPayload.serializer(),
                    MergeJournalPayload(
                        groupId,
                        group.mangaId,
                        orderedSourceMangaIds,
                        ranksBefore = current.associate { it.sourceMangaId to it.rank },
                    ),
                ),
                groupId = groupId,
                mangaId = group.mangaId,
            )
            reconcileInTransaction(groupId)
        }
    }

    fun setOffsets(
        groupId: Int,
        offsets: Map<Int, Float>,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val group = requireGroup(groupId)
            forkRequire(group.mode == MergeMode.SEQUENCE, "MODE_INVALID") { "Offsets apply to sequence merges only (D12)" }
            val current = ForkMergeRepository.sources(group)
            forkRequire(offsets.keys.all { id -> current.any { it.sourceMangaId == id } }, "SOURCES_MISMATCH") { "Unknown source" }
            offsets.forEach { (id, offset) ->
                ForkMergeSourceTable.update({ (ForkMergeSourceTable.groupId eq groupId) and (ForkMergeSourceTable.sourceMangaId eq id) }) {
                    it[ForkMergeSourceTable.chapterOffset] = offset
                }
            }
            ForkJournal.record(
                ForkOperation.MERGE_OPTIONS,
                ForkJournal.json.encodeToString(
                    MergeJournalPayload.serializer(),
                    MergeJournalPayload(
                        groupId,
                        group.mangaId,
                        current.map { it.sourceMangaId },
                        offsetsBefore = current.associate { it.sourceMangaId to it.offset },
                    ),
                ),
                groupId = groupId,
                mangaId = group.mangaId,
            )
            reconcileInTransaction(groupId)
        }
    }

    fun setCover(
        groupId: Int,
        coverSourceMangaId: Int?,
    ) {
        gate.require(Feature.MERGE)
        dbTransaction {
            val group = requireGroup(groupId)
            if (coverSourceMangaId != null) {
                forkRequire(ForkMergeRepository.sources(group).any { it.sourceMangaId == coverSourceMangaId }, "SOURCES_MISMATCH") {
                    "The cover must come from a source of this merge"
                }
            }
            ForkMergeGroupTable.update({ ForkMergeGroupTable.id eq groupId }) {
                it[ForkMergeGroupTable.coverSourceMangaId] = coverSourceMangaId
                it[ForkMergeGroupTable.updatedAt] = ForkClock.now()
            }
            ForkJournal.record(
                ForkOperation.MERGE_OPTIONS,
                ForkJournal.json.encodeToString(
                    MergeJournalPayload.serializer(),
                    MergeJournalPayload(groupId, group.mangaId, emptyList(), coverBefore = group.coverSourceMangaId),
                ),
                groupId = groupId,
                mangaId = group.mangaId,
            )
        }
    }

    /** Replaces the per-merge options (exclusions, manual picks, overrides); journaled with before/after. */
    fun updateOptions(
        groupId: Int,
        transform: (MergeOptions) -> MergeOptions,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val group = requireGroup(groupId)
            val after = transform(group.options)
            ForkMergeRepository.saveOptions(groupId, after)
            ForkJournal.record(
                ForkOperation.MERGE_OPTIONS,
                ForkJournal.json.encodeToString(
                    MergeJournalPayload.serializer(),
                    MergeJournalPayload(groupId, group.mangaId, emptyList(), optionsBefore = group.options, optionsAfter = after),
                ),
                groupId = groupId,
                mangaId = group.mangaId,
            )
            reconcileInTransaction(groupId)
        }
    }

    /** Undo of MERGE_OPTIONS / MERGE_REORDER journal rows (optimistic: current options must equal "after"). */
    fun undoOptions(journalId: Int): ReconcileResult {
        gate.require(Feature.MERGE)
        return dbTransaction {
            val entry = ForkJournal.get(journalId)
            forkRequire(entry != null && entry.state == ForkJournalState.APPLIED && entry.reversible, "NOT_UNDOABLE") {
                "Journal entry cannot be undone"
            }
            val payload = ForkJournal.json.decodeFromString(MergeJournalPayload.serializer(), entry!!.payload!!)
            val group = requireGroup(payload.groupId)
            when (entry.operation) {
                ForkOperation.MERGE_OPTIONS -> {
                    payload.optionsBefore?.let { before ->
                        forkRequire(group.options == payload.optionsAfter, "STALE") { "The merge changed since this entry; undo refused" }
                        ForkMergeRepository.saveOptions(group.id, before)
                    }
                    payload.offsetsBefore.forEach { (id, offset) ->
                        ForkMergeSourceTable.update({ sourceRow(group.id, id) }) {
                            it[ForkMergeSourceTable.chapterOffset] = offset
                        }
                    }
                    if (payload.optionsBefore == null && payload.offsetsBefore.isEmpty()) {
                        ForkMergeGroupTable.update({ ForkMergeGroupTable.id eq group.id }) {
                            it[ForkMergeGroupTable.coverSourceMangaId] = payload.coverBefore
                        }
                    }
                }

                ForkOperation.MERGE_REORDER -> {
                    payload.ranksBefore.forEach { (id, rank) ->
                        ForkMergeSourceTable.update({ sourceRow(group.id, id) }) {
                            it[ForkMergeSourceTable.rank] = rank
                        }
                    }
                }

                else -> {
                    forkRequire(false, "NOT_UNDOABLE") { "Use the lifecycle undo for ${entry.operation}" }
                }
            }
            ForkJournal.markState(journalId, ForkJournalState.UNDONE)
            reconcileInTransaction(group.id)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun requireGroup(groupId: Int): MergeGroupRow =
        ForkMergeRepository.groupById(groupId) ?: throw ForkRequestException("NOT_FOUND", "Merge $groupId does not exist")

    private fun sourceRow(
        groupId: Int,
        sourceMangaId: Int,
    ) = (ForkMergeSourceTable.groupId eq groupId) and (ForkMergeSourceTable.sourceMangaId eq sourceMangaId)

    private fun validate(request: CreateMergeRequest) {
        val ids = request.sourceMangaIds
        forkRequire(ids.distinct().size == ids.size, "DUPLICATE_SOURCE") { "A manga can be listed only once" }
        if (request.mode == MergeMode.SPLIT) {
            forkRequire(ids.size == 1, "MIN_SOURCES") { "A split view uses exactly one manga" }
        } else {
            val minimum = policy().minimumSources.coerceAtLeast(2)
            forkRequire(ids.size >= minimum, "MIN_SOURCES") { "A merge needs at least $minimum sources" }
        }
        val rows = MangaTable.selectAll().where { MangaTable.id inList ids }.toList()
        forkRequire(rows.size == ids.size, "MANGA_NOT_FOUND") { "Unknown manga in request" }
        ids.forEach { id ->
            if (request.mode == MergeMode.SPLIT) {
                forkRequire(!ForkMergeRepository.isMergedManga(id), "NESTED_MERGE") { "A merged manga cannot be split" }
            } else {
                validateSourceCandidate(id)
            }
        }
        request.coverSourceMangaId?.let { cover ->
            forkRequire(cover in ids, "SOURCES_MISMATCH") { "The cover must come from a source of this merge" }
        }
        if (request.mode == MergeMode.SPLIT) {
            ForkSeriesSplitPlanner.validate(ids.single(), request.options.includedSourceChapterIds)
        }
    }

    /** D10: a merged manga is never a source, and a manga is in at most one merge. */
    private fun validateSourceCandidate(sourceMangaId: Int) {
        forkRequire(!ForkMergeRepository.isMergedManga(sourceMangaId), "NESTED_MERGE") {
            "A merged manga cannot be a source of another merge"
        }
        forkRequire(ForkMergeRepository.groupIdOfSource(sourceMangaId) == null, "ALREADY_MERGED") {
            "Manga $sourceMangaId is already in a merge"
        }
    }

    private fun planFor(
        request: CreateMergeRequest,
        groupId: Int,
    ): MergePlan {
        val chapters = ForkMergeRepository.chaptersOf(request.sourceMangaIds)
        val input =
            PlanInput(
                mode = request.mode,
                sources =
                    request.sourceMangaIds.mapIndexed { rank, id ->
                        PlanSource(
                            id,
                            rank,
                            if (request.mode == MergeMode.SEQUENCE) request.offsets[id] ?: 0f else 0f,
                            true,
                            chapters[id].orEmpty(),
                        )
                    },
                options = request.options,
            )
        return planFrom(input)
    }

    internal fun planFrom(input: PlanInput): MergePlan {
        val p = policy()
        return ForkMergePlanner.plan(input, p.dedupeStrategy, p.unnumberedMatching, p.fallbackSourcesEnabled)
    }

    /**
     * Brings the merged manga's virtual chapter rows and links in line with the current plan.
     * Virtual rows: never copy `is_downloaded` (D23); keep real numbers/names (D2); progress of NEW rows is
     * folded from the linked source chapters; existing rows keep their own progress.
     */
    internal fun reconcileInTransaction(
        groupId: Int,
        removedSourceMangaId: Int? = null,
    ): ReconcileResult {
        val group = requireGroup(groupId)
        val plan = planFrom(ForkMergeRepository.planInput(group))
        val display =
            group.options.chapterDisplay
                ?.let { runCatching { MergeChapterDisplay.valueOf(it) }.getOrNull() } ?: policy().chapterDisplay
        val now = ForkClock.now()

        val existing =
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq group.mangaId }
                .associateBy { it[ChapterTable.url] }
        val desired = plan.entries.associateBy { ForkMergePlanner.virtualChapterUrl(group.id, it.entryKey) }

        // D25/setting 1042: a chapter that vanished from its source keeps its virtual row (and progress) with a
        // SOURCE_MISSING link; it re-links automatically when an entry with the same key returns. Rows removed
        // on purpose (exclusions, chapters replaced by parts) are deleted; links and parts cascade.
        val deliberatelyRemoved = plan.excludedEntryKeys.toSet() + group.options.partDefinitions.flatMap { it.hidesEntryKeys }
        val urlPrefix = ForkMergePlanner.virtualChapterUrl(group.id, "")
        val vanished = existing.keys - desired.keys
        val removedSourceChapterIds =
            if (removedSourceMangaId == null) {
                emptySet()
            } else {
                ForkChapterLinkTable
                    .selectAll()
                    .where {
                        (ForkChapterLinkTable.sourceMangaId eq removedSourceMangaId) and
                            (ForkChapterLinkTable.mergedChapterId inList existing.values.map { it[ChapterTable.id].value })
                    }.map { it[ForkChapterLinkTable.mergedChapterId] }
                    .toSet()
            }
        val (toDelete, toFlag) =
            vanished.partition { url ->
                val key = url.removePrefix(urlPrefix)
                val chapterId = existing.getValue(url)[ChapterTable.id].value
                !policy().detectRemovedChapters ||
                    key in deliberatelyRemoved ||
                    key.startsWith("p:") ||
                    chapterId in removedSourceChapterIds
            }
        if (toDelete.isNotEmpty()) {
            val ids = toDelete.map { existing.getValue(it)[ChapterTable.id].value }
            ChapterTable.deleteWhere { ChapterTable.id inList ids }
        }
        if (toFlag.isNotEmpty()) {
            val ids = toFlag.map { existing.getValue(it)[ChapterTable.id].value }
            ForkChapterLinkTable.update({ ForkChapterLinkTable.mergedChapterId inList ids }) {
                it[ForkChapterLinkTable.status] = LinkStatus.SOURCE_MISSING.name
            }
        }

        val sourceProgress = loadSourceProgress(plan)
        var added = 0
        var updated = 0
        plan.entries.forEachIndexed { index, entry ->
            val url = ForkMergePlanner.virtualChapterUrl(group.id, entry.entryKey)
            val name =
                entry.nameOverride ?: when (display) {
                    MergeChapterDisplay.ORIGINAL -> {
                        entry.primary.name
                    }

                    MergeChapterDisplay.NORMALIZED -> {
                        if (entry.number >= 0f) "Chapter ${formatNumber(entry.number)}" else entry.primary.name
                    }
                }
            val row = existing[url]
            val chapterId =
                if (row == null) {
                    added++
                    val initial =
                        ForkProgressResolver.fold(
                            (listOf(entry.primary) + entry.fallbacks).mapNotNull { sourceProgress[it.id] },
                            progressPolicy(),
                        )
                    ChapterTable
                        .insertAndGetId {
                            it[ChapterTable.url] = url
                            it[ChapterTable.name] = name
                            it[ChapterTable.date_upload] = entry.primary.uploadDate
                            it[ChapterTable.chapter_number] = entry.number
                            it[ChapterTable.scanlator] = entry.primary.scanlator
                            it[ChapterTable.sourceOrder] = index + 1
                            it[ChapterTable.fetchedAt] = now
                            it[ChapterTable.manga] = group.mangaId
                            it[ChapterTable.isRead] = initial.read
                            it[ChapterTable.isBookmarked] = initial.bookmarked
                            it[ChapterTable.lastPageRead] = if (entry.kind == LinkKind.CHAPTER) initial.lastPageRead else 0
                            it[ChapterTable.lastReadAt] = initial.lastReadAt
                            it[ChapterTable.isDownloaded] = false
                            it[ChapterTable.pageCount] =
                                if (entry.kind == LinkKind.CHAPTER) -1 else entry.parts.sumOf { p -> p.pageEnd - p.pageStart + 1 }
                            it[ChapterTable.memo] = JsonObject.EMPTY
                        }.value
                } else {
                    val id = row[ChapterTable.id].value
                    val changed =
                        row[ChapterTable.name] != name ||
                            row[ChapterTable.chapter_number] != entry.number ||
                            row[ChapterTable.sourceOrder] != index + 1 ||
                            row[ChapterTable.scanlator] != entry.primary.scanlator
                    if (changed) {
                        updated++
                        ChapterTable.update({ ChapterTable.id eq id }) {
                            it[ChapterTable.name] = name
                            it[ChapterTable.chapter_number] = entry.number
                            it[ChapterTable.scanlator] = entry.primary.scanlator
                            it[ChapterTable.sourceOrder] = index + 1
                            it[ChapterTable.date_upload] = entry.primary.uploadDate
                        }
                    }
                    id
                }
            writeLink(group.id, chapterId, entry)
        }

        if (group.mode != MergeMode.SPLIT) {
            ForkMergeRepository.sources(group).forEach { source ->
                if (ForkLifecycleStore.stateOf(source.sourceMangaId) == LifecycleState.ACTIVE) {
                    ForkLifecycleStore.set(source.sourceMangaId, LifecycleState.HIDDEN_BY_MERGE, "merged", group.id)
                }
            }
        }
        return ReconcileResult(added = added, removed = toDelete.size, updated = updated, total = plan.entries.size)
    }

    private fun writeLink(
        groupId: Int,
        mergedChapterId: Int,
        entry: PlanEntry,
    ) {
        val primary = entry.primary
        val first = entry.parts.firstOrNull()
        val existingLinkId =
            ForkChapterLinkTable
                .select(ForkChapterLinkTable.id)
                .where { ForkChapterLinkTable.mergedChapterId eq mergedChapterId }
                .firstOrNull()
                ?.get(ForkChapterLinkTable.id)
                ?.value
        val linkId =
            if (existingLinkId == null) {
                ForkChapterLinkTable
                    .insertAndGetId {
                        it[ForkChapterLinkTable.mergedChapterId] = mergedChapterId
                        fillLink(it, primary, entry, first)
                    }.value
            } else {
                ForkChapterLinkTable.update({ ForkChapterLinkTable.id eq existingLinkId }) { fillLink(it, primary, entry, first) }
                existingLinkId
            }

        ForkChapterFallbackTable.deleteWhere { ForkChapterFallbackTable.linkId eq linkId }
        entry.fallbacks.forEachIndexed { rank, fallback ->
            ForkChapterFallbackTable.insert {
                it[ForkChapterFallbackTable.linkId] = linkId
                it[ForkChapterFallbackTable.sourceChapterId] = fallback.id
                it[ForkChapterFallbackTable.sourceMangaId] = fallback.sourceMangaId
                it[ForkChapterFallbackTable.rank] = rank
            }
        }

        ForkChapterPartTable.deleteWhere { ForkChapterPartTable.mergedChapterId eq mergedChapterId }
        if (entry.kind == LinkKind.PARTS) {
            val sourceMangaByChapter = ForkMergeRepository.chaptersOfIds(entry.parts.map { it.sourceChapterId })
            entry.parts.forEachIndexed { index, part ->
                ForkChapterPartTable.insert {
                    it[ForkChapterPartTable.mergedChapterId] = mergedChapterId
                    it[ForkChapterPartTable.partIndex] = index
                    it[ForkChapterPartTable.sourceChapterId] = part.sourceChapterId
                    it[ForkChapterPartTable.sourceMangaId] = sourceMangaByChapter[part.sourceChapterId] ?: primary.sourceMangaId
                    it[ForkChapterPartTable.pageStart] = part.pageStart
                    it[ForkChapterPartTable.pageEnd] = part.pageEnd
                    it[ForkChapterPartTable.sourcePageCountSnapshot] = part.sourcePageCount
                    it[ForkChapterPartTable.status] = LinkStatus.OK.name
                }
            }
        }
    }

    private fun fillLink(
        it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>,
        primary: PlanChapter,
        entry: PlanEntry,
        first: PartRange?,
    ) {
        it[ForkChapterLinkTable.sourceChapterId] = primary.id
        it[ForkChapterLinkTable.sourceMangaId] = primary.sourceMangaId
        it[ForkChapterLinkTable.rank] = 0
        it[ForkChapterLinkTable.originalName] = primary.name.take(2048)
        it[ForkChapterLinkTable.originalUrl] = primary.url.take(2048)
        it[ForkChapterLinkTable.status] = LinkStatus.OK.name
        it[ForkChapterLinkTable.originalNumber] = primary.number
        it[ForkChapterLinkTable.originalScanlator] = primary.scanlator?.take(256)
        it[ForkChapterLinkTable.originalUploadDate] = primary.uploadDate
        it[ForkChapterLinkTable.entryKey] = entry.entryKey.take(512)
        it[ForkChapterLinkTable.linkKind] = entry.kind.name
        it[ForkChapterLinkTable.pageStart] = first?.pageStart
        it[ForkChapterLinkTable.pageEnd] = first?.pageEnd
        it[ForkChapterLinkTable.sourcePageCountSnapshot] = first?.sourcePageCount ?: primary.pageCount
    }

    private fun loadSourceProgress(plan: MergePlan): Map<Int, ProgressState> {
        val ids = plan.entries.flatMap { listOf(it.primary.id) + it.fallbacks.map { f -> f.id } }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return ChapterTable
            .selectAll()
            .where { ChapterTable.id inList ids }
            .associate {
                it[ChapterTable.id].value to
                    ProgressState(
                        read = it[ChapterTable.isRead],
                        bookmarked = it[ChapterTable.isBookmarked],
                        lastPageRead = it[ChapterTable.lastPageRead],
                        lastReadAt = it[ChapterTable.lastReadAt],
                    )
            }
    }

    /** Decision 5: copy tracker records once (original numbers are used later via the link). */
    private fun copyTrackers(
        mergedMangaId: Int,
        sourceMangaIds: List<Int>,
    ): List<String> {
        if (policy().trackerPolicy == MergeTrackerPolicy.IGNORE) return emptyList()
        val warnings = mutableListOf<String>()
        val existingTrackers =
            TrackRecordTable
                .selectAll()
                .where { TrackRecordTable.mangaId eq mergedMangaId }
                .map { it[TrackRecordTable.trackerId] }
                .toMutableSet()
        val records = TrackRecordTable.selectAll().where { TrackRecordTable.mangaId inList sourceMangaIds }.toList()
        records.groupBy { it[TrackRecordTable.trackerId] }.forEach { (trackerId, rows) ->
            if (rows.size > 1) warnings += "Several sources are tracked on tracker $trackerId; the first source's record was used"
            if (trackerId in existingTrackers) return@forEach
            val row = rows.first()
            TrackRecordTable.insert {
                it[TrackRecordTable.mangaId] = mergedMangaId
                it[TrackRecordTable.trackerId] = trackerId
                it[TrackRecordTable.remoteId] = row[TrackRecordTable.remoteId]
                it[TrackRecordTable.libraryId] = row[TrackRecordTable.libraryId]
                it[TrackRecordTable.title] = row[TrackRecordTable.title]
                it[TrackRecordTable.lastChapterRead] = row[TrackRecordTable.lastChapterRead]
                it[TrackRecordTable.totalChapters] = row[TrackRecordTable.totalChapters]
                it[TrackRecordTable.status] = row[TrackRecordTable.status]
                it[TrackRecordTable.score] = row[TrackRecordTable.score]
                it[TrackRecordTable.remoteUrl] = row[TrackRecordTable.remoteUrl]
                it[TrackRecordTable.startDate] = row[TrackRecordTable.startDate]
                it[TrackRecordTable.finishDate] = row[TrackRecordTable.finishDate]
                it[TrackRecordTable.private] = row[TrackRecordTable.private]
            }
            existingTrackers += trackerId
        }
        return warnings
    }

    private fun formatNumber(number: Float): String = number.toBigDecimal().stripTrailingZeros().toPlainString()

    companion object {
        /** Production instance; tests replace it to inject a gate without writing global settings. */
        @Volatile
        var default: ForkMergeService = ForkMergeService()
            internal set
    }
}
