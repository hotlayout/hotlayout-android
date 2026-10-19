package com.hotlayout.icons

import com.hotlayout.json.JsonValue

/** One drawn element of an icon in the 24×24 grid, with its paint. */
public data class IconElement(
    val path: IconPath,
    /** `fill='currentColor'`: the element is filled with the foreground colour. */
    val filled: Boolean,
    /** `stroke='none'` turns the stroke off; otherwise it is drawn with [strokeWidth]. */
    val stroked: Boolean,
    val strokeWidth: Double,
)

/**
 * The icon set the reader ships with (`icons.json`): for every icon name, the inner elements of an
 * SVG on one shared grid. Drawing is done from geometry as vector paths, never through an icon font
 * (a release build would strip glyphs it believes unused) and never by an SVG engine: the body is a
 * closed vocabulary (`format`) and anything outside it is not drawn.
 */
public object IconSet {
    /** Names drawn mirrored in right-to-left text (`x-mirroredIcons`): a direction, not a thing. */
    public val mirrored: Set<String> = setOf(
        "chevron", "arrow-left", "arrow-right", "arrow-up-left", "arrow-up-right", "arrow-down-left", "arrow-down-right",
        "chevron-left", "chevron-right", "chevrons-left", "chevrons-right", "circle-arrow-left", "circle-arrow-right",
        "circle-chevron-left", "circle-chevron-right", "log-in", "log-out", "undo", "redo", "undo-2", "redo-2", "reply",
        "forward", "send", "external-link", "trending-up", "trending-down", "list", "list-checks",
    )

    /** The grid of every icon (`grid`): 24 units, stroke 2, round caps and joins. */
    public const val GRID_SIZE: Double = 24.0
    public const val DEFAULT_STROKE_WIDTH: Double = 2.0

    private val artifact: JsonValue by lazy {
        val bytes = IconSet::class.java.getResourceAsStream("/com/hotlayout/icons.json")?.use { it.readBytes() }
            ?: error("HotLayout: the icon set is missing from the library resources")
        JsonValue.parse(bytes)
    }

    /** The raw bodies by name, loaded once. */
    internal val bodies: Map<String, String> by lazy {
        artifact["icons"]?.obj?.mapNotNull { (name, body) -> body.string?.let { name to it } }?.toMap().orEmpty()
    }

    /** The version of the shipped set (`iconSetVersion`). */
    public val shippedVersion: String? get() = artifact["iconSetVersion"]?.string

    public val names: List<String> get() = bodies.keys.sorted()

    private val parsed = HashMap<String, List<IconElement>>()

    /**
     * The elements of an icon, or `null` for a name the set does not have (`unknownIcon:
     * 'empty-box-same-size'`: the caller keeps an empty box of the same size).
     */
    public fun elements(name: String): List<IconElement>? {
        synchronized(parsed) { parsed[name]?.let { return it } }
        val body = bodies[name] ?: return null
        val elements = IconBody.parse(body)
        synchronized(parsed) { parsed[name] = elements }
        return elements
    }
}

/** The closed vocabulary of an icon body: self-closing elements with single-quoted attributes. */
internal object IconBody {
    val geometry: Map<String, Set<String>> = mapOf(
        "path" to setOf("d"), "circle" to setOf("cx", "cy", "r"), "ellipse" to setOf("cx", "cy", "rx", "ry"),
        "rect" to setOf("x", "y", "width", "height", "rx", "ry"), "line" to setOf("x1", "y1", "x2", "y2"),
        "polyline" to setOf("points"), "polygon" to setOf("points"),
    )

    fun parse(body: String): List<IconElement> {
        val elements = ArrayList<IconElement>()
        var rest = body
        while (true) {
            val open = rest.indexOf('<')
            if (open < 0) break
            val close = rest.indexOf("/>", open)
            if (close < 0) break
            val tag = rest.substring(open + 1, close)
            rest = rest.substring(close + 2)
            val nameEnd = tag.indexOf(' ').let { if (it < 0) tag.length else it }
            val name = tag.substring(0, nameEnd)
            if (name !in geometry) continue
            val attributes = HashMap<String, String>()
            var cursor = tag.substring(nameEnd)
            while (true) {
                val equals = cursor.indexOf('=')
                if (equals < 0) break
                val key = cursor.substring(0, equals).trim()
                if (equals + 1 >= cursor.length || cursor[equals + 1] != '\'') break
                val end = cursor.indexOf('\'', equals + 2)
                if (end < 0) break
                attributes[key] = cursor.substring(equals + 2, end)
                cursor = cursor.substring(end + 1)
            }
            val path = shape(name, attributes) ?: continue
            val width = attributes["stroke-width"]?.toDoubleOrNull()
            elements.add(
                IconElement(
                    path = path,
                    filled = attributes["fill"] == "currentColor",
                    stroked = attributes["stroke"] != "none",
                    strokeWidth = if (width != null && width.isFinite() && width >= 0) width else IconSet.DEFAULT_STROKE_WIDTH,
                ),
            )
        }
        return elements
    }

    private fun shape(name: String, a: Map<String, String>): IconPath? {
        fun n(key: String) = a[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val path = IconPath()
        when (name) {
            "path" -> return SvgPathParser.parse(a["d"] ?: return null)
            "circle" -> {
                val cx = n("cx") ?: return null
                val cy = n("cy") ?: return null
                val r = n("r")?.takeIf { it > 0 } ?: return null
                path.ellipse(cx - r, cy - r, 2 * r, 2 * r)
            }
            "ellipse" -> {
                val cx = n("cx") ?: return null
                val cy = n("cy") ?: return null
                val rx = n("rx")?.takeIf { it > 0 } ?: return null
                val ry = n("ry")?.takeIf { it > 0 } ?: return null
                path.ellipse(cx - rx, cy - ry, 2 * rx, 2 * ry)
            }
            "rect" -> {
                val width = n("width")?.takeIf { it > 0 } ?: return null
                val height = n("height")?.takeIf { it > 0 } ?: return null
                val rx = n("rx") ?: n("ry") ?: 0.0
                val ry = n("ry") ?: rx
                path.roundedRect(n("x") ?: 0.0, n("y") ?: 0.0, width, height, minOf(rx, width / 2), minOf(ry, height / 2))
            }
            "line" -> {
                path.move(n("x1") ?: return null, n("y1") ?: return null)
                path.line(n("x2") ?: return null, n("y2") ?: return null)
            }
            "polyline", "polygon" -> {
                val numbers = SvgPathParser.numbers(a["points"] ?: "")
                if (numbers.size < 4) return null
                path.move(numbers[0], numbers[1])
                var index = 2
                while (index + 1 < numbers.size) {
                    path.line(numbers[index], numbers[index + 1])
                    index += 2
                }
                if (name == "polygon") path.close()
            }
            else -> return null
        }
        return path
    }
}

/** A platform-neutral path: drawing commands in grid units. The renderer and the tests both read it. */
public class IconPath {
    public sealed class Command {
        public data class Move(val x: Double, val y: Double) : Command()
        public data class Line(val x: Double, val y: Double) : Command()
        public data class Quad(val cx: Double, val cy: Double, val x: Double, val y: Double) : Command()
        public data class Cubic(val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x: Double, val y: Double) : Command()
        public data class Ellipse(val x: Double, val y: Double, val width: Double, val height: Double) : Command()
        public data class RoundedRect(val x: Double, val y: Double, val width: Double, val height: Double, val rx: Double, val ry: Double) : Command()
        public object Close : Command()
    }

    private val list = ArrayList<Command>()
    public val commands: List<Command> get() = list

    internal fun move(x: Double, y: Double) { list.add(Command.Move(x, y)) }
    internal fun line(x: Double, y: Double) { list.add(Command.Line(x, y)) }
    internal fun quad(cx: Double, cy: Double, x: Double, y: Double) { list.add(Command.Quad(cx, cy, x, y)) }
    internal fun cubic(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) { list.add(Command.Cubic(c1x, c1y, c2x, c2y, x, y)) }
    internal fun ellipse(x: Double, y: Double, width: Double, height: Double) { list.add(Command.Ellipse(x, y, width, height)) }
    internal fun roundedRect(x: Double, y: Double, width: Double, height: Double, rx: Double, ry: Double) { list.add(Command.RoundedRect(x, y, width, height, rx, ry)) }
    internal fun close() { list.add(Command.Close) }

    override fun equals(other: Any?): Boolean = other is IconPath && other.list == list
    override fun hashCode(): Int = list.hashCode()
}

/** SVG path data (`d`) into commands. Arcs become cubic curves. */
internal object SvgPathParser {
    fun numbers(text: String): List<Double> {
        val result = ArrayList<Double>()
        val scanner = Scanner(text)
        while (true) result.add(scanner.next() ?: break)
        return result
    }

    class Scanner(val text: String) {
        var index = 0

        fun skipSeparators() {
            while (index < text.length && text[index] in " ,\t\n\r") index++
        }

        fun next(): Double? {
            skipSeparators()
            if (index >= text.length) return null
            val start = index
            if (text[index] == '-' || text[index] == '+') index++
            var sawDot = false
            var sawDigit = false
            while (index < text.length) {
                val c = text[index]
                when {
                    c in '0'..'9' -> { sawDigit = true; index++ }
                    c == '.' && !sawDot -> { sawDot = true; index++ }
                    (c == 'e' || c == 'E') && sawDigit -> {
                        index++
                        if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
                    }
                    else -> break
                }
            }
            val value = if (sawDigit) text.substring(start, index).toDoubleOrNull() else null
            if (value == null) index = start
            return value
        }

        /** An arc flag: a single 0 or 1, which may be written without a separator. */
        fun flag(): Boolean? {
            skipSeparators()
            if (index >= text.length) return null
            val c = text[index]
            if (c != '0' && c != '1') return null
            index++
            return c == '1'
        }

        fun command(): Char? {
            skipSeparators()
            if (index >= text.length) return null
            val c = text[index]
            if (!(c in 'A'..'Z' || c in 'a'..'z') || c == 'e' || c == 'E') return null
            index++
            return c
        }

        val atEnd: Boolean
            get() {
                val save = index
                skipSeparators()
                val end = index >= text.length
                index = save
                return end
            }
    }

    fun parse(d: String): IconPath {
        val path = IconPath()
        val scanner = Scanner(d)
        var cx = 0.0
        var cy = 0.0
        var startX = 0.0
        var startY = 0.0
        var lastControl: Pair<Double, Double>? = null
        var lastCommand = ' '
        var command: Char? = null
        var guard = 0
        while (!scanner.atEnd && guard < 10_000) {
            guard++
            val next = scanner.command()
            if (next != null) command = next else if (command == null) break
            val op = command ?: break
            val relative = op.isLowerCase()
            fun px(x: Double) = if (relative) cx + x else x
            fun py(y: Double) = if (relative) cy + y else y
            when (op.uppercaseChar()) {
                'M' -> {
                    val x = scanner.next() ?: return path
                    val y = scanner.next() ?: return path
                    cx = px(x); cy = py(y)
                    startX = cx; startY = cy
                    path.move(cx, cy)
                    // Further pairs after a move are lines.
                    command = if (relative) 'l' else 'L'
                    lastControl = null
                }
                'L' -> {
                    val x = scanner.next() ?: return path
                    val y = scanner.next() ?: return path
                    cx = px(x); cy = py(y)
                    path.line(cx, cy)
                    lastControl = null
                }
                'H' -> {
                    val x = scanner.next() ?: return path
                    cx = px(x)
                    path.line(cx, cy)
                    lastControl = null
                }
                'V' -> {
                    val y = scanner.next() ?: return path
                    cy = py(y)
                    path.line(cx, cy)
                    lastControl = null
                }
                'C' -> {
                    val v = List(6) { scanner.next() ?: return path }
                    val c1x = px(v[0]); val c1y = py(v[1]); val c2x = px(v[2]); val c2y = py(v[3])
                    val ex = px(v[4]); val ey = py(v[5])
                    path.cubic(c1x, c1y, c2x, c2y, ex, ey)
                    lastControl = c2x to c2y
                    cx = ex; cy = ey
                }
                'S' -> {
                    val v = List(4) { scanner.next() ?: return path }
                    val previous = lastControl
                    val reflected = if (lastCommand.uppercaseChar() in "CS" && previous != null) (2 * cx - previous.first) to (2 * cy - previous.second) else cx to cy
                    val c2x = px(v[0]); val c2y = py(v[1]); val ex = px(v[2]); val ey = py(v[3])
                    path.cubic(reflected.first, reflected.second, c2x, c2y, ex, ey)
                    lastControl = c2x to c2y
                    cx = ex; cy = ey
                }
                'Q' -> {
                    val v = List(4) { scanner.next() ?: return path }
                    val qx = px(v[0]); val qy = py(v[1]); val ex = px(v[2]); val ey = py(v[3])
                    path.quad(qx, qy, ex, ey)
                    lastControl = qx to qy
                    cx = ex; cy = ey
                }
                'T' -> {
                    val x = scanner.next() ?: return path
                    val y = scanner.next() ?: return path
                    val previous = lastControl
                    val control = if (lastCommand.uppercaseChar() in "QT" && previous != null) (2 * cx - previous.first) to (2 * cy - previous.second) else cx to cy
                    val ex = px(x); val ey = py(y)
                    path.quad(control.first, control.second, ex, ey)
                    lastControl = control
                    cx = ex; cy = ey
                }
                'A' -> {
                    val rx = scanner.next() ?: return path
                    val ry = scanner.next() ?: return path
                    val rotation = scanner.next() ?: return path
                    val large = scanner.flag() ?: return path
                    val sweep = scanner.flag() ?: return path
                    val x = scanner.next() ?: return path
                    val y = scanner.next() ?: return path
                    val ex = px(x); val ey = py(y)
                    arc(path, cx, cy, ex, ey, Math.abs(rx), Math.abs(ry), rotation, large, sweep)
                    cx = ex; cy = ey
                    lastControl = null
                }
                'Z' -> {
                    path.close()
                    cx = startX; cy = startY
                    lastControl = null
                    command = null
                }
                else -> return path
            }
            lastCommand = op
        }
        return path
    }

    /** An elliptical arc (SVG endpoint form) as cubic curves. */
    fun arc(path: IconPath, x0: Double, y0: Double, x1: Double, y1: Double, rxIn: Double, ryIn: Double, rotation: Double, large: Boolean, sweep: Boolean) {
        if (x0 == x1 && y0 == y1) return
        if (rxIn <= 0 || ryIn <= 0) {
            path.line(x1, y1)
            return
        }
        var rx = rxIn
        var ry = ryIn
        val phi = Math.toRadians(rotation)
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)
        val dx = (x0 - x1) / 2
        val dy = (y0 - y1) / 2
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            rx *= Math.sqrt(lambda)
            ry *= Math.sqrt(lambda)
        }
        val numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coefficient = if (denominator == 0.0) 0.0 else Math.sqrt(maxOf(0.0, numerator / denominator))
        if (large == sweep) coefficient = -coefficient
        val cxp = coefficient * rx * y1p / ry
        val cyp = -coefficient * ry * x1p / rx
        val centerX = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2
        val centerY = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val sign = if (ux * vy - uy * vx < 0) -1.0 else 1.0
            val dot = ux * vx + uy * vy
            val length = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy)
            return sign * Math.acos((dot / length).coerceIn(-1.0, 1.0))
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI

        val segments = maxOf(1, Math.ceil(Math.abs(delta) / (Math.PI / 2)).toInt())
        val step = delta / segments
        val k = 4.0 / 3.0 * Math.tan(step / 4)
        var theta = theta1
        fun pointX(t: Double) = centerX + rx * Math.cos(t) * cosPhi - ry * Math.sin(t) * sinPhi
        fun pointY(t: Double) = centerY + rx * Math.cos(t) * sinPhi + ry * Math.sin(t) * cosPhi
        fun derivativeX(t: Double) = -rx * Math.sin(t) * cosPhi - ry * Math.cos(t) * sinPhi
        fun derivativeY(t: Double) = -rx * Math.sin(t) * sinPhi + ry * Math.cos(t) * cosPhi
        repeat(segments) {
            val next = theta + step
            path.cubic(
                pointX(theta) + k * derivativeX(theta), pointY(theta) + k * derivativeY(theta),
                pointX(next) - k * derivativeX(next), pointY(next) - k * derivativeY(next),
                pointX(next), pointY(next),
            )
            theta = next
        }
    }
}
