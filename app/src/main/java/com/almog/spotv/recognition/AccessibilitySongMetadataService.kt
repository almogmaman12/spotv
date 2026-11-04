package com.almog.spotv.recognition

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VisibleTextTrack(
    val packageName: String,
    val title: String,
    val artist: String,
) {
    val key: String get() = "$packageName:$artist:$title"
}

/**
 * Optional local-only fallback for apps that expose a song title in their accessibility tree.
 * Text is inspected on-device and is never uploaded by this service.
 */
class AccessibilitySongMetadataService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName) return

        val root = rootInActiveWindow ?: run { AccessibilitySongSource.publish(null); return }
        try {
            val texts = ArrayList<String>()
            collectVisibleText(root, texts, 0)
            val candidate = texts.asSequence()
                .mapNotNull(::parseTrackText)
                .firstOrNull()
            AccessibilitySongSource.publish(candidate?.let {
                VisibleTextTrack(packageName, it.first, it.second)
            })
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() {
        AccessibilitySongSource.publish(null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, output: MutableList<String>, depth: Int) {
        if (node == null || depth > MAX_DEPTH || output.size >= MAX_NODES) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(output::add)
        for (index in 0 until node.childCount) {
            if (output.size >= MAX_NODES) break
            val child = node.getChild(index)
            try {
                collectVisibleText(child, output, depth + 1)
            } finally {
                @Suppress("DEPRECATION")
                child?.recycle()
            }
        }
    }

    /** Returns title/artist. Only explicit title separators are accepted to reduce false matches. */
    private fun parseTrackText(raw: String): Pair<String, String>? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.length !in 7..160 || text.contains("://")) return null
        val lower = text.lowercase(Locale.ROOT)
        if (IGNORED_LABELS.any { lower == it }) return null

        val separators = listOf(" - ", " – ", " — ", " • ", " | ")
        for (separator in separators) {
            val index = text.indexOf(separator)
            if (index <= 1 || index >= text.length - separator.length - 1) continue
            val artist = text.substring(0, index).trim()
            val title = text.substring(index + separator.length).trim()
            if (artist.length in 2..70 && title.length in 2..90) return title to artist
        }

        val byIndex = lower.lastIndexOf(" by ")
        if (byIndex > 1 && byIndex < text.length - 5) {
            val title = text.substring(0, byIndex).trim()
            val artist = text.substring(byIndex + 4).trim()
            if (artist.length in 2..70 && title.length in 2..90) return title to artist
        }
        return null
    }

    companion object {
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 18
        private val IGNORED_LABELS = setOf(
            "now playing", "search", "home", "settings", "play", "pause", "next", "previous",
            "shuffle", "repeat", "queue", "library", "subscribe", "sign in",
        )
    }
}

object AccessibilitySongSource {
    private val _track = MutableStateFlow<VisibleTextTrack?>(null)
    val track: StateFlow<VisibleTextTrack?> = _track
    internal fun publish(track: VisibleTextTrack?) { _track.value = track }
}
