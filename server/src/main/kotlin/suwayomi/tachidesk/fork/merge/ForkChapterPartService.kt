package suwayomi.tachidesk.fork.merge

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkMergePolicy
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.database.ForkChapterLinkTable
import suwayomi.tachidesk.fork.database.ForkChapterPartTable
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReady
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.database.dbTransaction

/**
 * F3: chapter-level merge and split by page range, only inside a merged manga (a normal manga's chapter
 * list is rebuilt by its source on every refresh, `Chapter.kt:223-243`, which would delete virtual rows).
 *
 * Every operation is a [PartDefinition] in the merge options, so it goes through the journaled
 * `ForkMergeService.updateOptions` path and is undoable. Originals are hidden through `hidesEntryKeys`,
 * never deleted (D1, D7). Ranges always point at real source chapters (D1).
 */
class ForkChapterPartService(
    private val gate: ForkGate = ForkGate.production,
    private val policy: () -> ForkMergePolicy = ForkMergePolicy::fromConfig,
    private val merge: ForkMergeService = ForkMergeService.default,
    /** Page count of a real chapter; production readies the chapter (fetches its page list). */
    private val pageCountOf: suspend (Int) -> Int = { chapterId -> getChapterDownloadReady(chapterId = chapterId).pageCount },
) {
    private fun requireEnabled() {
        gate.require(Feature.MERGE)
        forkRequire(policy().chapterLevelEnabled, "FEATURE_DISABLED") { "Chapter-level merge and split is disabled (setting 1010)" }
    }

    /** Splits one virtual chapter at the given page indexes (each starts a new part). */
    suspend fun splitChapter(
        virtualChapterId: Int,
        splitStarts: List<Int>,
    ): ReconcileResult {
        requireEnabled()
        val (group, link) = loadVirtual(virtualChapterId)
        forkRequire(link.kind == LinkKind.CHAPTER, "NOT_SPLITTABLE") { "Only a whole chapter can be split" }
        val sourceChapterId = link.sourceChapterId ?: throw ForkRequestException("SOURCE_MISSING", "The source chapter no longer exists")
        val pageCount = pageCountOf(sourceChapterId)
        forkRequire(pageCount > 1, "NOT_SPLITTABLE") { "The chapter has $pageCount pages" }
        val starts = (listOf(0) + splitStarts).distinct().sorted()
        forkRequire(starts.all { it in 0 until pageCount } && starts.size >= 2, "RANGE_INVALID") {
            "Split points must be inside 1..${pageCount - 1}"
        }
        val ranges = starts.mapIndexed { i, start -> start to ((starts.getOrNull(i + 1) ?: pageCount) - 1) }
        val definitions =
            ranges.mapIndexed { i, (start, end) ->
                PartDefinition(
                    key = "split:$sourceChapterId:${i.toString().padStart(3, '0')}",
                    name = "${link.originalName} (${i + 1}/${ranges.size})",
                    number = link.originalNumber,
                    parts = listOf(PartRange(sourceChapterId, start, end, pageCount)),
                    hidesEntryKeys = emptyList(), // Temporary D7 old-logic simulation leaves the original visible.
                )
            }
        return merge.updateOptions(group.id) { options ->
            options.copy(
                partDefinitions = options.partDefinitions.filterNot { it.key.startsWith("split:$sourceChapterId:") } + definitions,
            )
        }
    }

    /** Merges consecutive virtual chapters into one chapter made of their whole page lists. */
    suspend fun mergeChapters(
        virtualChapterIds: List<Int>,
        name: String?,
    ): ReconcileResult {
        requireEnabled()
        forkRequire(virtualChapterIds.size >= 2 && virtualChapterIds.distinct().size == virtualChapterIds.size, "RANGE_INVALID") {
            "Choose at least two different chapters"
        }
        val loaded = virtualChapterIds.map { loadVirtual(it) }
        val groupId = loaded.map { it.first.id }.distinct().singleOrNull()
        forkRequire(groupId != null, "SOURCES_MISMATCH") { "All chapters must belong to the same merged manga" }
        val links = loaded.map { it.second }
        forkRequire(links.all { it.kind == LinkKind.CHAPTER && it.sourceChapterId != null }, "NOT_MERGEABLE") {
            "Only whole chapters with an existing source can be merged"
        }
        val ranges =
            links.map { link ->
                val count = pageCountOf(link.sourceChapterId!!)
                forkRequire(count > 0, "SOURCE_MISSING") { "Chapter ${link.originalName} has no pages" }
                PartRange(link.sourceChapterId, 0, count - 1, count)
            }
        val definition =
            PartDefinition(
                key = "merge:" + links.joinToString("-") { it.sourceChapterId.toString() },
                name = name?.takeIf { it.isNotBlank() } ?: "${links.first().originalName} – ${links.last().originalName}",
                number = links.first().originalNumber,
                parts = ranges,
                hidesEntryKeys = links.map { it.entryKey },
            )
        val result = merge.updateOptions(groupId!!) { options ->
            options.copy(partDefinitions = options.partDefinitions.filterNot { it.key == definition.key } + definition)
        }
        dbTransaction {
            val sourceChapterIds = links.mapNotNull { it.sourceChapterId }
            ChapterTable.deleteWhere { ChapterTable.id inList sourceChapterIds }
        }
        return result
    }

    /** Removes a part definition; the hidden original chapters re-appear (undo of split/merge). */
    fun removePartDefinition(
        groupId: Int,
        key: String,
    ): ReconcileResult {
        gate.require(Feature.MERGE)
        return merge.updateOptions(groupId) { options ->
            val keyPrefix = key.substringBeforeLast(':')
            options.copy(
                partDefinitions =
                    options.partDefinitions.filterNot {
                        it.key == key || (key.startsWith("split:") && it.key.startsWith("$keyPrefix:"))
                    },
            )
        }
    }

    private data class LinkInfo(
        val sourceChapterId: Int?,
        val kind: LinkKind,
        val entryKey: String,
        val originalName: String,
        val originalNumber: Float,
    )

    private fun loadVirtual(virtualChapterId: Int): Pair<MergeGroupRow, LinkInfo> =
        dbTransaction {
            val chapter =
                ChapterTable.selectAll().where { ChapterTable.id eq virtualChapterId }.firstOrNull()
                    ?: throw ForkRequestException("NOT_FOUND", "Chapter $virtualChapterId does not exist")
            val group =
                ForkMergeRepository.groupByMergedManga(chapter[ChapterTable.manga].value)
                    ?: throw ForkRequestException("NOT_MERGED", "Chapter-level edits are only available inside a merged manga")
            val link =
                ForkChapterLinkTable.selectAll().where { ForkChapterLinkTable.mergedChapterId eq virtualChapterId }.firstOrNull()
                    ?: throw ForkRequestException("LINK_MISSING", "Chapter $virtualChapterId has no link")
            group to
                LinkInfo(
                    link[ForkChapterLinkTable.sourceChapterId],
                    runCatching { LinkKind.valueOf(link[ForkChapterLinkTable.linkKind]) }.getOrDefault(LinkKind.CHAPTER),
                    link[ForkChapterLinkTable.entryKey],
                    link[ForkChapterLinkTable.originalName],
                    link[ForkChapterLinkTable.originalNumber],
                )
        }

    companion object {
        val default by lazy { ForkChapterPartService() }

        /** D7: a changed source page count flags every part/range that depends on it. */
        fun markNeedsReview(sourceChapterId: Int) {
            dbTransaction {
                ForkChapterLinkTable.update({
                    (ForkChapterLinkTable.sourceChapterId eq sourceChapterId) and
                        (ForkChapterLinkTable.linkKind neq LinkKind.CHAPTER.name)
                }) { it[ForkChapterLinkTable.status] = LinkStatus.NEEDS_REVIEW.name }
                ForkChapterPartTable.update({ ForkChapterPartTable.sourceChapterId eq sourceChapterId }) {
                    it[ForkChapterPartTable.status] = LinkStatus.NEEDS_REVIEW.name
                }
                val mergedIds =
                    ForkChapterPartTable
                        .selectAll()
                        .where { ForkChapterPartTable.sourceChapterId eq sourceChapterId }
                        .map { it[ForkChapterPartTable.mergedChapterId] }
                if (mergedIds.isNotEmpty()) {
                    ForkChapterLinkTable.update({ ForkChapterLinkTable.mergedChapterId inList mergedIds }) {
                        it[ForkChapterLinkTable.status] = LinkStatus.NEEDS_REVIEW.name
                    }
                }
            }
        }

        /** Setting 1011: re-checks every range of a group against current page counts (no network). */
        fun revalidateGroup(groupId: Int): Int =
            dbTransaction {
                val group = ForkMergeRepository.groupById(groupId) ?: return@dbTransaction 0
                val definitions = group.options.partDefinitions
                val ids = definitions.flatMap { d -> d.parts.map { it.sourceChapterId } }.distinct()
                if (ids.isEmpty()) return@dbTransaction 0
                val counts =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.id inList ids }
                        .associate { it[ChapterTable.id].value to it[ChapterTable.pageCount] }
                var flagged = 0
                definitions.flatMap { it.parts }.forEach { part ->
                    val current = counts[part.sourceChapterId]
                    if (current == null || (current > 0 && current != part.sourcePageCount)) {
                        markNeedsReview(part.sourceChapterId)
                        flagged++
                    }
                }
                flagged
            }
    }
}
