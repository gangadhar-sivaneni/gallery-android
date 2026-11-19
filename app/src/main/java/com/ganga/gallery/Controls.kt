package com.ganga.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Snaps [v] into [range]; with [steps] > 0 it lands on one of steps+2 evenly spaced values. */
fun snapTo(v: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val c = v.coerceIn(range.start, range.endInclusive)
    if (steps <= 0) return c
    val span = range.endInclusive - range.start
    val n = steps + 1
    return range.start + span * ((c - range.start) / span * n).roundToInt() / n
}

/**
 * The app's slider: a thin line with a round knob, filled from zero when the range goes both ways
 * (so "-20" and "+20" read at a glance). Drag or tap; TalkBack can set it too.
 */
@Composable
fun AppSlider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, enabled: Boolean = true, label: String? = null,
    color: Color = MaterialTheme.colorScheme.primary, track: Color = LocalContentColor.current.copy(alpha = .2f),
    onDone: () -> Unit = {},
) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var active by remember { mutableStateOf(false) }
    val knob by animateDpAsState(if (active) 11.dp else 9.dp, label = "knob")
    val span = range.endInclusive - range.start
    val fill = if (enabled) color else color.copy(alpha = .35f)
    Canvas(
        modifier.fillMaxWidth().height(36.dp)
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
                if (enabled) setProgress { v -> change(snapTo(v, range, steps)); true }
            }
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(range, steps) {
                        val pad = 12.dp.toPx()
                        fun at(x: Float) = snapTo(range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span, range, steps)
                        detectHorizontalDragGestures(
                            onDragStart = { active = true; change(at(it.x)) },
                            onDragEnd = { active = false; done() }, onDragCancel = { active = false; done() },
                        ) { ch, _ -> ch.consume(); change(at(ch.position.x)) }
                    }
                    .pointerInput(range, steps) {
                        val pad = 12.dp.toPx()
                        detectTapGestures { change(snapTo(range.start + ((it.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span, range, steps)); done() }
                    },
            ),
    ) {
        val pad = 12.dp.toPx(); val y = size.height / 2; val w = size.width - 2 * pad
        fun xOf(v: Float) = pad + (v.coerceIn(range.start, range.endInclusive) - range.start) / span * w
        val sw = 4.dp.toPx()
        drawLine(track, Offset(pad, y), Offset(pad + w, y), sw, StrokeCap.Round)
        val centred = range.start < 0f && range.endInclusive > 0f
        val from = if (centred) xOf(0f) else pad
        drawLine(fill, Offset(from, y), Offset(xOf(value), y), sw, StrokeCap.Round)
        if (centred) drawCircle(track.copy(alpha = .6f), 3.dp.toPx(), Offset(from, y))
        drawCircle(Color.White, knob.toPx(), Offset(xOf(value), y))
        drawCircle(fill, knob.toPx(), Offset(xOf(value), y), style = Stroke(2.dp.toPx()))
    }
}

/** Dark rounded button for the black editor screens. */
@Composable
fun DarkAction(icon: ImageVector?, label: String, enabled: Boolean = true, onClick: () -> Unit) = Row(
    Modifier.clip(CircleShape).background(Color(0xFF2C2C2E)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    val c = if (enabled) Color.White else Color.White.copy(alpha = .35f)
    if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = c); Spacer(Modifier.width(8.dp)) }
    Text(label, color = c, fontSize = 14.sp)
}
