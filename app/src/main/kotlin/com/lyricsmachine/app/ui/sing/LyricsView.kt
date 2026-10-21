package com.lyricsmachine.app.ui.sing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsmachine.core.lyrics.LyricLine
import kotlin.math.roundToInt

private enum class Role { CURRENT, PREVIOUS, NEXT, OTHER }

/**
 * Karaoke lyrics: the current line large and filled word by word, the previous lyric line faded above it and
 * the next two dimmed below. The list scrolls so the current line sits about a third from the top. Spacer
 * lines with a label (e.g. "[Chorus]") show as small muted text; blank spacers are an 8dp gap. Every line
 * calls [onTapLine] when tapped. Text sizes are in sp, so the system font scale applies.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    currentLine: Int?,
    currentWord: Int?,
    wordProgress: Float,
    wordTimed: Boolean,
    onTapLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val currentSize = if (wide) 40.sp else 28.sp
    val previousSize = if (wide) 28.sp else 20.sp
    val nextSize = if (wide) 26.sp else 18.sp
    val otherSize = if (wide) 24.sp else 17.sp
    val labelSize = if (wide) 16.sp else 13.sp
    val fill = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val lyricOrder = remember(lines) { lines.filter { !it.isSpacer }.map { it.index } }
    val roles = remember(lyricOrder, currentLine) {
        val m = HashMap<Int, Role>()
        val pos = currentLine?.let { lyricOrder.indexOf(it) } ?: -1
        if (pos >= 0) {
            m[lyricOrder[pos]] = Role.CURRENT
            lyricOrder.getOrNull(pos - 1)?.let { m[it] = Role.PREVIOUS }
            lyricOrder.getOrNull(pos + 1)?.let { m[it] = Role.NEXT }
            lyricOrder.getOrNull(pos + 2)?.let { m[it] = Role.NEXT }
        }
        m
    }

    val listState = rememberLazyListState()
    BoxWithConstraints(modifier) {
        val viewport = maxHeight
        // With a top padding of a third of the viewport, scrolling an item to offset 0 puts it a third down.
        LaunchedEffect(currentLine, viewport) {
            val target = currentLine ?: return@LaunchedEffect
            if (target in lines.indices) listState.animateScrollToItem(target)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = viewport / 3, bottom = viewport * 2 / 3),
        ) {
            items(lines, key = { it.index }) { line ->
                val tap = Modifier.fillMaxWidth().clickable(onClickLabel = "Go to this line") { onTapLine(line.index) }
                when {
                    line.isSpacer && line.label == null -> Box(tap.height(8.dp))
                    line.isSpacer -> Box(
                        tap.heightIn(min = 48.dp).padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(line.label.orEmpty(), color = muted, fontSize = labelSize, modifier = Modifier.alpha(0.7f))
                    }
                    else -> {
                        val role = if (currentLine == null) Role.NEXT else roles[line.index] ?: Role.OTHER
                        val text = line.words.joinToString(" ") { it.display }
                        Box(
                            tap.heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            when (role) {
                                Role.CURRENT -> Text(
                                    filled(line, currentWord, wordProgress, wordTimed, fill, base),
                                    fontSize = currentSize,
                                    lineHeight = currentSize * 1.2f,
                                    fontWeight = FontWeight.Bold,
                                )
                                Role.PREVIOUS -> Text(text, color = base, fontSize = previousSize, modifier = Modifier.alpha(0.4f))
                                Role.NEXT -> Text(text, color = base, fontSize = nextSize, modifier = Modifier.alpha(0.6f))
                                Role.OTHER -> Text(text, color = base, fontSize = otherSize, modifier = Modifier.alpha(0.25f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The current line with the words already sung in [fill]. With word timings, words before [currentWord] are
 * filled and [currentWord] is filled left to right by [wordProgress] (per character). Without word timings the
 * whole line is filled.
 */
private fun filled(
    line: LyricLine,
    currentWord: Int?,
    wordProgress: Float,
    wordTimed: Boolean,
    fill: Color,
    base: Color,
): AnnotatedString = buildAnnotatedString {
    if (!wordTimed) {
        withStyle(SpanStyle(color = fill)) { append(line.words.joinToString(" ") { it.display }) }
        return@buildAnnotatedString
    }
    line.words.forEachIndexed { i, w ->
        if (i > 0) withStyle(SpanStyle(color = if (currentWord != null && i <= currentWord) fill else base)) { append(" ") }
        val word = w.display
        when {
            currentWord == null || i > currentWord -> withStyle(SpanStyle(color = base)) { append(word) }
            i < currentWord -> withStyle(SpanStyle(color = fill)) { append(word) }
            else -> {
                val n = (word.length * wordProgress.coerceIn(0f, 1f)).roundToInt()
                withStyle(SpanStyle(color = fill)) { append(word.substring(0, n)) }
                withStyle(SpanStyle(color = base)) { append(word.substring(n)) }
            }
        }
    }
}
