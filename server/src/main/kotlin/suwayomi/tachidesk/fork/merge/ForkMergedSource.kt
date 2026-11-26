package suwayomi.tachidesk.fork.merge

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import suwayomi.tachidesk.manga.impl.util.source.GetSource

/**
 * R9 / decision 2: the reserved virtual source that owns merged manga rows.
 *
 * It is NOT `LocalSource` (id 0) and has no `source`/`extension` table rows, so it never appears in
 * the source list. It is registered in memory with [GetSource.registerSource]; because
 * `GetSource.unregisterAllSources()` keeps only id 0 (`GetSource.kt:84-90`), every fork entry point
 * calls [ensureRegistered] (a map lookup, no SQL).
 *
 * Merged manga never use the stock source pipeline for chapter lists: `Manga.updateMangaAndChapters`
 * hands them to [ForkMergeHooks.refreshMerged], which re-plans from the sources' stored chapters.
 */
class ForkMergedSource : Source {
    override val id: Long = ID
    override val name: String = NAME
    override val lang: String = "other"
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.source.model.FilterList,
    ): MangasPage = MangasPage(emptyList(), false)

    /** Details/chapters are owned by the fork; the stock caller receives its own input back unchanged. */
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(manga, chapters)

    /** Resolves a virtual chapter to its real source chapter(s); a missing source is an explicit error (D8). */
    override suspend fun getPageList(chapter: SChapter): List<Page> = ForkPageResolver.pageListForVirtualUrl(chapter.url)

    companion object {
        /** ASCII "SWMERGED"; positive, never 0 (LocalSource). */
        const val ID: Long = 0x5357_4D45_5247_4544L
        const val NAME = "Merged"

        private val instance by lazy { ForkMergedSource() }

        fun ensureRegistered() {
            GetSource.registerSource(ID to instance)
        }
    }
}
