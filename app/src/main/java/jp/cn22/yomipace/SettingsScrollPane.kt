package jp.cn22.yomipace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Always show position while overflowing, with directional cues only where content continues. */
@Composable internal fun SettingsScrollPane(state: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f)
    val thumb = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("settings-scroll-pane")) {
        Column(Modifier.fillMaxWidth().verticalScroll(state).padding(end = 24.dp), content = content)
        // These overlays must not participate in sizing the scroll viewport.
        Box(Modifier.matchParentSize()) {
            if (state.canScrollBackward) ContinuationHint(true, Modifier.align(Alignment.TopStart).padding(end = 24.dp))
            if (state.canScrollForward) ContinuationHint(false, Modifier.align(Alignment.BottomStart).padding(end = 24.dp))
            if (state.maxValue > 0 && state.viewportSize > 0) {
                Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp)
                    .testTag("settings-scrollbar")
                    .semantics {
                        contentDescription = "設定のスクロール位置"
                        progressBarRangeInfo = ProgressBarRangeInfo(state.value.toFloat(), 0f..state.maxValue.toFloat())
                        setProgress { target ->
                            scope.launch { state.scrollTo(target.roundToInt().coerceIn(0, state.maxValue)) }; true
                        }
                    }
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val height = size.height.toFloat()
                            val thumbHeight = (height * state.viewportSize.toFloat() / (state.viewportSize + state.maxValue))
                                .coerceIn(minOf(28.dp.toPx(), height), height)
                            val travel = height - thumbHeight
                            val top = if (travel > 0) travel * state.value / state.maxValue else 0f
                            val grab = if (down.position.y in top..(top + thumbHeight)) down.position.y - top else thumbHeight / 2
                            fun move(y: Float) {
                                if (travel > 0) {
                                    val value = (((y - grab) / travel).coerceIn(0f, 1f) * state.maxValue).roundToInt()
                                    scope.launch { state.scrollTo(value) }
                                }
                            }
                            move(down.position.y)
                            down.consume()
                            drag(down.id) { change -> move(change.position.y); change.consume() }
                        }
                    }) {
                    val thumbHeight = (size.height * state.viewportSize.toFloat() / (state.viewportSize + state.maxValue))
                        .coerceIn(minOf(28.dp.toPx(), size.height), size.height)
                    val top = (size.height - thumbHeight) * state.value / state.maxValue
                    val width = 4.dp.toPx()
                    val x = (size.width - width) / 2
                    drawRoundRect(track, Offset(x, 0f), Size(width, size.height), CornerRadius(width))
                    drawRoundRect(thumb, Offset(x, top), Size(width, thumbHeight), CornerRadius(width))
                }
            }
        }
    }
}

@Composable private fun ContinuationHint(above: Boolean, modifier: Modifier) {
    val shade = Color.Black.copy(alpha = .24f)
    Box(modifier.fillMaxWidth().height(32.dp)
        .background(Brush.verticalGradient(if (above) listOf(shade, Color.Transparent) else listOf(Color.Transparent, shade)))
        .testTag(if (above) "settings-more-above" else "settings-more-below"),
        contentAlignment = if (above) Alignment.TopCenter else Alignment.BottomCenter) {
        Text(if (above) "↑ 上に続き" else "↓ 下に続き", fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
