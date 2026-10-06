package com.yawnandpawn.app.core.checks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanResolverTest {
    private val math = CheckEntry(CheckType.Math, Difficulty.Easy, count = 2)
    private val hardMath = CheckEntry(CheckType.Math, Difficulty.Hard, count = 5)
    private val placeholder = CheckPlan.PLACEHOLDER_ENTRY

    @Test
    fun `All keeps every entry in the user's order`() {
        val plan = CheckPlan(CheckMode.All, listOf(hardMath, placeholder, math))

        assertSame(plan, PlanResolver.resolve(plan, pickSeed = 1))
        assertSame(plan, PlanResolver.resolve(plan, pickSeed = 2))
    }

    @Test
    fun `Random keeps one entry, picked by the seed, in All mode`() {
        val plan = CheckPlan(CheckMode.Random, listOf(math, hardMath, placeholder))
        val picks = List(3_000) { seed -> PlanResolver.resolve(plan, seed.toLong()) }

        picks.forEach { resolved ->
            assertEquals(CheckMode.All, resolved.mode)
            assertEquals(1, resolved.entries.size)
            assertTrue(resolved.entries.single() in plan.entries)
        }
        val counts = picks.groupingBy { it.entries.single() }.eachCount()
        assertEquals(setOf(math, hardMath, placeholder), counts.keys)
        assertTrue(counts.values.all { it in 850..1_150 }, "each entry about a third of the time: $counts")
        assertEquals(PlanResolver.resolve(plan, 77), PlanResolver.resolve(plan, 77), "the same seed picks the same entry")
    }

    @Test
    fun `a re-ring can pick another type, because each ring has its own pick seed`() {
        val plan = CheckPlan(CheckMode.Random, listOf(math, placeholder))
        val changed =
            (0 until 100).count { session ->
                val ring1 = PlanResolver.resolve(plan, SeedDeriver.seed("s$session", 1, SeedDeriver.PICK, 0))
                val ring2 = PlanResolver.resolve(plan, SeedDeriver.seed("s$session", 2, SeedDeriver.PICK, 0))
                ring1 != ring2
            }

        assertTrue(changed in 30..70, "about half the sessions pick another type on ring 2: $changed")
    }

    @Test
    fun `a Random plan with one entry or none resolves to itself in All mode`() {
        assertEquals(CheckPlan(CheckMode.All, listOf(math)), PlanResolver.resolve(CheckPlan(CheckMode.Random, listOf(math)), 5))
        assertEquals(CheckPlan(CheckMode.All, emptyList()), PlanResolver.resolve(CheckPlan(CheckMode.Random, emptyList()), 5))
    }
}
