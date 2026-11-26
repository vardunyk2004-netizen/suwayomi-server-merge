package suwayomi.tachidesk.fork.duplicates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure scorer tests. Written for CI; not run in the authoring sandbox. */
class ForkDuplicateScorerTest {
    private fun subject(
        id: Int,
        title: String,
        author: String? = "Author",
        chapters: Set<Float> = (1..20).map { it.toFloat() }.toSet(),
        url: String = "/series/${title.lowercase().replace(' ', '-')}",
    ) = DuplicateSubject(id, title, author, null, url, chapters, emptySet())

    @Test
    fun `same series on two sources scores strong`() {
        // Punctuation differences only: every available signal is 1.0.
        val score = ForkDuplicateScorer.score(subject(1, "The Great Hero"), subject(2, "The Great Hero!"), DuplicateWeights())
        assertTrue(score.score >= 88, "score was ${score.score}")
        assertTrue(score.strong)
        assertTrue(score.breakdown.isNotEmpty(), "breakdown is always returned")
    }

    @Test
    fun `sequel markers block auto merge`() {
        val score = ForkDuplicateScorer.score(subject(1, "Hero Academy"), subject(2, "Hero Academy Season 2"), DuplicateWeights())
        assertTrue(score.sequelBlocked)
        assertFalse(score.strong)
    }

    @Test
    fun `D18 weights from settings change the score`() {
        val a = subject(1, "Alpha Story", chapters = setOf(1f, 2f))
        val b = subject(2, "Beta Story", chapters = setOf(1f, 2f))
        val titleHeavy =
            ForkDuplicateScorer.score(
                a,
                b,
                DuplicateWeights(title = 1.0, coreTitle = 0.0, token = 0.0, alias = 0.0, url = 0.0, chapter = 0.0),
            )
        val chapterHeavy =
            ForkDuplicateScorer.score(
                a,
                b,
                DuplicateWeights(title = 0.0, coreTitle = 0.0, token = 0.0, alias = 0.0, url = 0.0, chapter = 1.0),
            )
        assertTrue(chapterHeavy.score > titleHeavy.score)
    }

    @Test
    fun `D19 scoring is deterministic and symmetric`() {
        val a = subject(1, "One Piece")
        val b = subject(2, "One-Piece")
        assertEquals(ForkDuplicateScorer.score(a, b, DuplicateWeights()), ForkDuplicateScorer.score(b, a, DuplicateWeights()))
    }

    @Test
    fun `candidate generation is not quadratic on a large synthetic library`() {
        val subjects = (1..5000).map { subject(it, "Series ${it.toString().padStart(5, '0')} Unique$it") }
        val started = System.nanoTime()
        val pairs = ForkDuplicateScorer.candidatePairs(subjects, blockCap = 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(pairs.size < 5000L * 4999 / 2 / 10, "blocking must prune most pairs, got ${pairs.size}")
        assertTrue(elapsedMs < 30_000, "took $elapsedMs ms")
    }
}
