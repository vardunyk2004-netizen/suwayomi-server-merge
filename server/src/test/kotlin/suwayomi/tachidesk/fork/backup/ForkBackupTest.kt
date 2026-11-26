package suwayomi.tachidesk.fork.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupManga
import kotlin.test.Test
import kotlin.test.assertEquals

/** F9 backup format tests (D13, D14). Written for CI; not run in the authoring sandbox. */
@OptIn(ExperimentalSerializationApi::class)
class ForkBackupTest {
    /** The top-level shape a stock reader knows (no field 1500). */
    @Serializable
    class StockLikeBackup(
        @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
        @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
    )

    private val forkData =
        BackupForkData(
            groups =
                listOf(
                    BackupForkGroup(
                        mode = "MIRROR",
                        title = "Merged",
                        sources = listOf(BackupForkSource(42L, "/a", 0, 0f, true), BackupForkSource(43L, "/b", 1, 0f, true)),
                        coverSourceIndex = 1,
                        optionsJson = "{}",
                    ),
                ),
        )

    @Test
    fun `fork section round trips in field 1500`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(backupCategories = listOf(BackupCategory("Reading", 1)), forkData = forkData),
            )
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(
            "Merged",
            decoded.forkData!!
                .groups
                .single()
                .title,
        )
        assertEquals(
            listOf(42L, 43L),
            decoded.forkData!!
                .groups
                .single()
                .sources
                .map { it.sourceId },
        )
    }

    @Test
    fun `D13 stock decoding ignores the fork field`() {
        val bytes =
            ProtoBuf.encodeToByteArray(
                Backup.serializer(),
                Backup(backupCategories = listOf(BackupCategory("Reading", 1)), forkData = forkData),
            )
        val stock = ProtoBuf.decodeFromByteArray(StockLikeBackup.serializer(), bytes)
        assertEquals(listOf("Reading"), stock.backupCategories.map { it.name })
    }

    @Test
    fun `D14 merged rows are excluded from export and sync`() {
        val merged = BackupManga(source = ForkMergedSource.ID, url = "fork-merge://group/x", title = "Merged")
        val normal = BackupManga(source = 42L, url = "/a", title = "Normal")
        assertEquals(listOf("Normal"), ForkBackup.excludeMergedFromExport(listOf(merged, normal)).map { it.title })
    }
}
