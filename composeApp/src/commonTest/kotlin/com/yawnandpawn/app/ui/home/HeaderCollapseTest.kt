package com.yawnandpawn.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device test round 1: with no hero (Epic 1) the Home header collapses continuously with the scroll, also once the
 * first (often empty) item has left the screen. A simulated lazy list: a 0 px notices item, the 40 px "Rings in" line
 * and 80 px alarm cards, 12 px apart, in a 600 px viewport whose top 100 px (the header zone) keeps items laid out.
 */
class HeaderCollapseTest {
    private val sizes = listOf(0, 40, 80, 80, 80, 80, 80, 80)
    private val gap = 12
    private val starts = sizes.indices.map { index -> (0 until index).sumOf { sizes[it] + gap } }

    /** What LazyList reports after scrolling [scrolled] px: the laid-out items, the first visible one and its scroll. */
    private fun layoutAt(scrolled: Int): Triple<List<Pair<Int, Int>>, Int, Int> {
        val laidOut = sizes.indices.filter { starts[it] + sizes[it] >= scrolled - HEADER_ZONE && starts[it] - scrolled < VIEWPORT }
        // Like LazyList, an item counts with the spacing after it.
        val first = sizes.indices.last { starts[it] <= scrolled }
        return Triple(laidOut.map { it to starts[it] - scrolled }, first, scrolled - starts[first])
    }

    @Test
    fun `the scroll distance follows the scroll past the empty first item`() {
        val distance = ScrollDistance()
        for (scrolled in 0..300) {
            val (laidOut, first, off) = layoutAt(scrolled)
            assertEquals(scrolled.toFloat(), distance.of(laidOut, first, off), "at $scrolled px")
        }
    }

    @Test
    fun `with no hero the collapse is strictly between 0 and 1 while scrolling the header's height, and never goes back`() {
        val distance = ScrollDistance()
        val header = 56f
        val fractions =
            (0..120).map { scrolled ->
                val (laidOut, first, off) = layoutAt(scrolled)
                collapseFraction(distance.of(laidOut, first, off), header)
            }

        assertEquals(0f, fractions.first(), "at rest")
        (1 until header.toInt()).forEach { assertTrue(fractions[it] > 0f && fractions[it] < 1f, "at $it px: ${fractions[it]}") }
        assertEquals(1f, fractions[header.toInt()], "collapsed after the header's height")
        assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a }, "monotonic: $fractions")
        assertTrue(fractions.zipWithNext().all { (a, b) -> b - a <= 1f / header + 1e-4f }, "no jump")
    }

    @Test
    fun `a first item never laid out with the top is reported as unknown, which is fully collapsed`() {
        assertNull(ScrollDistance().of(listOf(6 to -10, 7 to 82), firstIndex = 6, firstScrolledOff = 10))
        assertEquals(1f, collapseFraction(null, 56f))
    }

    private companion object {
        const val VIEWPORT = 600
        const val HEADER_ZONE = 100
    }
}
