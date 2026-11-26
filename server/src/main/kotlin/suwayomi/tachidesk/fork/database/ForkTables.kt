package suwayomi.tachidesk.fork.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.columns.unlimitedVarchar

/*
 * Exposed mappings of the fork tables created by F0001-F0003. These objects never create or alter
 * schema (the fork migration runner owns DDL); they only give typed access. Plain Int columns are
 * used for references: the database enforces the foreign keys and ON DELETE rules.
 */

object ForkMergeGroupTable : IntIdTable("fork_merge_group") {
    val mangaId = integer("manga_id")
    val mode = varchar("mode", 32)
    val options = unlimitedVarchar("options").nullable()
    val coverSourceMangaId = integer("cover_source_manga_id").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

object ForkMergeSourceTable : IntIdTable("fork_merge_source") {
    val groupId = integer("group_id")
    val sourceMangaId = integer("source_manga_id")
    val rank = integer("rank")
    val chapterOffset = float("chapter_offset")
    val enabled = bool("enabled")
}

object ForkChapterLinkTable : IntIdTable("fork_chapter_link") {
    val mergedChapterId = integer("merged_chapter_id")
    val sourceChapterId = integer("source_chapter_id").nullable()
    val sourceMangaId = integer("source_manga_id")
    val rank = integer("rank")
    val originalName = varchar("original_name", 2048)
    val originalUrl = varchar("original_url", 2048)
    val status = varchar("status", 32)
    val originalNumber = float("original_number")
    val originalScanlator = varchar("original_scanlator", 256).nullable()
    val originalUploadDate = long("original_upload_date")
    val entryKey = varchar("entry_key", 512)
    val linkKind = varchar("link_kind", 16)
    val pageStart = integer("page_start").nullable()
    val pageEnd = integer("page_end").nullable()
    val sourcePageCountSnapshot = integer("source_page_count_snapshot")
}

object ForkChapterFallbackTable : IntIdTable("fork_chapter_fallback") {
    val linkId = integer("link_id")
    val sourceChapterId = integer("source_chapter_id").nullable()
    val sourceMangaId = integer("source_manga_id")
    val rank = integer("rank")
}

object ForkChapterPartTable : IntIdTable("fork_chapter_part") {
    val mergedChapterId = integer("merged_chapter_id")
    val partIndex = integer("part_index")
    val sourceChapterId = integer("source_chapter_id").nullable()
    val sourceMangaId = integer("source_manga_id")
    val pageStart = integer("page_start")
    val pageEnd = integer("page_end")
    val sourcePageCountSnapshot = integer("source_page_count_snapshot")
    val status = varchar("status", 32)
}

object ForkSplitSourceTable : IntIdTable("fork_split_source") {
    val groupId = integer("group_id")
    val sourceMangaId = integer("source_manga_id")
}

object ForkMangaLifecycleTable : Table("fork_manga_lifecycle") {
    val mangaId = integer("manga_id")
    val state = varchar("state", 32)
    val reason = varchar("reason", 2048).nullable()
    val ownerGroupId = integer("owner_group_id").nullable()
    val changedAt = long("changed_at")

    override val primaryKey = PrimaryKey(mangaId)
}

object ForkJournalTable : IntIdTable("fork_merge_journal") {
    val groupId = integer("group_id").nullable()
    val mangaId = integer("manga_id").nullable()
    val operation = varchar("operation", 64)
    val state = varchar("state", 32)
    val payload = unlimitedVarchar("payload").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val reversible = bool("reversible")
    val undoneAt = long("undone_at").nullable()
    val errorMessage = varchar("error_message", 2048).nullable()
}

object ForkDuplicateSignatureTable : Table("fork_duplicate_signature") {
    val mangaId = integer("manga_id")
    val algorithmVersion = integer("algorithm_version")
    val normalizedTitle = varchar("normalized_title", 512)
    val coreTitle = varchar("core_title", 512)
    val tokens = unlimitedVarchar("tokens")
    val sequelMarker = varchar("sequel_marker", 64).nullable()
    val computedAt = long("computed_at")

    override val primaryKey = PrimaryKey(mangaId)
}

object ForkDuplicateIgnoredPairTable : IntIdTable("fork_duplicate_ignored_pair") {
    val mangaIdLow = integer("manga_id_low")
    val mangaIdHigh = integer("manga_id_high")
    val createdAt = long("created_at")
}

object ForkMaintenanceReportTable : IntIdTable("fork_maintenance_report") {
    val kind = varchar("kind", 32)
    val dryRun = bool("dry_run")
    val payload = unlimitedVarchar("payload")
    val createdAt = long("created_at")
}

/** Every fork-owned table, in an order safe for test cleanup (children first). */
val forkTablesChildFirst: List<Table> =
    listOf(
        ForkChapterFallbackTable,
        ForkChapterPartTable,
        ForkChapterLinkTable,
        ForkMergeSourceTable,
        ForkSplitSourceTable,
        ForkMangaLifecycleTable,
        ForkJournalTable,
        ForkMergeGroupTable,
        ForkDuplicateSignatureTable,
        ForkDuplicateIgnoredPairTable,
        ForkMaintenanceReportTable,
    )
