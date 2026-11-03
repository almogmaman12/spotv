package com.almog.spotv.lyrics.kinetic

import com.almog.spotv.lyrics.model.LyricTrack
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---- beat and energy -----------------------------------------------------------------------

/** How loud / busy the song is over time, 0..1, in fixed-size buckets. */
class EnergyCurve(private val buckets: FloatArray, private val bucketMs: Long) {
    fun at(posMs: Long): Float {
        if (buckets.isEmpty()) return 0.5f
        val x = (posMs.coerceAtLeast(0L).toFloat() / bucketMs)
        val i = x.toInt().coerceIn(0, buckets.lastIndex)
        val j = (i + 1).coerceAtMost(buckets.lastIndex)
        val f = (x - i).coerceIn(0f, 1f)
        return buckets[i] + (buckets[j] - buckets[i]) * f
    }

    companion object {
        const val BUCKET_MS = 1000L

        val FLAT = EnergyCurve(floatArrayOf(0.5f), BUCKET_MS)

        /** Busier stretches of lyrics (more words per second) read as higher energy. */
        fun fromLyrics(track: LyricTrack): EnergyCurve {
            val starts = track.lines.flatMap { line ->
                if (line.words.isNotEmpty()) line.words.map { it.startTimeMs } else listOf(line.startTimeMs)
            }
            if (starts.isEmpty()) return FLAT
            val size = (starts.max() / BUCKET_MS).toInt() + 3
            val raw = FloatArray(size)
            for (t in starts) raw[(t / BUCKET_MS).toInt().coerceIn(0, size - 1)] += 1f
            return smoothed(raw)
        }

        /** From Spotify's sections: louder sections are higher. */
        fun fromSections(startsSec: List<Float>, durationsSec: List<Float>, loudnessDb: List<Float>): EnergyCurve {
            if (startsSec.isEmpty()) return FLAT
            val end = (startsSec.last() + durationsSec.last())
            val size = (end * 1000f / BUCKET_MS).toInt() + 2
            val lo = loudnessDb.min()
            val hi = loudnessDb.max()
            val span = (hi - lo).coerceAtLeast(1f)
            val raw = FloatArray(size)
            for (i in startsSec.indices) {
                val from = (startsSec[i] * 1000f / BUCKET_MS).toInt().coerceIn(0, size - 1)
                val to = ((startsSec[i] + durationsSec[i]) * 1000f / BUCKET_MS).toInt().coerceIn(from, size - 1)
                val v = 0.2f + 0.8f * ((loudnessDb[i] - lo) / span)
                for (b in from..to) raw[b] = v
            }
            return smoothedValues(raw)
        }

        private fun smoothed(raw: FloatArray): EnergyCurve {
            val avg = FloatArray(raw.size)
            for (i in raw.indices) {
                var sum = 0f
                var n = 0
                for (k in -3..3) {
                    val j = i + k
                    if (j in raw.indices) {
                        sum += raw[j]
                        n++
                    }
                }
                avg[i] = sum / n
            }
            val peak = max(avg.max(), 3f)
            for (i in avg.indices) avg[i] = kotlin.math.sqrt((avg[i] / peak).coerceIn(0f, 1f))
            return EnergyCurve(avg, BUCKET_MS)
        }

        private fun smoothedValues(raw: FloatArray): EnergyCurve {
            val avg = FloatArray(raw.size)
            for (i in raw.indices) {
                var sum = 0f
                var n = 0
                for (k in -2..2) {
                    val j = i + k
                    if (j in raw.indices) {
                        sum += raw[j]
                        n++
                    }
                }
                avg[i] = sum / n
            }
            return EnergyCurve(avg, BUCKET_MS)
        }
    }
}

/** Where the beats are: [pulse] is 1 right on a beat and dies away until the next. */
interface BeatClock {
    val periodMs: Float
    fun pulse(posMs: Long): Float
    fun energy(posMs: Long): Float
}

/** No beat information: no pulse, middling energy. */
class FlatBeatClock(private val curve: EnergyCurve = EnergyCurve.FLAT) : BeatClock {
    override val periodMs: Float = 500f
    override fun pulse(posMs: Long): Float = 0f
    override fun energy(posMs: Long): Float = curve.at(posMs)
}

/** A steady tempo: a beat every [periodMs], the first at [offsetMs]. */
class GridBeatClock(
    override val periodMs: Float,
    private val offsetMs: Float,
    private val curve: EnergyCurve,
) : BeatClock {
    override fun pulse(posMs: Long): Float {
        val rel = (posMs - offsetMs).toDouble()
        val phase = (((rel % periodMs) + periodMs) % periodMs / periodMs).toFloat()
        return exp(-phase * 5f)
    }

    override fun energy(posMs: Long): Float = curve.at(posMs)
}

/** Real beat times (from Spotify's audio analysis). */
class ListBeatClock(private val beatsMs: LongArray, private val curve: EnergyCurve) : BeatClock {
    override val periodMs: Float =
        if (beatsMs.size >= 2) ((beatsMs.last() - beatsMs.first()).toFloat() / (beatsMs.size - 1)).coerceIn(250f, 1000f) else 500f

    override fun pulse(posMs: Long): Float {
        if (beatsMs.isEmpty()) return 0f
        var lo = 0
        var hi = beatsMs.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (beatsMs[mid] <= posMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return 0f
        val since = (posMs - beatsMs[found]).toFloat()
        if (since > periodMs * 2f) return 0f
        return exp(-since / (periodMs * 0.2f))
    }

    override fun energy(posMs: Long): Float = curve.at(posMs)
}

object Beats {
    /**
     * Estimates the tempo from when words start: sung words land on beats and their subdivisions, so
     * onsets line up with the right period. Returns (period ms, offset ms), or null when nothing
     * lines up well enough to trust.
     */
    fun estimateGrid(onsetsMs: LongArray, knownBpm: Float? = null): Pair<Float, Float>? {
        if (onsetsMs.size < 16) return null
        fun coherence(period: Double): Triple<Double, Double, Double> {
            var re = 0.0
            var im = 0.0
            for (t in onsetsMs) {
                val a = 2.0 * PI * t / period
                re += cos(a)
                im += sin(a)
            }
            val n = onsetsMs.size.toDouble()
            return Triple(hypot(re, im) / n, re, im)
        }

        var bestPeriod = 0.0
        var bestR = 0.0
        var bestRe = 0.0
        var bestIm = 0.0
        if (knownBpm != null && knownBpm in 50f..220f) {
            bestPeriod = 60000.0 / knownBpm
            val (r, re, im) = coherence(bestPeriod)
            bestR = r; bestRe = re; bestIm = im
        } else {
            var p = 340.0
            while (p <= 820.0) {
                val (r, re, im) = coherence(p)
                if (r > bestR) {
                    bestR = r; bestPeriod = p; bestRe = re; bestIm = im
                }
                p += 3.0
            }
            // Refine around the best.
            var q = bestPeriod - 3.0
            while (q <= bestPeriod + 3.0) {
                val (r, re, im) = coherence(q)
                if (r > bestR) {
                    bestR = r; bestPeriod = q; bestRe = re; bestIm = im
                }
                q += 0.5
            }
            if (bestR < 0.2) return null
        }
        val angle = atan2(bestIm, bestRe)
        val offset = ((angle / (2.0 * PI)) * bestPeriod + bestPeriod) % bestPeriod
        return bestPeriod.toFloat() to offset.toFloat()
    }

    /** The best beat clock that can be had from the lyrics alone. */
    fun fromLyrics(track: LyricTrack): BeatClock {
        val curve = EnergyCurve.fromLyrics(track)
        val onsets = track.lines.flatMap { line ->
            if (line.words.isNotEmpty()) line.words.map { it.startTimeMs } else listOf(line.startTimeMs)
        }.distinct().sorted().toLongArray()
        val grid = estimateGrid(onsets, track.bpm) ?: return FlatBeatClock(curve)
        return GridBeatClock(grid.first, grid.second, curve)
    }
}

// ---- word motion ------------------------------------------------------------------------------

enum class Motion {
    /** An ordinary word: a quick pop as it is sung. */
    Normal,

    /** A held note: it stretches and glows for as long as it is sung. */
    Held,

    /** A shouted word (ALL CAPS or with !): pops harder and shakes. */
    Shake,
}

/** The look of one word at one moment. [dyEm] is a vertical shift in font-size units. */
class WordPose {
    var scaleX = 1f
    var scaleY = 1f
    var dyEm = 0f
    var rotationDeg = 0f
    var glow = 0f
}

object WordMotion {
    private const val PRE_ROLL_MS = 120L
    private const val POP_MS = 280f

    fun classify(text: String, durationMs: Long, beatPeriodMs: Float): Motion {
        val t = text.trim()
        val letters = t.filter { it.isLetter() }
        if (t.contains('!') || (letters.length >= 2 && letters.all { it.isUpperCase() })) return Motion.Shake
        if (durationMs >= max(650f, beatPeriodMs * 1.3f)) return Motion.Held
        return Motion.Normal
    }

    /** 0 before the word starts, 0..1 while it is sung (eased), 1 after. */
    fun fill(elapsedMs: Long, durationMs: Long): Float {
        if (elapsedMs <= 0L) return 0f
        if (elapsedMs >= durationMs) return 1f
        val p = elapsedMs.toFloat() / durationMs.coerceAtLeast(1L)
        return p * p * (3f - 2f * p)
    }

    private fun bump(elapsedMs: Long): Float {
        if (elapsedMs < 0L || elapsedMs > POP_MS) return 0f
        return sin(PI.toFloat() * elapsedMs / POP_MS)
    }

    /** Fills [pose] for a word that started [elapsedMs] ago (negative before it) and lasts [durationMs]. */
    fun pose(motion: Motion, elapsedMs: Long, durationMs: Long, pulse: Float, pose: WordPose) {
        val d = max(durationMs, 60L)
        pose.scaleX = 1f; pose.scaleY = 1f; pose.dyEm = 0f; pose.rotationDeg = 0f; pose.glow = 0f

        if (elapsedMs < -PRE_ROLL_MS) {
            pose.scaleX = 0.965f; pose.scaleY = 0.965f; pose.dyEm = 0.02f
            return
        }
        if (elapsedMs < 0L) {
            val k = (elapsedMs + PRE_ROLL_MS).toFloat() / PRE_ROLL_MS
            val s = 0.965f + 0.035f * k
            pose.scaleX = s; pose.scaleY = s; pose.dyEm = 0.02f * (1f - k)
            return
        }

        val sungFor = min(elapsedMs, d)
        val progress = sungFor.toFloat() / d
        val after = (elapsedMs - d).coerceAtLeast(0L).toFloat()
        val pop = bump(elapsedMs)
        when (motion) {
            Motion.Normal -> {
                val s = 1f + 0.2f * pop
                pose.scaleX = s; pose.scaleY = s
                pose.dyEm = -0.12f * pop
                pose.glow = 0.8f * pop
            }
            Motion.Held -> {
                val grow = (progress * progress * (3f - 2f * progress)) * exp(-after / 260f)
                pose.scaleX = 1f + 0.28f * grow + 0.06f * pop
                pose.scaleY = 1f + 0.12f * grow + 0.06f * pop
                pose.dyEm = -0.03f * grow
                pose.glow = grow
            }
            Motion.Shake -> {
                val s = 1f + 0.28f * pop
                pose.scaleX = s; pose.scaleY = s
                pose.dyEm = -0.09f * pop
                pose.glow = 0.7f * pop
                if (elapsedMs <= d + 160L) {
                    val decay = 1f - (elapsedMs.toFloat() / (d + 160f)).coerceIn(0f, 1f)
                    pose.rotationDeg = sin(elapsedMs * 0.085f) * 2.6f * decay
                }
            }
        }
        if (elapsedMs <= d) {
            val boost = 1f + 0.035f * pulse
            pose.scaleX *= boost
            pose.scaleY *= boost
            pose.glow = (pose.glow + 0.25f * pulse).coerceAtMost(1f)
        }
    }
}
