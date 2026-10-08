package com.yawnandpawn.app.playcatalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogRunnerTest {
    private val output = mutableListOf<String>()

    private fun run(
        api: FakePlayCatalogApi,
        mode: Mode,
    ): Int {
        output.clear()
        return CatalogRunner(api, { output += it }).run(mode)
    }

    private val text get() = output.joinToString("\n")

    @Test
    fun `dry run reads Play, converts all 50 prices, prints the plan and writes nothing`() {
        val api = FakePlayCatalogApi(TestCatalogs.partial())
        val before = api.products.toMap()

        val exit = run(api, Mode.DRY_RUN)

        assertEquals(ExitCode.OK, exit)
        assertEquals(emptyList(), api.writes)
        assertEquals(listOf("list") + (1..50).map { "convert USD $it.00" }, api.calls)
        assertEquals(before, api.products)
        assertTrue("Play catalogue for com.yawnandpawn.app (mode: dry-run)" in output)
        assertTrue("Play prices 3 regions (regions version 2025/03)." in output)
        assertTrue(output.any { it.startsWith("snooze_usd_02   update     price USD 3.00 -> USD 2.00") }, text)
        assertTrue(output.any { it.startsWith("snooze_usd_04   activate   inactive; reactivate") }, text)
        assertTrue(output.any { it.startsWith("snooze_usd_11   create     USD 11.00, 3 regions") }, text)
        assertTrue(output.any { it.startsWith("spike_s1_test   unmanaged") }, text)
        assertTrue("40 create, 2 update, 1 activate, 7 unchanged, 1 unmanaged, 0 attention. 43 changes." in output, text)
        assertTrue(output.last().startsWith("Dry run: nothing was written."), text)
    }

    @Test
    fun `apply creates 40, patches 2, reactivates 1, and a second run reports 0 changes with no write`() {
        val api = FakePlayCatalogApi(TestCatalogs.partial())

        assertEquals(ExitCode.OK, run(api, Mode.APPLY))

        val patches = api.writes.filter { it.startsWith("patch") }
        assertEquals(40, patches.count { it.endsWith("[listings, purchaseOptions] allowMissing") })
        assertEquals(
            listOf("patch snooze_usd_02 [purchaseOptions]", "patch snooze_usd_03 [purchaseOptions]"),
            patches.filterNot { it.endsWith("allowMissing") },
        )
        assertEquals(41, api.writes.count { it.startsWith("activate") })
        assertTrue("activate snooze_usd_04/buy" in api.writes)
        assertTrue("Applied 43 changes. Run the dry run again: it should report 0 changes." in output, text)

        assertEquals(SnoozeCatalog.productIds + "spike_s1_test", api.products.keys.sorted())
        SnoozeCatalog.productIds.forEachIndexed { index, id ->
            val option =
                api.products
                    .getValue(id)
                    .purchaseOptions
                    .single()
            assertEquals(OptionState.ACTIVE, option.state, id)
            assertEquals(Price.usd(index + 1L), option.regions.getValue("US").price, id)
            assertEquals(listOf(SnoozeCatalog.listing), api.products.getValue(id).listings, id)
        }

        val writesAfterFirstRun = api.writes.size
        assertEquals(ExitCode.OK, run(api, Mode.APPLY))
        assertEquals(writesAfterFirstRun, api.writes.size, "the second apply wrote again")
        assertTrue("0 create, 0 update, 0 activate, 50 unchanged, 1 unmanaged, 0 attention. 0 changes." in output, text)
        assertEquals("0 changes. Play matches the catalogue.", output.last())

        assertEquals(ExitCode.OK, run(api, Mode.DRY_RUN))
        assertEquals(writesAfterFirstRun, api.writes.size)
        assertEquals("0 changes. Play matches the catalogue.", output.last())
    }

    @Test
    fun `the unmanaged spike product is never written and stays as it was`() {
        val api = FakePlayCatalogApi(TestCatalogs.partial())

        run(api, Mode.APPLY)

        assertFalse(api.writes.any { "spike_s1_test" in it }, api.writes.toString())
        assertEquals(FakePlayCatalogApi.spikeProduct, api.products.getValue("spike_s1_test"))
    }

    @Test
    fun `a created product is activated after its patch`() {
        val api = FakePlayCatalogApi()

        run(api, Mode.APPLY)

        assertEquals(100, api.writes.size)
        assertEquals("patch snooze_usd_01 [listings, purchaseOptions] allowMissing", api.writes[0])
        assertEquals("activate snooze_usd_01/buy", api.writes[1])
    }

    @Test
    fun `a failed write stops the run with the API message and exit 1, without retrying or writing more`() {
        val api = FakePlayCatalogApi(TestCatalogs.partial()).apply { failOn = "patch snooze_usd_03" }

        val exit = run(api, Mode.APPLY)

        assertEquals(ExitCode.API_FAILURE, exit)
        assertEquals("patch snooze_usd_03 [purchaseOptions]", api.writes.last())
        assertEquals(1, api.writes.count { it.startsWith("patch snooze_usd_03") }, "retried")
        assertTrue(
            output.any { it == "FAILED while applying change 2 of 43: patch snooze_usd_03 [purchaseOptions]: HTTP 500: injected failure" },
            text,
        )
        assertEquals("Stopped. Nothing is retried; run the dry run to see what is left.", output.last())
        assertFalse(output.any { "Manage store presence" in it })
    }

    @Test
    fun `a permission error names the Play Console permission the service account needs`() {
        val api =
            FakePlayCatalogApi().apply {
                failOn = "list"
                failStatus = 403
            }

        val exit = run(api, Mode.DRY_RUN)

        assertEquals(ExitCode.API_FAILURE, exit)
        assertEquals(listOf("list"), api.calls)
        assertTrue(output.any { it == "FAILED: list: HTTP 403: injected failure" }, text)
        assertTrue(output.any { "\"Manage store presence\"" in it }, text)
    }

    @Test
    fun `a failed price conversion stops before any write`() {
        val api = FakePlayCatalogApi().apply { failOn = "convert USD 17.00" }

        assertEquals(ExitCode.API_FAILURE, run(api, Mode.APPLY))
        assertEquals(emptyList(), api.writes)
    }

    @Test
    fun `the API port has no delete call`() {
        val names = PlayCatalogApi::class.java.methods.map { it.name.lowercase() }

        assertTrue(names.none { "delete" in it || "remove" in it || "deactivate" in it }, names.toString())
    }
}
