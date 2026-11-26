package suwayomi.tachidesk.fork.duplicates

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.fork.ForkClock
import suwayomi.tachidesk.fork.ForkFeatureAvailability.Feature
import suwayomi.tachidesk.fork.ForkGate
import suwayomi.tachidesk.fork.database.ForkDuplicateIgnoredPairTable
import suwayomi.tachidesk.fork.database.ForkDuplicateSignatureTable
import suwayomi.tachidesk.fork.forkRequire
import suwayomi.tachidesk.fork.lifecycle.ForkVisibility
import suwayomi.tachidesk.fork.merge.ForkMergePlanner
import suwayomi.tachidesk.fork.merge.ForkMergedSource
import suwayomi.tachidesk.fork.require
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.server.database.dbTransaction
import suwayomi.tachidesk.server.serverConfig

/** Weights and thresholds from settings 1061-1068 and 1100-1107 only (D18: one source of truth). */
data class DuplicateWeights(
    val title: Double = 0.25,
    val coreTitle: Double = 0.20,
    val token: Double = 0.15,
    val alias: Double = 0.10,
    val url: Double = 0.10,
    val chapter: Double = 0.10,
    val thumbnail: Double = 0.05,
    val tracker: Double = 0.05,
    val strongThreshold: Int = 88,
    val minThreshold: Int = 74,
    val useThumbnailHash: Boolean = false,
    val useTrackerMatching: Boolean = true,
    val blockSequels: Boolean = true,
) {
    companion object {
        fun fromConfig() =
            DuplicateWeights(
                title = serverConfig.duplicatesWeightTitle.value,
                coreTitle = serverConfig.duplicatesWeightCoreTitle.value,
                token = serverConfig.duplicatesWeightToken.value,
                alias = serverConfig.duplicatesWeightAlias.value,
                url = serverConfig.duplicatesWeightUrl.value,
                chapter = serverConfig.duplicatesWeightChapter.value,
                thumbnail = serverConfig.duplicatesWeightThumbnail.value,
                tracker = serverConfig.duplicatesWeightTracker.value,
                strongThreshold = serverConfig.duplicatesStrongThreshold.value,
                minThreshold = serverConfig.duplicatesMinThreshold.value,
                useThumbnailHash = serverConfig.duplicatesUseThumbnailHash.value,
                useTrackerMatching = serverConfig.duplicatesUseTrackerMatching.value,
                blockSequels = serverConfig.duplicatesBlockSequels.value,
            )
    }
}

/** Everything the scorer needs about one manga (no database access in the scorer). */
data class DuplicateSubject(
    val mangaId: Int,
    val title: String,
    val author: String?,
    val artist: String?,
    val url: String,
    val chapterNumbers: Set<Float>,
    val trackerKeys: Set<String>,
)

data class SignalScore(
    val signal: String,
    val weight: Double,
    val similarity: Double,
)

data class DuplicateScore(
    val a: Int,
    val b: Int,
    /** 0-100. */
    val score: Int,
    val breakdown: List<SignalScore>,
    val sequelBlocked: Boolean,
    val strong: Boolean,
)

/**
 * Pure duplicate scorer (F10). The score is the weighted mean of the available signals (a disabled signal,
 * e.g. thumbnail hashing, is left out instead of counting as 0). The breakdown is always returned.
 */
object ForkDuplicateScorer {
    const val ALGORITHM_VERSION = 1

    private val sequelPattern =
        Regex(
            "(?i)\\b(season|s|part|vol(ume)?|book|arc|cour)\\s*\\.?\\s*(\\d+|[ivx]+)\\b|\\b(\\d+)(st|nd|rd|th)\\s+season\\b|\\b(ii|iii|iv)\\b",
        )
    private val bracketPattern = Regex("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")

    fun normalizedTitle(title: String): String = ForkMergePlanner.normalize(title)

    /** Title without brackets and sequel markers ("Foo (Official) Season 2" -> "foo"). */
    fun coreTitle(title: String): String = ForkMergePlanner.normalize(sequelPattern.replace(bracketPattern.replace(title, " "), " "))

    fun sequelMarker(title: String): String? =
        sequelPattern
            .findAll(title)
            .map { ForkMergePlanner.normalize(it.value) }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|")

    fun tokens(title: String): Set<String> = normalizedTitle(title).split(' ').filter { it.length > 1 }.toSet()

    fun score(
        a: DuplicateSubject,
        b: DuplicateSubject,
        weights: DuplicateWeights,
    ): DuplicateScore {
        val signals = mutableListOf<SignalScore>()
        signals += SignalScore("title", weights.title, similarity(normalizedTitle(a.title), normalizedTitle(b.title)))
        val coreA = coreTitle(a.title)
        val coreB = coreTitle(b.title)
        signals += SignalScore("coreTitle", weights.coreTitle, if (coreA.isNotEmpty() && coreA == coreB) 1.0 else similarity(coreA, coreB))
        signals += SignalScore("token", weights.token, jaccard(tokens(a.title), tokens(b.title)))
        val people = { s: DuplicateSubject ->
            listOfNotNull(s.author, s.artist)
                .map(ForkMergePlanner::normalize)
                .filter { it.isNotBlank() }
                .toSet()
        }
        val peopleA = people(a)
        val peopleB = people(b)
        if (peopleA.isNotEmpty() && peopleB.isNotEmpty()) signals += SignalScore("alias", weights.alias, jaccard(peopleA, peopleB))
        signals += SignalScore("url", weights.url, similarity(slug(a.url), slug(b.url)))
        if (a.chapterNumbers.isNotEmpty() && b.chapterNumbers.isNotEmpty()) {
            signals += SignalScore("chapter", weights.chapter, jaccard(a.chapterNumbers, b.chapterNumbers))
        }
        if (weights.useTrackerMatching && a.trackerKeys.isNotEmpty() && b.trackerKeys.isNotEmpty()) {
            signals += SignalScore("tracker", weights.tracker, if (a.trackerKeys.intersect(b.trackerKeys).isNotEmpty()) 1.0 else 0.0)
        }
        // Thumbnail hashing is not implemented in this run; it is never counted (documented gap).

        val totalWeight = signals.sumOf { it.weight }
        val raw = if (totalWeight <= 0.0) 0.0 else signals.sumOf { it.weight * it.similarity } / totalWeight
        val score = (raw * 100).toInt().coerceIn(0, 100)
        val blocked = weights.blockSequels && sequelMarker(a.title) != sequelMarker(b.title)
        val (low, high) = if (a.mangaId <= b.mangaId) a.mangaId to b.mangaId else b.mangaId to a.mangaId
        return DuplicateScore(low, high, score, signals, blocked, score >= weights.strongThreshold && !blocked)
    }

    private fun slug(url: String): String =
        ForkMergePlanner.normalize(url.substringAfterLast('/').ifBlank { url.trimEnd('/').substringAfterLast('/') })

    fun <T> jaccard(
        a: Set<T>,
        b: Set<T>,
    ): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    /** 1 - normalized Levenshtein distance. */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /**
     * Candidate pairs by blocking: subjects sharing a core-title prefix (first 4 chars) or a token are compared;
     * each block is capped so a huge library never becomes a quadratic comparison.
     */
    fun candidatePairs(
        subjects: List<DuplicateSubject>,
        blockCap: Int = 200,
    ): Set<Pair<Int, Int>> {
        val blocks = HashMap<String, MutableList<Int>>()
        subjects.forEach { s ->
            val core = coreTitle(s.title)
            if (core.length >= 2) blocks.getOrPut("c:" + core.take(4)) { mutableListOf() }.add(s.mangaId)
            tokens(s.title).filter { it.length >= 4 }.forEach { blocks.getOrPut("t:$it") { mutableListOf() }.add(s.mangaId) }
        }
        val pairs = HashSet<Pair<Int, Int>>()
        blocks.values.forEach { ids ->
            val capped = ids.distinct().take(blockCap)
            for (i in capped.indices) for (j in i + 1 until capped.size) {
                val (x, y) = if (capped[i] < capped[j]) capped[i] to capped[j] else capped[j] to capped[i]
                pairs += x to y
            }
        }
        return pairs
    }
}

/** DB side of F10: subjects, signatures, ignored pairs, candidates. */
class ForkDuplicateService(
    private val gate: ForkGate = ForkGate.production,
    private val weights: () -> DuplicateWeights = DuplicateWeights::fromConfig,
) {
    fun candidates(
        mangaId: Int? = null,
        limit: Int = 100,
    ): List<DuplicateScore> {
        gate.require(Feature.DUPLICATES)
        return dbTransaction {
            val subjects = librarySubjects()
            val byId = subjects.associateBy { it.mangaId }
            val ignored = ignoredPairs()
            val w = weights()
            val pairs =
                if (mangaId != null) {
                    forkRequire(byId.containsKey(mangaId), "NOT_IN_LIBRARY") { "Manga $mangaId is not a visible library entry" }
                    subjects.filter { it.mangaId != mangaId }.map { minOf(it.mangaId, mangaId) to maxOf(it.mangaId, mangaId) }.toSet()
                } else {
                    ForkDuplicateScorer.candidatePairs(subjects)
                }
            pairs
                .asSequence()
                .filterNot { it in ignored }
                .map { (a, b) -> ForkDuplicateScorer.score(byId.getValue(a), byId.getValue(b), w) }
                .filter { it.score >= w.minThreshold }
                .sortedByDescending { it.score }
                .take(limit.coerceIn(1, 1000))
                .toList()
        }
    }

    /** Auto-merge suggestions: strong, not sequel-blocked (setting 1068 must be on). */
    fun suggestions(limit: Int = 50): List<DuplicateScore> {
        gate.require(Feature.DUPLICATES)
        forkRequire(serverConfig.duplicatesAutoSuggestMerges.value, "FEATURE_DISABLED") { "Merge suggestions are disabled (setting 1068)" }
        return candidates(null, limit * 4).filter { it.strong }.take(limit)
    }

    fun ignorePair(
        a: Int,
        b: Int,
        ignore: Boolean,
    ) {
        gate.require(Feature.DUPLICATES)
        val (low, high) = if (a <= b) a to b else b to a
        dbTransaction {
            ForkDuplicateIgnoredPairTable.deleteWhere {
                (ForkDuplicateIgnoredPairTable.mangaIdLow eq low) and (ForkDuplicateIgnoredPairTable.mangaIdHigh eq high)
            }
            if (ignore) {
                ForkDuplicateIgnoredPairTable.insert {
                    it[ForkDuplicateIgnoredPairTable.mangaIdLow] = low
                    it[ForkDuplicateIgnoredPairTable.mangaIdHigh] = high
                    it[ForkDuplicateIgnoredPairTable.createdAt] = ForkClock.now()
                }
            }
        }
    }

    /** Recomputes stored signatures (algorithm version recorded; stale versions are rewritten). */
    fun refreshSignatures(): Int {
        gate.require(Feature.DUPLICATES)
        return dbTransaction {
            val subjects = librarySubjects()
            val now = ForkClock.now()
            subjects.forEach { s ->
                ForkDuplicateSignatureTable.upsert {
                    it[ForkDuplicateSignatureTable.mangaId] = s.mangaId
                    it[ForkDuplicateSignatureTable.algorithmVersion] = ForkDuplicateScorer.ALGORITHM_VERSION
                    it[ForkDuplicateSignatureTable.normalizedTitle] = ForkDuplicateScorer.normalizedTitle(s.title).take(512)
                    it[ForkDuplicateSignatureTable.coreTitle] = ForkDuplicateScorer.coreTitle(s.title).take(512)
                    it[ForkDuplicateSignatureTable.tokens] = ForkDuplicateScorer.tokens(s.title).sorted().joinToString(" ")
                    it[ForkDuplicateSignatureTable.sequelMarker] = ForkDuplicateScorer.sequelMarker(s.title)?.take(64)
                    it[ForkDuplicateSignatureTable.computedAt] = now
                }
            }
            subjects.size
        }
    }

    private fun ignoredPairs(): Set<Pair<Int, Int>> =
        ForkDuplicateIgnoredPairTable
            .selectAll()
            .map { it[ForkDuplicateIgnoredPairTable.mangaIdLow] to it[ForkDuplicateIgnoredPairTable.mangaIdHigh] }
            .toSet()

    /** Visible library manga (merged sources and trash excluded), never merged manga themselves. */
    private fun librarySubjects(): List<DuplicateSubject> {
        val query = MangaTable.selectAll().where { (MangaTable.inLibrary eq true) }
        ForkVisibility.applyTo(query)
        val rows = query.toList().filter { it[MangaTable.sourceReference] != ForkMergedSource.ID }
        val ids = rows.map { it[MangaTable.id].value }
        if (ids.isEmpty()) return emptyList()
        val numbers =
            ChapterTable
                .select(ChapterTable.manga, ChapterTable.chapter_number)
                .where { ChapterTable.manga inList ids }
                .groupBy({ it[ChapterTable.manga].value }, { it[ChapterTable.chapter_number] })
        val trackers =
            TrackRecordTable
                .selectAll()
                .where { TrackRecordTable.mangaId inList ids }
                .groupBy({ it[TrackRecordTable.mangaId].value }, { "${it[TrackRecordTable.trackerId]}:${it[TrackRecordTable.remoteId]}" })
        return rows.map { row ->
            val id = row[MangaTable.id].value
            DuplicateSubject(
                mangaId = id,
                title = row[MangaTable.title],
                author = row[MangaTable.author],
                artist = row[MangaTable.artist],
                url = row[MangaTable.url],
                chapterNumbers = numbers[id].orEmpty().filter { it >= 0f }.toSet(),
                trackerKeys = trackers[id].orEmpty().toSet(),
            )
        }
    }

    companion object {
        val default by lazy { ForkDuplicateService() }
    }
}
