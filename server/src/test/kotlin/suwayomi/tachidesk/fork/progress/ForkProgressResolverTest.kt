package suwayomi.tachidesk.fork.progress

import suwayomi.tachidesk.fork.ForkProgressPolicy
import suwayomi.tachidesk.fork.merge.PartRange
import suwayomi.tachidesk.graphql.types.ProgressBookmarkPolicy
import suwayomi.tachidesk.graphql.types.ProgressConflictPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure progress rules. Written for CI; not run in the authoring sandbox. */
class ForkProgressResolverTest {
    private val policy = ForkProgressPolicy()

    @Test
    fun `newest last_read_at wins and read never silently reverts`() {
        val target = ProgressState(read = true, bookmarked = false, lastPageRead = 9, lastReadAt = 100)
        val origin = ProgressState(read = false, bookmarked = false, lastPageRead = 2, lastReadAt = 200)
        val merged = ForkProgressResolver.merge(origin, ProgressSide.VIRTUAL, target, policy)

        assertEquals(2, merged.lastPageRead)
        assertEquals(200, merged.lastReadAt)
        assertTrue(merged.read, "monotonic read")

        val notMonotonic = ForkProgressResolver.merge(origin, ProgressSide.VIRTUAL, target, policy.copy(readIsMonotonic = false))
        assertEquals(false, notMonotonic.read)
    }

    @Test
    fun `older origin does not overwrite newer target`() {
        val target = ProgressState(false, false, 7, 300)
        val origin = ProgressState(false, false, 1, 100)
        assertEquals(target, ForkProgressResolver.merge(origin, ProgressSide.SOURCE, target, policy))
    }

    @Test
    fun `bookmark union and follow latest`() {
        val target = ProgressState(false, true, 0, 300)
        val origin = ProgressState(false, false, 0, 400)
        assertTrue(ForkProgressResolver.merge(origin, ProgressSide.SOURCE, target, policy).bookmarked)
        assertEquals(
            false,
            ForkProgressResolver
                .merge(origin, ProgressSide.SOURCE, target, policy.copy(bookmark = ProgressBookmarkPolicy.FOLLOW_LATEST))
                .bookmarked,
        )
    }

    @Test
    fun `virtual wins and source wins policies`() {
        val target = ProgressState(false, false, 5, 500)
        val origin = ProgressState(false, false, 1, 100)
        assertEquals(
            1,
            ForkProgressResolver
                .merge(origin, ProgressSide.VIRTUAL, target, policy.copy(conflict = ProgressConflictPolicy.VIRTUAL_WINS))
                .lastPageRead,
        )
        assertEquals(
            5,
            ForkProgressResolver
                .merge(origin, ProgressSide.VIRTUAL, target, policy.copy(conflict = ProgressConflictPolicy.SOURCE_WINS))
                .lastPageRead,
        )
    }

    @Test
    fun `D7 D29 part mapping translates pages both ways`() {
        val mapping = PartMapping(listOf(PartRange(10, 5, 9, 20), PartRange(11, 0, 2, 3)))
        assertEquals(8, mapping.virtualPageCount)
        assertEquals(10 to 5, mapping.toSource(0))
        assertEquals(11 to 1, mapping.toSource(6))
        assertNull(mapping.toSource(8))
        assertEquals(2, mapping.toVirtual(10, 7))
        assertEquals(0, mapping.toVirtual(10, 1), "page before the range maps to its start")
        assertEquals(4, mapping.toVirtual(10, 19), "page after the range maps to its end")
        assertNull(mapping.toVirtual(99, 0))
        assertTrue(mapping.completesVirtual(11, 2))
    }

    @Test
    fun `fold builds the initial virtual state from all sources`() {
        val folded =
            ForkProgressResolver.fold(
                listOf(ProgressState(true, false, 3, 100), ProgressState(false, true, 8, 200)),
                policy,
            )
        assertEquals(ProgressState(true, true, 8, 200), folded)
    }
}
