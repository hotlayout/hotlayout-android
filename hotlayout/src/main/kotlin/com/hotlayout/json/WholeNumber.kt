package com.hotlayout.json

import com.hotlayout.JsonValue

/**
 * A finite whole number inside [range], else `null`: the one rule behind every field of the contract that is
 * "an integer in bounds" (a blur radius, an own glyph size, a count).
 */
internal fun Double?.wholeIn(range: IntRange): Int? =
    this?.takeIf { it.isFinite() && it == Math.rint(it) && it >= range.first && it <= range.last }?.toInt()

/** The number of a JSON value as [wholeIn] reads it; `null` for anything that is not a number. */
internal fun JsonValue?.wholeIn(range: IntRange): Int? = this?.number.wholeIn(range)
