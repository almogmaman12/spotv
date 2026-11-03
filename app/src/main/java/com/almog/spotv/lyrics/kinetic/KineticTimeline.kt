package com.almog.spotv.lyrics.kinetic

import com.almog.spotv.lyrics.model.LyricLine
import com.almog.spotv.lyrics.model.LyricTrack
import com.almog.spotv.lyrics.web.SpicyLyricsJson
import kotlin.math.max
import kotlin.math.min

/** One thing on the kinetic stage: a lyric block, or an instrumental break between two of them. */
sealed class Entry {
    abstract val startMs: Long
    abstract val endMs: Long
}

/** A lead line with the background vocals that go with it. [endMs] is when its last word ends. */
class LyricBlock(
    val lead: LyricLine,
    val backgrounds: List<LyricLine>,
    override val startMs: Long,
    override val endMs: Long,
    val stanza: Int,
    /** A second singer in a duet: drawn on the other side. */
    val oppositeAligned: Boolean,
    /** 0..1: how loud / urgent the line is, from shouting and word density. Bigger text for higher. */
    val emphasis: Float,
) : Entry()

class BreakEntry(override val startMs: Long, override val endMs: Long) : Entry()

/** A verse or chorus: lines that belong together, set apart by a pause. */
class Stanza(
    val index: Int,
    val blocks: List<LyricBlock>,
    val startMs: Long,
    val endMs: Long,
    /** Silence between this stanza's last word and the next stanza's first. */
    val gapAfterMs: Long,
) {
    /** When the poster (the whole stanza at once) starts, after the last word has faded. */
    val posterStartMs: Long get() = endMs + POSTER_DELAY_MS
    val posterHoldMs: Long get() = min(POSTER_MAX_MS, gapAfterMs - POSTER_TAIL_MS)
    val hasPoster: Boolean get() = blocks.size >= 2 && gapAfterMs >= POSTER_MIN_GAP_MS && posterHoldMs >= POSTER_MIN_MS

    companion object {
        const val POSTER_DELAY_MS = 350L
        const val POSTER_TAIL_MS = 1300L
        const val POSTER_MAX_MS = 3300L
        const val POSTER_MIN_MS = 1400L
        const val POSTER_MIN_GAP_MS = 3000L
    }
}

class KineticTimeline(
    val entries: List<Entry>,
    val stanzas: List<Stanza>,
    val hasDuet: Boolean,
) {
    /** The entry being sung at [posMs] (the last one that has started), or -1 before the first. */
    fun activeIndex(posMs: Long): Int {
        var lo = 0
        var hi = entries.size - 1
        var found = -1
        val t = posMs + LEAD_MS
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].startMs <= t) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The stanza whose poster should be up at [posMs], if any. */
    fun posterAt(posMs: Long): Stanza? =
        stanzas.firstOrNull { it.hasPoster && posMs >= it.posterStartMs && posMs < it.posterStartMs + it.posterHoldMs }

    companion object {
        /** A line counts as sung this long before its first word, so the eye is already there. */
        const val LEAD_MS = 150L
    }
}

object KineticTimelineBuilder {
    /** Pause between stanzas. */
    private const val STANZA_GAP_MS = 1400L
    private const val STANZA_MAX_LINES = 8

    /** Silence at least this long between lines becomes a break entry (a countdown ring). */
    private const val BREAK_MIN_GAP_MS = 6000L
    private const val BREAK_PAD_BEFORE_MS = 600L
    private const val BREAK_PAD_AFTER_MS = 400L
    private const val INTRO_BREAK_MIN_MS = 7000L

    fun build(track: LyricTrack): KineticTimeline {
        val syllable = SpicyLyricsJson.isSyllableSynced(track)
        val groups: List<Pair<LyricLine, List<LyricLine>>> = if (syllable) {
            SpicyLyricsJson.groupBackground(track.lines)
        } else {
            track.lines.filterNot { it.isBackground }.sortedBy { it.startTimeMs }.map { it to emptyList<LyricLine>() }
        }.filter { (lead, _) -> lead.rawText.isNotBlank() || lead.words.isNotEmpty() }

        if (groups.isEmpty()) return KineticTimeline(emptyList(), emptyList(), false)

        val firstAgent = groups.firstNotNullOfOrNull { it.first.agentId }
        val agents = groups.mapNotNull { it.first.agentId }.toSet()
        val hasDuet = agents.size >= 2

        // Stanza numbers.
        val spans = groups.map { (lead, bgs) ->
            val all = listOf(lead) + bgs
            val start = all.minOf { it.startTimeMs }
            val end = all.maxOf { sungEnd(it) }.coerceAtLeast(start + 1)
            start to end
        }
        val stanzaOf = IntArray(groups.size)
        var stanza = 0
        var inStanza = 0
        for (i in groups.indices) {
            if (i > 0) {
                val gap = spans[i].first - spans[i - 1].second
                if (gap >= STANZA_GAP_MS || inStanza >= STANZA_MAX_LINES) {
                    stanza++
                    inStanza = 0
                }
            }
            stanzaOf[i] = stanza
            inStanza++
        }

        val entries = ArrayList<Entry>(groups.size + 4)
        val blocks = ArrayList<LyricBlock>(groups.size)
        if (spans[0].first >= INTRO_BREAK_MIN_MS) {
            entries.add(BreakEntry(0L, spans[0].first - BREAK_PAD_AFTER_MS))
        }
        for (i in groups.indices) {
            val (lead, bgs) = groups[i]
            val block = LyricBlock(
                lead = lead,
                backgrounds = bgs,
                startMs = spans[i].first,
                endMs = spans[i].second,
                stanza = stanzaOf[i],
                oppositeAligned = hasDuet && lead.agentId != null && lead.agentId != firstAgent,
                emphasis = emphasis(lead, spans[i].second - spans[i].first),
            )
            blocks.add(block)
            entries.add(block)
            if (i + 1 < groups.size) {
                val gap = spans[i + 1].first - spans[i].second
                if (gap >= BREAK_MIN_GAP_MS) {
                    entries.add(BreakEntry(spans[i].second + BREAK_PAD_BEFORE_MS, spans[i + 1].first - BREAK_PAD_AFTER_MS))
                }
            }
        }

        val stanzas = blocks.groupBy { it.stanza }.entries.sortedBy { it.key }.mapIndexed { n, (_, list) ->
            val end = list.maxOf { it.endMs }
            val next = blocks.firstOrNull { it.stanza == list.first().stanza + 1 }
            Stanza(
                index = n,
                blocks = list,
                startMs = list.minOf { it.startMs },
                endMs = end,
                gapAfterMs = if (next != null) next.startMs - end else 0L,
            )
        }
        return KineticTimeline(entries, stanzas, hasDuet)
    }

    /** When the last word of [line] ends. */
    internal fun sungEnd(line: LyricLine): Long =
        if (line.words.isNotEmpty()) max(line.words.maxOf { it.endTimeMs }, line.startTimeMs + 1) else line.endTimeMs

    private fun emphasis(lead: LyricLine, durationMs: Long): Float {
        val text = lead.rawText.ifBlank { lead.words.joinToString("") { it.text } }
        val letters = text.filter { it.isLetter() }
        val shouting = text.contains('!') || (letters.length >= 4 && letters.count { it.isUpperCase() } >= letters.length * 0.8f)
        val wordCount = lead.words.size.coerceAtLeast(text.trim().split(Regex("\\s+")).size)
        val wordsPerSecond = wordCount * 1000f / durationMs.coerceAtLeast(400L)
        val density = ((wordsPerSecond - 2f) / 4f).coerceIn(0f, 1f)
        return ((if (shouting) 0.6f else 0f) + density * 0.5f).coerceIn(0f, 1f)
    }
}
