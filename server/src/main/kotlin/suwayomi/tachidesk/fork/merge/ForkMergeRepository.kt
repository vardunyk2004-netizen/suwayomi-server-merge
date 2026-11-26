package suwayomi.tachidesk.fork.merge

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkMergeGroupTable
import suwayomi.tachidesk.fork.database.ForkMergeSourceTable
import suwayomi.tachidesk.fork.database.ForkSplitSourceTable
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction

/** Typed reads of fork merge tables. All functions join the caller's transaction. */
object ForkMergeRepository {
    fun decodeOptions(raw: String?): MergeOptions =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { ForkJournal.json.decodeFromString<MergeOptions>(it) }.getOrNull() }
            ?: MergeOptions()

    fun encodeOptions(options: MergeOptions): String = ForkJournal.json.encodeToString(MergeOptions.serializer(), options)

    fun groupByMergedManga(mangaId: Int): MergeGroupRow? =
        dbTransaction {
            ForkMergeGroupTable
                .selectAll()
                .where { ForkMergeGroupTable.mangaId eq mangaId }
                .firstOrNull()
                ?.let(::toGroup)
        }

    fun groupById(groupId: Int): MergeGroupRow? =
        dbTransaction {
            ForkMergeGroupTable
                .selectAll()
                .where { ForkMergeGroupTable.id eq groupId }
                .firstOrNull()
                ?.let(::toGroup)
        }

    fun allGroups(): List<MergeGroupRow> =
        dbTransaction {
            ForkMergeGroupTable.selectAll().orderBy(ForkMergeGroupTable.id to SortOrder.ASC).map(::toGroup)
        }

    /** Group (mirror/sequence) a source manga belongs to; a manga is in at most one (D10, unique constraint). */
    fun groupIdOfSource(sourceMangaId: Int): Int? =
        dbTransaction {
            ForkMergeSourceTable
                .selectAll()
                .where { ForkMergeSourceTable.sourceMangaId eq sourceMangaId }
                .firstOrNull()
                ?.get(ForkMergeSourceTable.groupId)
        }

    /** Split views that use a manga as their single source (F4). */
    fun splitGroupIdsOfSource(sourceMangaId: Int): List<Int> =
        dbTransaction {
            ForkSplitSourceTable
                .selectAll()
                .where { ForkSplitSourceTable.sourceMangaId eq sourceMangaId }
                .map { it[ForkSplitSourceTable.groupId] }
        }

    fun sources(group: MergeGroupRow): List<MergeSourceRow> =
        dbTransaction {
            if (group.mode == MergeMode.SPLIT) {
                ForkSplitSourceTable
                    .selectAll()
                    .where { ForkSplitSourceTable.groupId eq group.id }
                    .map { MergeSourceRow(it[ForkSplitSourceTable.sourceMangaId], 0, 0f, true) }
            } else {
                ForkMergeSourceTable
                    .selectAll()
                    .where { ForkMergeSourceTable.groupId eq group.id }
                    .orderBy(ForkMergeSourceTable.rank to SortOrder.ASC)
                    .map {
                        MergeSourceRow(
                            sourceMangaId = it[ForkMergeSourceTable.sourceMangaId],
                            rank = it[ForkMergeSourceTable.rank],
                            offset = it[ForkMergeSourceTable.chapterOffset],
                            enabled = it[ForkMergeSourceTable.enabled],
                        )
                    }
            }
        }

    fun chaptersOf(mangaIds: Collection<Int>): Map<Int, List<PlanChapter>> =
        dbTransaction {
            if (mangaIds.isEmpty()) return@dbTransaction emptyMap()
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga inList mangaIds }
                .map {
                    PlanChapter(
                        id = it[ChapterTable.id].value,
                        sourceMangaId = it[ChapterTable.manga].value,
                        url = it[ChapterTable.url],
                        name = it[ChapterTable.name],
                        number = it[ChapterTable.chapter_number],
                        scanlator = it[ChapterTable.scanlator],
                        uploadDate = it[ChapterTable.date_upload],
                        pageCount = it[ChapterTable.pageCount],
                        sourceOrder = it[ChapterTable.sourceOrder],
                    )
                }.groupBy { it.sourceMangaId }
        }

    fun planInput(group: MergeGroupRow): PlanInput {
        val sources = sources(group)
        val chapters = chaptersOf(sources.map { it.sourceMangaId })
        return PlanInput(
            mode = group.mode,
            sources =
                sources.map {
                    PlanSource(it.sourceMangaId, it.rank, it.offset, it.enabled, chapters[it.sourceMangaId].orEmpty())
                },
            options = group.options,
        )
    }

    fun virtualChapterIds(mergedMangaId: Int): List<Int> =
        dbTransaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mergedMangaId }
                .map { it[ChapterTable.id].value }
        }

    /** chapter id -> owning manga id. */
    fun chaptersOfIds(chapterIds: Collection<Int>): Map<Int, Int> =
        dbTransaction {
            if (chapterIds.isEmpty()) return@dbTransaction emptyMap()
            ChapterTable
                .select(ChapterTable.id, ChapterTable.manga)
                .where { ChapterTable.id inList chapterIds }
                .associate { it[ChapterTable.id].value to it[ChapterTable.manga].value }
        }

    fun linkSourceChapterIds(mergedChapterIds: Collection<Int>): Map<Int, Int?> =
        dbTransaction {
            if (mergedChapterIds.isEmpty()) return@dbTransaction emptyMap()
            ForkChapterLinkTable
                .selectAll()
                .where { ForkChapterLinkTable.mergedChapterId inList mergedChapterIds }
                .associate { it[ForkChapterLinkTable.mergedChapterId] to it[ForkChapterLinkTable.sourceChapterId] }
        }

    fun isMergedManga(mangaId: Int): Boolean =
        dbTransaction {
            MangaTable
                .select(MangaTable.sourceReference)
                .where { MangaTable.id eq mangaId }
                .firstOrNull()
                ?.get(MangaTable.sourceReference) == ForkMergedSource.ID
        }

    fun saveOptions(
        groupId: Int,
        options: MergeOptions,
    ) {
        dbTransaction {
            ForkMergeGroupTable.update({ ForkMergeGroupTable.id eq groupId }) {
                it[ForkMergeGroupTable.options] = encodeOptions(options)
                it[ForkMergeGroupTable.updatedAt] = ForkClock.now()
            }
        }
    }

    private fun toGroup(row: org.jetbrains.exposed.v1.core.ResultRow) =
        MergeGroupRow(
            id = row[ForkMergeGroupTable.id].value,
            mangaId = row[ForkMergeGroupTable.mangaId],
            mode = runCatching { MergeMode.valueOf(row[ForkMergeGroupTable.mode]) }.getOrDefault(MergeMode.MIRROR),
            options = decodeOptions(row[ForkMergeGroupTable.options]),
            coverSourceMangaId = row[ForkMergeGroupTable.coverSourceMangaId],
        )
}
