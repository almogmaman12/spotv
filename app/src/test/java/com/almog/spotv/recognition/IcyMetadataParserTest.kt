package com.almog.spotv.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IcyMetadataParserTest {
    @Test
    fun readsArtistAndTitleMetadataBlock() {
        val metadata = "StreamTitle='Billie Eilish - bad guy';".toByteArray(Charsets.UTF_8)
        val input = streamWithMetadata(interval = 8, metadata)
        assertEquals("Billie Eilish - bad guy", IcyMetadataParser.readNextTitle(input, 8))
    }

    @Test
    fun returnsNullForEmptyMetadataBlock() {
        val input = streamWithMetadata(interval = 4, ByteArray(0))
        assertNull(IcyMetadataParser.readNextTitle(input, 4))
    }

    @Test
    fun returnsNullWhenStreamEndsBeforeMetadata() {
        assertNull(IcyMetadataParser.readNextTitle(ByteArrayInputStream(byteArrayOf(1, 2)), 8))
    }

    private fun streamWithMetadata(interval: Int, metadata: ByteArray): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        output.write(ByteArray(interval))
        val blocks = if (metadata.isEmpty()) 0 else (metadata.size + 15) / 16
        output.write(blocks)
        output.write(metadata)
        output.write(ByteArray(blocks * 16 - metadata.size))
        return ByteArrayInputStream(output.toByteArray())
    }
}
