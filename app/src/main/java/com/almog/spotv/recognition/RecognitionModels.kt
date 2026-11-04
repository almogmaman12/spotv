package com.almog.spotv.recognition

data class RecognizedTrack(val provider: String, val title: String, val artist: String, val album: String = "", val durationMs: Long = 0L, val providerTrackId: String = "", val artworkUrl: String = "", val trackUrl: String = "", val matchOffsetMs: Long = 0L, val positionMs: Long = 0L)
data class RadioStation(val id: String, val name: String, val streamUrl: String, val homepage: String, val country: String, val tags: String, val codec: String, val bitrate: Int)
data class EnrichedTrack(val title: String, val artist: String, val album: String, val durationMs: Long, val musicBrainzRecordingId: String)
