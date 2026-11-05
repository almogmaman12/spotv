package com.almog.spotv.lyrics.kinetic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.spotv.TvPrefs
import com.almog.spotv.lyrics.mobile.LyricsFonts
import com.almog.spotv.lyrics.mobile.parser.RtlDetector
import com.almog.spotv.lyrics.model.LyricLine
import com.almog.spotv.lyrics.viewmodel.LyricsViewModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One displayed word (syllables with no space between them are joined). */
internal class KWord(val text: String, val startMs: Long, val endMs: Long, val motion: Motion)

internal fun wordsOf(line: LyricLine, beatPeriodMs: Float): List<KWord> {
    val out = ArrayList<KWord>()
    if (line.words.isNotEmpty()) {
        var sb = StringBuilder()
        var start = 0L
        var end = 0L
        for ((i, w) in line.words.withIndex()) {
            if (sb.isEmpty()) start = w.startTimeMs
            sb.append(w.text.trim())
            end = w.endTimeMs
            val last = i == line.words.lastIndex
            if (w.trailingSpace || last || w.text.endsWith(" ")) {
                val t = sb.toString()
                if (t.isNotBlank()) out.add(KWord(t, start, end, WordMotion.classify(t, end - start, beatPeriodMs)))
                sb = StringBuilder()
            }
        }
    } else {
        val parts = line.rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) {
            val total = (line.endTimeMs - line.startTimeMs).coerceAtLeast(200L)
            val weights = parts.map { it.length + 2 }
            val sum = weights.sum().toFloat()
            var acc = 0f
            for ((i, p) in parts.withIndex()) {
                val s = line.startTimeMs + (acc / sum * total).toLong()
                acc += weights[i]
                val e = line.startTimeMs + (acc / sum * total).toLong()
                out.add(KWord(p, s, e, WordMotion.classify(p, e - s, beatPeriodMs)))
            }
        }
    }
    return out
}

/** The song's playback position, updated every frame while playing. Read it only in draw / layer blocks. */
@Composable
fun rememberLyricsClock(vm: LyricsViewModel): () -> Long {
    val state = vm.uiState.collectAsState()
    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // The clock runs on frame time and is only nudged toward Spotify's reported position, so a
        // re-sync every few seconds never makes words stutter. Big differences (seeks) snap.
        var shown = 0.0
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = if (last == 0L) 16.0 else ((now - last) / 1_000_000.0).coerceIn(0.0, 100.0)
            last = now
            val a = state.value.anchor
            if (a.isPlaying) {
                val target = a.positionMs + (android.os.SystemClock.elapsedRealtime() - a.anchorRealtimeMs) * a.speed.toDouble()
                shown += dtMs * a.speed
                val err = target - shown
                shown = if (kotlin.math.abs(err) > 600.0) target else shown + err * 0.06
            } else {
                shown = a.positionMs.toDouble()
            }
            pos.longValue = shown.toLong()
        }
    }
    return { pos.longValue }
}

/**
 * "Stage": one line at a time, set huge. Each word rises into place just before it is sung and
 * lights up in the accent colour while it is sung; the next line waits small underneath.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
fun KineticLyrics(
    viewModel: LyricsViewModel,
    beat: BeatClock,
    positionMs: () -> Long,
    accent: Color,
    baseSp: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { TvPrefs.prefs(context) }
    val pulseOn = remember { prefs.getBoolean(TvPrefs.BEAT_PULSE, false) }
    val fontKey = remember { prefs.getString(LyricsFonts.PREF_LYRICS_FONT, LyricsFonts.DEFAULT_KEY) }
    val family = remember(fontKey) {
        LyricsFonts.find(fontKey).resId?.let { FontFamily(Font(it, FontWeight.Bold)) } ?: FontFamily.Default
    }

    val ui = viewModel.uiState.collectAsState()
    val track = ui.value.track
    val timeline = remember(track) { track?.let { KineticTimelineBuilder.build(it) } }
    if (timeline == null || timeline.entries.isEmpty()) return

    val active by remember(timeline) { derivedStateOf { timeline.activeIndex(positionMs()) } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = maxWidth
        val screenH = maxHeight
        AnimatedContent(
            targetState = active,
            transitionSpec = {
                fadeIn(tween(200, delayMillis = 140)) togetherWith
                    (fadeOut(tween(320)) + slideOutVertically(tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 14 })
            },
            label = "line",
            modifier = Modifier.fillMaxSize(),
        ) { idx ->
            val entry = timeline.entries.getOrNull(idx)
            val next = timeline.entries.drop(idx + 1).filterIsInstance<LyricBlock>().firstOrNull()
            Box(Modifier.fillMaxSize()) {
                when (entry) {
                    is LyricBlock -> {
                        val text = entry.lead.rawText.ifBlank { entry.lead.words.joinToString(" ") { it.text.trim() } }
                        // Big, but never more than about three rows: size from the amount of text.
                        val area = screenW.value * 0.84f * screenH.value * 0.46f
                        val fit = sqrt(area / (max(text.length, 8) * 0.6f * 1.25f))
                        val sp = fit.coerceIn(baseSp * 1.1f, baseSp * 2.6f) * (1f + 0.12f * entry.emphasis)
                        val words = remember(entry, beat.periodMs) { wordsOf(entry.lead, beat.periodMs) }
                        val rtl = remember(entry) { RtlDetector.isRtl(text) }
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides
                                if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                        ) {
                            Column(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        // Once a line is done it settles back a little, so the eye moves on.
                                        val d = ((positionMs() - entry.endMs - 1500L) / 2000f).coerceIn(0f, 1f)
                                        alpha = 1f - 0.5f * d
                                        // A slow drift across the length of the line keeps the screen alive.
                                        val prog = ((positionMs() - entry.startMs).toFloat() / (entry.endMs - entry.startMs).coerceAtLeast(500L)).coerceIn(0f, 1f)
                                        translationX = (0.5f - prog) * 16.dp.toPx()
                                    }
                                    .padding(horizontal = screenW * 0.07f),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalArrangement = Arrangement.spacedBy((sp * -0.04f).dp),
                                ) {
                                    for (w in words) StageWord(w, sp, family, positionMs, beat, pulseOn, accent)
                                }
                                entry.lead.translation?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it, color = Color.White.copy(alpha = 0.55f), fontSize = (sp * 0.34f).sp,
                                        fontFamily = family, modifier = Modifier.padding(top = (sp * 0.2f).dp),
                                    )
                                }
                                LineTimer(entry, positionMs, accent, sp)
                            }
                            if (entry.backgrounds.isNotEmpty()) {
                                // Backing vocals ("ooh", echoes) float in the corner, small and slanted.
                                Column(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = screenH * 0.14f, end = screenW * 0.06f)
                                        .fillMaxWidth(0.4f),
                                    horizontalAlignment = Alignment.End,
                                ) {
                                    for (bg in entry.backgrounds) {
                                        val bgWords = remember(bg) { wordsOf(bg, beat.periodMs) }
                                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            for (w in bgWords) StageWord(w, baseSp * 0.7f, family, positionMs, beat, false, accent, italic = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is BreakEntry -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BreakRing(entry, beat, positionMs, pulseOn, accent, size = (baseSp * 3.2f).dp)
                    }
                    null -> Unit
                }
                if (next != null) {
                    Text(
                        next.lead.rawText.ifBlank { next.lead.words.joinToString(" ") { it.text.trim() } },
                        color = Color.White.copy(alpha = 0.38f),
                        fontSize = (baseSp * 0.62f).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = family,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = screenW * 0.07f, end = screenW * 0.07f, bottom = screenH * 0.14f),
                    )
                }
            }
        }
    }
}

/** A thin line under the lyric that fills as the line is sung. */
@Composable
private fun LineTimer(block: LyricBlock, positionMs: () -> Long, accent: Color, sp: Float) {
    Canvas(Modifier.padding(top = (sp * 0.4f).dp).fillMaxWidth().height((sp * 0.06f).coerceAtLeast(3f).dp)) {
        val total = (block.endMs - block.startMs).coerceAtLeast(500L)
        val p = ((positionMs() - block.startMs).toFloat() / total).coerceIn(0f, 1f)
        val h = size.height
        drawRect(Color.White.copy(alpha = 0.12f), size = Size(size.width * 0.35f, h))
        drawRect(accent, size = Size(size.width * 0.35f * p, h))
    }
}

/**
 * One word: rises into place just before it is sung and lights up as it is sung. Words that are
 * not near their moment are drawn statically (no per-frame work), so only a few words animate at once.
 */
@Composable
private fun StageWord(
    w: KWord,
    sp: Float,
    family: FontFamily,
    positionMs: () -> Long,
    beat: BeatClock,
    pulseOn: Boolean,
    accent: Color,
    italic: Boolean = false,
) {
    val pose = remember { WordPose() }
    val style = androidx.compose.ui.text.TextStyle(
        fontSize = sp.sp, fontWeight = FontWeight.ExtraBold, fontFamily = family, lineHeight = (sp * 1.02f).sp,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
    )
    // 0 = not yet, 1 = animating, 2 = done. Changes only a few times per word.
    val phase by remember(w) {
        androidx.compose.runtime.derivedStateOf {
            val p = positionMs()
            when {
                p < w.startMs - 800L -> 0
                p <= w.endMs + 1400L -> 1
                else -> 2
            }
        }
    }
    val letters = remember(w) {
        if (w.motion == Motion.Held && w.endMs - w.startMs >= 1000L && !italic) com.almog.spotv.lyrics.model.extractGraphemeClusters(w.text) else emptyList()
    }
    val base = Modifier.padding(end = (sp * 0.24f).dp)
    val mod = when (phase) {
        0 -> base.graphicsLayer { alpha = 0f }
        2 -> base
        else -> base.graphicsLayer {
            val pos = positionMs()
            val t = ((pos - w.startMs + 700L) / 800f).coerceIn(0f, 1f)
            // Rises with a very small overshoot, then settles: alive, not bouncy.
            val u = t - 1f
            val back = 1f + 2.1f * u * u * u + 1.1f * u * u
            WordMotion.pose(w.motion, pos - w.startMs, w.endMs - w.startMs, if (pulseOn) beat.pulse(pos) else 0f, pose)
            alpha = (t * 2.4f).coerceIn(0f, 1f)
            translationY = (1f - back) * sp.sp.toPx() * 0.38f
            scaleX = pose.scaleX
            scaleY = pose.scaleY
            transformOrigin = TransformOrigin(0.5f, 0.85f)
        }
    }
    Box(mod) {
        if (letters.size >= 2) {
            // A held note: the light and a small lift travel through the letters as it is sung.
            androidx.compose.foundation.layout.Row {
                val n = letters.size
                letters.forEachIndexed { i, ch ->
                    val ls = w.startMs + (w.endMs - w.startMs) * i / n
                    val letterMod = if (phase == 1) {
                        Modifier.graphicsLayer {
                            val q = ((positionMs() - ls) / (0.4f * (w.endMs - w.startMs))).coerceIn(0f, 1f)
                            translationY = -kotlin.math.sin(Math.PI.toFloat() * q) * sp.sp.toPx() * 0.05f
                        }
                    } else {
                        Modifier
                    }
                    Box(letterMod) {
                        LitText(ch, style, phase, positionMs, ls, w.startMs + (w.endMs - w.startMs) * (i + 1) / n)
                    }
                }
            }
        } else {
            LitText(w.text, style, phase, positionMs, w.startMs, w.endMs)
        }
    }
}

/** Dim text with a bright copy on top, revealed from one end to the other as the word is sung. */
@Composable
private fun LitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    phase: Int,
    positionMs: () -> Long,
    startMs: Long,
    endMs: Long,
) {
    if (phase == 2) {
        Text(text, style = style, color = Color.White, softWrap = false)
        return
    }
    Text(text, style = style, color = Color.White.copy(alpha = 0.45f), softWrap = false)
    if (phase == 1) {
        // A clip, not an offscreen mask: cheap enough for any TV GPU.
        Text(
            text, style = style, color = Color.White, softWrap = false,
            modifier = Modifier.drawWithContent {
                val dur = (endMs - startMs).coerceAtLeast(140L)
                val f = ((positionMs() - startMs).toFloat() / dur).coerceIn(0f, 1f)
                if (f > 0f) {
                    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val x = size.width * f
                    clipRect(left = if (rtl) size.width - x else 0f, right = if (rtl) size.width else x) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
        )
    }
}

/** A ring that fills as an instrumental break runs out, with a soft beat in the middle. */
@Composable
private fun BreakRing(
    entry: BreakEntry,
    beat: BeatClock,
    positionMs: () -> Long,
    pulseOn: Boolean,
    accent: Color,
    size: Dp,
) {
    Canvas(Modifier.size(size)) {
        val pos = positionMs()
        val total = (entry.endMs - entry.startMs).coerceAtLeast(1L)
        val p = ((pos - entry.startMs).toFloat() / total).coerceIn(0f, 1f)
        val stroke = this.size.minDimension * 0.07f
        val inset = stroke
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        drawArc(Color.White.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(accent, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val pulse = if (pulseOn) beat.pulse(pos) else 0f
        val breathe = (kotlin.math.sin(pos / 700f) * 0.5f + 0.5f)
        val r = this.size.minDimension * (0.15f + 0.03f * breathe + 0.05f * pulse)
        drawCircle(accent.copy(alpha = 0.55f + 0.35f * pulse), r, center)
    }
}

