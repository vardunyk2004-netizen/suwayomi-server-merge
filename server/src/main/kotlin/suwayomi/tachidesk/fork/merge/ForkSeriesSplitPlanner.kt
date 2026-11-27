package suwayomi.tachidesk.fork.merge

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.database.dbTransaction

data class SplitViewRequest(
    val title: String,
    val chapterIds: List<Int>,
)

data class SplitPreviewGroup(
    val title: String,
    val chapterCount: Int,
)

/**
 * F4 series split: new views over subsets of ONE manga's chapters. Real numbers and names are kept; each
 * view is a normal SPLIT merge group whose links point at the real chapters (no renumbering, D6).
 */
object ForkSeriesSplitPlanner {
    /** Pure check (D6): chapters exist, belong to [sourceMangaId], no repeats, and are in no other view. */
    fun validate(
        sourceMangaId: Int,
        chapterIds: List<Int>,
        otherViewChapterIds: Set<Int> = existingViewChapterIds(sourceMangaId),
    ) {
        forkRequire(chapterIds.isNotEmpty(), "EMPTY_SPLIT") { "A split view needs at least one chapter" }
        val owners = ForkMergeRepository.chaptersOfIds(chapterIds)
        forkRequire(owners.size == chapterIds.size && owners.values.all { it == sourceMangaId }, "FOREIGN_CHAPTER") {
            "Every chapter must belong to manga $sourceMangaId"
        }
        val clash = chapterIds.filter { it in otherViewChapterIds }
        forkRequire(clash.isEmpty(), "CHAPTER_IN_OTHER_VIEW") { "Chapters $clash are already in another split view" }
    }

    fun existingViewChapterIds(sourceMangaId: Int): Set<Int> =
        dbTransaction {
            ForkMergeRepository
                .splitGroupIdsOfSource(sourceMangaId)
                .mapNotNull { ForkMergeRepository.groupById(it) }
                .flatMap { it.options.includedSourceChapterIds }
                .toSet()
        }

    /** Validates all groups together (no chapter in two groups of this request) and returns counts. */
    fun preview(
        gate: ForkGate,
        sourceMangaId: Int,
        groups: List<SplitViewRequest>,
    ): List<SplitPreviewGroup> {
        gate.require(Feature.MERGE)
        val all = groups.flatMap { it.chapterIds }
        forkRequire(all.distinct().size == all.size, "CHAPTER_IN_TWO_GROUPS") { "A chapter can be in only one group" }
        val existing = existingViewChapterIds(sourceMangaId)
        groups.forEach { validate(sourceMangaId, it.chapterIds, existing) }
        return groups.map { SplitPreviewGroup(it.title, it.chapterIds.size) }
    }

    /** Creates every view, or none: the whole request is one transaction. */
    fun create(
        gate: ForkGate,
        service: ForkMergeService,
        sourceMangaId: Int,
        groups: List<SplitViewRequest>,
    ): List<MergeResult> {
        preview(gate, sourceMangaId, groups)
        return dbTransaction {
            groups.map { group ->
                val ordered =
                    ChapterTable
                        .select(ChapterTable.id, ChapterTable.sourceOrder)
                        .where { (ChapterTable.id inList group.chapterIds) and (ChapterTable.manga eq sourceMangaId) }
                        .sortedBy { it[ChapterTable.sourceOrder] }
                        .map { it[ChapterTable.id].value }
                service.create(
                    CreateMergeRequest(
                        sourceMangaIds = listOf(sourceMangaId),
                        mode = MergeMode.SPLIT,
                        title = group.title,
                        options = MergeOptions(includedSourceChapterIds = ordered),
                    ),
                )
            }
        }
    }
}
