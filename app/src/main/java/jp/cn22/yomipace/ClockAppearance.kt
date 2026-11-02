package jp.cn22.yomipace

import java.util.Locale

/** Opaque RGB only. Null means the theme's automatic clock color. */
fun normalizedClockColor(value: String?): String? = value?.trim()?.removePrefix("#")
    ?.takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }
    ?.uppercase(Locale.ROOT)?.let { "#$it" }

internal data class ClockFontFit(val sizes: List<Float>, val labelScale: Float = 1f)

/** Allocate the entire row by saved font-size ratios, then fit text inside each share. */
internal fun fitClockFonts(
    requested: List<Float>, available: Float,
    measure: (index: Int, size: Float, labelScale: Float) -> Float
): ClockFontFit {
    require(requested.size == 3 && requested.all { it.isFinite() && it > 0 } && available > 0)
    val widths = requested.map { available * it / requested.sum() }
    fun largestFit(fits: (Float) -> Boolean): Float {
        if (fits(1f)) return 1f
        var low = 0f
        var high = 1f
        repeat(18) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }
    // Keep headings at a common size; only shrink them on exceptionally narrow shares.
    val labels = largestFit { scale -> requested.indices.all { measure(it, .01f, scale) <= widths[it] } }
    val sizes = requested.mapIndexed { index, size ->
        size * largestFit { scale -> measure(index, maxOf(.01f, size * scale), labels) <= widths[index] }
    }
    return ClockFontFit(sizes, labels)
}
