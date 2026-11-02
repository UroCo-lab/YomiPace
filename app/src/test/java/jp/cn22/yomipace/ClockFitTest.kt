package jp.cn22.yomipace

import org.junit.Assert.*
import org.junit.Test

class ClockFitTest {
    private fun width(index: Int, size: Float, labels: Float) = maxOf(44f * labels, size * (2f + index * .1f)) + 2f

    private fun assertFits(requested: List<Float>, available: Float, fit: ClockFontFit,
        measure: (Int, Float, Float) -> Float = ::width) {
        requested.indices.forEach { index ->
            assertTrue(fit.sizes[index] > 0f && fit.sizes[index] <= requested[index])
            assertTrue(measure(index, fit.sizes[index], fit.labelScale) <= available * requested[index] / requested.sum())
        }
    }

    @Test fun growingAnyClockLeavesMoreWidthForItAndShrinksNeighboursIfNeeded() {
        val before = fitClockFonts(listOf(44f, 44f, 44f), 340f, ::width)
        for (priority in 0..2) {
            val requested = List(3) { if (it == priority) 72f else 44f }
            val fit = fitClockFonts(requested, 340f, ::width)
            assertTrue(fit.sizes[priority] > before.sizes[priority])
            for (index in 0..2) if (index != priority) assertTrue(fit.sizes[index] <= before.sizes[index])
            assertTrue((0..2).any { it != priority && fit.sizes[it] < before.sizes[it] })
            assertFits(requested, 340f, fit)
        }
    }

    @Test fun aWiderScreenRestoresSavedSizesWithoutChangingTheRequest() {
        val requested = listOf(72f, 56f, 64f)
        val narrow = fitClockFonts(requested, 260f, ::width)
        val wide = fitClockFonts(requested, 800f, ::width)
        assertEquals(listOf(72f, 56f, 64f), requested)
        assertEquals(requested, wide.sizes)
        assertFits(requested, 260f, narrow)
        assertTrue(narrow.sizes.all { it < 56f })
    }

    @Test fun veryLongTimesAndLargeLabelsFitInsideEachProportionalShare() {
        fun longWidth(index: Int, size: Float, labels: Float) = maxOf(44f * labels, size * (8f + index)) + 2f
        for (requested in listOf(listOf(72f, 72f, 72f), listOf(12f, 18f, 30f), listOf(12f, 72f, 12f))) {
            for (available in listOf(100f, 280f, 600f)) {
                assertFits(requested, available, fitClockFonts(requested, available, ::longWidth), ::longWidth)
            }
        }
    }
}
