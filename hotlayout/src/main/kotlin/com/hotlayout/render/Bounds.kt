package com.hotlayout.render

import androidx.compose.ui.unit.Dp

/**
 * The ONE place where a number of a document, a theme or the data becomes a length. Compose throws on
 * a size or a constraint it cannot represent (above ~2^16 px a side, negative padding, a NaN corner), and
 * a number arrives over the network, so every length is made finite, not negative and bounded here:
 * `.dp` of this file replaces `androidx.compose.ui.unit.dp` in the reader, and `.signedDp` is for the few
 * offsets that may point backwards.
 */
internal const val MAX_DP: Double = 8192.0

/** The most pixels a computed size is allowed to have before it is a constraint (any density up to 8 stays representable). */
internal const val MAX_PX: Int = 32767

/** A length: `NaN` is 0, a negative value is 0, a huge one is [MAX_DP]. */
internal fun safeLength(value: Double): Double = if (value.isNaN()) 0.0 else value.coerceIn(0.0, MAX_DP)

/** An offset: `NaN` is 0, the sign is kept, the size is bounded. */
internal fun safeOffset(value: Double): Double = if (value.isNaN()) 0.0 else value.coerceIn(-MAX_DP, MAX_DP)

internal val Double.dp: Dp get() = Dp(safeLength(this).toFloat())

internal val Float.dp: Dp get() = Dp(safeLength(this.toDouble()).toFloat())

internal val Int.dp: Dp get() = Dp(safeLength(this.toDouble()).toFloat())

/** For an offset that may be negative (a displacement, not a size). */
internal val Double.signedDp: Dp get() = Dp(safeOffset(this).toFloat())

/** A computed pixel size as a whole number a constraint can hold: finite, 0…[MAX_PX]. */
internal fun Double.safePx(): Int = if (isNaN()) 0 else coerceIn(0.0, MAX_PX.toDouble()).let { Math.round(it).toInt() }

/** A computed pixel offset: finite, ±[MAX_PX]. */
internal fun Double.safePxOffset(): Int = if (isNaN()) 0 else coerceIn(-MAX_PX.toDouble(), MAX_PX.toDouble()).let { Math.round(it).toInt() }

/** Replaces `kotlin.math.roundToInt` in the reader: that one throws on `NaN`; this one is [safePxOffset]. */
internal fun Double.roundToInt(): Int = safePxOffset()

internal fun Float.roundToInt(): Int = toDouble().safePxOffset()
