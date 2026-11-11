package suwayomi.tachidesk.fork.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForkLifecyclePlannerTest {
    @Test
    fun `D3 unmerge deletes only virtual rows and requires progress write back`() {
        val plan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.UNMERGE,
                    mergedMangaId = 100,
                    sourceMangaIds = listOf(1, 2),
                    virtualChapterIds = listOf(101, 102),
                ),
            )

        assertEquals(listOf(101, 102), plan.virtualChapterIdsToRemove)
        assertEquals(emptyList(), plan.sourceMangaIdsToRemove)
        assertTrue(plan.requiresProgressWriteBack)
        assertTrue(plan.journal.reversible)
    }

    @Test
    fun `D4 removing a source restores only that source and rejects a one-source merge`() {
        val removePlan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.REMOVE_SOURCE_FROM_MERGE,
                    mergedMangaId = 100,
                    sourceMangaIds = listOf(1, 2, 3),
                    sourceMangaIdToRemove = 2,
                ),
            )

        assertEquals(listOf(2), removePlan.sourceMangaIdsToRestore)
        assertEquals(emptyList(), removePlan.sourceMangaIdsToRemove)
        assertTrue(removePlan.journal.reversible)

        assertFailsWith<IllegalArgumentException> {
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.REMOVE_SOURCE_FROM_MERGE,
                    mergedMangaId = 100,
                    sourceMangaIds = listOf(1, 2),
                    sourceMangaIdToRemove = 2,
                ),
            )
        }
    }

    @Test
    fun `D5 soft removal does not restore independently removed sources`() {
        val plan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.SOFT_REMOVE,
                    mergedMangaId = 100,
                    sourceMangaIds = listOf(1, 2),
                    restoreSources = false,
                ),
            )

        assertEquals(LifecycleState.SOFT_REMOVED, plan.targetLifecycleState)
        assertEquals(emptyList(), plan.sourceMangaIdsToRestore)
        assertEquals(emptyList(), plan.sourceMangaIdsToRemove)
        assertTrue(plan.journal.reversible)
    }

    @Test
    fun `D16 non-purge removal never authorizes source download deletion`() {
        val plan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.SOFT_REMOVE,
                    mergedMangaId = 100,
                    sourceMangaIds = listOf(1, 2),
                    sourceDownloadIds = listOf(10, 11),
                    materializedDownloadIds = listOf(20),
                    deleteMaterializedDownloads = true,
                ),
            )

        assertEquals(emptyList(), plan.sourceDownloadIdsToDelete)
        assertEquals(listOf(20), plan.materializedDownloadIdsToDelete)
    }

    @Test
    fun `D22 soft removal creates a reversible lifecycle journal record`() {
        val plan =
            ForkLifecyclePlanner.plan(
                LifecycleRequest(
                    action = LifecycleAction.SOFT_REMOVE,
                    mergedMangaId = 100,
                ),
            )

        assertEquals(LifecycleAction.SOFT_REMOVE, plan.journal.action)
        assertEquals(LifecycleJournalState.PLANNED, plan.journal.state)
        assertTrue(plan.journal.reversible)
    }
}
