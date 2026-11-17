package com.almog.spotv.spotify

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoQueueTest {
    private fun e(id: String) = Entry(Kind.TRACK, id, "spotify:track:$id", id, "", null)

    @Test
    fun neverPicksSomethingHeardRecently() {
        val pick = AutoQueue.pick(listOf(e("a"), e("b")), listOf(e("c")), setOf("a", "c"), Random(1))
        assertEquals("b", pick?.id)
    }

    @Test
    fun fallsBackToLikesWhenArtistIsExhausted_andIsNullWhenEverythingWasHeard() {
        assertNotNull(AutoQueue.pick(listOf(e("a")), listOf(e("c")), setOf("a"), Random(2)))
        assertNull(AutoQueue.pick(listOf(e("a")), listOf(e("c")), setOf("a", "c"), Random(3)))
    }
}
