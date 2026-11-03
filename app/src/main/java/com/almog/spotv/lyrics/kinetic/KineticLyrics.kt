package com.almog.spotv.lyrics.kinetic

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.spotv.TvPrefs
import com.almog.spotv.lyrics.mobile.LyricsFonts
import com.almog.spotv.lyrics.mobile.parser.RtlDetector
import com.almog.spotv.lyrics.model.LyricLine
import com.almog.spotv.lyrics.viewmodel.LyricsViewModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
        while (true) {
            withFrameNanos { }
            val a = state.value.anchor
            pos.longValue = if (a.isPlaying) {
                a.positionMs + ((android.os.SystemClock.elapsedRealtime() - a.anchorRealtimeMs) * a.speed).toLong()
            } else {
                a.positionMs
            }
        }
    }
    return { pos.longValue }
}

/**
 * The second lyrics style: words move the way they are sung, lines further away fall back into the
 * distance, silences get a countdown ring, and a finished verse is shown whole as a poster.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val pulseOn = remember { prefs.getBoolean(TvPrefs.BEAT_PULSE, true) }
    val posterOn = remember { prefs.getBoolean(TvPrefs.POSTER, true) }
    val blurOn = remember { prefs.getBoolean(TvPrefs.DEPTH_BLUR, true) && Build.VERSION.SDK_INT >= 31 }
    val fontKey = remember { prefs.getString(LyricsFonts.PREF_LYRICS_FONT, LyricsFonts.DEFAULT_KEY) }
    val family = remember(fontKey) {
        LyricsFonts.find(fontKey).resId?.let { FontFamily(Font(it, FontWeight.Bold)) } ?: FontFamily.Default
    }

    val ui = viewModel.uiState.collectAsState()
    val track = ui.value.track
    val timeline = remember(track) { track?.let { KineticTimelineBuilder.build(it) } }
    if (timeline == null || timeline.entries.isEmpty()) return

    val active by remember(timeline) { derivedStateOf { timeline.activeIndex(positionMs()) } }
    val poster by remember(timeline) { derivedStateOf { if (posterOn) timeline.posterAt(positionMs()) else null } }

    // A float that glides to the active index, so lines scroll rather than jump.
    val scroll = remember(timeline) { Animatable(max(active, 0).toFloat()) }
    LaunchedEffect(active, timeline) {
        scroll.animateTo(max(active, 0).toFloat(), tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val gap = with(LocalDensity.current) { (baseSp * 0.5f).sp.roundToPx() }
        val from = max(0, floor(scroll.value).toInt() - 3)
        val to = min(timeline.entries.lastIndex, floor(scroll.value).toInt() + 5)

        Layout(
            content = {
                for (i in from..to) {
                    val entry = timeline.entries[i]
                    val dist = abs(i - active.coerceAtLeast(0))
                    val rel = i.compareTo(active.coerceAtLeast(0))
                    androidx.compose.runtime.key(i) {
                        EntryView(
                            entry = entry, distance = dist, relation = rel, baseSp = baseSp, family = family,
                            beat = beat, positionMs = positionMs, pulseOn = pulseOn, blurOn = blurOn,
                            accent = accent, hasDuet = timeline.hasDuet,
                            modifier = Modifier.padding(horizontal = maxWidth * 0.06f),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxWidth)) }
            // Top of each child with the active one's centre at the screen centre.
            val tops = IntArray(placeables.size)
            var y = 0
            for (k in placeables.indices) {
                tops[k] = y
                y += placeables[k].height + gap
            }
            fun centreOf(index: Int): Float {
                val k = (index - from).coerceIn(0, placeables.lastIndex)
                return tops[k] + placeables[k].height / 2f
            }
            val f = scroll.value
            val lo = floor(f).toInt()
            val frac = f - lo
            val c = centreOf(lo) + (centreOf(lo + 1) - centreOf(lo)) * frac
            layout(constraints.maxWidth, constraints.maxHeight) {
                for (k in placeables.indices) {
                    placeables[k].place(0, (constraints.maxHeight / 2f - c + tops[k]).toInt())
                }
            }
        }

        // Poster: after a verse, the whole stanza at once.
        AnimatedVisibility(
            visible = poster != null,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(450)),
        ) {
            val p = remember { arrayOf<Stanza?>(null) }
            poster?.let { p[0] = it }
            p[0]?.let { PosterView(it, baseSp, family, accent) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryView(
    entry: Entry,
    distance: Int,
    relation: Int,
    baseSp: Float,
    family: FontFamily,
    beat: BeatClock,
    positionMs: () -> Long,
    pulseOn: Boolean,
    blurOn: Boolean,
    accent: Color,
    hasDuet: Boolean,
    modifier: Modifier,
) {
    // Every change of distance is eased, so lines never snap between sizes.
    val spec = tween<Float>(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val depthScale by animateFloatAsState(if (distance == 0) 1.05f else (1f - 0.08f * distance).coerceAtLeast(0.7f), spec, label = "scale")
    val depthAlpha by animateFloatAsState(
        when (distance) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.3f; else -> 0.15f }, spec, label = "alpha",
    )
    val blurTarget = if (blurOn) ((distance - 1) * 1.6f).coerceIn(0f, 4f) else 0f
    val blurNow by animateFloatAsState(blurTarget, spec, label = "blur")
    val blurDp: Dp = blurNow.dp
    val shiftNow by animateFloatAsState((relation * distance * 6f), spec, label = "shift")
    val layerMod = modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = depthScale; scaleY = depthScale
            translationX = shiftNow.dp.toPx()
            alpha = depthAlpha
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
        .let { if (blurOn && blurNow > 0.1f) it.blur(blurDp) else it }

    when (entry) {
        is BreakEntry -> Box(layerMod, contentAlignment = Alignment.Center) {
            BreakRing(entry, beat, positionMs, pulseOn, accent, size = (baseSp * 2.6f).dp)
        }
        is LyricBlock -> {
            val live = distance == 0
            val sp = baseSp * (1f + 0.2f * entry.emphasis)
            val words = remember(entry, beat.periodMs) { wordsOf(entry.lead, beat.periodMs) }
            val rtl = remember(entry) { RtlDetector.isRtl(entry.lead.rawText.ifBlank { words.joinToString(" ") { it.text } }) }
            val arrangement = when {
                entry.oppositeAligned -> Arrangement.End
                hasDuet -> Arrangement.Start
                else -> Arrangement.Center
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                Column(layerMod, horizontalAlignment = Alignment.CenterHorizontally) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy((sp * 0.1f).dp)) {
                        for (w in words) {
                            KWordView(w, sp, family, positionMs, beat, pulseOn, live, relation, accent)
                        }
                    }
                    for (bg in entry.backgrounds) {
                        val bgWords = remember(bg) { wordsOf(bg, beat.periodMs) }
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = (sp * 0.1f).dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            for (w in bgWords) KWordView(w, sp * 0.55f, family, positionMs, beat, pulseOn, live, relation, accent)
                        }
                    }
                    entry.lead.translation?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it, color = Color.White.copy(alpha = 0.6f), fontSize = (sp * 0.5f).sp,
                            textAlign = TextAlign.Center, fontFamily = family,
                            modifier = Modifier.padding(top = (sp * 0.12f).dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KWordView(
    w: KWord,
    sp: Float,
    family: FontFamily,
    positionMs: () -> Long,
    beat: BeatClock,
    pulseOn: Boolean,
    live: Boolean,
    relation: Int,
    accent: Color,
) {
    val pose = remember { WordPose() }
    val staticAlpha = if (relation < 0) 1f else 0.5f
    Box(
        Modifier
            .padding(horizontal = (sp * 0.15f).dp)
            .graphicsLayer {
                if (live) {
                    val pos = positionMs()
                    WordMotion.pose(w.motion, pos - w.startMs, w.endMs - w.startMs, if (pulseOn) beat.pulse(pos) else 0f, pose)
                    scaleX = pose.scaleX
                    scaleY = pose.scaleY
                    translationY = pose.dyEm * sp.sp.toPx()
                    rotationZ = pose.rotationDeg
                    transformOrigin = TransformOrigin(0.5f, 0.75f)
                    val fill = WordMotion.fill(pos - w.startMs, w.endMs - w.startMs)
                    alpha = 0.42f + 0.58f * fill
                }
            }
            .drawBehind {
                if (live && pose.glow > 0.02f) {
                    val r = size.maxDimension * (0.55f + 0.5f * pose.glow)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.55f * pose.glow), Color.Transparent),
                            center = Offset(size.width / 2f, size.height / 2f), radius = r,
                        ),
                        radius = r, center = Offset(size.width / 2f, size.height / 2f),
                    )
                }
            }
            .let { if (live) it else it.alpha(staticAlpha) },
    ) {
        Text(w.text, color = Color.White, fontSize = sp.sp, fontWeight = FontWeight.Bold, fontFamily = family, softWrap = false)
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
        val r = this.size.minDimension * (0.16f + 0.05f * pulse)
        drawCircle(accent.copy(alpha = 0.55f + 0.35f * pulse), r, center)
    }
}

/** The finished verse, all at once, in a big editorial layout. */
@Composable
private fun PosterView(stanza: Stanza, baseSp: Float, family: FontFamily, accent: Color) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val lines = stanza.blocks.take(6)
        val sp = (baseSp * if (lines.size > 4) 1.0f else 1.25f)
        Column(Modifier.padding(horizontal = (baseSp * 1.6f).dp), verticalArrangement = Arrangement.spacedBy((sp * 0.12f).dp)) {
            Text(
                "%02d".format(stanza.index + 1),
                color = accent, fontSize = (baseSp * 0.5f).sp, fontWeight = FontWeight.Bold, fontFamily = family,
            )
            lines.forEachIndexed { i, b ->
                val text = b.lead.rawText.ifBlank { b.lead.words.joinToString(" ") { it.text.trim() } }
                Text(
                    text,
                    color = if (i % 2 == 0) Color.White else Color.White.copy(alpha = 0.62f),
                    fontSize = sp.sp, fontWeight = FontWeight.Bold, fontFamily = family,
                    lineHeight = (sp * 1.12f).sp, maxLines = 2,
                    modifier = Modifier.padding(start = if (i % 2 == 0) 0.dp else (baseSp * 1.2f).dp),
                )
            }
        }
    }
}
