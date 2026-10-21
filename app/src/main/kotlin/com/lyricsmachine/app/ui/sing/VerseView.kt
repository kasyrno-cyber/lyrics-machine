package com.lyricsmachine.app.ui.sing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsmachine.core.lyrics.LyricLine
import com.lyricsmachine.core.lyrics.Verse

private const val CROSSFADE_MS = 250

/**
 * Karaoke lyrics as a verse: every line of the verse at [currentVerse] in large bold text (no highlighting), its
 * label (e.g. "[Chorus]") small and muted above, and a faded preview of the next verse's first line below. A
 * verse change crossfades; a verse taller than the viewport scrolls and starts at the top. Tapping a line calls
 * [onTapLine] with that line; tapping the preview calls [onTapPreview]. Sizes are in sp, so
 * the system font scale applies.
 */
@Composable
fun VerseView(
    lines: List<LyricLine>,
    verses: List<Verse>,
    currentVerse: Int?,
    onTapLine: (Int) -> Unit,
    onTapPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (verses.isEmpty()) {
        Box(modifier)
        return
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val verseSize = if (wide) 44.sp else 30.sp
    val previewSize = if (wide) 22.sp else 16.sp
    val labelSize = if (wide) 16.sp else 13.sp
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val byIndex = remember(lines) { lines.associateBy { it.index } }
    val shown = (currentVerse ?: 0).coerceIn(0, verses.lastIndex)

    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val viewport = maxHeight
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS)) },
            label = "verse",
        ) { index ->
            val verse = verses.getOrNull(index) ?: return@AnimatedContent
            val next = verses.getOrNull(index + 1)
            val nextFirst = next?.lineIndices?.firstOrNull()?.let { byIndex[it] }
            // The scroll state lives inside the content, so each verse starts at the top.
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                verse.label?.let {
                    Text(it, color = muted, fontSize = labelSize, modifier = Modifier.alpha(0.7f).padding(bottom = 8.dp))
                }
                for (lineIndex in verse.lineIndices) {
                    val line = byIndex[lineIndex] ?: continue
                    Text(
                        line.words.joinToString(" ") { w -> w.display },
                        color = onSurface,
                        fontSize = verseSize,
                        lineHeight = verseSize * 1.25f,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Go to this line") { onTapLine(lineIndex) }
                            .padding(vertical = 4.dp),
                    )
                }
                if (nextFirst != null) {
                    Text(
                        nextFirst.words.joinToString(" ") { w -> w.display },
                        color = onSurface,
                        fontSize = previewSize,
                        lineHeight = previewSize * 1.25f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .alpha(0.5f)
                            .clickable(onClickLabel = "Go to next verse") { onTapPreview() }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
