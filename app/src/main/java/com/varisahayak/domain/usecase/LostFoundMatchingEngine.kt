package com.varisahayak.domain.usecase

import com.varisahayak.domain.model.GeoPoint
import com.varisahayak.domain.model.LostFoundKind
import com.varisahayak.domain.model.LostFoundReport
import com.varisahayak.domain.model.MatchConfidence
import com.varisahayak.domain.model.MatchScore
import com.varisahayak.domain.model.MatchSignal
import com.varisahayak.domain.model.SignalKind
import com.varisahayak.domain.model.SignalStrength
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ranks Lost Person ↔ Found Person pairings.
 *
 * Three properties govern the whole design:
 *
 * 1. **A missing attribute is no signal, not a mismatch.** This is the rule that makes the
 *    system usable at all. Most real reports are partial — a parent knows their child's
 *    clothing but not their exact height, a volunteer has a photo but no name. Scoring
 *    absence as disagreement would bury every genuine pairing under the handful of
 *    complete ones.
 *
 * 2. **Faces are one signal among many, and never decisive.** The face score enters the
 *    same weighted average as clothing and timing. A pair with no photographs on either
 *    side can still reach HIGH confidence on description, place and time — which is
 *    exactly the case in §7.23.
 *
 * 3. **Every number carries its reason.** Each signal returns a sentence a volunteer can
 *    act on, because the output of this engine is a request that somebody walk somewhere
 *    and look at a child, and "89%" is not a reason to do that.
 *
 * Pure and dependency-free: no Android, no Room, no network. The weights are a proposed
 * implementation decision, not a PRD requirement, and are meant to be tuned against real
 * Wari data before production use.
 */
@Singleton
class LostFoundMatchingEngine @Inject constructor() {

    /**
     * Scores one candidate pairing.
     *
     * [faceDistance] is the cosine distance from the Python CV service, or null when
     * either side has no usable embedding. It is passed in rather than computed here
     * because embeddings are server-side only and must never reach the client.
     */
    fun score(
        lost: LostFoundReport,
        found: LostFoundReport,
        faceDistance: Double? = null,
    ): MatchScore {
        val signals = listOf(
            faceSignal(faceDistance),
            nameSignal(lost.personName, found.personName),
            ageSignal(lost.approximateAge, found.approximateAge),
            genderSignal(lost.gender, found.gender),
            clothingSignal(lost.clothingDescription, found.clothingDescription),
            languageSignal(lost.language, found.language),
            physicalSignal(lost.physicalDescription, found.physicalDescription),
            locationSignal(lost, found),
            timeSignal(lost.occurredAtEpochMillis, found.occurredAtEpochMillis),
            routeProgressionSignal(lost, found),
        )

        val overall = weightedAverage(signals)

        return MatchScore(
            overall = overall,
            confidence = confidenceFor(overall, signals),
            signals = signals,
        )
    }

    /**
     * Ranks a whole pool, highest first, dropping pairings with nothing to say.
     *
     * [faceDistances] is keyed by the opposite report's client id.
     */
    fun rank(
        subject: LostFoundReport,
        candidates: List<LostFoundReport>,
        faceDistances: Map<String, Double> = emptyMap(),
    ): List<RankedCandidate> {
        return candidates
            // Only ever pair opposite sides, and only active reports. Two Lost reports for
            // the same child are duplicates, not a match.
            .filter { it.kind == subject.kind.opposite }
            .filter { it.isActive && it.subjectType == subject.subjectType }
            .map { candidate ->
                val lost = if (subject.kind == LostFoundKind.LOST) subject else candidate
                val found = if (subject.kind == LostFoundKind.LOST) candidate else subject

                RankedCandidate(
                    report = candidate,
                    score = score(lost, found, faceDistances[candidate.clientId]),
                )
            }
            .filter { it.score.overall >= MINIMUM_SURFACING_SCORE }
            // A candidate the engine itself rates LOW is not worth a volunteer's walk.
            // Surfacing them was what made the review list read as noise: two reports of
            // the same subject type in the same window scored above the old floor on
            // almost nothing, and a reviewer who rejects ten bad rows stops reading the
            // eleventh. Anything genuinely borderline still arrives as MEDIUM.
            .filter { it.score.confidence != MatchConfidence.LOW }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.score.overall }
                    // Deterministic tie-break so the same pool always ranks the same way
                    // and a review decision can be reproduced afterwards.
                    .thenBy { it.report.clientId },
            )
    }

    data class RankedCandidate(val report: LostFoundReport, val score: MatchScore)

    // --- signals ---------------------------------------------------------------------------

    /**
     * Face similarity from the server-side cosine distance.
     *
     * `distance <= 0.40` is the threshold inherited from the reference implementation. It
     * is an engineering starting point, not a proven identity threshold, and is treated
     * here as a ranking indicator only.
     */
    private fun faceSignal(distance: Double?): MatchSignal {
        if (distance == null) {
            return MatchSignal(
                kind = SignalKind.FACE,
                strength = SignalStrength.NO_SIGNAL,
                value = 0.0,
                explanation = "No photograph available to compare",
            )
        }

        // distance 0.0 = identical, 1.0 = unrelated. Map onto a 0-1 similarity.
        val similarity = (1.0 - distance).coerceIn(0.0, 1.0)

        return when {
            distance <= FACE_MATCH_TOLERANCE -> MatchSignal(
                kind = SignalKind.FACE,
                strength = SignalStrength.SUPPORTS,
                value = similarity,
                explanation = "Photographs look similar",
            )

            distance <= FACE_WEAK_TOLERANCE -> MatchSignal(
                kind = SignalKind.FACE,
                strength = SignalStrength.NEUTRAL,
                value = similarity,
                explanation = "Photographs are not conclusive",
            )

            else -> MatchSignal(
                kind = SignalKind.FACE,
                strength = SignalStrength.CONTRADICTS,
                value = similarity,
                explanation = "Photographs do not appear to match",
            )
        }
    }

    private fun nameSignal(lost: String?, found: String?): MatchSignal {
        val a = lost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val b = found?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // A found child who cannot say their name is the normal case, not a mismatch.
        if (a == null || b == null) {
            return noSignal(SignalKind.NAME, "Name not known on both sides")
        }

        val similarity = normalisedSimilarity(a, b)

        return when {
            similarity >= 0.85 -> MatchSignal(
                SignalKind.NAME,
                SignalStrength.SUPPORTS,
                similarity,
                "Name matches",
            )

            similarity >= 0.6 -> MatchSignal(
                SignalKind.NAME,
                SignalStrength.NEUTRAL,
                similarity,
                "Name is similar",
            )

            else -> MatchSignal(
                SignalKind.NAME,
                SignalStrength.CONTRADICTS,
                similarity,
                "Name is different",
            )
        }
    }

    private fun ageSignal(lost: Int?, found: Int?): MatchSignal {
        if (lost == null || found == null) {
            return noSignal(SignalKind.AGE, "Age not recorded on both sides")
        }

        val difference = abs(lost - found)

        // Ages in these reports are estimates from a stranger's glance, so the tolerance is
        // wide on purpose. A volunteer guessing "about 8" for a 10-year-old is normal.
        return when {
            difference <= 2 -> MatchSignal(
                SignalKind.AGE,
                SignalStrength.SUPPORTS,
                1.0 - difference / 10.0,
                "Approximate age is compatible",
            )

            difference <= 5 -> MatchSignal(
                SignalKind.AGE,
                SignalStrength.NEUTRAL,
                0.5,
                "Ages differ by about $difference years",
            )

            else -> MatchSignal(
                SignalKind.AGE,
                SignalStrength.CONTRADICTS,
                0.0,
                "Ages are far apart",
            )
        }
    }

    private fun genderSignal(lost: String?, found: String?): MatchSignal {
        val a = lost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val b = found?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        if (a == null || b == null) return noSignal(SignalKind.GENDER, "Gender not recorded")

        return if (a == b) {
            MatchSignal(SignalKind.GENDER, SignalStrength.SUPPORTS, 1.0, "Gender matches")
        } else {
            MatchSignal(SignalKind.GENDER, SignalStrength.CONTRADICTS, 0.0, "Gender differs")
        }
    }

    /**
     * Clothing, compared as a bag of words.
     *
     * Word overlap rather than string similarity because these are free-text descriptions
     * written in a hurry: "yellow shirt, blue shorts" and "blue shorts and a yellow shirt"
     * are the same child described by two people.
     */
    private fun clothingSignal(lost: String?, found: String?): MatchSignal {
        val a = tokenise(lost)
        val b = tokenise(found)

        if (a.isEmpty() || b.isEmpty()) {
            return noSignal(SignalKind.CLOTHING, "Clothing not described on both sides")
        }

        val overlap = jaccard(a, b)

        return when {
            overlap >= 0.5 -> MatchSignal(
                SignalKind.CLOTHING,
                SignalStrength.SUPPORTS,
                overlap,
                "Clothing description is similar",
            )

            overlap > 0.0 -> MatchSignal(
                SignalKind.CLOTHING,
                SignalStrength.NEUTRAL,
                overlap,
                "Clothing partly matches",
            )

            else -> MatchSignal(
                SignalKind.CLOTHING,
                SignalStrength.CONTRADICTS,
                0.0,
                "Clothing description is different",
            )
        }
    }

    private fun languageSignal(lost: String?, found: String?): MatchSignal {
        val a = lost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val b = found?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        if (a == null || b == null) return noSignal(SignalKind.LANGUAGE, "Language not recorded")

        return if (a == b) {
            MatchSignal(SignalKind.LANGUAGE, SignalStrength.SUPPORTS, 1.0, "Language matches")
        } else {
            // Not a contradiction. A frightened child may not speak at all, and a
            // volunteer's guess at a language is easily wrong.
            MatchSignal(
                SignalKind.LANGUAGE,
                SignalStrength.NEUTRAL,
                0.3,
                "Recorded languages differ",
            )
        }
    }

    private fun physicalSignal(lost: String?, found: String?): MatchSignal {
        val a = tokenise(lost)
        val b = tokenise(found)

        if (a.isEmpty() || b.isEmpty()) {
            return noSignal(SignalKind.PHYSICAL, "Description not given on both sides")
        }

        val overlap = jaccard(a, b)

        return when {
            overlap >= 0.4 -> MatchSignal(
                SignalKind.PHYSICAL,
                SignalStrength.SUPPORTS,
                overlap,
                "Physical description is similar",
            )

            else -> MatchSignal(
                SignalKind.PHYSICAL,
                SignalStrength.NEUTRAL,
                overlap,
                "Physical descriptions overlap little",
            )
        }
    }

    /**
     * Geographic plausibility.
     *
     * Prefers the fixed QR location over device GPS: a sign bolted to a post is an exact
     * known point, while a phone fix in a crowd on a cheap handset can be tens of metres
     * out. Falls back through last-known to device location.
     */
    private fun locationSignal(lost: LostFoundReport, found: LostFoundReport): MatchSignal {
        val a = bestLocation(lost)
        val b = bestLocation(found)

        if (a == null || b == null) {
            return noSignal(SignalKind.LOCATION, "Location not known on both sides")
        }

        val metres = haversineMetres(a, b)

        return when {
            metres <= 500 -> MatchSignal(
                SignalKind.LOCATION,
                SignalStrength.SUPPORTS,
                1.0,
                "Found very close to where they were last seen",
            )

            metres <= 3_000 -> MatchSignal(
                SignalKind.LOCATION,
                SignalStrength.SUPPORTS,
                1.0 - (metres / 6_000.0),
                "Found ${formatDistance(metres)} from the last-seen point",
            )

            metres <= 15_000 -> MatchSignal(
                SignalKind.LOCATION,
                SignalStrength.NEUTRAL,
                0.3,
                "Found ${formatDistance(metres)} away",
            )

            else -> MatchSignal(
                SignalKind.LOCATION,
                SignalStrength.CONTRADICTS,
                0.0,
                "Found ${formatDistance(metres)} away, which is a long way along the route",
            )
        }
    }

    /**
     * Temporal plausibility.
     *
     * A found time *before* the last-seen time is the one case treated as a real
     * contradiction, because a person cannot be found before they go missing. Everything
     * else degrades gently with elapsed time.
     */
    private fun timeSignal(lostAt: Long?, foundAt: Long?): MatchSignal {
        if (lostAt == null || foundAt == null) {
            return noSignal(SignalKind.TIME, "Times not recorded on both sides")
        }

        val deltaMinutes = (foundAt - lostAt) / 60_000.0

        return when {
            // Tolerance rather than zero: clocks drift, and "last seen" is a person's
            // estimate. Only a clear inversion counts against the pairing.
            deltaMinutes < -CLOCK_TOLERANCE_MINUTES -> MatchSignal(
                SignalKind.TIME,
                SignalStrength.CONTRADICTS,
                0.0,
                "Found before they were reported missing",
            )

            deltaMinutes <= 60 -> MatchSignal(
                SignalKind.TIME,
                SignalStrength.SUPPORTS,
                1.0,
                "Found within an hour of going missing",
            )

            deltaMinutes <= 6 * 60 -> MatchSignal(
                SignalKind.TIME,
                SignalStrength.SUPPORTS,
                1.0 - (deltaMinutes / (12 * 60)),
                "Time difference is plausible",
            )

            deltaMinutes <= 24 * 60 -> MatchSignal(
                SignalKind.TIME,
                SignalStrength.NEUTRAL,
                0.25,
                "Found many hours later",
            )

            else -> MatchSignal(
                SignalKind.TIME,
                SignalStrength.NEUTRAL,
                0.1,
                "Found more than a day later",
            )
        }
    }

    /**
     * Movement along the route, which is a one-dimensional sequence rather than open
     * ground.
     *
     * The Wari moves in one direction, so a child found *ahead* of where they were lost is
     * ordinary and one found well behind is unusual. This is a better signal than
     * straight-line distance whenever route sequence numbers are known, which is why
     * §7.25 asks for it explicitly.
     */
    private fun routeProgressionSignal(
        lost: LostFoundReport,
        found: LostFoundReport,
    ): MatchSignal {
        val from = lost.routeSequence
        val to = found.routeSequence

        if (from == null || to == null) {
            return noSignal(SignalKind.ROUTE_PROGRESSION, "Route position not known")
        }

        val advance = to - from

        return when {
            advance in 0..3 -> MatchSignal(
                SignalKind.ROUTE_PROGRESSION,
                SignalStrength.SUPPORTS,
                1.0,
                if (advance == 0) {
                    "Found at the same route point"
                } else {
                    "Found $advance route ${pointWord(advance)} ahead"
                },
            )

            advance in 4..8 -> MatchSignal(
                SignalKind.ROUTE_PROGRESSION,
                SignalStrength.NEUTRAL,
                0.5,
                "Found $advance route points ahead",
            )

            // Behind is possible — a child can double back — but less likely than ahead.
            advance < 0 && advance >= -2 -> MatchSignal(
                SignalKind.ROUTE_PROGRESSION,
                SignalStrength.NEUTRAL,
                0.4,
                "Found ${abs(advance)} route ${pointWord(abs(advance))} back along the route",
            )

            else -> MatchSignal(
                SignalKind.ROUTE_PROGRESSION,
                SignalStrength.CONTRADICTS,
                0.0,
                "Route positions are far apart",
            )
        }
    }

    // --- aggregation ------------------------------------------------------------------------

    /**
     * Weighted average over the signals that produced anything.
     *
     * NO_SIGNAL entries are excluded from both numerator *and* denominator. That is what
     * makes "no photograph" cost nothing: a pair matched on five available signals is
     * scored on those five, not diluted by the five that could not be evaluated.
     */
    private fun weightedAverage(signals: List<MatchSignal>): Double {
        val usable = signals.filter { it.strength != SignalStrength.NO_SIGNAL }
        if (usable.isEmpty()) return 0.0

        var weightedSum = 0.0
        var totalWeight = 0.0

        usable.forEach { signal ->
            val weight = WEIGHTS.getValue(signal.kind)
            val contribution = when (signal.strength) {
                SignalStrength.SUPPORTS -> signal.value
                SignalStrength.NEUTRAL -> signal.value * 0.5
                SignalStrength.CONTRADICTS -> 0.0
                SignalStrength.NO_SIGNAL -> 0.0
            }
            weightedSum += contribution * weight
            totalWeight += weight
        }

        return (weightedSum / totalWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Confidence, tempered by how much evidence there actually was.
     *
     * A pairing scoring 0.9 on two signals is not the same as one scoring 0.9 on seven,
     * and presenting them identically would send volunteers chasing thin coincidences.
     */
    private fun confidenceFor(overall: Double, signals: List<MatchSignal>): MatchConfidence {
        val evidenceCount = signals.count { it.strength != SignalStrength.NO_SIGNAL }
        val contradictions = signals.count { it.strength == SignalStrength.CONTRADICTS }

        return when {
            // A single hard contradiction caps the pairing. Two demote it outright.
            contradictions >= 2 -> MatchConfidence.LOW
            overall >= 0.75 && evidenceCount >= 4 && contradictions == 0 -> MatchConfidence.HIGH
            overall >= 0.55 && evidenceCount >= 3 -> MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun noSignal(kind: SignalKind, explanation: String) =
        MatchSignal(kind, SignalStrength.NO_SIGNAL, 0.0, explanation)

    private fun bestLocation(report: LostFoundReport): GeoPoint? =
        report.lastKnownLocation ?: report.deviceLocation

    private fun tokenise(text: String?): Set<String> =
        text.orEmpty()
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val union = a.size + b.size - a.count { it in b }
        if (union == 0) return 0.0
        return a.count { it in b }.toDouble() / union
    }

    /**
     * Levenshtein-based similarity, normalised by the longer string.
     *
     * Chosen over exact matching because these names are transliterated by ear into a form
     * on a phone: "Aarav", "Arav" and "Aarv" are one child.
     */
    private fun normalisedSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 1.0
        return 1.0 - (levenshtein(a, b).toDouble() / longer)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }

    private fun haversineMetres(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)

        return 2 * earthRadius * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun formatDistance(metres: Double): String =
        if (metres < 1_000) "${metres.toInt()} m" else "${"%.1f".format(metres / 1_000)} km"

    private fun pointWord(count: Int) = if (count == 1) "point" else "points"

    companion object {
        /**
         * Inherited from the reference CV implementation. An engineering starting point for
         * ranking, **not** a proven real-world identity threshold — it must be validated
         * against representative Wari data before anyone relies on it.
         */
        const val FACE_MATCH_TOLERANCE = 0.40

        /** Beyond this, the photographs actively argue against the pairing. */
        const val FACE_WEAK_TOLERANCE = 0.60

        /** Below this a pairing is not worth a volunteer's attention. */
        const val MINIMUM_SURFACING_SCORE = 0.55

        /** Reported times are human estimates; small inversions are not contradictions. */
        const val CLOCK_TOLERANCE_MINUTES = 10

        /**
         * Relative importance of each signal. Proposed, configurable, and to be tuned
         * against real data — the PRD names the signals, not the numbers.
         *
         * Face is weighted highest but is nowhere near decisive on its own: with every
         * other signal present it is under a third of the total.
         */
        private val WEIGHTS = mapOf(
            SignalKind.FACE to 3.0,
            SignalKind.NAME to 2.0,
            SignalKind.AGE to 1.5,
            SignalKind.GENDER to 1.0,
            SignalKind.CLOTHING to 2.0,
            SignalKind.LANGUAGE to 1.0,
            SignalKind.PHYSICAL to 1.0,
            SignalKind.LOCATION to 2.0,
            SignalKind.TIME to 1.5,
            SignalKind.ROUTE_PROGRESSION to 1.5,
        )

        private val STOP_WORDS = setOf(
            "and", "the", "with", "wearing", "has", "was", "her", "his", "some", "about",
        )
    }
}
