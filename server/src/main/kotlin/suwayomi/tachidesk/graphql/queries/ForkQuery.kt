package suwayomi.tachidesk.graphql.queries

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.fork.ForkFeatureAvailability
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkProgressPolicy
import suwayomi.tachidesk.fork.database.ForkChapterFallbackTable
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleExecutor
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkMergeHooks
import suwayomi.tachidesk.fork.merge.ForkMergeRepository
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.merge.LinkKind
import suwayomi.tachidesk.fork.merge.LinkStatus
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.fork.merge.MergePlan
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ForkChapterLinkType
import suwayomi.tachidesk.graphql.types.ForkJournalEntryType
import suwayomi.tachidesk.graphql.types.ForkLinkKind
import suwayomi.tachidesk.graphql.types.ForkLinkStatus
import suwayomi.tachidesk.graphql.types.ForkMangaState
import suwayomi.tachidesk.graphql.types.ForkMangaStateType
import suwayomi.tachidesk.graphql.types.ForkMergeInfoType
import suwayomi.tachidesk.graphql.types.ForkMergeMode
import suwayomi.tachidesk.graphql.types.ForkMergePlanEntryType
import suwayomi.tachidesk.graphql.types.ForkMergePreviewType
import suwayomi.tachidesk.graphql.types.ForkMergeSourceType
import suwayomi.tachidesk.graphql.types.ForkMigrationCheckType
import suwayomi.tachidesk.graphql.types.ForkPartDefinitionType
import suwayomi.tachidesk.graphql.types.ForkPreviewSourceType
import suwayomi.tachidesk.graphql.types.ForkProgressSyncStatus
import suwayomi.tachidesk.graphql.types.ForkTrashItemType
import suwayomi.tachidesk.graphql.types.MergeDefaultMode
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig

/**
 * Read-only fork queries. Each resolver checks the feature gate BEFORE any table access (R8): with merge
 * disabled it throws `FEATURE_DISABLED` and runs no SQL.
 */
class ForkQuery {
    data class ForkSourceOffsetInput(
        val sourceMangaId: Int,
        val offset: Double,
    )

    data class MergePreviewInput(
        val sourceMangaIds: List<Int>,
        /** Null -> setting 1002 (default MIRROR). */
        val mode: ForkMergeMode? = null,
        val offsets: List<ForkSourceOffsetInput>? = null,
    )

    @RequireAuth
    fun mergeInfo(mangaId: Int): ForkMergeInfoType? {
        ForkGate.production.require(ForkFeatureAvailability.Feature.MERGE)
        return ForkGraphQLMapper.mergeInfo(mangaId)
    }

    @RequireAuth
    fun mergePreview(input: MergePreviewInput): ForkMergePreviewType {
        val request = ForkGraphQLMapper.createRequest(input.sourceMangaIds, input.mode, null, null, input.offsets)
        val preview = ForkMergeService.default.preview(request)
        return ForkGraphQLMapper.preview(preview.plan, input.sourceMangaIds)
    }

    @RequireAuth
    fun trashMangas(): List<ForkTrashItemType> =
        ForkLifecycleExecutor.default.trash().map {
            ForkTrashItemType(it.mangaId, it.title, it.reason, it.removedAt, it.ownerGroupId, it.isMerged, it.purgeAfter)
        }

    @RequireAuth
    fun forkJournal(limit: Int? = null): List<ForkJournalEntryType> {
        ForkGate.production.require(ForkFeatureAvailability.Feature.MERGE)
        return ForkJournal.recent(limit ?: 100).map {
            ForkJournalEntryType(
                it.id,
                it.operation.name,
                it.state.name,
                it.groupId,
                it.mangaId,
                it.reversible,
                it.createdAt,
                it.undoneAt,
                it.errorMessage,
            )
        }
    }

    @RequireAuth
    fun forkMangaStates(mangaIds: List<Int>): List<ForkMangaStateType> {
        ForkGate.production.require(ForkFeatureAvailability.Feature.MERGE)
        return dbTransaction {
            val rows = MangaTable.selectAll().where { MangaTable.id inList mangaIds }.associateBy { it[MangaTable.id].value }
            mangaIds.mapNotNull { id ->
                val row = rows[id] ?: return@mapNotNull null
                val lifecycle = ForkLifecycleStore.get(id)
                val mergedInto = ForkMergeRepository.groupIdOfSource(id)?.let { ForkMergeRepository.groupById(it)?.mangaId }
                ForkMangaStateType(
                    mangaId = id,
                    state = lifecycle.state?.let { ForkMangaState.valueOf(it) } ?: ForkMangaState.ACTIVE,
                    isMerged = row[MangaTable.sourceReference] == ForkMergedSource.ID,
                    mergedIntoMangaId = mergedInto,
                )
            }
        }
    }

    /** F13: whether the WebUI migration flow may migrate this manga (setting 1090). */
    @RequireAuth
    fun forkMigrationCheck(mangaId: Int): ForkMigrationCheckType {
        if (!ForkFeatureAvailability.isEnabled(ForkFeatureAvailability.Feature.MERGE) || !serverConfig.migrationBlockMergedSources.value) {
            return ForkMigrationCheckType(mangaId, allowed = true, reason = null)
        }
        return dbTransaction {
            when {
                ForkMergeRepository.isMergedManga(mangaId) -> {
                    ForkMigrationCheckType(mangaId, false, "A merged manga cannot be migrated. Migrate its sources instead.")
                }

                ForkMergeRepository.groupIdOfSource(mangaId) != null -> {
                    ForkMigrationCheckType(
                        mangaId,
                        false,
                        "This manga is a source of a merged manga. Remove it from the merge first, then migrate it.",
                    )
                }

                else -> {
                    ForkMigrationCheckType(mangaId, true, null)
                }
            }
        }
    }
}

/**
 * Domain -> GraphQL mapping shared by the fork query and mutation classes.
 *
 * Stored enum names are plain text; an unknown value (written by a newer build) falls back instead of failing the query.
 */
private inline fun <reified E : Enum<E>> enumOr(
    stored: String,
    fallback: E,
): E = runCatching { enumValueOf<E>(stored) }.getOrDefault(fallback)

object ForkGraphQLMapper {
    fun mode(mode: ForkMergeMode?): MergeMode =
        when (mode) {
            ForkMergeMode.MIRROR -> MergeMode.MIRROR
            ForkMergeMode.SEQUENCE -> MergeMode.SEQUENCE
            ForkMergeMode.SPLIT -> MergeMode.SPLIT
            null -> if (serverConfig.mergeDefaultMode.value == MergeDefaultMode.SEQUENCE) MergeMode.SEQUENCE else MergeMode.MIRROR
        }

    fun createRequest(
        sourceMangaIds: List<Int>,
        mode: ForkMergeMode?,
        title: String?,
        coverSourceMangaId: Int?,
        offsets: List<ForkQuery.ForkSourceOffsetInput>?,
    ) = CreateMergeRequest(
        sourceMangaIds = sourceMangaIds,
        mode = mode(mode),
        title = title,
        coverSourceMangaId = coverSourceMangaId,
        offsets = offsets.orEmpty().associate { it.sourceMangaId to it.offset.toFloat() },
    )

    fun preview(
        plan: MergePlan,
        sourceMangaIds: List<Int>,
    ): ForkMergePreviewType {
        val rows =
            dbTransaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList sourceMangaIds }
                    .associate { it[MangaTable.id].value to it[MangaTable.title] }
            }
        val counts = ForkMergeRepository.chaptersOf(sourceMangaIds).mapValues { it.value.size }
        return ForkMergePreviewType(
            sources = sourceMangaIds.map { ForkPreviewSourceType(it, rows[it] ?: "", counts[it] ?: 0) },
            entries =
                plan.entries.map {
                    ForkMergePlanEntryType(
                        entryKey = it.entryKey,
                        number = it.number.toDouble(),
                        name = it.nameOverride ?: it.primary.name,
                        scanlator = it.primary.scanlator,
                        sourceMangaId = it.primary.sourceMangaId,
                        sourceChapterId = it.primary.id,
                        fallbackSourceMangaIds = it.fallbacks.map { f -> f.sourceMangaId },
                        kind = ForkLinkKind.valueOf(it.kind.name),
                        needsManualChoice = it.needsManualChoice,
                    )
                },
            excludedEntryKeys = plan.excludedEntryKeys,
            warnings = plan.warnings,
            chapterCount = plan.entries.size,
        )
    }

    fun mergeInfo(mangaId: Int): ForkMergeInfoType? =
        dbTransaction {
            val group = ForkMergeRepository.groupByMergedManga(mangaId) ?: return@dbTransaction null
            val sources = ForkMergeRepository.sources(group)
            val mangaRows =
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList sources.map { it.sourceMangaId } }
                    .associateBy { it[MangaTable.id].value }
            val virtualIds = ForkMergeRepository.virtualChapterIds(mangaId)
            val downloadedVirtual = ForkMergeHooks.sourceDownloadedVirtualIds(mangaId)
            val links =
                ForkChapterLinkTable
                    .selectAll()
                    .where { ForkChapterLinkTable.mergedChapterId inList virtualIds }
                    .orderBy(ForkChapterLinkTable.mergedChapterId to SortOrder.ASC)
                    .toList()
            val fallbacks =
                ForkChapterFallbackTable
                    .selectAll()
                    .where { ForkChapterFallbackTable.linkId inList links.map { it[ForkChapterLinkTable.id].value } }
                    .groupBy({ it[ForkChapterFallbackTable.linkId] }, { it[ForkChapterFallbackTable.sourceMangaId] })
            val linkTypes =
                links.map {
                    ForkChapterLinkType(
                        mergedChapterId = it[ForkChapterLinkTable.mergedChapterId],
                        sourceChapterId = it[ForkChapterLinkTable.sourceChapterId],
                        sourceMangaId = it[ForkChapterLinkTable.sourceMangaId],
                        originalNumber = it[ForkChapterLinkTable.originalNumber].toDouble(),
                        originalName = it[ForkChapterLinkTable.originalName],
                        originalScanlator = it[ForkChapterLinkTable.originalScanlator],
                        status = ForkLinkStatus.valueOf(enumOr(it[ForkChapterLinkTable.status], LinkStatus.OK).name),
                        kind = ForkLinkKind.valueOf(enumOr(it[ForkChapterLinkTable.linkKind], LinkKind.CHAPTER).name),
                        entryKey = it[ForkChapterLinkTable.entryKey],
                        pageStart = it[ForkChapterLinkTable.pageStart],
                        pageEnd = it[ForkChapterLinkTable.pageEnd],
                        fallbackSourceMangaIds = fallbacks[it[ForkChapterLinkTable.id].value].orEmpty(),
                        sourceDownloaded = it[ForkChapterLinkTable.mergedChapterId] in downloadedVirtual,
                    )
                }
            val syncStatus =
                when {
                    !ForkProgressPolicy.fromConfig().enabled -> ForkProgressSyncStatus.OFF
                    linkTypes.any { it.status != ForkLinkStatus.OK } -> ForkProgressSyncStatus.NEEDS_REVIEW
                    else -> ForkProgressSyncStatus.SYNCED
                }
            ForkMergeInfoType(
                groupId = group.id,
                mergedMangaId = mangaId,
                mode = ForkMergeMode.valueOf(group.mode.name),
                coverSourceMangaId = group.coverSourceMangaId,
                sources =
                    sources.map { source ->
                        val row = mangaRows[source.sourceMangaId]
                        ForkMergeSourceType(
                            sourceMangaId = source.sourceMangaId,
                            rank = source.rank,
                            offset = source.offset.toDouble(),
                            enabled = source.enabled,
                            title = row?.get(MangaTable.title) ?: "",
                            sourceId = row?.get(MangaTable.sourceReference)?.toString() ?: "",
                            state =
                                ForkLifecycleStore.stateOf(source.sourceMangaId).let {
                                    if (it == LifecycleState.ACTIVE) ForkMangaState.ACTIVE else ForkMangaState.valueOf(it.name)
                                },
                        )
                    },
                excludedEntryKeys = group.options.excludedEntryKeys.sorted(),
                partDefinitions =
                    group.options.partDefinitions.map { d ->
                        ForkPartDefinitionType(
                            key = d.key,
                            name = d.name,
                            number = d.number.toDouble(),
                            pageCount = d.parts.sumOf { it.pageEnd - it.pageStart + 1 },
                            sourceChapterIds = d.parts.map { it.sourceChapterId },
                        )
                    },
                chapterLinks = linkTypes,
                progressSyncStatus = syncStatus,
            )
        }
}
