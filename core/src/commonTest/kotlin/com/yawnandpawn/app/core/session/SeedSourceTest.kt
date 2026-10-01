package com.yawnandpawn.app.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedSourceTest {
    @Test
    fun `the random seed source gives one seed per check step from its random`() {
        val plan = CheckPlan(List(3) { CheckStep.Placeholder })
        val expected = Random(7).let { random -> List(3) { random.nextLong() } }

        assertEquals(expected, RandomSeedSource(Random(7)).seedsFor(plan))
    }

    @Test
    fun `the Epic 1 placeholder plan gets one seed`() {
        assertEquals(1, RandomSeedSource().seedsFor(CheckPlan.placeholder()).size)
    }
}
