package com.almog.spotv.lyrics.kinetic

import com.almog.spotv.lyrics.model.LyricLine
import com.almog.spotv.lyrics.model.LyricTrack
import com.almog.spotv.lyrics.model.WordSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KineticTest {
    private fun word(text: String, start: Long, end: Long) = WordSync(text, start, end)

    private fun line(start: Long, end: Long, vararg words: String, bg: Boolean = false, agent: String? = null): LyricLine {
        val step = (end - start) / words.size
        val ws = words.mapIndexed { i, w -> word(w, start + i * step, start + (i + 1) * step) }
        return LyricLine(start, end, ws, words.joinToString(" "), isBackground = bg, agentId = agent)
    }

    private fun track(vararg lines: LyricLine) = LyricTrack(isWordSynced = true, lines = lines.toList())

    @Test
    fun stanzasSplitOnPauses_andBreaksAreInserted() {
        val t = KineticTimelineBuilder.build(
            track(
                line(10_000, 12_000, "one", "two"),
                line(12_200, 14_000, "three", "four"),
                line(17_500, 19_000, "five", "six"),   // 3.5 s pause: new stanza
                line(30_000, 32_000, "seven", "eight"), // 11 s pause: break
            )
        )
        // Intro break (first line at 10 s), 4 blocks, one break before "seven".
        assertEquals(6, t.entries.size)
        assertTrue(t.entries.first() is BreakEntry)
        assertEquals(3, t.stanzas.size)
        assertEquals(2, t.stanzas[0].blocks.size)
        assertTrue(t.stanzas[0].hasPoster)
        assertNotNull(t.posterAt(14_000 + 400))
        assertNull(t.posterAt(10_500))
    }

    @Test
    fun activeIndexFollowsPosition() {
        val t = KineticTimelineBuilder.build(
            track(line(1_000, 3_000, "a", "b"), line(3_500, 5_000, "c", "d"))
        )
        assertEquals(-1, t.activeIndex(0))
        assertEquals(0, t.activeIndex(1_000))
        assertEquals(0, t.activeIndex(2_900))
        assertEquals(1, t.activeIndex(3_400))
        assertEquals(1, t.activeIndex(60_000))
    }

    @Test
    fun backgroundVocalsJoinTheirLead_andDuetsAlignOpposite() {
        val t = KineticTimelineBuilder.build(
            track(
                line(1_000, 3_000, "lead", "one", agent = "v1"),
                line(1_500, 2_500, "ooh", bg = true, agent = "v1"),
                line(4_000, 6_000, "lead", "two", agent = "v2"),
            )
        )
        val blocks = t.entries.filterIsInstance<LyricBlock>()
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].backgrounds.size)
        assertTrue(t.hasDuet)
        assertEquals(false, blocks[0].oppositeAligned)
        assertEquals(true, blocks[1].oppositeAligned)
    }

    @Test
    fun tempoIsFoundFromWordOnsets() {
        // A beat every 500 ms, the first at 120 ms, with a few off-beat eighths.
        val onsets = (0 until 120).flatMap { n -> listOf(120L + n * 500L) + if (n % 3 == 0) listOf(120L + n * 500L + 250L) else emptyList() }
            .sorted().toLongArray()
        val grid = Beats.estimateGrid(onsets)
        assertNotNull(grid)
        assertEquals(500f, grid!!.first, 12f)
        assertEquals(120f, grid.second, 25f)
        val clock = GridBeatClock(grid.first, grid.second, EnergyCurve.FLAT)
        assertTrue(clock.pulse(120L + 5 * 500L) > 0.9f)
        assertTrue(clock.pulse(120L + 5 * 500L + 250L) < 0.4f)
    }

    @Test
    fun wordMotion_classifiesAndAnimates() {
        assertEquals(Motion.Shake, WordMotion.classify("HEY", 200, 500f))
        assertEquals(Motion.Shake, WordMotion.classify("no!", 200, 500f))
        assertEquals(Motion.Held, WordMotion.classify("love", 1200, 500f))
        assertEquals(Motion.Normal, WordMotion.classify("the", 180, 500f))

        val pose = WordPose()
        WordMotion.pose(Motion.Normal, 140, 200, 0f, pose)
        assertTrue(pose.scaleX > 1.015f)
        WordMotion.pose(Motion.Normal, 2000, 200, 0f, pose)
        assertEquals(1f, pose.scaleX, 0.001f)
        WordMotion.pose(Motion.Held, 1000, 1200, 0f, pose)
        assertTrue(pose.scaleX > 1.04f && pose.glow > 0.4f)
        assertEquals(0f, WordMotion.fill(-5, 100), 0f)
        assertEquals(1f, WordMotion.fill(500, 100), 0f)
    }
}
