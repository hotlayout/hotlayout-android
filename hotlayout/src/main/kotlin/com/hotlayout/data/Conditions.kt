package com.hotlayout.data

import com.hotlayout.DataScope
import com.hotlayout.JsonValue
import com.hotlayout.text.BindableText

/**
 * Conditions of the contract: `visibleIf`, the cases of `styleWhen`, `disabledIf`, the filter of a
 * list (`where`).
 *
 * Rules (`x-compatibility.policy`, `x-conditions`):
 *  - a condition that is not an object or has no path or operator is «no condition»;
 *  - an unknown operator is visible (`unknownVisibilityOp: 'visible'`), and that is stronger than
 *    the next rule: «I do not know the question» is not «I have no answer»;
 *  - a missing value is EMPTY (`missingConditionValue: 'absent-is-empty'`): `isEmpty` holds, every
 *    other operator does not;
 *  - emptiness judges the VALUE: absent, `null`, the empty string and a list without records are
 *    empty; zero, `false` and a string of spaces are not.
 */
internal object Conditions {
    internal val knownOps = setOf("isTrue", "isFalse", "isEmpty", "isNotEmpty", "eq", "neq", "gt", "gte", "lt", "lte")

    /** Whether a node with this `visibleIf` is shown. */
    internal fun isVisible(condition: JsonValue?, scope: DataScope): Boolean =
        condition == null || condition.isNull || holds(condition, scope, filterable = true)

    /** Whether a condition holds. Used by `styleWhen` too: the first case that holds applies. */
    internal fun holds(condition: JsonValue, scope: DataScope): Boolean = holds(condition, scope, filterable = true)

    /** For `disabledIf`: only a condition the reader UNDERSTANDS can disable a control. */
    internal fun understoodHolds(condition: JsonValue?, scope: DataScope): Boolean {
        if (condition?.obj == null || condition["path"]?.string == null) return false
        val op = condition["op"]?.string ?: return false
        return op in knownOps && holds(condition, scope, filterable = true)
    }

    /** An operand from another path that is absent, `null` or the empty string. */
    internal fun isOperandMissing(value: JsonValue?): Boolean = when (value) {
        null, JsonValue.Null -> true
        is JsonValue.Str -> value.value.isEmpty()
        else -> false
    }

    /**
     * The filter of a list: a record passes when the operand from another path is missing
     * (`listFilter.valuePath.operandMissing.inWhere`), otherwise by the condition.
     */
    internal fun wherePasses(condition: JsonValue, scope: DataScope): Boolean {
        if (condition.obj == null || condition["path"]?.string == null) return true
        val op = condition["op"]?.string ?: return true
        val other = condition["valuePath"]?.string
        if (other != null && op in knownOps && isOperandMissing(scope[other])) return true
        return holds(condition, scope, filterable = false)
    }

    private fun holds(condition: JsonValue, scope: DataScope, filterable: Boolean): Boolean {
        if (condition.obj == null) return true
        val path = condition["path"]?.string ?: return true
        val op = condition["op"]?.string ?: return true
        // Emptiness of a FILTERED list: the records that pass are counted, without `limit`.
        val filter = condition["where"]
        if (filterable && (op == "isEmpty" || op == "isNotEmpty") && filter?.obj != null) {
            return somePasses(scope, path, filter, condition["item"]?.string ?: "") == (op == "isNotEmpty")
        }
        if (op !in knownOps) return true
        val value = scope[path]
        if (value == null || value.isNull) return op == "isEmpty"
        return when (op) {
            "isTrue" -> asBoolean(value)
            "isFalse" -> !asBoolean(value)
            "isEmpty" -> isEmpty(value)
            "isNotEmpty" -> !isEmpty(value)
            else -> compare(op, value, condition, scope)
        }
    }

    private fun somePasses(scope: DataScope, path: String, filter: JsonValue, item: String): Boolean {
        val records = scope[path]?.array ?: return false
        return records.any { record ->
            val fields = record.obj ?: com.hotlayout.JsonObject()
            wherePasses(filter, RecordScope.overlay(scope, RecordScope.instanceRecord(item, fields)) ?: scope)
        }
    }

    internal fun isEmpty(value: JsonValue): Boolean = when (value) {
        JsonValue.Null -> true
        is JsonValue.Arr -> value.items.isEmpty()
        is JsonValue.Str -> value.value.isEmpty()
        else -> false
    }

    internal fun asBoolean(value: JsonValue): Boolean = when (value) {
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value != 0.0
        is JsonValue.Str -> value.value.isNotEmpty()
        else -> false
    }

    private fun compare(op: String, value: JsonValue, condition: JsonValue, scope: DataScope): Boolean {
        val other = condition["valuePath"]?.string
        val operand = if (other != null) {
            scope[other].also { if (isOperandMissing(it)) return false }
        } else {
            condition["value"]
        }
        return when (op) {
            "eq" -> equals(value, operand)
            "neq" -> !equals(value, operand)
            else -> {
                val a = comparable(value) ?: return false
                val b = comparable(operand) ?: return false
                when (op) {
                    "gt" -> a > b
                    "gte" -> a >= b
                    "lt" -> a < b
                    else -> a <= b
                }
            }
        }
    }

    /** Equality without type coercion; values of different types compare as text. */
    private fun equals(left: JsonValue, right: JsonValue?): Boolean = when {
        left is JsonValue.Str && right is JsonValue.Str -> left.value == right.value
        left is JsonValue.Num && right is JsonValue.Num -> left.value == right.value
        left is JsonValue.Bool && right is JsonValue.Bool -> left.value == right.value
        else -> conditionText(left) == conditionText(right)
    }

    private fun conditionText(value: JsonValue?): String = when (value) {
        null, JsonValue.Null -> ""
        is JsonValue.Arr, is JsonValue.Obj -> "[object]"
        else -> BindableText.asText(value)
    }

    /** A number for an order comparison. A string of spaces (a cleared field) is NOT zero. */
    private fun comparable(value: JsonValue?): Double? = when (value) {
        is JsonValue.Num -> value.value
        is JsonValue.Bool -> if (value.value) 1.0 else 0.0
        is JsonValue.Str -> value.value.trim().takeIf { it.isNotEmpty() }?.let(::javaScriptNumberParse)
        JsonValue.Null -> 0.0
        else -> null
    }

    /** `Number(text)` for the shapes a data value takes: decimal, with exponent, hex. */
    private fun javaScriptNumberParse(text: String): Double? {
        if (text.startsWith("0x") || text.startsWith("0X")) return text.drop(2).toULongOrNull(16)?.toDouble()
        if (!text.all { it in '0'..'9' || it in "+-.eE" }) {
            return when (text) {
                "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                else -> null
            }
        }
        return text.toDoubleOrNull()
    }
}

/**
 * The records a list from data draws (`Repeater.where`, `limit`; `x-listFilter`): each record is
 * judged in ITS scope — the record under its prefix over the outer scope; `limit` counts the
 * records that passed.
 */
internal object ListFilter {
    internal fun passedIndexes(records: List<JsonValue>, item: String, filter: JsonValue?, outer: DataScope, limit: Int? = null): List<Int> {
        val passed = ArrayList<Int>()
        for ((index, record) in records.withIndex()) {
            if (limit != null && passed.size >= limit) break
            if (filter?.obj == null) {
                passed.add(index)
                continue
            }
            val scope = RecordScope.overlay(outer, RecordScope.instanceRecord(item, record.obj ?: com.hotlayout.JsonObject())) ?: outer
            if (Conditions.wherePasses(filter, scope)) passed.add(index)
        }
        return passed
    }
}
