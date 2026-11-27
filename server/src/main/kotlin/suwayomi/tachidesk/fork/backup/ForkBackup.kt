package suwayomi.tachidesk.fork.backup

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.ForkRequestException
import suwayomi.tachidesk.fork.journal.ForkJournal
import suwayomi.tachidesk.fork.lifecycle.ForkLifecycleStore
import suwayomi.tachidesk.fork.lifecycle.LifecycleState
import suwayomi.tachidesk.fork.merge.CreateMergeRequest
import suwayomi.tachidesk.fork.merge.ForkMergeRepository
import suwayomi.tachidesk.fork.merge.ForkMergeService
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.merge.MergeMode
import suwayomi.tachidesk.fork.merge.MergeOptions
import suwayomi.tachidesk.fork.merge.PartDefinition
import suwayomi.tachidesk.fork.merge.PartRange
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig

/*
 * F9 backup section. It lives in a NEW top-level field of `Backup` (proto number 1500, fork range) and
 * never in `BackupManga` (the old fork's 9001-9003 collided, D13). Everything is keyed by stable
 * (source id, url) pairs, so a backup restores into another database. Trash is not exported.
 */

@Serializable
class BackupForkData(
    @ProtoNumber(1) val formatVersion: Int = 1,
    @ProtoNumber(2) val groups: List<BackupForkGroup> = emptyList(),
)

@Serializable
class BackupForkGroup(
    @ProtoNumber(1) val mode: String = MergeMode.MIRROR.name,
    @ProtoNumber(2) val title: String = "",
    @ProtoNumber(3) val sources: List<BackupForkSource> = emptyList(),
    /** Index into [sources]; -1 = default cover. */
    @ProtoNumber(4) val coverSourceIndex: Int = -1,
    /** [PortableMergeOptions] as JSON. */
    @ProtoNumber(5) val optionsJson: String = "",
)

@Serializable
class BackupForkSource(
    @ProtoNumber(1) val sourceId: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val rank: Int = 0,
    @ProtoNumber(4) val offset: Float = 0f,
    @ProtoNumber(5) val enabled: Boolean = true,
)

/** A chapter reference that survives a database change: source manga index + chapter url. */
@Serializable
data class PortableChapterRef(
    val sourceIndex: Int,
    val url: String,
)

@Serializable
data class PortablePart(
    val chapter: PortableChapterRef,
    val pageStart: Int,
    val pageEnd: Int,
    val sourcePageCount: Int,
)

@Serializable
data class PortablePartDefinition(
    val key: String,
    val name: String,
    val number: Float,
    val parts: List<PortablePart>,
    val hidesEntryKeys: List<String>,
)

@Serializable
data class PortableMergeOptions(
    val dedupeStrategy: String? = null,
    val unnumberedMatching: String? = null,
    val chapterDisplay: String? = null,
    val offlineMode: String? = null,
    val excludedEntryKeys: Set<String> = emptySet(),
    val preferredSourceIndexByEntry: Map<String, Int> = emptyMap(),
    val includedChapters: List<PortableChapterRef> = emptyList(),
    val partDefinitions: List<PortablePartDefinition> = emptyList(),
)

object ForkBackup {
    private val logger = KotlinLogging.logger {}

    @Volatile
    internal var gate: ForkGate = ForkGate.production

    /** Export allowed: merge enabled and setting 1051 on. */
    private fun exportActive(): Boolean = gate.isEnabled(Feature.MERGE) && serverConfig.mergeBackupEnabled.value

    /**
     * Merged manga are never exported as `BackupManga` rows: their reserved source is unknown to other apps.
     * Called by `BackupMangaHandler.backup` (normal backup and SyncYomi). No SQL when merge is off.
     */
    fun excludeMergedFromExport(mangas: List<suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga>) =
        mangas

    /** SyncYomi (R10, D14): merged rows never sync while setting 1050 is on. Sources sync unchanged. */
    fun excludeMergedFromSync(mangas: List<suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga>) =
        if (serverConfig.mergeExcludeFromSync.value) excludeMergedFromExport(mangas) else mangas

    /** The fork section of a normal backup, or null (off, setting 1051 off, flattened export 1052). */
    fun export(): BackupForkData? {
        if (!exportActive() || serverConfig.mergeBackupFlattenExport.value) return null
        return dbTransaction {
            val groups =
                ForkMergeRepository.allGroups().mapNotNull { group ->
                    if (ForkLifecycleStore.stateOf(group.mangaId) == LifecycleState.SOFT_REMOVED) return@mapNotNull null
                    val sources = ForkMergeRepository.sources(group)
                    val mangaRows =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id inList (sources.map { it.sourceMangaId } + group.mangaId) }
                            .associateBy { it[MangaTable.id].value }
                    val indexOf = sources.mapIndexed { index, source -> source.sourceMangaId to index }.toMap()
                    BackupForkGroup(
                        mode = group.mode.name,
                        title = mangaRows[group.mangaId]?.get(MangaTable.title) ?: "",
                        sources =
                            sources.map {
                                val row = mangaRows.getValue(it.sourceMangaId)
                                BackupForkSource(row[MangaTable.sourceReference], row[MangaTable.url], it.rank, it.offset, it.enabled)
                            },
                        coverSourceIndex = group.coverSourceMangaId?.let { indexOf[it] } ?: -1,
                        optionsJson =
                            ForkJournal.json.encodeToString(
                                PortableMergeOptions.serializer(),
                                toPortable(group.options, indexOf),
                            ),
                    )
                }
            BackupForkData(groups = groups)
        }
    }

    data class RestoreReport(
        val restored: Int,
        val skipped: List<String>,
    )

    /**
     * Restores groups after all manga were restored. Idempotent: a group whose sources are already merged is
     * skipped (a second restore changes nothing). Groups with a missing source manga are skipped with a reason.
     */
    fun restore(data: BackupForkData?): RestoreReport {
        if (data == null || !exportActive()) return RestoreReport(0, emptyList())
        val skipped = mutableListOf<String>()
        var restored = 0
        val service = ForkMergeService(gate = gate)
        data.groups.forEach { group ->
            try {
                val ids =
                    group.sources.map { source ->
                        dbTransaction {
                            MangaTable
                                .selectAll()
                                .where { (MangaTable.sourceReference eq source.sourceId) and (MangaTable.url eq source.url) }
                                .firstOrNull()
                                ?.get(MangaTable.id)
                                ?.value
                        } ?: throw ForkRequestException(
                            "MISSING_SOURCE",
                            "Source manga ${source.url} (${source.sourceId}) is not in this library",
                        )
                    }
                if (ids.any { ForkMergeRepository.groupIdOfSource(it) != null }) {
                    skipped += "'${group.title}': already merged"
                    return@forEach
                }
                val mode = runCatching { MergeMode.valueOf(group.mode) }.getOrDefault(MergeMode.MIRROR)
                if (mode == MergeMode.SPLIT) {
                    skipped += "'${group.title}': split views are not restored automatically"
                    return@forEach
                }
                val portable =
                    group.optionsJson.takeIf { it.isNotBlank() }?.let {
                        ForkJournal.json.decodeFromString(PortableMergeOptions.serializer(), it)
                    } ?: PortableMergeOptions()
                val ordered = group.sources.zip(ids).sortedBy { it.first.rank }
                service.create(
                    CreateMergeRequest(
                        sourceMangaIds = ordered.map { it.second },
                        mode = mode,
                        title = group.title,
                        coverSourceMangaId = group.coverSourceIndex.takeIf { it in ids.indices }?.let { ids[it] },
                        offsets = ordered.associate { it.second to it.first.offset },
                        options = fromPortable(portable, ids),
                    ),
                )
                restored++
            } catch (e: ForkRequestException) {
                skipped += "'${group.title}': ${e.message}"
            } catch (e: Exception) {
                logger.warn(e) { "Restoring merge '${group.title}' failed" }
                skipped += "'${group.title}': ${e.message}"
            }
        }
        return RestoreReport(restored, skipped)
    }

    private fun toPortable(
        options: MergeOptions,
        indexOf: Map<Int, Int>,
    ): PortableMergeOptions {
        val chapterIds =
            options.includedSourceChapterIds + options.partDefinitions.flatMap { d -> d.parts.map { it.sourceChapterId } }
        val chapters =
            if (chapterIds.isEmpty()) {
                emptyMap()
            } else {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id inList chapterIds }
                    .associate { it[ChapterTable.id].value to (it[ChapterTable.manga].value to it[ChapterTable.url]) }
            }

        fun ref(chapterId: Int): PortableChapterRef? =
            chapters[chapterId]?.let { (mangaId, url) -> indexOf[mangaId]?.let { PortableChapterRef(it, url) } }

        return PortableMergeOptions(
            dedupeStrategy = options.dedupeStrategy,
            unnumberedMatching = options.unnumberedMatching,
            chapterDisplay = options.chapterDisplay,
            offlineMode = options.offlineMode,
            excludedEntryKeys = options.excludedEntryKeys,
            preferredSourceIndexByEntry = options.preferredSourceByEntry.mapNotNull { (k, v) -> indexOf[v]?.let { k to it } }.toMap(),
            includedChapters = options.includedSourceChapterIds.mapNotNull(::ref),
            partDefinitions =
                options.partDefinitions.map { d ->
                    PortablePartDefinition(
                        d.key,
                        d.name,
                        d.number,
                        d.parts.mapNotNull { p ->
                            ref(p.sourceChapterId)?.let { PortablePart(it, p.pageStart, p.pageEnd, p.sourcePageCount) }
                        },
                        d.hidesEntryKeys,
                    )
                },
        )
    }

    private fun fromPortable(
        portable: PortableMergeOptions,
        sourceIds: List<Int>,
    ): MergeOptions {
        val refs = portable.includedChapters + portable.partDefinitions.flatMap { d -> d.parts.map { it.chapter } }
        val chapterIdByRef =
            dbTransaction {
                refs
                    .distinct()
                    .mapNotNull { ref ->
                        val mangaId = sourceIds.getOrNull(ref.sourceIndex) ?: return@mapNotNull null
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga eq mangaId) and (ChapterTable.url eq ref.url) }
                            .firstOrNull()
                            ?.let { ref to it[ChapterTable.id].value }
                    }.toMap()
            }
        return MergeOptions(
            dedupeStrategy = portable.dedupeStrategy,
            unnumberedMatching = portable.unnumberedMatching,
            chapterDisplay = portable.chapterDisplay,
            offlineMode = portable.offlineMode,
            excludedEntryKeys = portable.excludedEntryKeys,
            preferredSourceByEntry =
                portable.preferredSourceIndexByEntry
                    .mapNotNull { (k, i) -> sourceIds.getOrNull(i)?.let { k to it } }
                    .toMap(),
            includedSourceChapterIds = portable.includedChapters.mapNotNull { chapterIdByRef[it] },
            partDefinitions =
                portable.partDefinitions.mapNotNull { d ->
                    val parts =
                        d.parts.mapNotNull { p ->
                            chapterIdByRef[p.chapter]?.let { PartRange(it, p.pageStart, p.pageEnd, p.sourcePageCount) }
                        }
                    if (parts.size != d.parts.size) null else PartDefinition(d.key, d.name, d.number, parts, d.hidesEntryKeys)
                },
        )
    }
}
