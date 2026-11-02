package com.almog.spotv.lyrics.mobile.models

/**
 * Represents a single character (letter) with its specific timing within a [Word].
 * Used for granular syllable-level highlighting.
 */
data class Letter(
    val char: String,
    val startMs: Long,
    val endMs: Long,
)
