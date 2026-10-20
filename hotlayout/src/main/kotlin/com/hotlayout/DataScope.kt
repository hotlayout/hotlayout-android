package com.hotlayout

import com.hotlayout.actions.ActionCatalog

/**
 * Values available to a node: a flat map from a data PATH to a value.
 *
 * Flat on purpose. A path in the contract is a string (`task.title`), not a walk through nested
 * objects; splitting it would raise questions the contract does not answer.
 */
public typealias DataScope = Map<String, JsonValue>

/** A data scope from plain Kotlin values (see [JsonValue.of]). */
public fun dataScopeOf(vararg pairs: Pair<String, Any?>): DataScope = pairs.associate { it.first to JsonValue.of(it.second) }

/**
 * Which data paths the reader may write to. The document names the path of a write, and a
 * document arrives over the network: the host says what it expects. By default every path of the
 * shape of a data path is allowed (a forbidden segment such as `__proto__` never is).
 */
public class WritablePaths private constructor(private val allows: (String) -> Boolean) {
    /** Only the paths of the set. */
    public constructor(paths: Set<String>) : this({ it in paths })

    /** Whether the host lets the reader write to [path]. */
    public fun permits(path: String): Boolean = allows(path)

    public companion object {
        public fun where(predicate: (String) -> Boolean): WritablePaths = WritablePaths(predicate)

        /** The gate of the contract (the shape of a data path), then the host's list when there is one. */
        internal fun permits(gate: WritablePaths?, path: String): Boolean =
            ActionCatalog.isDataPath(path) && (gate?.permits(path) ?: true)
    }
}
