package com.hotlayout.render.interaction

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.hotlayout.model.DocumentNode
import kotlinx.coroutines.delay

/** The feedback of a pressable node to the finger (`x-pressFeedback`): which kind, which pose. */
internal object PressFeedbackRules {
    enum class Kind(val word: String) { Card("card"), Button("button"), Fade("fade") }

    data class Pose(val scale: Double, val opacity: Double)

    const val FADE_OPACITY = 0.7
    const val DOWN_MS = 80.0
    const val UP_MS = 160.0
    val downCurve = listOf(0.2, 0.0, 0.0, 1.0)
    val upCurve = listOf(0.34, 1.56, 0.64, 1.0)

    /** The only word of the node field `pressFeedback`. */
    const val OFF_WORD = "none"

    /** The kind of feedback of a node that performs the action of a press, or `null`. */
    fun kind(node: DocumentNode, disabled: Boolean = false, swipe: Boolean = false): Kind? {
        if (node.fields["pressFeedback"]?.string == OFF_WORD || disabled || swipe) return null
        return when (node.type) {
            "Button" -> if (node.props["variant"]?.string == "ghost") Kind.Fade else Kind.Button
            "IconButton", "NavItem" -> Kind.Fade
            else -> Kind.Card
        }
    }

    /** «Reduce motion» keeps only the fade. */
    fun pose(kind: Kind, reduceMotion: Boolean = false): Pose {
        if (reduceMotion) return Pose(1.0, FADE_OPACITY)
        return when (kind) {
            Kind.Card -> Pose(0.97, 1.0)
            Kind.Button -> Pose(0.96, 1.0)
            Kind.Fade -> Pose(1.0, FADE_OPACITY)
        }
    }
}

/** Whether the person asked the system to drop animations (the animator scale is zero). */
@Composable
private fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * Sinks a node under the finger and brings it back (`x-pressFeedback`): the scale is paint only,
 * so the neighbours do not move. A press shorter than the way down still reaches the pressed pose
 * before it returns.
 */
@Composable
internal fun Modifier.pressPose(kind: PressFeedbackRules.Kind?, pressed: Boolean): Modifier {
    if (kind == null) return this
    val reduce = reducedMotion()
    val pose = PressFeedbackRules.pose(kind, reduce)
    var sunk by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    LaunchedEffect(pressed) {
        if (pressed) {
            downAt = System.nanoTime()
            sunk = true
        } else if (sunk) {
            val elapsed = (System.nanoTime() - downAt) / 1_000_000.0
            delay(maxOf(0.0, PressFeedbackRules.DOWN_MS - elapsed).toLong())
            sunk = false
        }
    }
    val curve = if (sunk) PressFeedbackRules.downCurve else PressFeedbackRules.upCurve
    val spec = tween<Float>(
        durationMillis = (if (sunk) PressFeedbackRules.DOWN_MS else PressFeedbackRules.UP_MS).toInt(),
        easing = CubicBezierEasing(curve[0].toFloat(), curve[1].toFloat(), curve[2].toFloat(), curve[3].toFloat()),
    )
    val scale by animateFloatAsState(if (sunk) pose.scale.toFloat() else 1f, spec, label = "press-scale")
    val opacity by animateFloatAsState(if (sunk) pose.opacity.toFloat() else 1f, spec, label = "press-opacity")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = opacity
    }
}
