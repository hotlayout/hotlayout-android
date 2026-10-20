package com.hotlayout.model

import com.hotlayout.JsonValue
import com.hotlayout.json.wholeIn

/** The blur radius of a modal layer's backdrop (`backdropBlur`) and of the glass of a node (`style.backdropBlur`). */
internal object BlurRules {
    /** The radii the contract allows. */
    val RANGE = 1..40

    /** A whole number 1…40, else no blur. */
    fun radius(value: JsonValue?): Int? = value.wholeIn(RANGE)
}
