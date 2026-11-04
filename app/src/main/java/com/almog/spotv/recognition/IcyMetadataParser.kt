package com.almog.spotv.recognition

import java.io.InputStream

/** Reads the next ICY metadata block after one audio interval. */
object IcyMetadataParser {
    fun readNextTitle(input: InputStream, metadataInterval: Int): String? {
        if (metadataInterval <= 0 || !skipFully(input, metadataInterval.toLong())) return null
        val blocks = input.read()
        if (blocks <= 0) return null
        val metadata = ByteArray(blocks * 16)
        var offset = 0
        while (offset < metadata.size) {
            val count = input.read(metadata, offset, metadata.size - offset)
            if (count < 0) return null
            offset += count
        }
        val raw = String(metadata, Charsets.UTF_8).trim('\u0000', ' ', '\t', '\r', '\n')
        return Regex("""StreamTitle\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else { if (input.read() < 0) return false; remaining-- }
        }
        return true
    }
}
