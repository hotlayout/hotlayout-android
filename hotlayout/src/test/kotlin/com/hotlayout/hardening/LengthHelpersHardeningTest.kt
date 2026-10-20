package com.hotlayout.hardening

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.MAX_DP
import com.hotlayout.render.MAX_PX
import com.hotlayout.render.dp
import com.hotlayout.render.layout.validWeight
import com.hotlayout.render.roundToInt
import com.hotlayout.render.safePx
import com.hotlayout.render.signedDp
import com.hotlayout.text.DecimalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one place a number becomes a length: finite, not negative (offsets keep the sign) and bounded; and what the sweep found around it. */
class LengthHelpersHardeningTest {
    private val hostile = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e308, -1e308, -0.0, 2147483648.0, -2147483649.0)

    @Test fun aLengthIsFiniteNotNegativeAndBounded() {
        for (value in hostile) {
            val length = value.dp.value.toDouble()
            assertEquals("$value", true, length.isFinite() && length >= 0 && length <= MAX_DP)
            val offset = value.signedDp.value.toDouble()
            assertEquals("$value", true, offset.isFinite() && Math.abs(offset) <= MAX_DP)
            assertEquals("$value", true, value.safePx() in 0..MAX_PX)
            value.roundToInt() // never throws, unlike kotlin.math.roundToInt
        }
        assertEquals(-12.0, (-12.0).signedDp.value.toDouble(), 0.0)
        assertEquals(0.0, (-12.0).dp.value.toDouble(), 0.0)
        assertEquals(12.0, 12.0.dp.value.toDouble(), 0.0)
    }

    /** Found by the sweep: a computed value that overflowed to infinity threw while it was written as a label. */
    @Test fun aNumberThatIsNotFiniteIsWrittenAsZeroAndNeverThrows() {
        for (value in hostile) DecimalText.plain(value)
        assertEquals("0", DecimalText.plain(Double.POSITIVE_INFINITY))
    }

    @Test fun aWeightIsHeldToTheSchemasBounds() {
        fun weight(value: Double) = validWeight(DocumentNode("n", "Text", JsonObject(), JsonObject.of("weight" to JsonValue.Num(value)), emptyList()))
        for (ok in listOf(1.0, 2.0, 3.0, 4.0)) assertEquals(ok, weight(ok)!!, 0.0)
        for (bad in listOf(0.0, 5.0, 1e308, 2147483648.0, -1.0, 1.5, Double.NaN)) assertNull("$bad", weight(bad))
    }
}
